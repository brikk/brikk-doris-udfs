package dev.brikk.doris.udf.lucene

import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.ar.ArabicStemFilter
import org.apache.lucene.analysis.bg.BulgarianStemFilter
import org.apache.lucene.analysis.bn.BengaliStemFilter
import org.apache.lucene.analysis.br.BrazilianStemFilter
import org.apache.lucene.analysis.ckb.SoraniStemFilter
import org.apache.lucene.analysis.cz.CzechStemFilter
import org.apache.lucene.analysis.de.GermanLightStemFilter
import org.apache.lucene.analysis.de.GermanMinimalStemFilter
import org.apache.lucene.analysis.de.GermanStemFilter
import org.apache.lucene.analysis.el.GreekStemFilter
import org.apache.lucene.analysis.en.EnglishMinimalStemFilter
import org.apache.lucene.analysis.en.EnglishPossessiveFilter
import org.apache.lucene.analysis.en.KStemFilter
import org.apache.lucene.analysis.en.PorterStemFilter
import org.apache.lucene.analysis.es.SpanishLightStemFilter
import org.apache.lucene.analysis.fi.FinnishLightStemFilter
import org.apache.lucene.analysis.fr.FrenchLightStemFilter
import org.apache.lucene.analysis.fr.FrenchMinimalStemFilter
import org.apache.lucene.analysis.gl.GalicianMinimalStemFilter
import org.apache.lucene.analysis.gl.GalicianStemFilter
import org.apache.lucene.analysis.hi.HindiStemFilter
import org.apache.lucene.analysis.hu.HungarianLightStemFilter
import org.apache.lucene.analysis.id.IndonesianStemFilter
import org.apache.lucene.analysis.it.ItalianLightStemFilter
import org.apache.lucene.analysis.lv.LatvianStemFilter
import org.apache.lucene.analysis.no.NorwegianLightStemFilter
import org.apache.lucene.analysis.no.NorwegianLightStemmer
import org.apache.lucene.analysis.no.NorwegianMinimalStemFilter
import org.apache.lucene.analysis.pt.PortugueseLightStemFilter
import org.apache.lucene.analysis.pt.PortugueseMinimalStemFilter
import org.apache.lucene.analysis.pt.PortugueseStemFilter
import org.apache.lucene.analysis.ru.RussianLightStemFilter
import org.apache.lucene.analysis.snowball.SnowballFilter
import org.apache.lucene.analysis.sv.SwedishLightStemFilter

/**
 * The OpenSearch `stemmer` token filter's `language`/`name` table, mapped to Lucene stem
 * filters — the same mapping OpenSearch's own `StemmerTokenFilterFactory` implements
 * (algorithmic stemmers only; dictionary stemmers like hunspell are out of scope).
 */
object Stemmers {

    /** Snowball program names accepted by the `snowball` filter's `language` param. */
    private val SNOWBALL = setOf(
        "Arabic", "Armenian", "Basque", "Catalan", "Danish", "Dutch", "English", "Estonian",
        "Finnish", "French", "German", "German2", "Hungarian", "Irish", "Italian", "Kp",
        "Lithuanian", "Lovins", "Norwegian", "Porter", "Portuguese", "Romanian", "Russian",
        "Serbian", "Spanish", "Swedish", "Turkish",
    )

    fun snowball(language: String, input: TokenStream): TokenStream {
        val canonical = SNOWBALL.firstOrNull { it.equals(language, ignoreCase = true) }
            ?: throw AnalysisConfigException(
                "unsupported snowball language '$language'. Supported: ${SNOWBALL.sorted()}",
            )
        val stemmer = Class.forName("org.tartarus.snowball.ext.${canonical}Stemmer")
            .getDeclaredConstructor().newInstance() as org.tartarus.snowball.SnowballStemmer
        return SnowballFilter(input, stemmer)
    }

    private val BY_NAME: Map<String, (TokenStream) -> TokenStream> = mapOf(
        // English family
        "english" to { ts -> PorterStemFilter(ts) },
        "light_english" to { ts -> KStemFilter(ts) },
        "minimal_english" to { ts -> EnglishMinimalStemFilter(ts) },
        "possessive_english" to { ts -> EnglishPossessiveFilter(ts) },
        "porter" to { ts -> PorterStemFilter(ts) },
        "porter2" to { ts -> snowball("English", ts) },
        "lovins" to { ts -> snowball("Lovins", ts) },
        // German family
        "german" to { ts -> GermanStemFilter(ts) },
        "german2" to { ts -> snowball("German2", ts) },
        "light_german" to { ts -> GermanLightStemFilter(ts) },
        "minimal_german" to { ts -> GermanMinimalStemFilter(ts) },
        // French
        "french" to { ts -> snowball("French", ts) },
        "light_french" to { ts -> FrenchLightStemFilter(ts) },
        "minimal_french" to { ts -> FrenchMinimalStemFilter(ts) },
        // Spanish / Italian / Portuguese / Galician
        "spanish" to { ts -> snowball("Spanish", ts) },
        "light_spanish" to { ts -> SpanishLightStemFilter(ts) },
        "italian" to { ts -> snowball("Italian", ts) },
        "light_italian" to { ts -> ItalianLightStemFilter(ts) },
        "portuguese" to { ts -> snowball("Portuguese", ts) },
        "light_portuguese" to { ts -> PortugueseLightStemFilter(ts) },
        "minimal_portuguese" to { ts -> PortugueseMinimalStemFilter(ts) },
        "portuguese_rslp" to { ts -> PortugueseStemFilter(ts) },
        "galician" to { ts -> GalicianStemFilter(ts) },
        "minimal_galician" to { ts -> GalicianMinimalStemFilter(ts) },
        // Nordic
        "danish" to { ts -> snowball("Danish", ts) },
        "norwegian" to { ts -> snowball("Norwegian", ts) },
        "light_norwegian" to { ts -> NorwegianLightStemFilter(ts, NorwegianLightStemmer.BOKMAAL) },
        "minimal_norwegian" to { ts -> NorwegianMinimalStemFilter(ts, NorwegianLightStemmer.BOKMAAL) },
        "light_nynorsk" to { ts -> NorwegianLightStemFilter(ts, NorwegianLightStemmer.NYNORSK) },
        "minimal_nynorsk" to { ts -> NorwegianMinimalStemFilter(ts, NorwegianLightStemmer.NYNORSK) },
        "swedish" to { ts -> snowball("Swedish", ts) },
        "light_swedish" to { ts -> SwedishLightStemFilter(ts) },
        "finnish" to { ts -> snowball("Finnish", ts) },
        "light_finnish" to { ts -> FinnishLightStemFilter(ts) },
        // Eastern / other European
        "russian" to { ts -> snowball("Russian", ts) },
        "light_russian" to { ts -> RussianLightStemFilter(ts) },
        "czech" to { ts -> CzechStemFilter(ts) },
        "greek" to { ts -> GreekStemFilter(ts) },
        "hungarian" to { ts -> snowball("Hungarian", ts) },
        "light_hungarian" to { ts -> HungarianLightStemFilter(ts) },
        "romanian" to { ts -> snowball("Romanian", ts) },
        "bulgarian" to { ts -> BulgarianStemFilter(ts) },
        "latvian" to { ts -> LatvianStemFilter(ts) },
        "lithuanian" to { ts -> snowball("Lithuanian", ts) },
        "armenian" to { ts -> snowball("Armenian", ts) },
        "basque" to { ts -> snowball("Basque", ts) },
        "catalan" to { ts -> snowball("Catalan", ts) },
        "irish" to { ts -> snowball("Irish", ts) },
        "serbian" to { ts -> snowball("Serbian", ts) },
        "estonian" to { ts -> snowball("Estonian", ts) },
        "turkish" to { ts -> snowball("Turkish", ts) },
        // Dutch
        "dutch" to { ts -> snowball("Dutch", ts) },
        "dutch_kp" to { ts -> snowball("Kp", ts) },
        // Non-European
        "arabic" to { ts -> ArabicStemFilter(ts) },
        "bengali" to { ts -> BengaliStemFilter(ts) },
        "brazilian" to { ts -> BrazilianStemFilter(ts) },
        "hindi" to { ts -> HindiStemFilter(ts) },
        "indonesian" to { ts -> IndonesianStemFilter(ts) },
        "sorani" to { ts -> SoraniStemFilter(ts) },
    )

    val names: Set<String> get() = BY_NAME.keys

    fun apply(name: String, input: TokenStream): TokenStream {
        val factory = BY_NAME[name.lowercase()]
            ?: throw AnalysisConfigException(
                "unsupported stemmer language '$name'. Supported: ${names.sorted()}",
            )
        return factory(input)
    }
}
