#!/usr/bin/env python3
"""Flatten the Kotlin toolchain's Spring-Boot-style executable jar into a classic
shaded fat jar that a plain URLClassLoader (= the Doris BE java-udf loader) can read.

The toolchain's `./kotlin package` emits BOOT-INF/classes/** + BOOT-INF/lib/*.jar with a
launcher Main-Class — Doris's BaseExecutor loads the jar via URLClassLoader + reflection,
which cannot see BOOT-INF nesting. This script explodes module classes and every nested
dependency jar to the root of one flat jar.

Handling:
  - META-INF/services/*   : MERGED (concatenated, deduped) across all sources
  - module-info.class     : DROPPED (multiple modular jars cannot share one root)
  - META-INF/versions/**  : kept (Multi-Release: true set in the manifest)
  - signature files       : DROPPED (*.SF/*.DSA/*.RSA — shading invalidates them)
  - duplicates            : first wins (module classes take precedence over deps)

usage: flatten-jar.py <executable.jar> <output.jar> [Main-Class]
"""

import sys
import zipfile
from collections import OrderedDict

SKIP_SUFFIXES = (".SF", ".DSA", ".RSA", ".EC")


def want(name: str) -> bool:
    if name.endswith("/"):
        return False
    if name == "module-info.class" or name.endswith("/module-info.class"):
        return False
    if name == "META-INF/MANIFEST.MF":
        return False
    if name.startswith("META-INF/") and name.upper().endswith(SKIP_SUFFIXES):
        return False
    return True


def main() -> None:
    src_path, out_path = sys.argv[1], sys.argv[2]
    main_class = sys.argv[3] if len(sys.argv) > 3 else None

    entries: "OrderedDict[str, bytes]" = OrderedDict()
    services: "OrderedDict[str, list[str]]" = OrderedDict()

    def add(name: str, data: bytes) -> None:
        if not want(name):
            return
        if name.startswith("META-INF/services/"):
            lines = services.setdefault(name, [])
            for line in data.decode("utf-8", "replace").splitlines():
                line = line.strip()
                if line and not line.startswith("#") and line not in lines:
                    lines.append(line)
            return
        if name not in entries:
            entries[name] = data

    with zipfile.ZipFile(src_path) as src:
        names = src.namelist()
        # 1) module classes first (take precedence over dependency classes)
        for n in names:
            if n.startswith("BOOT-INF/classes/") and not n.endswith("/"):
                add(n[len("BOOT-INF/classes/"):], src.read(n))
        # 2) every nested dependency jar
        for n in names:
            if n.startswith("BOOT-INF/lib/") and n.endswith(".jar"):
                import io
                with zipfile.ZipFile(io.BytesIO(src.read(n))) as dep:
                    for dn in dep.namelist():
                        if not dn.endswith("/"):
                            add(dn, dep.read(dn))

    manifest_lines = ["Manifest-Version: 1.0", "Multi-Release: true"]
    if main_class:
        manifest_lines.append(f"Main-Class: {main_class}")
    manifest = ("\r\n".join(manifest_lines) + "\r\n\r\n").encode()

    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as out:
        out.writestr("META-INF/MANIFEST.MF", manifest)
        for name, lines in services.items():
            out.writestr(name, "\n".join(lines) + "\n")
        for name, data in entries.items():
            out.writestr(name, data)

    print(f"wrote {out_path}: {len(entries)} entries, {len(services)} merged service files")


if __name__ == "__main__":
    main()
