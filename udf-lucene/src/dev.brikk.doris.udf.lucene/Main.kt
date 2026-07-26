package dev.brikk.doris.udf.lucene

/**
 * CLI smoke tool over the exact same analysis path the Doris UDF uses:
 *
 * ```
 * java -jar udf-lucene-all.jar '<config>' 'text to analyze'
 * java -jar udf-lucene-all.jar english 'John''s running Straße'   # bare analyzer name
 * echo "text" | java -jar udf-lucene-all.jar '<config>'           # text from stdin
 * ```
 *
 * Prints one line: the space-joined tokens (identical to the UDF's return value).
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println(
            """
            usage: udf-lucene <config> [text]
              config: OpenSearch-style analysis config JSON, or a bare analyzer name
                      e.g. english
                           {"analyzer": "german"}
                           {"tokenizer": "icu_tokenizer", "filter": ["icu_folding", {"type": "stemmer", "language": "english"}]}
              text:   the text to analyze (read from stdin when omitted)
            """.trimIndent(),
        )
        kotlin.system.exitProcess(2)
    }
    val config = args[0]
    val text = if (args.size > 1) args.drop(1).joinToString(" ") else generateSequence(::readLine).joinToString("\n")
    println(TokenizeUdf().evaluate(text, config))
}
