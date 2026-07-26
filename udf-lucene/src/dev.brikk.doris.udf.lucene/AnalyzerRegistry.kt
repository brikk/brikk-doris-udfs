package dev.brikk.doris.udf.lucene

import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import java.util.concurrent.ConcurrentHashMap

/**
 * The two-hop analyzer cache:
 *
 *   config text ── hop 1 ──> [AnalysisConfig] ── hop 2 ──> Lucene [Analyzer]
 *
 * Hop 2 is keyed by the CANONICAL config data class (structural equals/hashCode via
 * `ConcurrentHashMap` — never a raw 32-bit hash, so key collisions cannot silently serve
 * the wrong analyzer). Different config-text spellings of the same chain (whitespace, key
 * order, `"english"` vs its expanded chain) land on the SAME analyzer instance; a
 * programmatically-built [AnalysisConfig] skips hop 1 and still hits the same entry.
 *
 * Both hops only cache successes — a bad config throws (loudly) on every call.
 */
object AnalyzerRegistry {

    // In practice a deployment uses a handful of configs; the caps are a defensive
    // circuit-breaker against unbounded growth (e.g. someone interpolating text into the
    // config argument), not an eviction policy. Blowing the cap clears and rebuilds.
    private const val MAX_ENTRIES = 1024

    private val parseCache = ConcurrentHashMap<String, AnalysisConfig>()
    private val analyzerCache = ConcurrentHashMap<AnalysisConfig, Analyzer>()

    fun config(configText: String): AnalysisConfig {
        if (parseCache.size > MAX_ENTRIES) parseCache.clear()
        return parseCache.computeIfAbsent(configText, AnalysisConfig::parse)
    }

    fun analyzer(config: AnalysisConfig): Analyzer {
        if (analyzerCache.size > MAX_ENTRIES) analyzerCache.clear()
        return analyzerCache.computeIfAbsent(config, LuceneChainBuilder::build)
    }

    fun analyzer(configText: String): Analyzer = analyzer(config(configText))

    /** Runs [text] through the configured chain, returning the tokens in order. */
    fun tokenize(text: String, configText: String): List<String> =
        tokenize(text, analyzer(configText))

    fun tokenize(text: String, analyzer: Analyzer): List<String> {
        val tokens = ArrayList<String>()
        analyzer.tokenStream("", text).use { stream ->
            val term = stream.addAttribute(CharTermAttribute::class.java)
            stream.reset()
            while (stream.incrementToken()) {
                tokens.add(term.toString())
            }
            stream.end()
        }
        return tokens
    }
}
