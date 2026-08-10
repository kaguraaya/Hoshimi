package com.acgcompass.domain.matching

import java.text.Normalizer
import java.util.Locale

/**
 * VN-specific confidence that refuses to erase chapter, route, sequel, or Greek-variant markers.
 * General punctuation and script normalization still use the shared matcher after this guard.
 */
fun visualNovelMatchConfidence(candidate: String, query: String, yearMatch: Boolean?): Double {
    val candidateMarkers = visualNovelVariantMarkers(candidate)
    val queryMarkers = visualNovelVariantMarkers(query)
    if (candidateMarkers != queryMarkers && (candidateMarkers.isNotEmpty() || queryMarkers.isNotEmpty())) {
        return 0.0
    }
    return matchConfidence(candidate, query, yearMatch = yearMatch, typeMatch = true)
}

internal fun visualNovelVariantMarkers(raw: String): Set<String> {
    val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    val markers = linkedSetOf<String>()
    CHAPTER.findAll(normalized).forEach { markers += "chapter:${it.groupValues[1]}" }
    ROMAN_SUFFIX.find(normalized)?.let { markers += "roman:${it.groupValues[1]}" }
    GREEK.findAll(normalized).forEach { markers += "greek:${it.value}" }
    ROUTE.findAll(normalized).forEach { markers += "route:${it.groupValues[1]}" }
    VERSION.findAll(normalized).forEach { markers += "version:${it.groupValues[1]}" }
    return markers
}

private val CHAPTER = Regex("第\\s*([一二三四五六七八九十0-9]+)\\s*(?:章|篇|部)")
private val ROMAN_SUFFIX = Regex("(?:^|[\\s:：_-])(i{1,3}|iv|v|vi{0,3})(?:\\s*)$")
private val GREEK = Regex("[αβγδ]")
private val ROUTE = Regex("(?:^|[\\s:：_-])(route|side|after|another|外传|后日谈)(?:$|[\\s:：_-])")
private val VERSION = Regex("(?:^|[\\s:：_-])(remake|reboot|version|edition|hd|重制版|完全版)(?:$|[\\s:：_-])")
