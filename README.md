# brikk-doris-udfs

Helpful Java UDFs for [Apache Doris](https://doris.apache.org/), verified against Doris 4.1.1 and later.

## Modules

### `udf-lucene` — OpenSearch-compatible text analysis 

| UDF Name           | Typical Function Name           |Description|
|--------------------|---------------------------------|-----------|
| TokenizeUdf        | `tokenize_with_lucene`          | Tokenize text using a fully configured analysis chain OpenSearch style, as a string|
| TokenizeUdfToArray | `tokenize_with_lucene_to_array` | Tokenize text, but this time returning an array of each token|

Using an OpenSearch style configuration of a custom analysis chain, tokenize a string and return either a string or an array.

The Doris builtin full text analysis has minimal configurability and some parts of Lucene are not exposed at all.  Here you
can define a completely custom analysis chain as you would in OpenSearch, with only a few minor limitations.

This includes using:
* tokenizers
* stemming
* stop words
* possessive / plural stripping
* ICU normalizationfolding
* multilingual tokenization


The configuration format is based on the [OpenSearch documentation](https://docs.opensearch.org/latest/analyzers/custom-analyzer/) of the same.  Any unsupported configuration fails loudly 
with an exception.

Register the function to use it: (note the link is from github releases)

```sql
CREATE GLOBAL FUNCTION tokenize_with_lucene(STRING, STRING) RETURNS STRING PROPERTIES (
  "type"   = "JAVA_UDF",
  "file"   = "https://github.com/brikk/brikk-doris-udfs/releases/download/v0.5.0/brikk-doris-udfs-lucene-all.jar",
  "symbol" = "dev.brikk.doris.udf.lucene.TokenizeUdf",
  "always_nullable" = "true"
);
```

or the array version:

```sql
CREATE GLOBAL FUNCTION tokenize_with_lucene_to_array(STRING, STRING) RETURNS ARRAY<STRING> PROPERTIES (
  "type"   = "JAVA_UDF",
  "file"   = "https://github.com/brikk/brikk-doris-udfs/releases/download/v0.5.0/brikk-doris-udfs-lucene-all.jar",
  "symbol" = "dev.brikk.doris.udf.lucene.TokenizeUdfToArray",
  "always_nullable" = "true"
);
```

Quick example using the declared function with [built-in analyzers](https://docs.opensearch.org/latest/analyzers/language-analyzers/index/):

```sql
SELECT tokenize_with_lucene('John''s running quickly the boxes', 'english');
-- 'john run quickli box'      (possessive stripped, stop words removed, Porter-stemmed)
```

And with a custom analyzer:

```sql
SELECT tokenize_with_lucene('Grüße aus Zürich: Straße', '{
  "tokenizer": "icu_tokenizer",
  "filter": [{"type": "icu_normalizer", "name": "nfkc_cf"}, "asciifolding"]
}');
-- 'grusse aus zurich strasse' (NFKC casefold: ß→ss; ASCII fold: ü→u)
```

#### Configuration

A builtin is simply the name of the builtin: i.e. `english` or as simple JSON `{"analyzer": "german"}`

Or the full OpenSearch style JSON
```jsonc
{    
  "char_filter": [ 
      {
        "type": "icu_normalizer", 
        "name": "nfkc_cf"
      }
  ],
  "tokenizer": "icu_tokenizer",
  "filter": [
    {
      "type": "word_delimiter_graph", 
      "stem_english_possessive": true,
      "protected_words": ["Wi-Fi"]
    },
    "asciifolding",
    {
      "type": "stop", 
      "stopwords": ["_english_", "corp"]
    },
    {
      "type": "stemmer", 
      "language": "english"
    }
  ]
}
```

#### Supported features

| kind | names                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
|---|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| analyzers | `standard` `simple` `whitespace` `keyword` `stop` `pattern` `fingerprint` `english` `german` `french` `italian` `spanish` `portuguese` `russian` `swedish` `danish` `norwegian` `finnish` `hungarian` `turkish`                                                                                                                                                                                                                                                                                                                 |
| tokenizers | `standard` `whitespace` `letter` `lowercase` `keyword` `classic` `uax_url_email` `thai` `icu_tokenizer` `ngram` `edge_ngram` `pattern` `char_group`                                                                                                                                                                                                                                                                                                                                                                |
| char filters | `html_strip` `mapping` `pattern_replace` `icu_normalizer`                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| token filters | `lowercase` `uppercase` `asciifolding` `stop` `stemmer` `snowball` `porter_stem` `kstem` `keyword_marker` `keyword_repeat` `remove_duplicates` `flatten_graph` `keep` `length` `limit` `truncate` `trim` `reverse` `apostrophe` `classic` `decimal_digit` `elision` `fingerprint` `ngram` `edge_ngram` `shingle` `word_delimiter` `word_delimiter_graph` `cjk_width` `cjk_bigram` `*_normalization` (german/arabic/persian/hindi/indic/sorani/bengali/serbian) `scandinavian_folding` `scandinavian_normalization` `icu_folding` `icu_normalizer` `icu_transform` |
| `stemmer` languages | `full` `OpenSearch` `algorithmic` `table` (english/light/minimal/possessive/porter2/lovins, german(2)/light/minimal, french/spanish/italian/portuguese families, Nordic + Snowball languages, arabic bengali brazilian czech greek hindi indonesian sorani …)                                                                                                                                                                                                                                      |
| `stopwords` sets | `_english_` `_german_` `_french_` …` (35 languages) + `_none_` + inline lists (mixable)                                                                                                                                                                                                                                                                                                                                                                                                         |

Not supported : `hunspell`, `synonym`/`synonym_graph`, `phonetic`,
`*_decompounder`, `multiplexer`/`condition`, `stopwords_path` (no files on BEs),
`stemmer_override`, `pattern_capture`, `common_grams`, `min_hash`, `path_hierarchy`.

#### Caching & canonicalization

The configuration and analyzer are cached, both by the exact text, and by the configuration object after parsing, this
allows either to find the already constructed analyzer for re-use.

To make things easier, use an "alias" or "global alias" function to wrap a configuration you want to re-use.

```sql
   CREATE ALIAS FUNCTION tokenize_multilang(STRING) WITH PARAMETER(text) AS
     tokenize_with_lucene(text,
       '{"char_filter":[{"type":"icu_normalizer","name":"nfkc_cf"}],"tokenizer":"icu_tokenizer","filter":[{"type":"stemmer","language":"possessive_english"},"asciifolding",{"type":"stop","stopwords":"_english_"},{"type":"stemmer","language":"english"}]}'
     );
```

Now your same configuration would be used for all calls, either indexing or querying.

> ⚠ **Doris alias functions were broken in 4.1.0**, please use **4.1.1 or newer** (fixed by
> [#63254](https://github.com/apache/doris/pull/63254), backported to 4.1 in
> [#63349](https://github.com/apache/doris/pull/63349); our original report.

#### Using with full text indexes

You must use the same tokenization for indexing and querying.  Creating an alias function
as mentioned above helps to guarantee this.  And Doris needs to be configured to pass through
the tokenization instead of retokenizing.

1. create your alias function
2. insert your text field into a tokens field wrapped by your tokenization function
3. wrap your query text field in the same tokenization function
4. create your inverted index with settings that break only by whitespace and pass through the rest

```
-- Doris index side: whitespace passthrough (Doris must NOT re-analyze our tokens)
CREATE INVERTED INDEX TOKENIZER ws PROPERTIES("type"="char_group","tokenize_on_chars"="whitespace");
CREATE INVERTED INDEX ANALYZER passthru PROPERTIES("tokenizer"="ws");

-- create a text field to hold the tokenized version
CREATE TABLE docs (
    id BIGINT, body TEXT, body_tokens TEXT,
    INDEX idx_tok (body_tokens) USING INVERTED PROPERTIES("analyzer"="passthru", "support_phrase"="true")
);

-- tokenize yourself on insert
INSERT INTO docs VALUES (id, body, body_tokens)
VALUES (1, :myBodyText, tokenize_multilang(:myBodyText));
```

#### A fancy multi-lingual preset: `brikk_multilang_english_v1`

We provide a preset that is mostly focused on English but does not destroy multi-lingual as well.

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
for display; stop-word removal makes pure-stop-word queries return nothing. 

This preset is defined as:

```json
{"char_filter": [{"type": "icu_normalizer", "name": "nfkc_cf"}],
 "tokenizer": "icu_tokenizer",
 "filter": [{"type": "stemmer", "language": "possessive_english"},
            "asciifolding",
            {"type": "stop", "stopwords": ["_english_"]},
            {"type": "stemmer", "language": "english"}]}
```

## Build & deploy

Easiest, use directly from Github releases, or download the JAR.

```bash
./kotlin test               # 19 tests
./tools/build-udf-jar.sh    # -> build/brikk-doris-udfs-lucene-all.jar (flat shaded jar, ~22 MB)
```

The toolchain's `package` emits a Spring-Boot-style nested jar which Doris's plain
`URLClassLoader` cannot read; `tools/flatten-jar.py` flattens it (merging service files,
dropping `module-info.class`) and the build script smokes the result exactly the way
Doris loads it (`java -cp …`, no launcher).

CLI smoke tool (same code path as the UDF):

```bash
java -cp build/brikk-doris-udfs-lucene-all.jar dev.brikk.doris.udf.lucene.MainKt english "John's running"
java -jar build/tasks/_udf-lucene_executableJarJvm/udf-lucene-jvm-executable.jar german "Häuser Bücher"
```

## License

Apache-2.0.
