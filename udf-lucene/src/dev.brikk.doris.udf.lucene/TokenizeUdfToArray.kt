package dev.brikk.doris.udf.lucene

/**
 * Array-returning variant of [TokenizeUdf]: same analysis path, but returns the tokens as
 * `ARRAY<STRING>` instead of a space-joined string — for callers that want to
 * `array_filter`/`array_map`/`explode` the tokens directly without a `split_by_string`
 * round-trip.
 *
 * Doris marshals ARRAY columns to/from `java.util.ArrayList` in the java-udf runtime
 * (BaseExecutor), so the evaluate return type must be `ArrayList<String>`.
 *
 * ```sql
 * CREATE FUNCTION tokenize_with_lucene_array(STRING, STRING) RETURNS ARRAY<STRING> PROPERTIES (
 *     "type"   = "JAVA_UDF",
 *     "file"   = "https://github.com/<org>/brikk-doris-udfs/releases/download/<tag>/brikk-doris-udfs-lucene-all.jar",
 *     "symbol" = "dev.brikk.doris.udf.lucene.TokenizeUdfToArray",
 *     "always_nullable" = "true"
 * );
 * ```
 */
class TokenizeUdfToArray {
    fun evaluate(text: String?, config: String?): ArrayList<String>? {
        if (text == null || config == null) return null
        return ArrayList(AnalyzerRegistry.tokenize(text, config))
    }
}
