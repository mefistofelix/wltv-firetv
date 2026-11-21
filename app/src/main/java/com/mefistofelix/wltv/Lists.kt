package com.mefistofelix.wltv

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * The channel lists published by World Live TV (same server the Kodi addon reads: utils.getRemoteLists and
 * computeListUrl). Lists tagged "kodi" carry plugin:// links that [Resolver] turns into streams.
 * Both the index and each list are cached in files/ so the app starts offline with the last good copy.
 */
object Lists {
    const val API = "https://tvchannels.worldtvlive.eu/tv"
    const val DEFAULT = "_dtt_kodi"
    const val CUSTOM = "custom"

    data class Info(val id: String, val title: String)

    private fun prefs(c: Context) = c.getSharedPreferences("lists", Context.MODE_PRIVATE)

    fun selected(c: Context): String = prefs(c).getString("selected", DEFAULT) ?: DEFAULT
    fun select(c: Context, id: String) = prefs(c).edit().putString("selected", id).apply()
    fun customUrl(c: Context): String = prefs(c).getString("customUrl", "") ?: ""
    fun setCustomUrl(c: Context, url: String) = prefs(c).edit().putString("customUrl", url).apply()

    /** Available lists; downloads all.json when [refresh] or nothing is cached. */
    fun index(c: Context, refresh: Boolean): List<Info> {
        val f = File(c.filesDir, "lists.json")
        var text: String? = null
        if (refresh || !f.exists()) {
            text = runCatching { Http.get("$API/all.json") }.getOrNull()
                ?.takeIf { it.trimStart().startsWith("[") }
            if (text != null) f.writeText(text)
        }
        if (text == null && f.exists()) text = f.readText()
        val out = ArrayList<Info>()
        runCatching {
            val a = JSONArray(text ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val tags = o.optJSONArray("Tags")
                val kodi = tags != null && (0 until tags.length()).any { tags.optString(it) == "kodi" }
                if (!o.optBoolean("Enabled") || !kodi) continue
                val id = o.optString("Name")
                out += Info(id, o.optString("DisplayName").ifEmpty { id })
            }
        }
        if (out.none { it.id == DEFAULT }) out.add(0, Info(DEFAULT, "DTT"))
        return out
    }

    fun title(c: Context, id: String): String = when (id) {
        CUSTOM -> "Personalizzata"
        else -> index(c, false).firstOrNull { it.id == id }?.title ?: id
    }

    private fun urlOf(c: Context, id: String) = if (id == CUSTOM) customUrl(c) else "$API/$id/list.m3u8"

    /**
     * Channels of list [id]. [refresh] forces a download; otherwise the cached copy is used when present.
     * A failed download keeps the cache. Returns null when there is nothing at all.
     */
    fun load(c: Context, id: String, refresh: Boolean): List<Channel>? {
        val f = File(c.filesDir, "list_" + id.replace(Regex("[^\\w-]"), "_") + ".m3u")
        if (refresh || !f.exists()) {
            val url = urlOf(c, id)
            if (url.isNotEmpty()) {
                runCatching { Http.get(url, mapOf("User-Agent" to "WLTV ${Http.UA}")) }.getOrNull()
                    ?.takeIf { it.contains("#EXTINF") }
                    ?.let { f.writeText(it) }
            }
        }
        if (!f.exists()) return null
        return M3u.parse(f.readText()).takeIf { it.isNotEmpty() }
    }
}
