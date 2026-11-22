package com.mefistofelix.wltv

/**
 * One channel. [source] is the title of the list it comes from (several lists can be on at once);
 * [key] identifies it for the watch-time stats across list refreshes.
 */
data class Channel(
    val name: String,
    val url: String,
    val number: Int,
    val group: String,
    val logo: String,
    val tvgId: String,
    val source: String = "",
    /** The list numbers it (tvg-chno, e.g. the DTT LCN): shown in number order, not split by group. */
    val lcn: Boolean = false,
) {
    val key: String get() = url.trim()
}

object M3u {
    private val attr = Regex("""([\w-]+)="([^"]*)"""")

    /**
     * EXTINF parser. Per-channel HTTP headers (iptv-org's `http-user-agent` / `http-referrer` attributes and
     * `#EXTVLCOPT:http-…` lines) are appended to the URL in Kodi's `url|Header=value` form, which [Resolver] reads.
     */
    fun parse(text: String, source: String = ""): List<Channel> {
        val out = ArrayList<Channel>()
        var info: String? = null
        val opts = LinkedHashMap<String, String>()
        var auto = 0
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF", true)) {
                info = line; opts.clear(); continue
            }
            if (line.startsWith("#EXTVLCOPT:", true)) {
                val o = line.substringAfter(':')
                when (o.substringBefore('=').lowercase()) {
                    "http-user-agent" -> opts["User-Agent"] = o.substringAfter('=')
                    "http-referrer" -> opts["Referer"] = o.substringAfter('=')
                }
                continue
            }
            if (line.startsWith("#")) continue
            val inf = info ?: "#EXTINF:-1,$line"
            info = null
            val split = titleComma(inf)
            val head = if (split >= 0) inf.substring(0, split) else inf
            val attrs = attr.findAll(head).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
            attrs["http-user-agent"]?.let { opts.putIfAbsent("User-Agent", it) }
            attrs["http-referrer"]?.let { opts.putIfAbsent("Referer", it) }
            val title = (if (split >= 0) inf.substring(split + 1) else "").trim()
                .ifEmpty { attrs["tvg-name"].orEmpty() }.ifEmpty { line }
            var url = line
            if (opts.isNotEmpty() && !url.contains('|')) {
                url += "|" + opts.entries.joinToString("&") { "${it.key}=${Http.enc(it.value)}" }
            }
            opts.clear()
            auto++
            out += Channel(
                name = title,
                url = url,
                number = attrs["tvg-chno"]?.toIntOrNull() ?: auto,
                group = attrs["group-title"]?.trim().orEmpty().substringBefore(';').ifEmpty { "Altri" },
                logo = attrs["tvg-logo"].orEmpty(),
                tvgId = attrs["tvg-id"].orEmpty(),
                source = source,
                lcn = attrs["tvg-chno"]?.toIntOrNull() != null,
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
