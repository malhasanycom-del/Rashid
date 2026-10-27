package com.rashid.iptv

import java.io.BufferedReader
import java.io.StringReader

data class Channel(
    val id: Int,
    val name: String,
    val url: String,
    val logo: String = "",
    val group: String = "غير مصنف",
    val tvgId: String = "",
    val contentType: String = "live",
    val seriesId: String = ""
)

private fun classifyM3uEntry(name: String, group: String, url: String): String {
    val g = group.lowercase()
    val n = name.lowercase()
    val u = url.substringBefore('?').lowercase()
    val seriesWords = listOf("series", "tv shows", "tv show", "series pack", "مسلسل", "مسلسلات", "حلقات", "مواسم")
    val movieWords = listOf("movie", "movies", "vod", "films", "film", "cinema", "أفلام", "افلام", "فيلم", "سينما")
    val liveWords = listOf("live", "المنوعات", "رياضة مباشرة", "بث مباشر", "قنوات", "news", "sports", "الاخبار", "الأخبار")
    return when {
        seriesWords.any { g.contains(it) } -> "series"
        movieWords.any { g.contains(it) } -> "movie"
        liveWords.any { g.contains(it) } -> "live"
        listOf(".m3u8", ".ts", ".mpegts").any { u.endsWith(it) } -> "live"
        listOf(".mp4", ".mkv", ".avi", ".mov", ".wmv", ".webm").any { u.endsWith(it) } -> "movie"
        seriesWords.any { n.contains(it) } && (n.contains("s0") || n.contains("e0") || n.contains("season") || n.contains("الحلقة")) -> "series"
        else -> "live"
    }
}

object M3uParser {
    private val ATTRIBUTE_PATTERN = Regex("([\\w-]+)\\s*=\\s*[\\\"']([^\\\"']*)[\\\"']")

    fun parse(text: String): List<Channel> =
        StringReader(text.removePrefix("\uFEFF")).buffered().use { parse(it) }

    /** Parses incrementally; never reads the entire file into a second String. */
    fun parse(reader: BufferedReader): List<Channel> {
        val result = ArrayList<Channel>()
        val seenUrls = HashSet<String>()
        var attrs: Map<String, String>? = null
        var pendingName = ""

        while (true) {
            val raw = reader.readLine() ?: break
            val line = raw.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                val comma = line.indexOf(',')
                val meta = if (comma >= 0) line.substring(0, comma) else line
                pendingName = if (comma >= 0) line.substring(comma + 1).trim() else ""
                attrs = ATTRIBUTE_PATTERN.findAll(meta)
                    .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
            } else if (!line.startsWith("#") && attrs != null) {
                val a = attrs.orEmpty()
                if (seenUrls.add(line)) {
                    val name = pendingName.ifBlank { a["tvg-name"].orEmpty() }
                        .ifBlank { "قناة ${result.size + 1}" }
                    val group = a["group-title"].orEmpty().ifBlank { "غير مصنف" }
                    val contentType = classifyM3uEntry(name, group, line)
                    result.add(Channel(result.size + 1, name, line, a["tvg-logo"].orEmpty(), group, a["tvg-id"].orEmpty(), contentType))
                }
                attrs = null
                pendingName = ""
            }
        }
        return result
    }
}
