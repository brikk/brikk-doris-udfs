package dev.brikk.doris.udf.lucene

import org.apache.lucene.analysis.CharArraySet
import org.apache.lucene.analysis.ar.ArabicAnalyzer
import org.apache.lucene.analysis.bg.BulgarianAnalyzer
import org.apache.lucene.analysis.bn.BengaliAnalyzer
import org.apache.lucene.analysis.br.BrazilianAnalyzer
import org.apache.lucene.analysis.ca.CatalanAnalyzer
import org.apache.lucene.analysis.cjk.CJKAnalyzer
import org.apache.lucene.analysis.ckb.SoraniAnalyzer
import org.apache.lucene.analysis.cz.CzechAnalyzer
import org.apache.lucene.analysis.da.DanishAnalyzer
import org.apache.lucene.analysis.de.GermanAnalyzer
import org.apache.lucene.analysis.el.GreekAnalyzer
import org.apache.lucene.analysis.en.EnglishAnalyzer
import org.apache.lucene.analysis.es.SpanishAnalyzer
import org.apache.lucene.analysis.et.EstonianAnalyzer
import org.apache.lucene.analysis.eu.BasqueAnalyzer
import org.apache.lucene.analysis.fa.PersianAnalyzer
import org.apache.lucene.analysis.fi.FinnishAnalyzer
import org.apache.lucene.analysis.fr.FrenchAnalyzer
import org.apache.lucene.analysis.ga.IrishAnalyzer
import org.apache.lucene.analysis.gl.GalicianAnalyzer
import org.apache.lucene.analysis.hi.HindiAnalyzer
import org.apache.lucene.analysis.hu.HungarianAnalyzer
import org.apache.lucene.analysis.hy.ArmenianAnalyzer
import org.apache.lucene.analysis.id.IndonesianAnalyzer
import org.apache.lucene.analysis.it.ItalianAnalyzer
import org.apache.lucene.analysis.lt.LithuanianAnalyzer
import org.apache.lucene.analysis.lv.LatvianAnalyzer
import org.apache.lucene.analysis.nl.DutchAnalyzer
import org.apache.lucene.analysis.no.NorwegianAnalyzer
import org.apache.lucene.analysis.pt.PortugueseAnalyzer
import org.apache.lucene.analysis.ro.RomanianAnalyzer
import org.apache.lucene.analysis.ru.RussianAnalyzer
import org.apache.lucene.analysis.sv.SwedishAnalyzer
import org.apache.lucene.analysis.th.ThaiAnalyzer
import org.apache.lucene.analysis.tr.TurkishAnalyzer

/**
 * OpenSearch's predefined stop-word sets (`stopwords: "_english_"` etc.), resolved to
 * Lucene's per-language default stop sets — the same sets OpenSearch itself uses (its
 * `Analysis.NAMED_STOP_WORDS` maps the `_lang_` names to these exact Lucene constants).
 */
object StopWordSets {

    private val SETS: Map<String, () -> CharArraySet> = mapOf(
        "_arabic_" to { ArabicAnalyzer.getDefaultStopSet() },
        "_armenian_" to { ArmenianAnalyzer.getDefaultStopSet() },
        "_basque_" to { BasqueAnalyzer.getDefaultStopSet() },
        "_bengali_" to { BengaliAnalyzer.getDefaultStopSet() },
        "_brazilian_" to { BrazilianAnalyzer.getDefaultStopSet() },
        "_bulgarian_" to { BulgarianAnalyzer.getDefaultStopSet() },
        "_catalan_" to { CatalanAnalyzer.getDefaultStopSet() },
        "_cjk_" to { CJKAnalyzer.getDefaultStopSet() },
        "_czech_" to { CzechAnalyzer.getDefaultStopSet() },
        "_danish_" to { DanishAnalyzer.getDefaultStopSet() },
        "_dutch_" to { DutchAnalyzer.getDefaultStopSet() },
        "_english_" to { EnglishAnalyzer.ENGLISH_STOP_WORDS_SET },
        "_estonian_" to { EstonianAnalyzer.getDefaultStopSet() },
        "_finnish_" to { FinnishAnalyzer.getDefaultStopSet() },
        "_french_" to { FrenchAnalyzer.getDefaultStopSet() },
        "_galician_" to { GalicianAnalyzer.getDefaultStopSet() },
        "_german_" to { GermanAnalyzer.getDefaultStopSet() },
        "_greek_" to { GreekAnalyzer.getDefaultStopSet() },
        "_hindi_" to { HindiAnalyzer.getDefaultStopSet() },
        "_hungarian_" to { HungarianAnalyzer.getDefaultStopSet() },
        "_indonesian_" to { IndonesianAnalyzer.getDefaultStopSet() },
        "_irish_" to { IrishAnalyzer.getDefaultStopSet() },
        "_italian_" to { ItalianAnalyzer.getDefaultStopSet() },
        "_latvian_" to { LatvianAnalyzer.getDefaultStopSet() },
        "_lithuanian_" to { LithuanianAnalyzer.getDefaultStopSet() },
        "_norwegian_" to { NorwegianAnalyzer.getDefaultStopSet() },
        "_persian_" to { PersianAnalyzer.getDefaultStopSet() },
        "_portuguese_" to { PortugueseAnalyzer.getDefaultStopSet() },
        "_romanian_" to { RomanianAnalyzer.getDefaultStopSet() },
        "_russian_" to { RussianAnalyzer.getDefaultStopSet() },
        "_sorani_" to { SoraniAnalyzer.getDefaultStopSet() },
        "_spanish_" to { SpanishAnalyzer.getDefaultStopSet() },
        "_swedish_" to { SwedishAnalyzer.getDefaultStopSet() },
        "_thai_" to { ThaiAnalyzer.getDefaultStopSet() },
        "_turkish_" to { TurkishAnalyzer.getDefaultStopSet() },
    )

    val names: Set<String> get() = SETS.keys + "_none_"

    /**
     * Resolves an OpenSearch `stopwords` value (a single `_lang_` name, `_none_`, or a
     * word list that may itself mix `_lang_` names and literal words) to a [CharArraySet].
     */
    fun resolve(entries: List<String>, ignoreCase: Boolean): CharArraySet {
        val out = CharArraySet(entries.size, ignoreCase)
        for (entry in entries) {
            if (entry.startsWith("_") && entry.endsWith("_") && entry.length > 2) {
                if (entry == "_none_") continue
                val named = SETS[entry]
                    ?: throw AnalysisConfigException(
                        "unknown predefined stop-word set '$entry'. Supported: ${names.sorted()}",
                    )
                out.addAll(named())
            } else {
                out.add(entry)
            }
        }
        return out
    }
}
