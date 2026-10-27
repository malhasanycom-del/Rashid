package com.rashid.iptv

import java.io.BufferedReader
import java.io.StringReader

data class Channel(
    val id: Int,
    val name: String,
    val url: String,
    val logo: String = "",
    val group: String = "غير مصنف",
    val tvgId: String = ""
)

object M3uParser {
    private const val MAX_CHARS = 50 * 1024 * 1024
    private const val MAX_CHANNELS = 150_000

    fun parse(text: String): List<Channel> =
        StringReader(text.removePrefix("\uFEFF")).buffered().use { parse(it) }

    /** Reads incrementally so a large playlist is not duplicated in memory as one giant String. */
    fun parse(reader: BufferedReader): List<Channel> {
        val result = LinkedHashMap<String, Channel>()
        var attrs: Map<String, String>? = null
        var pendingName = ""
        var totalChars = 0

        while (true) {
            val raw = reader.readLine() ?: break
            totalChars += raw.length
            require(totalChars <= MAX_CHARS) {
                "حجم قائمة التشغيل أكبر من الحد الآمن (50 ميغابايت). استخدم رابط M3U أو قائمة أصغر."
            }
            val line = raw.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                val comma = line.indexOf(',')
                val meta = if (comma >= 0) line.substring(0, comma) else line
                pendingName = if (comma >= 0) line.substring(comma + 1).trim() else ""
                attrs = Regex("([\\w-]+)\\s*=\\s*[\\\"']([^\\\"']*)[\\\"']")
                    .findAll(meta).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
            } else if (!line.startsWith("#") && attrs != null) {
                val a = attrs.orEmpty()
                val name = pendingName.ifBlank { a["tvg-name"].orEmpty() }
                    .ifBlank { "قناة ${result.size + 1}" }
                if (!result.containsKey(line)) {
                    result[line] = Channel(
                        result.size + 1, name, line,
                        a["tvg-logo"].orEmpty(),
                        a["group-title"].orEmpty().ifBlank { "غير مصنف" },
                        a["tvg-id"].orEmpty()
                    )
                    require(result.size <= MAX_CHANNELS) {
                        "تحتوي القائمة على عدد قنوات كبير جدًا (أكثر من 150 ألف قناة)."
                    }
                }
                attrs = null
                pendingName = ""
            }
        }
        return result.values.toList()
    }
}
