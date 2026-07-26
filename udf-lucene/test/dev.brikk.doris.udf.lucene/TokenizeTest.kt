package dev.brikk.doris.udf.lucene

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TokenizeTest {

    private fun tokens(config: String, text: String): List<String> =
        AnalyzerRegistry.tokenize(text, config)

    // ── The headline multilingual chain: ICU split + NFKC casefold + ASCII fold ──

    @Test
    fun germanUmlautsAndSpecialsFoldToAscii() {
        val config = """{"tokenizer": "icu_tokenizer",
                         "filter": [{"type": "icu_normalizer", "name": "nfkc_cf"}, "asciifolding"]}"""
        assertEquals(
            listOf("grusse", "aus", "zurich", "strasse", "fussganger"),
            tokens(config, "Grüße aus Zürich: Straße, Fußgänger"),
        )
    }

    @Test
    fun icuNormalizerAsCharFilterNormalizesBeforeTokenization() {
        // Compatibility forms must be normalized BEFORE tokenization: UAX#29 drops
        // superscripts (²³) from word tokens, so a token-filter-stage normalizer would be
        // too late ('x²³' -> token 'x'). As a char_filter, NFKC_CF runs first: ﬁ -> fi,
        // ²³ -> 23 — this is why OpenSearch documents icu_normalizer as a char filter.
        val config = """{"char_filter": [{"type": "icu_normalizer", "name": "nfkc_cf"}],
                         "tokenizer": "icu_tokenizer",
                         "filter": ["asciifolding"]}"""
        assertEquals(listOf("file", "x23"), tokens(config, "ﬁle x²³"))
    }

    @Test
    fun icuFoldingFoldsAccentsAndCase() {
        val config = """{"tokenizer": "icu_tokenizer", "filter": ["icu_folding"]}"""
        assertEquals(listOf("dona", "cafe", "naive"), tokens(config, "Doña café naïve"))
    }

    // ── OpenSearch built-in analyzers (preset expansion) ──

    @Test
    fun englishAnalyzerStemsStopsAndStripsPossessives() {
        // standard + possessive_english + lowercase + stop(_english_) + porter
        assertEquals(
            listOf("john", "run", "quickli", "box"),
            tokens("english", "John's running quickly the boxes"),
        )
    }

    @Test
    fun germanAnalyzerNormalizesUmlautsAndStems() {
        assertEquals(listOf("haus", "buch"), tokens("german", "Häuser Bücher"))
    }

    @Test
    fun standardAnalyzerLowercasesWithoutStopWords() {
        assertEquals(
            listOf("life", "is", "like", "a", "box", "of", "chocolates"),
            tokens("standard", "Life is like a box of chocolates"),
        )
    }

    @Test
    fun stopFilterWithPredefinedEnglishSet() {
        val config = """{"tokenizer": "standard",
                         "filter": ["lowercase", {"type": "stop", "stopwords": "_english_"}]}"""
        assertEquals(
            listOf("life", "like", "box", "chocolates"),
            tokens(config, "Life is like a box of chocolates"),
        )
    }

    @Test
    fun stopFilterWithInlineWordListMixedWithNamedSet() {
        val config = """{"tokenizer": "standard",
                         "filter": ["lowercase",
                                    {"type": "stop", "stopwords": ["_english_", "chocolates"]}]}"""
        assertEquals(listOf("life", "like", "box"), tokens(config, "Life is like a box of chocolates"))
    }

    // ── word_delimiter_graph: possessives + protected words ──

    @Test
    fun wordDelimiterStripsPossessivesAndHonorsProtectedWords() {
        val config = """{"tokenizer": "whitespace",
                         "filter": [{"type": "word_delimiter_graph",
                                     "split_on_case_change": false,
                                     "split_on_numerics": false,
                                     "protected_words": ["Wi-Fi"]}]}"""
        assertEquals(
            listOf("John", "Wi-Fi", "TCP", "IP"),
            tokens(config, "John's Wi-Fi TCP/IP"),
        )
    }

    // ── Canonicalization / caching ──

    @Test
    fun namedAnalyzerAndSpellingVariantsShareOneAnalyzerInstance() {
        val a = AnalyzerRegistry.analyzer("english")
        val b = AnalyzerRegistry.analyzer("""{"analyzer": "english"}""")
        val c = AnalyzerRegistry.analyzer("""{  "analyzer"  :  "english"  }""")
        assertSame(a, b)
        assertSame(a, c)
        // A programmatically-built canonical config hits the same hop-2 entry.
        val fromDataClass = AnalyzerRegistry.analyzer(AnalysisConfig.parse("english"))
        assertSame(a, fromDataClass)
    }

    @Test
    fun differentConfigsGetDifferentAnalyzers() {
        assertTrue(AnalyzerRegistry.analyzer("english") !== AnalyzerRegistry.analyzer("german"))
    }

    // ── Fail-loud contract ──

    @Test
    fun unknownTokenFilterFailsWithSupportedList() {
        val e = assertFailsWith<AnalysisConfigException> {
            tokens("""{"tokenizer": "standard", "filter": ["hunspell"]}""", "x")
        }
        assertTrue("unsupported token filter 'hunspell'" in e.message!!)
        assertTrue("Supported:" in e.message!!)
    }

    @Test
    fun unknownAnalyzerFails() {
        val e = assertFailsWith<AnalysisConfigException> { tokens("klingon", "x") }
        assertTrue("unsupported analyzer 'klingon'" in e.message!!)
    }

    @Test
    fun typoedParameterFails() {
        val e = assertFailsWith<AnalysisConfigException> {
            tokens("""{"tokenizer": "standard", "filter": [{"type": "stemmer", "langauge": "english"}]}""", "x")
        }
        assertTrue("langauge" in e.message!!)
    }

    @Test
    fun unknownPredefinedStopSetFails() {
        assertFailsWith<AnalysisConfigException> {
            tokens("""{"tokenizer": "standard", "filter": [{"type": "stop", "stopwords": "_klingon_"}]}""", "x")
        }
    }

    @Test
    fun analyzerCombinedWithTokenizerFails() {
        assertFailsWith<AnalysisConfigException> {
            tokens("""{"analyzer": "english", "tokenizer": "standard"}""", "x")
        }
    }

    // ── UDF surface ──

    @Test
    fun udfNullPropagationAndJoining() {
        val udf = TokenizeUdf()
        assertNull(udf.evaluate(null, "english"))
        assertNull(udf.evaluate("text", null))
        assertEquals("john run quickli box", udf.evaluate("John's running quickly the boxes", "english"))
    }

    @Test
    fun bareAnalyzerNameShorthand() {
        assertEquals(listOf("Hello", "World"), tokens("whitespace", "Hello World"))
    }

    @Test
    fun brikkMultilangEnglishV1Preset() {
        // The one-token pinned config (Doris has no server-side config storage; the
        // preset name IS the contract, frozen per jar release).
        assertEquals(
            listOf("muller", "fussgang", "run", "quickli", "zurich", "cafe"),
            tokens("brikk_multilang_english_v1", "The Müller's Fußgänger are running quickly to Zürich's café"),
        )
        // The preset and its explicit spelled-out chain are the SAME analyzer instance.
        val explicit = """{"char_filter": [{"type": "icu_normalizer", "name": "nfkc_cf"}],
                           "tokenizer": "icu_tokenizer",
                           "filter": [{"type": "stemmer", "language": "possessive_english"},
                                      "asciifolding",
                                      {"type": "stop", "stopwords": ["_english_"]},
                                      {"type": "stemmer", "language": "english"}]}"""
        assertSame(
            AnalyzerRegistry.analyzer("brikk_multilang_english_v1"),
            AnalyzerRegistry.analyzer(explicit),
        )
    }

    @Test
    fun stemmerLanguageTable() {
        // snowball porter2 vs algorithmic porter vs kstem are all reachable by name.
        val porter2 = """{"tokenizer": "standard", "filter": ["lowercase", {"type": "stemmer", "language": "porter2"}]}"""
        val kstem = """{"tokenizer": "standard", "filter": ["lowercase", "kstem"]}"""
        assertEquals(listOf("run"), tokens(porter2, "running"))
        assertEquals(listOf("running"), tokens(kstem, "running")) // kstem keeps 'running' (dictionary-based)
    }
}
