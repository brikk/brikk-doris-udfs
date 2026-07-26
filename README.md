# brikk-doris-udfs

Java UDFs for [Apache Doris](https://doris.apache.org/), built with the
[Kotlin Toolchain CLI](https://github.com/JetBrains/amper) (`project.yaml` / `module.yaml`,
same layout as `brikk-house`). Kotlin 2.4.x, JDK 17 target (the Doris BE java-udf runtime
is JDK 17 — verified 17.0.2 on `apache/doris:be-4.1.0`).

## Modules

### `udf-lucene` — OpenSearch-compatible text analysis (`tokenize_with_lucene`)

Runs text through a **Lucene analysis chain configured with OpenSearch's analyzer
vocabulary** — the OpenSearch docs are the config reference — and returns the tokens
space-joined, in order. Fills the gap in Doris's built-in inverted-index analyzers:
real **stemming**, **stop words with custom/predefined lists**, **possessive stripping**,
**ICU normalization/folding**, multilingual tokenization.

- Implementation: Lucene **9.12.3** (`analysis-common` + `analysis-icu`).
  Lucene 10.x needs Java 21; the Doris BE java-udf runtime is JDK 17, so 9.x it is.
  9.x and 10.x analysis behavior is equivalent for this surface.
- Parity target: OpenSearch 3.x analyzer docs (`docs.opensearch.org/latest`, checked 2026-07).
- **Fail-loud contract:** any unsupported analyzer/tokenizer/filter *or parameter* throws
  (listing what IS supported) — a silently-skipped component would produce a different
  analyzer than configured and corrupt index/query symmetry.

```
SELECT tokenize_with_lucene('John''s running quickly the boxes', 'english');
-- 'john run quickli box'      (possessive stripped, stop words removed, Porter-stemmed)

SELECT tokenize_with_lucene('Grüße aus Zürich: Straße', '{
  "tokenizer": "icu_tokenizer",
  "filter": [{"type": "icu_normalizer", "name": "nfkc_cf"}, "asciifolding"]
}');
-- 'grusse aus zurich strasse' (NFKC casefold: ß→ss; ASCII fold: ü→u)
```

#### Config forms (OpenSearch `_analyze`-shaped)

```jsonc
"english"                                   // bare built-in analyzer name
{"analyzer": "german"}                      // same, JSON form
{"analyzer": {"type": "english", "stem_exclusion": ["organization"]}}
"brikk_multilang_english_v1"              // versioned brikk preset (see below)
{                                            // custom chain
  "char_filter": [{"type": "icu_normalizer", "name": "nfkc_cf"}],
  "tokenizer": "icu_tokenizer",
  "filter": [
    {"type": "word_delimiter_graph", "stem_english_possessive": true,
     "protected_words": ["Wi-Fi"]},
    "asciifolding",
    {"type": "stop", "stopwords": ["_english_", "corp"]},
    {"type": "stemmer", "language": "english"}
  ]
}
```

#### Supported surface (v1)

| kind | names |
|---|---|
| analyzers | `standard simple whitespace keyword stop pattern fingerprint english german french italian spanish portuguese russian swedish danish norwegian finnish hungarian turkish` |
| tokenizers | `standard whitespace letter lowercase keyword classic uax_url_email thai icu_tokenizer ngram edge_ngram pattern char_group` |
| char filters | `html_strip mapping pattern_replace icu_normalizer` |
| token filters | `lowercase uppercase asciifolding stop stemmer snowball porter_stem kstem keyword_marker keyword_repeat remove_duplicates flatten_graph keep length limit truncate trim reverse apostrophe classic decimal_digit elision fingerprint ngram edge_ngram shingle word_delimiter word_delimiter_graph cjk_width cjk_bigram *_normalization (german/arabic/persian/hindi/indic/sorani/bengali/serbian) scandinavian_folding scandinavian_normalization icu_folding icu_normalizer icu_transform` |
| `stemmer` languages | full OpenSearch algorithmic table (english/light/minimal/possessive/porter2/lovins, german(2)/light/minimal, french/spanish/italian/portuguese families, Nordic + Snowball languages, arabic bengali brazilian czech greek hindi indonesian sorani …) |
| `stopwords` sets | `_english_ _german_ _french_ …` (35 languages) + `_none_` + inline lists (mixable) |

Not supported (fail loud): `hunspell`, `synonym`/`synonym_graph`, `phonetic`,
`*_decompounder`, `multiplexer`/`condition`, `stopwords_path` (no files on BEs),
`stemmer_override`, `pattern_capture`, `common_grams`, `min_hash`, `path_hierarchy`.

#### Caching & canonicalization

Two-hop cache: config text → canonical `AnalysisConfig` (data class) → Lucene `Analyzer`.
Named analyzers expand to their full chains at parse time, so `"english"`, its JSON
spellings, and its hand-written equivalent chain share **one** analyzer instance. Keyed by
the data class (structural equality), never by a raw hash. Per-row cost is tokenization only.

#### Versioned brikk presets — the pinned-config answer

The config argument must be byte-identical at ingest *and* at query. Two complementary
pinning mechanisms:

1. **Doris `CREATE ALIAS FUNCTION`** — bake your config into a server-side name, so
   callers never repeat the JSON. Example with a fully custom chain:

   ```sql
   CREATE ALIAS FUNCTION tokenize_multilang(STRING) WITH PARAMETER(text) AS
     tokenize_with_lucene(text,
       '{"char_filter":[{"type":"icu_normalizer","name":"nfkc_cf"}],"tokenizer":"icu_tokenizer","filter":[{"type":"stemmer","language":"possessive_english"},"asciifolding",{"type":"stop","stopwords":"_english_"},{"type":"stemmer","language":"english"}]}'
     );

   SELECT tokenize_multilang("The Müller's Fußgänger are running quickly to Zürich's café");
   -- 'muller fussgang run quickli zurich cafe'
   ```

   The alias body can be any expression — wrap a jar preset instead of inline JSON
   (`tokenize_with_lucene(text, 'brikk_multilang_english_v1')`), or add post-filtering
   (e.g. `array_filter` to drop 1-char tokens). Add `GLOBAL` to use it across databases.

   > ⚠ **Doris 4.1.0's alias functions are broken** — use **4.1.1 or newer** (fixed by
   > [#63254](https://github.com/apache/doris/pull/63254), backported to 4.1 in
   > [#63349](https://github.com/apache/doris/pull/63349); we hit the bug, reported it,
   > and helped land the fix).

   The alias is FE metadata: changing its body is a REINDEX event — rebuild every
   pre-tokenized column, or matching silently breaks.

2. **Versioned jar presets** (below) — cluster-portable, frozen by the release tag, and
   usable anywhere the jar is (CLI, other clusters) without per-cluster DDL. The alias
   above is best defined *over* a preset name, layering both pins.

##### `brikk_multilang_english_v1`

"Search-normalize everything, rank in English": multilingual tokenization with aggressive
Latin normalization plus English stop words, possessives, and stemming. Good default for
mixed international content (names, places, product text) searched by English-speaking
users, where accent-, case-, inflection-, and possessive-insensitive **recall** matters
more than preserving distinctions.

| stage | component | does |
|---|---|---|
| char filter | `icu_normalizer` (`nfkc_cf`) | Unicode NFKC compatibility normalization + full case-folding, **before** tokenization (`ß→ss`, `ﬁ→fi`, `²³→23`, full-width→ASCII, lowercase) |
| tokenizer | `icu_tokenizer` | UAX#29 word segmentation across scripts (Latin, CJK dictionary segmentation, Thai, …) |
| filter 1 | `stemmer: possessive_english` | strips trailing `'s` (`zürich's → zürich`) |
| filter 2 | `asciifolding` | folds diacritics to ASCII (`ü→u ä→a é→e ñ→n ç→c`) |
| filter 3 | `stop: _english_` | drops English stop words (`the, is, a, of, to, are, …`) |
| filter 4 | `stemmer: english` | Porter stemming (`running→run`, `boxes→box`, `quickly→quickli`) |

```sql
SELECT tokenize_with_lucene("The Müller's Fußgänger are running quickly to Zürich's café",
                     'brikk_multilang_english_v1');
-- 'muller fussgang run quickli zurich cafe'
--  ß→ss (casefold) · 's stripped · ü/é→ASCII · the/are/to dropped · Porter-stemmed

SELECT tokenize_with_lucene("John's boxes ﬁnally arrived at 北京大学 today", 'brikk_multilang_english_v1');
-- 'john box final arriv 北京 大学 todai'
--  ﬁ ligature normalized · CJK dictionary-segmented · stemmed
```

Trade-offs to know: accented forms are **not distinguishable** after folding (`Müller` ≡
`muller`); Porter produces non-word stems (`quickli`, `todai`) — fine for matching, not
for display; stop-word removal makes pure-stop-word queries return nothing. If you need
different trade-offs, pass the explicit JSON chain (this preset is exactly):

```json
{"char_filter": [{"type": "icu_normalizer", "name": "nfkc_cf"}],
 "tokenizer": "icu_tokenizer",
 "filter": [{"type": "stemmer", "language": "possessive_english"},
            "asciifolding",
            {"type": "stop", "stopwords": ["_english_"]},
            {"type": "stemmer", "language": "english"}]}
```

(The preset and this JSON are the same cache key — the preset is pure shorthand.)

**Presets are immutable once published**: a changed chain is a NEW name (`_v2`), because
changing the analysis behind an existing name would silently break every index built
with it (see the symmetry contract). PRs adding presets are welcome under the same rule —
version-suffix the name, pin the output in a test.

#### ⚠ The symmetry contract

**Index-time and query-time analysis must be byte-identical.** The config string is part
of your index's contract — pin it in ONE place and use it on both sides:

```sql
-- Doris index side: whitespace passthrough (Doris must NOT re-analyze our tokens)
CREATE INVERTED INDEX TOKENIZER ws PROPERTIES("type"="char_group","tokenize_on_chars"="whitespace");
CREATE INVERTED INDEX ANALYZER passthru PROPERTIES("tokenizer"="ws");

CREATE TABLE docs (
  id BIGINT, body TEXT, body_tokens TEXT,
  INDEX idx_tok (body_tokens) USING INVERTED PROPERTIES("analyzer"="passthru", "support_phrase"="true")
) ...;

-- ingest:  INSERT ... SELECT ..., tokenize_with_lucene(body, '<CONFIG>') ...
-- query:   WHERE body_tokens MATCH_ALL tokenize_with_lucene('search terms', '<CONFIG>')
```

#### Build & deploy

```bash
./kotlin test               # 19 tests
./tools/build-udf-jar.sh    # -> build/brikk-doris-udfs-lucene-all.jar (flat shaded jar, ~22 MB)
```

The toolchain's `package` emits a Spring-Boot-style nested jar which Doris's plain
`URLClassLoader` cannot read; `tools/flatten-jar.py` flattens it (merging service files,
dropping `module-info.class`) and the build script smokes the result exactly the way
Doris loads it (`java -cp …`, no launcher).

Publish `brikk-doris-udfs-lucene-all.jar` as a GitHub release asset (public, anonymous download, BEs
fetch it once and cache), then:

```sql
CREATE FUNCTION tokenize_with_lucene(STRING, STRING) RETURNS STRING PROPERTIES (
  "type"   = "JAVA_UDF",
  "file"   = "https://github.com/<org>/brikk-doris-udfs/releases/download/<tag>/brikk-doris-udfs-lucene-all.jar",
  "symbol" = "dev.brikk.doris.udf.lucene.TokenizeUdf",
  "always_nullable" = "true"
);
```

Version the release **tag** on every change — BEs cache the jar per function signature
(~6 h default); `DROP`/`CREATE FUNCTION` against a new tag URL forces the reload.
Requires `enable_java_udf = true` on the BEs.

CLI smoke tool (same code path as the UDF):

```bash
java -cp build/brikk-doris-udfs-lucene-all.jar dev.brikk.doris.udf.lucene.MainKt english "John's running"
java -jar build/tasks/_udf-lucene_executableJarJvm/udf-lucene-jvm-executable.jar german "Häuser Bücher"
```

#### Gotcha worth knowing

Compatibility normalization (ligatures `ﬁ`, superscripts `²³`, full-width forms) must run
**before** tokenization — UAX#29 tokenizers drop such characters from word tokens, so a
token-filter-stage normalizer is too late. Use `icu_normalizer` as a **char_filter** (the
OpenSearch docs model it that way too); the test suite pins both behaviors.

## License

Apache-2.0.
