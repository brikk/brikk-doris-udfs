package dev.brikk.doris.udf.lucene

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Thrown for any invalid / unsupported analysis configuration. FAIL LOUD is the contract:
 * a silently-ignored component or parameter would produce a *different* analyzer than the
 * user intended, and index-time vs query-time analysis must be byte-identical — a silent
 * skip is exactly the class of corruption this library exists to prevent.
 */
class AnalysisConfigException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/** A normalized component parameter value (stable equals/hashCode for cache keying). */
sealed interface ParamValue {
    data class Str(val value: String) : ParamValue
    data class Bool(val value: Boolean) : ParamValue
    data class Num(val value: Long) : ParamValue
    data class Dec(val value: Double) : ParamValue
    data class Strings(val values: List<String>) : ParamValue

    companion object {
        fun of(element: JsonElement, path: String): ParamValue = when (element) {
            // NOTE: JsonNull IS a JsonPrimitive — must be checked first.
            is JsonNull -> throw AnalysisConfigException("null is not a valid value at $path")
            is JsonPrimitive -> when {
                element.isString -> Str(element.content)
                element.booleanOrNull != null -> Bool(element.booleanOrNull!!)
                element.longOrNull != null -> Num(element.longOrNull!!)
                element.doubleOrNull != null -> Dec(element.doubleOrNull!!)
                else -> throw AnalysisConfigException("unsupported primitive at $path: $element")
            }
            is JsonArray -> Strings(
                element.map {
                    (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                        ?: throw AnalysisConfigException(
                            "arrays must contain only strings at $path, got: $it",
                        )
                },
            )
            is JsonObject -> throw AnalysisConfigException(
                "nested objects are not supported as parameter values at $path",
            )
        }
    }
}

/**
 * One analysis component: a tokenizer, char filter, or token filter, as
 * `type` + parameters — the object form of an OpenSearch analysis component
 * (`{"type": "stop", "stopwords": "_english_"}`). The bare-string form
 * (`"lowercase"`) parses to a [ComponentSpec] with empty [params], so both
 * spellings are the SAME cache key (canonicalization).
 */
data class ComponentSpec(val type: String, val params: Map<String, ParamValue> = emptyMap()) {

    fun str(key: String): String? = when (val v = params[key]) {
        null -> null
        is ParamValue.Str -> v.value
        else -> throw AnalysisConfigException("'$key' of '$type' must be a string")
    }

    fun bool(key: String, default: Boolean): Boolean = when (val v = params[key]) {
        null -> default
        is ParamValue.Bool -> v.value
        // OpenSearch accepts "true"/"false" strings in many places; normalize.
        is ParamValue.Str -> when (v.value.lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw AnalysisConfigException("'$key' of '$type' must be a boolean")
        }
        else -> throw AnalysisConfigException("'$key' of '$type' must be a boolean")
    }

    fun int(key: String, default: Int): Int = when (val v = params[key]) {
        null -> default
        is ParamValue.Num -> v.value.toInt()
        is ParamValue.Str -> v.value.toIntOrNull()
            ?: throw AnalysisConfigException("'$key' of '$type' must be an integer")
        else -> throw AnalysisConfigException("'$key' of '$type' must be an integer")
    }

    /** A parameter that is either a single string or an array of strings (e.g. `stopwords`). */
    fun strings(key: String): List<String>? = when (val v = params[key]) {
        null -> null
        is ParamValue.Str -> listOf(v.value)
        is ParamValue.Strings -> v.values
        else -> throw AnalysisConfigException("'$key' of '$type' must be a string or string array")
    }

    /** Strict parameter validation: reject anything not in [allowed] (typo = wrong analyzer = corruption). */
    fun requireOnly(allowed: Set<String>) {
        val unknown = params.keys - allowed
        if (unknown.isNotEmpty()) {
            throw AnalysisConfigException(
                "unsupported parameter(s) ${unknown.sorted()} for '$type'. Supported: ${allowed.sorted()}",
            )
        }
    }
}

/**
 * The canonical, immutable analysis chain — the cache key for built Lucene analyzers.
 *
 * Canonicalization performed by [parse]:
 *  - a bare name (`"english"` / `{"analyzer": "english"}`) expands to its full
 *    chain via [Presets], so the named analyzer and its hand-written equivalent
 *    are the SAME key;
 *  - bare-string components and `{"type": ...}` objects unify into [ComponentSpec];
 *  - JSON formatting (key order / whitespace) never matters — equality is structural.
 *
 * Note: parameters are compared as given; an explicitly-set default (e.g.
 * `"preserve_original": false`) keys differently from an omitted one, building a
 * second — behaviorally identical — analyzer. Harmless (extra cache entry), and it
 * keeps this layer free of per-parameter default knowledge.
 */
data class AnalysisConfig(
    val tokenizer: ComponentSpec,
    val charFilters: List<ComponentSpec> = emptyList(),
    val tokenFilters: List<ComponentSpec> = emptyList(),
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = false }

        private val TOP_LEVEL_KEYS = setOf("analyzer", "tokenizer", "char_filter", "filter")

        /**
         * Parses the OpenSearch-shaped config. Accepted forms:
         *  - `english` (bare analyzer name, not JSON)
         *  - `{"analyzer": "english"}`
         *  - `{"analyzer": {"type": "english", "stem_exclusion": ["organization"]}}`
         *  - `{"tokenizer": ..., "char_filter": [...], "filter": [...]}` (custom chain,
         *    same vocabulary as an OpenSearch custom analyzer / `_analyze` request)
         */
        fun parse(text: String): AnalysisConfig {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) throw AnalysisConfigException("empty analysis config")
            // Ergonomic shorthand: a bare name is a named analyzer.
            if (!trimmed.startsWith("{")) return Presets.analyzer(ComponentSpec(trimmed))

            val root = try {
                json.parseToJsonElement(trimmed) as? JsonObject
                    ?: throw AnalysisConfigException("analysis config must be a JSON object")
            } catch (e: AnalysisConfigException) {
                throw e
            } catch (e: Exception) {
                throw AnalysisConfigException("invalid JSON in analysis config: ${e.message}", e)
            }

            val unknown = root.keys - TOP_LEVEL_KEYS
            if (unknown.isNotEmpty()) {
                throw AnalysisConfigException(
                    "unsupported top-level key(s) ${unknown.sorted()}. Supported: ${TOP_LEVEL_KEYS.sorted()}",
                )
            }

            val analyzer = root["analyzer"]
            if (analyzer != null) {
                if (root.size > 1) {
                    throw AnalysisConfigException(
                        "'analyzer' cannot be combined with 'tokenizer'/'char_filter'/'filter' — " +
                            "a named analyzer IS a full chain",
                    )
                }
                return Presets.analyzer(component(analyzer, "analyzer"))
            }

            val tokenizer = root["tokenizer"]
                ?: throw AnalysisConfigException("config must specify 'analyzer' or 'tokenizer'")
            return AnalysisConfig(
                tokenizer = component(tokenizer, "tokenizer"),
                charFilters = componentList(root["char_filter"], "char_filter"),
                tokenFilters = componentList(root["filter"], "filter"),
            )
        }

        private fun componentList(element: JsonElement?, path: String): List<ComponentSpec> =
            when (element) {
                null -> emptyList()
                is JsonArray -> element.mapIndexed { i, e -> component(e, "$path[$i]") }
                // OpenSearch also accepts a single non-array component here.
                else -> listOf(component(element, path))
            }

        private fun component(element: JsonElement, path: String): ComponentSpec = when (element) {
            is JsonPrimitive -> {
                if (!element.isString) {
                    throw AnalysisConfigException("component at $path must be a name or an object")
                }
                ComponentSpec(element.content)
            }
            is JsonObject -> {
                val type = (element["type"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                    ?: throw AnalysisConfigException("component at $path must have a string 'type'")
                val params = element.entries
                    .filter { it.key != "type" }
                    .associate { (k, v) -> k to ParamValue.of(v, "$path.$k") }
                ComponentSpec(type, params)
            }
            else -> throw AnalysisConfigException("component at $path must be a name or an object")
        }
    }
}
