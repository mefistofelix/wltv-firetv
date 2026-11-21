package com.mefistofelix.wltv

/** One channel of an M3U list. `key` identifies it for the watch-time stats across list refreshes. */
data class Channel(
    val name: String,
    val url: String,
    val number: Int,
    val group: String,
    val logo: String,
    val tvgId: String,
) {
    val key: String get() = url.trim()
}

object M3u {
    private val attr = Regex("""([\w-]+)="([^"]*)"""")

    fun parse(text: String): List<Channel> {
        val out = ArrayList<Channel>()
        var info: String? = null
        var auto = 0
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF", true)) {
                info = line; continue
            }
            if (line.startsWith("#")) continue
            val inf = info ?: "#EXTINF:-1,$line"
            info = null
            val split = titleComma(inf)
            val head = if (split >= 0) inf.substring(0, split) else inf
            val attrs = attr.findAll(head).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
            val title = (if (split >= 0) inf.substring(split + 1) else "").trim()
                .ifEmpty { attrs["tvg-name"].orEmpty() }.ifEmpty { line }
            auto++
            out += Channel(
                name = title,
                url = line,
                number = attrs["tvg-chno"]?.toIntOrNull() ?: auto,
                group = attrs["group-title"]?.trim().orEmpty().ifEmpty { "Altri" },
                logo = attrs["tvg-logo"].orEmpty(),
                tvgId = attrs["tvg-id"].orEmpty(),
            )
        }
        return out
    }

    /** Index of the comma that starts the title: the first one outside the quoted attributes. */
    private fun titleComma(inf: String): Int {
        var quoted = false
        for (i in inf.indices) {
            when (inf[i]) {
                '"' -> quoted = !quoted
                ',' -> if (!quoted) return i
            }
        }
        return -1
    }
}
