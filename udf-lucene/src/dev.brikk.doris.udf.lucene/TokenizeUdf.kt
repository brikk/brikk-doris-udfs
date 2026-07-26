package dev.brikk.doris.udf.lucene

/**
 * Doris Java UDF: `tokenize_with_lucene(text, config) -> STRING` — runs [text] through an
 * OpenSearch-configured Lucene analysis chain and returns the tokens **space-joined, in
 * order** (positions are implicit in the order, so a whitespace-tokenized inverted index
 * over the result supports MATCH_ANY/ALL and phrase queries).
 *
 * Doris UDF contract (BaseExecutor/UdfExecutor): public class, public no-arg constructor,
 * public method named `evaluate`. Register with:
 * ```sql
 * CREATE FUNCTION tokenize_with_lucene(STRING, STRING) RETURNS STRING PROPERTIES (
 *     "type"   = "JAVA_UDF",
 *     "file"   = "https://github.com/<org>/<repo>/releases/download/<tag>/udf-lucene-all.jar",
 *     "symbol" = "dev.brikk.doris.udf.lucene.TokenizeUdf",
 *     "always_nullable" = "true"
 * );
 * ```
 *
 * ⚠ Index/query symmetry: the config string is part of your index contract. The exact
 * same config MUST be used at ingest (building the pre-tokenized column) and at query
 * (analyzing the search string) — pin it in one place and reference it from both sides.
 *
 * The analyzer for a given config is built once and cached (see [AnalyzerRegistry]);
 * per-row cost is tokenization only. Invalid configs THROW (failing the query) rather
 * than degrade — a silently-different analysis would corrupt index/query symmetry.
 */
class TokenizeUdf {
    fun evaluate(text: String?, config: String?): String? {
        if (text == null || config == null) return null
        return AnalyzerRegistry.tokenize(text, config).joinToString(" ")
    }
}
