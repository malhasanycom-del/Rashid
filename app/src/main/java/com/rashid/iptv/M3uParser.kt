package com.rashid.iptv

data class Channel(
    val id: Int,
    val name: String,
    val url: String,
    val logo: String = "",
    val group: String = "غير مصنف",
    val tvgId: String = ""
)

object M3uParser {
    fun parse(text: String): List<Channel> {
        val result = mutableListOf<Channel>()
        var attrs: Map<String, String>? = null
        var pendingName = ""
        for (raw in text.removePrefix("\uFEFF").lineSequence()) {
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
                val name = pendingName.ifBlank { a["tvg-name"].orEmpty() }.ifBlank { "قناة ${result.size + 1}" }
                result += Channel(result.size + 1, name, line, a["tvg-logo"].orEmpty(),
                    a["group-title"].orEmpty().ifBlank { "غير مصنف" }, a["tvg-id"].orEmpty())
                attrs = null
                pendingName = ""
            }
        }
        return result.distinctBy { it.url }.mapIndexed { i, c -> c.copy(id = i + 1) }
    }
}
