package dev.brikk.doris.udf.lucene

/**
 * Built-in (named) analyzers, expanded to their canonical [AnalysisConfig] chains — the
 * decompositions OpenSearch documents for each built-in analyzer. Because expansion happens
 * at parse time, `{"analyzer":"english"}` and the equivalent hand-written custom chain are
 * the SAME cache key and share one Lucene analyzer instance.
 *
 * Analyzer parameters follow the OpenSearch configurable variants, e.g.
 * `{"analyzer": {"type": "standard", "stopwords": "_english_"}}` or
 * `{"analyzer": {"type": "english", "stem_exclusion": ["organization"]}}`.
 */
object Presets {

    private fun spec(type: String, vararg params: Pair<String, ParamValue>) =
        ComponentSpec(type, params.toMap())

    private fun str(v: String) = ParamValue.Str(v)
    private fun strs(v: List<String>) = ParamValue.Strings(v)

    /** `stopwords`/`stopwords_path` params shared by the configurable presets. */
    private fun stopFilter(spec: ComponentSpec, defaultSet: String): ComponentSpec? {
        val words = spec.strings("stopwords") ?: listOf(defaultSet)
        if (words == listOf("_none_")) return null
        return spec("stop", "stopwords" to strs(words))
    }

    /**
     * OpenSearch language analyzers all share this shape (documented per language):
     * standard tokenizer + optional pre-filters + lowercase + stop(_lang_) +
     * optional keyword_marker(stem_exclusion) + stemmer(s).
     */
    private fun language(
        spec: ComponentSpec,
        stopSet: String,
        stemmerName: String,
        preLowercase: List<ComponentSpec> = emptyList(),
        postStop: List<ComponentSpec> = emptyList(),
    ): AnalysisConfig {
        spec.requireOnly(setOf("stopwords", "stem_exclusion"))
        val filters = buildList {
            addAll(preLowercase)
            add(spec("lowercase"))
            stopFilter(spec, stopSet)?.let { add(it) }
            spec.strings("stem_exclusion")?.let { add(spec("keyword_marker", "keywords" to strs(it))) }
            addAll(postStop)
            add(spec("stemmer", "language" to str(stemmerName)))
        }
        return AnalysisConfig(tokenizer = spec("standard"), tokenFilters = filters)
    }

    fun analyzer(spec: ComponentSpec): AnalysisConfig = when (spec.type) {
        "standard" -> {
            spec.requireOnly(setOf("max_token_length", "stopwords"))
            val tokenizer = if (spec.params.containsKey("max_token_length")) {
                spec("standard", "max_token_length" to ParamValue.Num(spec.int("max_token_length", 255).toLong()))
            } else {
                spec("standard")
            }
            AnalysisConfig(
                tokenizer = tokenizer,
                tokenFilters = listOfNotNull(spec("lowercase"), stopFilter(spec, "_none_")),
            )
        }
        "simple" -> {
            spec.requireOnly(emptySet())
            AnalysisConfig(tokenizer = spec("letter"), tokenFilters = listOf(spec("lowercase")))
        }
        "whitespace" -> {
            spec.requireOnly(emptySet())
            AnalysisConfig(tokenizer = spec("whitespace"))
        }
        "keyword" -> {
            spec.requireOnly(emptySet())
            AnalysisConfig(tokenizer = spec("keyword"))
        }
        "stop" -> {
            // OpenSearch stop analyzer = lowercase tokenizer (letter+lowercase) + stop(_english_).
            spec.requireOnly(setOf("stopwords"))
            AnalysisConfig(
                tokenizer = spec("letter"),
                tokenFilters = listOfNotNull(spec("lowercase"), stopFilter(spec, "_english_")),
            )
        }
        "pattern" -> {
            spec.requireOnly(setOf("pattern", "lowercase", "stopwords"))
            val filters = buildList {
                if (spec.bool("lowercase", true)) add(spec("lowercase"))
                stopFilter(spec, "_none_")?.let { add(it) }
            }
            AnalysisConfig(
                tokenizer = spec("pattern", "pattern" to str(spec.str("pattern") ?: "\\W+")),
                tokenFilters = filters,
            )
        }
        "fingerprint" -> {
            spec.requireOnly(setOf("separator", "max_output_size", "stopwords"))
            AnalysisConfig(
                tokenizer = spec("standard"),
                tokenFilters = listOfNotNull(
                    spec("lowercase"),
                    spec("asciifolding"),
                    stopFilter(spec, "_none_"),
                    spec(
                        "fingerprint",
                        "separator" to str(spec.str("separator") ?: " "),
                        "max_output_size" to ParamValue.Num(spec.int("max_output_size", 255).toLong()),
                    ),
                ),
            )
        }

        // ── Language analyzers (documented OpenSearch decompositions) ──
        "english" -> {
            spec.requireOnly(setOf("stopwords", "stem_exclusion"))
            val filters = buildList {
                add(spec("stemmer", "language" to str("possessive_english")))
                add(spec("lowercase"))
                stopFilter(spec, "_english_")?.let { add(it) }
                spec.strings("stem_exclusion")?.let { add(spec("keyword_marker", "keywords" to strs(it))) }
                add(spec("stemmer", "language" to str("english")))
            }
            AnalysisConfig(tokenizer = spec("standard"), tokenFilters = filters)
        }
        "german" -> language(
            spec, "_german_", "light_german",
            postStop = listOf(spec("german_normalization")),
        )
        "french" -> language(
            spec, "_french_", "light_french",
            preLowercase = listOf(
                spec(
                    "elision",
                    "articles" to strs(FRENCH_ARTICLES),
                    "articles_case" to ParamValue.Bool(true),
                ),
            ),
        )
        "italian" -> language(
            spec, "_italian_", "light_italian",
            preLowercase = listOf(
                spec(
                    "elision",
                    "articles" to strs(ITALIAN_ARTICLES),
                    "articles_case" to ParamValue.Bool(true),
                ),
            ),
        )
        "spanish" -> language(spec, "_spanish_", "light_spanish")
        "portuguese" -> language(spec, "_portuguese_", "light_portuguese")
        "russian" -> language(spec, "_russian_", "russian")
        "swedish" -> language(spec, "_swedish_", "swedish")
        "danish" -> language(spec, "_danish_", "danish")
        "norwegian" -> language(spec, "_norwegian_", "norwegian")
        "finnish" -> language(spec, "_finnish_", "finnish")
        "hungarian" -> language(spec, "_hungarian_", "hungarian")
        "turkish" -> language(spec, "_turkish_", "turkish")

        // ── brikk presets (VERSIONED: the name is an index contract — never change a
        // published preset's chain; add _v2 instead). Doris has no server-side place to
        // pin a config (no variables/aliases), so a preset name IS the pinned config:
        // one short token to pass everywhere, its meaning frozen by the jar release. ──
        "brikk_multilang_english_v1" -> {
            // Multilingual UAX#29 splitting + NFKC casefold (pre-tokenization) + English
            // possessive stripping + ASCII folding + _english_ stop words + Porter stemming.
            // "The Müller's Fußgänger are running quickly to Zürich's café"
            //   -> muller fussgang run quickli zurich cafe
            spec.requireOnly(emptySet())
            AnalysisConfig(
                charFilters = listOf(spec("icu_normalizer", "name" to str("nfkc_cf"))),
                tokenizer = spec("icu_tokenizer"),
                tokenFilters = listOf(
                    spec("stemmer", "language" to str("possessive_english")),
                    spec("asciifolding"),
                    spec("stop", "stopwords" to strs(listOf("_english_"))),
                    spec("stemmer", "language" to str("english")),
                ),
            )
        }

        "brikk_multilang_english_fingerprint_v1" -> {
            // Query-normalization fingerprint: the brikk_multilang_english_v1 chain, then
            // length(min 2) (drops 1-char tokens — letters AND digits; placed after
            // stemming so tokens that stem down to one char are caught too), then
            // fingerprint (sort + dedupe + join into ONE canonical token). Two queries
            // that "mean the same" collapse to the same string:
            //   "running to Zürich's café" -> cafe run zurich
            //   "café runs Zurich"         -> cafe run zurich
            spec.requireOnly(emptySet())
            AnalysisConfig(
                charFilters = listOf(spec("icu_normalizer", "name" to str("nfkc_cf"))),
                tokenizer = spec("icu_tokenizer"),
                tokenFilters = listOf(
                    spec("stemmer", "language" to str("possessive_english")),
                    spec("asciifolding"),
                    spec("stop", "stopwords" to strs(listOf("_english_"))),
                    spec("stemmer", "language" to str("english")),
                    spec("length", "min" to ParamValue.Num(2)),
                    spec("fingerprint", "max_output_size" to ParamValue.Num(1000)),
                ),
            )
        }

        "brikk_multilang_english_fingerprint_v2" -> {
            // fingerprint_v1 + slash-date preservation. Dates written with `/` are rewritten
            // to `.` BEFORE tokenization (UAX#29 splits numbers at `/`, `-`, `:` but joins
            // them across `.`, `,`, `_`), so they survive as ONE token instead of being
            // shredded into length-filtered fragments. Shapes kept (3-part rules must run
            // before the 2-part rule, or d/d/d gets half-eaten):
            //   d{1,2}/d{1,2}/(d{4}|d{2})  6/7/1994  06/07/1994  6/7/94  6/19/26
            //   d{4}/d{1,2}/d{1,2}         1994/06/07
            //   d{1,2}/(d{4}|d{2})         10/1994  6/95  6/06
            // Deliberately NOT kept: d/d (6/6, 1/2 fractions) — shredded then dropped.
            spec.requireOnly(emptySet())
            AnalysisConfig(
                charFilters = listOf(
                    spec("icu_normalizer", "name" to str("nfkc_cf")),
                    spec(
                        "pattern_replace",
                        "pattern" to str("\\b(\\d{1,2})/(\\d{1,2})/(\\d{4}|\\d{2})\\b"),
                        "replacement" to str("$1.$2.$3"),
                    ),
                    spec(
                        "pattern_replace",
                        "pattern" to str("\\b(\\d{4})/(\\d{1,2})/(\\d{1,2})\\b"),
                        "replacement" to str("$1.$2.$3"),
                    ),
                    spec(
                        "pattern_replace",
                        "pattern" to str("\\b(\\d{1,2})/(\\d{4}|\\d{2})\\b"),
                        "replacement" to str("$1.$2"),
                    ),
                ),
                tokenizer = spec("icu_tokenizer"),
                tokenFilters = listOf(
                    spec("stemmer", "language" to str("possessive_english")),
                    spec("asciifolding"),
                    spec("stop", "stopwords" to strs(listOf("_english_"))),
                    spec("stemmer", "language" to str("english")),
                    spec("length", "min" to ParamValue.Num(2)),
                    spec("fingerprint", "max_output_size" to ParamValue.Num(1000)),
                ),
            )
        }

        else -> throw AnalysisConfigException(
            "unsupported analyzer '${spec.type}'. Supported: ${SUPPORTED.sorted()}",
        )
    }

    private val SUPPORTED = setOf(
        "standard", "simple", "whitespace", "keyword", "stop", "pattern", "fingerprint",
        "english", "german", "french", "italian", "spanish", "portuguese", "russian",
        "swedish", "danish", "norwegian", "finnish", "hungarian", "turkish",
        "brikk_multilang_english_v1", "brikk_multilang_english_fingerprint_v1",
        "brikk_multilang_english_fingerprint_v2",
    )

    // OpenSearch's french/italian analyzers use these elision article sets
    // (Lucene FrenchAnalyzer.DEFAULT_ARTICLES / ItalianAnalyzer.DEFAULT_ARTICLES).
    private val FRENCH_ARTICLES =
        listOf("l", "m", "t", "qu", "n", "s", "j", "d", "c", "jusqu", "quoiqu", "lorsqu", "puisqu")
    private val ITALIAN_ARTICLES = listOf(
        "c", "l", "all", "dall", "dell", "nell", "sull", "coll", "pell",
        "gl", "agl", "dagl", "degl", "negl", "sugl", "un", "m", "t", "s", "v", "d",
    )
}
