package dev.brikk.doris.udf.lucene

import com.ibm.icu.text.FilteredNormalizer2
import com.ibm.icu.text.Normalizer2
import com.ibm.icu.text.Transliterator
import com.ibm.icu.text.UnicodeSet
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.CharArraySet
import org.apache.lucene.analysis.LowerCaseFilter
import org.apache.lucene.analysis.StopFilter
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.Tokenizer
import org.apache.lucene.analysis.ar.ArabicNormalizationFilter
import org.apache.lucene.analysis.bn.BengaliNormalizationFilter
import org.apache.lucene.analysis.charfilter.HTMLStripCharFilter
import org.apache.lucene.analysis.charfilter.MappingCharFilter
import org.apache.lucene.analysis.charfilter.NormalizeCharMap
import org.apache.lucene.analysis.cjk.CJKBigramFilter
import org.apache.lucene.analysis.cjk.CJKWidthFilter
import org.apache.lucene.analysis.ckb.SoraniNormalizationFilter
import org.apache.lucene.analysis.core.DecimalDigitFilter
import org.apache.lucene.analysis.core.FlattenGraphFilter
import org.apache.lucene.analysis.core.KeywordTokenizer
import org.apache.lucene.analysis.core.LetterTokenizer
import org.apache.lucene.analysis.core.UpperCaseFilter
import org.apache.lucene.analysis.core.WhitespaceTokenizer
import org.apache.lucene.analysis.de.GermanNormalizationFilter
import org.apache.lucene.analysis.el.GreekLowerCaseFilter
import org.apache.lucene.analysis.fa.PersianNormalizationFilter
import org.apache.lucene.analysis.util.ElisionFilter
import org.apache.lucene.analysis.ga.IrishLowerCaseFilter
import org.apache.lucene.analysis.hi.HindiNormalizationFilter
import org.apache.lucene.analysis.icu.ICUFoldingFilter
import org.apache.lucene.analysis.icu.ICUNormalizer2CharFilter
import org.apache.lucene.analysis.icu.ICUNormalizer2Filter
import org.apache.lucene.analysis.icu.ICUTransformFilter
import org.apache.lucene.analysis.icu.segmentation.ICUTokenizer
import org.apache.lucene.analysis.`in`.IndicNormalizationFilter
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter
import org.apache.lucene.analysis.miscellaneous.FingerprintFilter
import org.apache.lucene.analysis.miscellaneous.KeepWordFilter
import org.apache.lucene.analysis.miscellaneous.KeywordRepeatFilter
import org.apache.lucene.analysis.miscellaneous.LengthFilter
import org.apache.lucene.analysis.miscellaneous.LimitTokenCountFilter
import org.apache.lucene.analysis.miscellaneous.RemoveDuplicatesTokenFilter
import org.apache.lucene.analysis.miscellaneous.ScandinavianFoldingFilter
import org.apache.lucene.analysis.miscellaneous.ScandinavianNormalizationFilter
import org.apache.lucene.analysis.miscellaneous.SetKeywordMarkerFilter
import org.apache.lucene.analysis.miscellaneous.TrimFilter
import org.apache.lucene.analysis.miscellaneous.TruncateTokenFilter
import org.apache.lucene.analysis.miscellaneous.WordDelimiterGraphFilter
import org.apache.lucene.analysis.ngram.EdgeNGramTokenFilter
import org.apache.lucene.analysis.ngram.EdgeNGramTokenizer
import org.apache.lucene.analysis.ngram.NGramTokenFilter
import org.apache.lucene.analysis.ngram.NGramTokenizer
import org.apache.lucene.analysis.pattern.PatternReplaceCharFilter
import org.apache.lucene.analysis.pattern.PatternTokenizer
import org.apache.lucene.analysis.reverse.ReverseStringFilter
import org.apache.lucene.analysis.shingle.ShingleFilter
import org.apache.lucene.analysis.sr.SerbianNormalizationFilter
import org.apache.lucene.analysis.classic.ClassicFilter
import org.apache.lucene.analysis.classic.ClassicTokenizer
import org.apache.lucene.analysis.standard.StandardTokenizer
import org.apache.lucene.analysis.email.UAX29URLEmailTokenizer
import org.apache.lucene.analysis.th.ThaiTokenizer
import org.apache.lucene.analysis.tr.ApostropheFilter
import org.apache.lucene.analysis.tr.TurkishLowerCaseFilter
import org.apache.lucene.analysis.util.CharTokenizer
import java.io.Reader
import java.util.regex.Pattern

/**
 * Translates a canonical [AnalysisConfig] (OpenSearch vocabulary) into a Lucene [Analyzer].
 *
 * Construction is DIRECT (explicit Lucene classes), deliberately avoiding Lucene's SPI
 * name lookup (`CustomAnalyzer.builder()`): SPI depends on `META-INF/services` files that
 * fat-jar packaging can merge incorrectly, and a missing service entry would surface as a
 * "name not found" at runtime. Direct construction is packaging-proof and makes the
 * supported surface explicit — anything unmapped fails loudly with the supported list.
 */
object LuceneChainBuilder {

    fun build(config: AnalysisConfig): Analyzer {
        val analyzer = ChainAnalyzer(config)
        // Validation ping: force one full chain construction NOW, so an unsupported
        // component/parameter throws at build/cache-insert time, not on first evaluate.
        analyzer.tokenStream("_validate", "").use { it.reset(); while (it.incrementToken()) Unit; it.end() }
        return analyzer
    }

    private class ChainAnalyzer(private val config: AnalysisConfig) : Analyzer() {
        override fun createComponents(fieldName: String): TokenStreamComponents {
            val built = buildTokenizer(config.tokenizer)
            var stream: TokenStream = built.implicitWrap(built.tokenizer)
            for (spec in config.tokenFilters) stream = buildTokenFilter(spec, stream)
            return TokenStreamComponents(built.tokenizer, stream)
        }

        override fun initReader(fieldName: String, reader: Reader): Reader =
            config.charFilters.fold(reader) { r, spec -> buildCharFilter(spec, r) }
    }

    // ── Tokenizers ────────────────────────────────────────────────────────────

    private class BuiltTokenizer(
        val tokenizer: Tokenizer,
        val implicitWrap: (TokenStream) -> TokenStream = { it },
    )

    private fun buildTokenizer(spec: ComponentSpec): BuiltTokenizer = when (spec.type) {
        "standard" -> {
            spec.requireOnly(setOf("max_token_length"))
            BuiltTokenizer(StandardTokenizer().apply { maxTokenLength = spec.int("max_token_length", 255) })
        }
        "whitespace" -> {
            spec.requireOnly(setOf("max_token_length"))
            BuiltTokenizer(WhitespaceTokenizer())
        }
        "letter" -> {
            spec.requireOnly(emptySet())
            BuiltTokenizer(LetterTokenizer())
        }
        // OpenSearch's `lowercase` tokenizer = letter tokenizer + lowercasing
        // (Lucene removed LowerCaseTokenizer; the composition is the documented equivalent).
        "lowercase" -> {
            spec.requireOnly(emptySet())
            BuiltTokenizer(LetterTokenizer()) { LowerCaseFilter(it) }
        }
        "keyword" -> {
            spec.requireOnly(emptySet())
            BuiltTokenizer(KeywordTokenizer())
        }
        "classic" -> {
            spec.requireOnly(setOf("max_token_length"))
            BuiltTokenizer(ClassicTokenizer().apply { maxTokenLength = spec.int("max_token_length", 255) })
        }
        "uax_url_email" -> {
            spec.requireOnly(setOf("max_token_length"))
            BuiltTokenizer(UAX29URLEmailTokenizer().apply { maxTokenLength = spec.int("max_token_length", 255) })
        }
        "thai" -> {
            spec.requireOnly(emptySet())
            BuiltTokenizer(ThaiTokenizer())
        }
        "icu_tokenizer" -> {
            spec.requireOnly(emptySet())
            BuiltTokenizer(ICUTokenizer())
        }
        "ngram" -> {
            spec.requireOnly(setOf("min_gram", "max_gram", "token_chars", "custom_token_chars"))
            if (!spec.strings("token_chars").isNullOrEmpty()) {
                throw AnalysisConfigException(
                    "'token_chars' on the ngram tokenizer is not supported yet; " +
                        "pre-filter with a char_filter or use the ngram token filter",
                )
            }
            BuiltTokenizer(NGramTokenizer(spec.int("min_gram", 1), spec.int("max_gram", 2)))
        }
        "edge_ngram" -> {
            spec.requireOnly(setOf("min_gram", "max_gram", "token_chars", "custom_token_chars"))
            if (!spec.strings("token_chars").isNullOrEmpty()) {
                throw AnalysisConfigException(
                    "'token_chars' on the edge_ngram tokenizer is not supported yet; " +
                        "pre-filter with a char_filter or use the edge_ngram token filter",
                )
            }
            BuiltTokenizer(EdgeNGramTokenizer(spec.int("min_gram", 1), spec.int("max_gram", 2)))
        }
        "pattern" -> {
            spec.requireOnly(setOf("pattern", "flags", "group"))
            val pattern = compilePattern(spec.str("pattern") ?: "\\W+", spec.str("flags"))
            BuiltTokenizer(PatternTokenizer(pattern, spec.int("group", -1)))
        }
        "char_group" -> {
            spec.requireOnly(setOf("tokenize_on_chars", "max_token_length"))
            val on = spec.strings("tokenize_on_chars")
                ?: throw AnalysisConfigException("char_group tokenizer requires 'tokenize_on_chars'")
            BuiltTokenizer(charGroupTokenizer(on))
        }
        else -> throw AnalysisConfigException(
            "unsupported tokenizer '${spec.type}'. Supported: ${TOKENIZERS.sorted()}",
        )
    }

    private val TOKENIZERS = setOf(
        "standard", "whitespace", "letter", "lowercase", "keyword", "classic",
        "uax_url_email", "thai", "icu_tokenizer", "ngram", "edge_ngram", "pattern", "char_group",
    )

    private fun charGroupTokenizer(tokenizeOn: List<String>): Tokenizer {
        var letters = false
        var digits = false
        var whitespace = false
        var punctuation = false
        var symbols = false
        val literals = HashSet<Int>()
        for (entry in tokenizeOn) {
            when (entry) {
                "letter" -> letters = true
                "digit" -> digits = true
                "whitespace" -> whitespace = true
                "punctuation" -> punctuation = true
                "symbol" -> symbols = true
                else -> {
                    val decoded = when (entry) {
                        "\\n" -> '\n'
                        "\\t" -> '\t'
                        "\\r" -> '\r'
                        else -> {
                            if (entry.codePointCount(0, entry.length) != 1) {
                                throw AnalysisConfigException(
                                    "char_group 'tokenize_on_chars' entries must be a char class " +
                                        "(letter|digit|whitespace|punctuation|symbol) or a single character, got '$entry'",
                                )
                            }
                            entry[0]
                        }
                    }
                    literals.add(decoded.code)
                }
            }
        }
        return CharTokenizer.fromSeparatorCharPredicate { c ->
            (letters && Character.isLetter(c)) ||
                (digits && Character.isDigit(c)) ||
                (whitespace && Character.isWhitespace(c)) ||
                (punctuation && isPunctuation(c)) ||
                (symbols && isSymbol(c)) ||
                literals.contains(c)
        }
    }

    private fun isPunctuation(c: Int): Boolean = when (Character.getType(c).toByte()) {
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
        Character.END_PUNCTUATION, Character.OTHER_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION,
        Character.FINAL_QUOTE_PUNCTUATION,
        -> true
        else -> false
    }

    private fun isSymbol(c: Int): Boolean = when (Character.getType(c).toByte()) {
        Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL,
        -> true
        else -> false
    }

    // ── Char filters ──────────────────────────────────────────────────────────

    private fun buildCharFilter(spec: ComponentSpec, reader: Reader): Reader = when (spec.type) {
        "html_strip" -> {
            spec.requireOnly(setOf("escaped_tags"))
            val escaped = spec.strings("escaped_tags")
            if (escaped == null) HTMLStripCharFilter(reader) else HTMLStripCharFilter(reader, escaped.toSet())
        }
        "mapping" -> {
            spec.requireOnly(setOf("mappings"))
            val mappings = spec.strings("mappings")
                ?: throw AnalysisConfigException("mapping char filter requires 'mappings'")
            val builder = NormalizeCharMap.Builder()
            for (m in mappings) {
                val idx = m.indexOf("=>")
                if (idx < 0) {
                    throw AnalysisConfigException("mapping entry must be 'from => to', got '$m'")
                }
                builder.add(m.take(idx).trim(), m.substring(idx + 2).trim())
            }
            MappingCharFilter(builder.build(), reader)
        }
        "pattern_replace" -> {
            spec.requireOnly(setOf("pattern", "replacement", "flags"))
            val pattern = compilePattern(
                spec.str("pattern")
                    ?: throw AnalysisConfigException("pattern_replace char filter requires 'pattern'"),
                spec.str("flags"),
            )
            PatternReplaceCharFilter(pattern, spec.str("replacement") ?: "", reader)
        }
        "icu_normalizer" -> {
            spec.requireOnly(setOf("name", "mode", "unicode_set_filter"))
            ICUNormalizer2CharFilter(reader, icuNormalizer(spec))
        }
        else -> throw AnalysisConfigException(
            "unsupported char_filter '${spec.type}'. Supported: ${CHAR_FILTERS.sorted()}",
        )
    }

    private val CHAR_FILTERS = setOf("html_strip", "mapping", "pattern_replace", "icu_normalizer")

    // ── Token filters ─────────────────────────────────────────────────────────

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun buildTokenFilter(spec: ComponentSpec, input: TokenStream): TokenStream = when (spec.type) {
        "lowercase" -> {
            spec.requireOnly(setOf("language"))
            when (val language = spec.str("language")) {
                null -> LowerCaseFilter(input)
                "greek" -> GreekLowerCaseFilter(input)
                "irish" -> IrishLowerCaseFilter(input)
                "turkish" -> TurkishLowerCaseFilter(input)
                else -> throw AnalysisConfigException(
                    "unsupported lowercase language '$language'. Supported: [greek, irish, turkish]",
                )
            }
        }
        "uppercase" -> {
            spec.requireOnly(emptySet())
            UpperCaseFilter(input)
        }
        "asciifolding" -> {
            spec.requireOnly(setOf("preserve_original"))
            ASCIIFoldingFilter(input, spec.bool("preserve_original", false))
        }
        "stop" -> {
            spec.requireOnly(setOf("stopwords", "ignore_case", "remove_trailing"))
            if (!spec.bool("remove_trailing", true)) {
                throw AnalysisConfigException("'remove_trailing: false' is not supported (suggester-only feature)")
            }
            val words = spec.strings("stopwords") ?: listOf("_english_")
            StopFilter(input, StopWordSets.resolve(words, spec.bool("ignore_case", false)))
        }
        "stemmer" -> {
            spec.requireOnly(setOf("language", "name"))
            val language = spec.str("language") ?: spec.str("name")
                ?: throw AnalysisConfigException("stemmer requires 'language' (or 'name')")
            Stemmers.apply(language, input)
        }
        "snowball" -> {
            spec.requireOnly(setOf("language"))
            Stemmers.snowball(spec.str("language") ?: "English", input)
        }
        "porter_stem" -> {
            spec.requireOnly(emptySet())
            Stemmers.apply("porter", input)
        }
        "kstem" -> {
            spec.requireOnly(emptySet())
            Stemmers.apply("light_english", input)
        }
        "keyword_marker" -> {
            spec.requireOnly(setOf("keywords", "ignore_case"))
            val keywords = spec.strings("keywords")
                ?: throw AnalysisConfigException("keyword_marker requires 'keywords'")
            SetKeywordMarkerFilter(input, toCharArraySet(keywords, spec.bool("ignore_case", false)))
        }
        "keyword_repeat" -> {
            spec.requireOnly(emptySet())
            KeywordRepeatFilter(input)
        }
        "remove_duplicates" -> {
            spec.requireOnly(emptySet())
            RemoveDuplicatesTokenFilter(input)
        }
        "flatten_graph" -> {
            spec.requireOnly(emptySet())
            FlattenGraphFilter(input)
        }
        "keep" -> {
            spec.requireOnly(setOf("keep_words", "keep_words_case"))
            val keep = spec.strings("keep_words")
                ?: throw AnalysisConfigException("keep filter requires 'keep_words'")
            KeepWordFilter(input, toCharArraySet(keep, spec.bool("keep_words_case", false)))
        }
        "length" -> {
            spec.requireOnly(setOf("min", "max"))
            LengthFilter(input, spec.int("min", 0), spec.int("max", Int.MAX_VALUE))
        }
        "limit" -> {
            spec.requireOnly(setOf("max_token_count", "consume_all_tokens"))
            LimitTokenCountFilter(input, spec.int("max_token_count", 1), spec.bool("consume_all_tokens", false))
        }
        "truncate" -> {
            spec.requireOnly(setOf("length"))
            TruncateTokenFilter(input, spec.int("length", 10))
        }
        "trim" -> {
            spec.requireOnly(emptySet())
            TrimFilter(input)
        }
        "reverse" -> {
            spec.requireOnly(emptySet())
            ReverseStringFilter(input)
        }
        "apostrophe" -> {
            spec.requireOnly(emptySet())
            ApostropheFilter(input)
        }
        "classic" -> {
            spec.requireOnly(emptySet())
            ClassicFilter(input)
        }
        "decimal_digit" -> {
            spec.requireOnly(emptySet())
            DecimalDigitFilter(input)
        }
        "elision" -> {
            spec.requireOnly(setOf("articles", "articles_case"))
            val articles = spec.strings("articles")
                ?: throw AnalysisConfigException("elision requires 'articles'")
            ElisionFilter(input, toCharArraySet(articles, spec.bool("articles_case", false)))
        }
        "fingerprint" -> {
            spec.requireOnly(setOf("separator", "max_output_size"))
            val separator = spec.str("separator") ?: " "
            if (separator.length != 1) {
                throw AnalysisConfigException("fingerprint 'separator' must be a single character")
            }
            FingerprintFilter(input, spec.int("max_output_size", 255), separator[0])
        }
        "ngram" -> {
            spec.requireOnly(setOf("min_gram", "max_gram", "preserve_original"))
            NGramTokenFilter(input, spec.int("min_gram", 1), spec.int("max_gram", 2), spec.bool("preserve_original", false))
        }
        "edge_ngram" -> {
            spec.requireOnly(setOf("min_gram", "max_gram", "preserve_original"))
            EdgeNGramTokenFilter(input, spec.int("min_gram", 1), spec.int("max_gram", 2), spec.bool("preserve_original", false))
        }
        "shingle" -> {
            spec.requireOnly(
                setOf(
                    "min_shingle_size", "max_shingle_size", "output_unigrams",
                    "output_unigrams_if_no_shingles", "token_separator", "filler_token",
                ),
            )
            ShingleFilter(input, spec.int("min_shingle_size", 2), spec.int("max_shingle_size", 2)).apply {
                setOutputUnigrams(spec.bool("output_unigrams", true))
                setOutputUnigramsIfNoShingles(spec.bool("output_unigrams_if_no_shingles", false))
                setTokenSeparator(spec.str("token_separator") ?: " ")
                setFillerToken(spec.str("filler_token") ?: "_")
            }
        }
        "word_delimiter_graph" -> {
            spec.requireOnly(WORD_DELIMITER_PARAMS)
            WordDelimiterGraphFilter(input, wordDelimiterFlags(spec, graph = true), protectedWords(spec))
        }
        "word_delimiter" -> {
            spec.requireOnly(WORD_DELIMITER_PARAMS)
            @Suppress("DEPRECATION")
            org.apache.lucene.analysis.miscellaneous.WordDelimiterFilter(
                input, wordDelimiterFlags(spec, graph = false), protectedWords(spec),
            )
        }
        "cjk_width" -> {
            spec.requireOnly(emptySet())
            CJKWidthFilter(input)
        }
        "cjk_bigram" -> {
            spec.requireOnly(setOf("ignored_scripts", "output_unigrams"))
            var flags = 0
            val ignored = spec.strings("ignored_scripts")?.toSet() ?: emptySet()
            if ("han" !in ignored) flags = flags or CJKBigramFilter.HAN
            if ("hiragana" !in ignored) flags = flags or CJKBigramFilter.HIRAGANA
            if ("katakana" !in ignored) flags = flags or CJKBigramFilter.KATAKANA
            if ("hangul" !in ignored) flags = flags or CJKBigramFilter.HANGUL
            CJKBigramFilter(input, flags, spec.bool("output_unigrams", false))
        }
        // Language normalization filters (parameterless)
        "german_normalization" -> parameterless(spec) { GermanNormalizationFilter(input) }
        "arabic_normalization" -> parameterless(spec) { ArabicNormalizationFilter(input) }
        "persian_normalization" -> parameterless(spec) { PersianNormalizationFilter(input) }
        "hindi_normalization" -> parameterless(spec) { HindiNormalizationFilter(input) }
        "indic_normalization" -> parameterless(spec) { IndicNormalizationFilter(input) }
        "sorani_normalization" -> parameterless(spec) { SoraniNormalizationFilter(input) }
        "bengali_normalization" -> parameterless(spec) { BengaliNormalizationFilter(input) }
        "serbian_normalization" -> parameterless(spec) { SerbianNormalizationFilter(input) }
        "scandinavian_folding" -> parameterless(spec) { ScandinavianFoldingFilter(input) }
        "scandinavian_normalization" -> parameterless(spec) { ScandinavianNormalizationFilter(input) }
        // ICU (analysis-icu plugin vocabulary)
        "icu_folding" -> {
            spec.requireOnly(setOf("unicode_set_filter"))
            val filter = spec.str("unicode_set_filter")
            if (filter == null) {
                ICUFoldingFilter(input)
            } else {
                ICUFoldingFilter(
                    input,
                    FilteredNormalizer2(ICUFoldingFilter.NORMALIZER, UnicodeSet(filter).freeze()),
                )
            }
        }
        "icu_normalizer" -> {
            spec.requireOnly(setOf("name", "unicode_set_filter"))
            ICUNormalizer2Filter(input, icuNormalizer(spec))
        }
        "icu_transform" -> {
            spec.requireOnly(setOf("id", "dir"))
            val id = spec.str("id") ?: throw AnalysisConfigException("icu_transform requires 'id'")
            val direction = when (spec.str("dir") ?: "forward") {
                "forward" -> Transliterator.FORWARD
                "reverse" -> Transliterator.REVERSE
                else -> throw AnalysisConfigException("icu_transform 'dir' must be forward or reverse")
            }
            ICUTransformFilter(input, Transliterator.getInstance(id, direction))
        }
        else -> throw AnalysisConfigException(
            "unsupported token filter '${spec.type}'. Supported: ${TOKEN_FILTERS.sorted()}",
        )
    }

    private val TOKEN_FILTERS = setOf(
        "lowercase", "uppercase", "asciifolding", "stop", "stemmer", "snowball", "porter_stem",
        "kstem", "keyword_marker", "keyword_repeat", "remove_duplicates", "flatten_graph", "keep",
        "length", "limit", "truncate", "trim", "reverse", "apostrophe", "classic", "decimal_digit",
        "elision", "fingerprint", "ngram", "edge_ngram", "shingle", "word_delimiter",
        "word_delimiter_graph", "cjk_width", "cjk_bigram",
        "german_normalization", "arabic_normalization", "persian_normalization",
        "hindi_normalization", "indic_normalization", "sorani_normalization",
        "bengali_normalization", "serbian_normalization",
        "scandinavian_folding", "scandinavian_normalization",
        "icu_folding", "icu_normalizer", "icu_transform",
    )

    // ── helpers ───────────────────────────────────────────────────────────────

    private inline fun parameterless(spec: ComponentSpec, build: () -> TokenStream): TokenStream {
        spec.requireOnly(emptySet())
        return build()
    }

    private fun toCharArraySet(words: List<String>, ignoreCase: Boolean): CharArraySet =
        CharArraySet(words, ignoreCase)

    private val WORD_DELIMITER_PARAMS = setOf(
        "generate_word_parts", "generate_number_parts", "catenate_words", "catenate_numbers",
        "catenate_all", "preserve_original", "split_on_case_change", "split_on_numerics",
        "stem_english_possessive", "protected_words", "adjust_offsets",
    )

    private fun protectedWords(spec: ComponentSpec): CharArraySet? =
        spec.strings("protected_words")?.let { CharArraySet(it, false) }

    private fun wordDelimiterFlags(spec: ComponentSpec, graph: Boolean): Int {
        // Flag constants are identical values in WordDelimiterGraphFilter and the
        // deprecated WordDelimiterFilter; use the graph filter's as canonical.
        var flags = 0
        fun flag(key: String, bit: Int, default: Boolean) {
            if (spec.bool(key, default)) flags = flags or bit
        }
        flag("generate_word_parts", WordDelimiterGraphFilter.GENERATE_WORD_PARTS, true)
        flag("generate_number_parts", WordDelimiterGraphFilter.GENERATE_NUMBER_PARTS, true)
        flag("catenate_words", WordDelimiterGraphFilter.CATENATE_WORDS, false)
        flag("catenate_numbers", WordDelimiterGraphFilter.CATENATE_NUMBERS, false)
        flag("catenate_all", WordDelimiterGraphFilter.CATENATE_ALL, false)
        flag("preserve_original", WordDelimiterGraphFilter.PRESERVE_ORIGINAL, false)
        flag("split_on_case_change", WordDelimiterGraphFilter.SPLIT_ON_CASE_CHANGE, true)
        flag("split_on_numerics", WordDelimiterGraphFilter.SPLIT_ON_NUMERICS, true)
        flag("stem_english_possessive", WordDelimiterGraphFilter.STEM_ENGLISH_POSSESSIVE, true)
        if (graph && spec.bool("adjust_offsets", true)) {
            // WordDelimiterGraphFilter adjusts offsets by default; the boolean ctor variant
            // is covered by the default flag set — nothing extra to set here.
        }
        return flags
    }

    private fun icuNormalizer(spec: ComponentSpec): Normalizer2 {
        val name = spec.str("name") ?: "nfkc_cf"
        val mode = spec.str("mode") // char-filter only; token filter never passes it
        val (icuName, icuMode) = when (name) {
            "nfc" -> "nfc" to Normalizer2.Mode.COMPOSE
            "nfd" -> "nfc" to Normalizer2.Mode.DECOMPOSE
            "nfkc" -> "nfkc" to Normalizer2.Mode.COMPOSE
            "nfkd" -> "nfkc" to Normalizer2.Mode.DECOMPOSE
            "nfkc_cf" -> "nfkc_cf" to Normalizer2.Mode.COMPOSE
            else -> throw AnalysisConfigException(
                "unsupported icu_normalizer name '$name'. Supported: [nfc, nfd, nfkc, nfkd, nfkc_cf]",
            )
        }
        val resolvedMode = when (mode) {
            null -> icuMode
            "compose" -> Normalizer2.Mode.COMPOSE
            "decompose" -> Normalizer2.Mode.DECOMPOSE
            else -> throw AnalysisConfigException("icu_normalizer 'mode' must be compose or decompose")
        }
        val base = Normalizer2.getInstance(null, icuName, resolvedMode)
        val setFilter = spec.str("unicode_set_filter") ?: return base
        return FilteredNormalizer2(base, UnicodeSet(setFilter).freeze())
    }

    private fun compilePattern(pattern: String, flags: String?): Pattern {
        var f = 0
        if (!flags.isNullOrBlank()) {
            for (name in flags.split("|")) {
                f = f or when (name.trim()) {
                    "CASE_INSENSITIVE" -> Pattern.CASE_INSENSITIVE
                    "MULTILINE" -> Pattern.MULTILINE
                    "DOTALL" -> Pattern.DOTALL
                    "UNICODE_CASE" -> Pattern.UNICODE_CASE
                    "CANON_EQ" -> Pattern.CANON_EQ
                    "UNIX_LINES" -> Pattern.UNIX_LINES
                    "LITERAL" -> Pattern.LITERAL
                    "COMMENTS" -> Pattern.COMMENTS
                    "UNICODE_CHARACTER_CLASS" -> Pattern.UNICODE_CHARACTER_CLASS
                    "" -> 0
                    else -> throw AnalysisConfigException("unsupported regex flag '$name'")
                }
            }
        }
        return try {
            Pattern.compile(pattern, f)
        } catch (e: Exception) {
            throw AnalysisConfigException("invalid regex pattern '$pattern': ${e.message}", e)
        }
    }
}
