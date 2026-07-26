#!/usr/bin/env bash
# Build the deployable Doris UDF fat jar: ./kotlin package, then flatten the
# Spring-Boot-style executable jar into a classic shaded jar a plain URLClassLoader
# (the Doris BE java-udf loader) can read. Output: build/udf-lucene-all.jar
set -euo pipefail
cd "$(dirname "$0")/.."

./kotlin package

python3 tools/flatten-jar.py \
    build/tasks/_udf-lucene_executableJarJvm/udf-lucene-jvm-executable.jar \
    build/udf-lucene-all.jar \
    dev.brikk.doris.udf.lucene.MainKt

# Smoke the flat jar EXACTLY the way Doris loads it: plain classpath (no -jar launcher).
out=$(java -cp build/udf-lucene-all.jar dev.brikk.doris.udf.lucene.MainKt english "John's running quickly the boxes")
if [ "$out" != "john run quickli box" ]; then
    echo "FLAT-JAR SMOKE FAILED: got '$out'" >&2
    exit 1
fi
echo "flat-jar smoke OK: $out"
ls -la build/udf-lucene-all.jar
