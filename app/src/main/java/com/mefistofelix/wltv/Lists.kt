package com.mefistofelix.wltv

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.Inflater

/**
 * Every channel source of the World Live TV addon, any number of them on at once:
 * - `wltv:<Name>`  the WLTV M3U lists (all.json, tag "kodi"), default `wltv:_dtt_kodi` (DTT Italia);
 * - `world:<cc>`   "TV dal mondo": one iptv-org M3U per country (ListWorldTv.bin, lib/worldtv.py);
 * - `cam:<Country>` SkylineWebcams of a country (ListSkyLineWebcam.bin, lib/skylinewebcams.py);
 * - `radio:<CC>`   "Radio dal mondo" of a country (ListWorldRadio.bin + worldradio/<CC>.bin, lib/worldradio.py);
 * - `custom`       an M3U address typed by the user.
 * The .bin resources are base64 of raw deflate (utils.getContentResource). Everything is cached in files/.
 */
object Lists {
    const val API = "https://tvchannels.worldtvlive.eu/tv"
    const val FILES = "https://www.worldtvlive.eu/world/files"
    const val DEFAULT = "wltv:_dtt_kodi"
    const val CUSTOM = "custom"
    private const val WEEK = 7 * 24 * 3600_000L

    data class Source(val id: String, val title: String, val section: String)

    val SECTIONS = listOf("Liste WLTV", "TV dal mondo", "Webcam", "Radio dal mondo")

    private fun prefs(c: Context) = c.getSharedPreferences("lists", Context.MODE_PRIVATE)

    fun selected(c: Context): Set<String> {
        val p = prefs(c)
        p.getStringSet("sources", null)?.let { return it.toSet() }
        // build 1-10 kept a single list
        val old = p.getString("selected", null)
        return setOf(if (old == null) DEFAULT else if (old == CUSTOM) CUSTOM else "wltv:$old")
    }

    fun setSelected(c: Context, ids: Set<String>) =
        prefs(c).edit().putStringSet("sources", ids.ifEmpty { setOf(DEFAULT) }).apply()

    fun customUrl(c: Context): String = prefs(c).getString("customUrl", "") ?: ""
    fun setCustomUrl(c: Context, url: String) = prefs(c).edit().putString("customUrl", url).apply()

    // ------------------------------------------------------------------ catalog

    /** All sources, by section ([SECTIONS] order). Downloads the indexes when [refresh] or not cached. */
    @Volatile private var memo: Map<String, List<Source>>? = null

    fun catalog(c: Context, refresh: Boolean): Map<String, List<Source>> {
        if (!refresh) memo?.let { return it }
        val out = LinkedHashMap<String, List<Source>>()
        out[SECTIONS[0]] = wltv(c, refresh)
        out[SECTIONS[1]] = runCatching { world(c, refresh).map { Source("world:" + it.first, it.second, SECTIONS[1]) } }.getOrDefault(emptyList())
        out[SECTIONS[2]] = runCatching { cams(c, refresh).map { Source("cam:" + it, it, SECTIONS[2]) } }.getOrDefault(emptyList())
        out[SECTIONS[3]] = runCatching { radios(c, refresh).map { Source("radio:" + it.first, it.second, SECTIONS[3]) } }.getOrDefault(emptyList())
        memo = out
        return out
    }

    fun title(c: Context, id: String): String {
        if (id == CUSTOM) return "Personalizzata"
        return runCatching { catalog(c, false).values.flatten().firstOrNull { it.id == id }?.let { label(it) } }.getOrNull()
            ?: id.substringAfter(':')
    }

    /** Name shown in groups and banners: "DTT", "Mondo · France", "Webcam · Italia", "Radio · Italy". */
    fun label(s: Source) = when (s.section) {
        SECTIONS[1] -> "Mondo · ${s.title}"
        SECTIONS[2] -> "Webcam · ${s.title}"
        SECTIONS[3] -> "Radio · ${s.title}"
        else -> s.title
    }

    private fun wltv(c: Context, refresh: Boolean): List<Source> {
        val text = cached(c, "lists.json", refresh, 0) { Http.get("$API/all.json").takeIf { it.trimStart().startsWith("[") } }
        val out = ArrayList<Source>()
        runCatching {
            val a = JSONArray(text ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val tags = o.optJSONArray("Tags")
                val kodi = tags != null && (0 until tags.length()).any { tags.optString(it) == "kodi" }
                if (!o.optBoolean("Enabled") || !kodi) continue
                val id = o.optString("Name")
                out += Source("wltv:$id", o.optString("DisplayName").ifEmpty { id }, SECTIONS[0])
            }
        }
        if (out.none { it.id == DEFAULT }) out.add(0, Source(DEFAULT, "DTT", SECTIONS[0]))
        return out
    }

    /** (country code, name) of the world TV lists; the regions overlap, so countries are de-duplicated. */
    private fun world(c: Context, refresh: Boolean): List<Pair<String, String>> {
        val j = JSONObject(resource(c, "ListWorldTv.bin", refresh) ?: return emptyList())
        val seen = LinkedHashMap<String, String>()
        for (region in j.getJSONArray("worlditems").objects()) for (cty in region.optJSONArray("worlditems").objects()) {
            seen.putIfAbsent(cty.optString("code").lowercase(), cty.optString("name"))
        }
        return seen.entries.map { it.key to it.value }.sortedBy { it.second }
    }

    /** Parsed resource kept in memory: "select all" loads dozens of sources out of the same file. */
    private val parsed = HashMap<String, Pair<Long, JSONObject>>()

    private fun obj(c: Context, name: String): JSONObject? = synchronized(parsed) {
        val f = File(c.filesDir, "res_" + safe(name) + ".json")
        parsed[name]?.let { if (it.first == f.lastModified()) return it.second }
        val text = resource(c, name, false) ?: return null
        JSONObject(text).also { parsed[name] = f.lastModified() to it }
    }

    private fun worldUrl(c: Context, code: String): String? {
        val j = obj(c, "ListWorldTv.bin") ?: return null
        for (region in j.getJSONArray("worlditems").objects()) for (cty in region.optJSONArray("worlditems").objects()) {
            if (cty.optString("code").equals(code, true)) return String(Base64.decode(cty.optString("url"), Base64.DEFAULT))
        }
        return null
    }

    private fun cams(c: Context, refresh: Boolean): List<String> {
        val j = JSONObject(resource(c, "ListSkyLineWebcam.bin", refresh) ?: return emptyList())
        return j.getJSONArray("worlditems").objects().flatMap { it.optJSONArray("worlditems").objects() }
            .map { it.optString("name") }.distinct().sorted()
    }

    private fun radios(c: Context, refresh: Boolean): List<Pair<String, String>> {
        val a = JSONArray(resource(c, "ListWorldRadio.bin", refresh) ?: return emptyList())
        return a.objects().map { it.optString("code") to it.optString("name") }.sortedBy { it.second }
    }

    // ------------------------------------------------------------------ channels

    /** Channels of source [id]; [refresh] forces a download, a failed one keeps the cache. Null when nothing at all. */
    fun load(c: Context, id: String, refresh: Boolean): List<Channel>? {
        val label = title(c, id)
        val kind = id.substringBefore(':')
        val arg = id.substringAfter(':')
        return runCatching {
            when (kind) {
                "wltv" -> m3u(c, id, "$API/$arg/list.m3u8", refresh, label)
                "world" -> worldUrl(c, arg)?.let { m3u(c, id, it, refresh, label) }
                "custom" -> customUrl(c).takeIf { it.isNotEmpty() }?.let { m3u(c, id, it, refresh, label) }
                "cam" -> camChannels(c, arg, label)
                "radio" -> radioChannels(c, arg, refresh, label)
                else -> null
            }
        }.getOrNull()?.filterNot { isYouTube(it.url) }?.takeIf { it.isNotEmpty() }
    }

    /** YouTube entries are skipped: they only open the YouTube app (the addon hands them to plugin.video.youtube). */
    fun isYouTube(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("/play/yt/") || u.startsWith("plugin://plugin.video.youtube") ||
            u.contains("youtube.com/") || u.contains("youtu.be/")
    }

    private fun m3u(c: Context, id: String, url: String, refresh: Boolean, label: String): List<Channel>? {
        val text = cached(c, "list_" + safe(id) + ".m3u", refresh, 0) {
            Http.get(url, mapOf("User-Agent" to "WLTV ${Http.UA}")).takeIf { it.contains("#EXTINF") }
        } ?: return null
        return M3u.parse(text, label)
    }

    private fun camChannels(c: Context, country: String, label: String): List<Channel> {
        val j = obj(c, "ListSkyLineWebcam.bin") ?: return emptyList()
        val out = ArrayList<Channel>()
        for (cont in j.getJSONArray("worlditems").objects()) for (cty in cont.optJSONArray("worlditems").objects()) {
            if (cty.optString("name") != country) continue
            for (reg in cty.optJSONArray("worlditems").objects()) for (city in reg.optJSONArray("worlditems").objects()) {
                for (cam in city.optJSONArray("worlditems").objects()) {
                    out += Channel(
                        name = cam.optString("name"), url = cam.optString("url"), number = out.size + 1,
                        group = reg.optString("name"), logo = cam.optString("thumb"), tvgId = "", source = label,
                    )
                }
            }
        }
        return out
    }

    private fun radioChannels(c: Context, code: String, refresh: Boolean, label: String): List<Channel> {
        val file = JSONArray(resource(c, "ListWorldRadio.bin", false) ?: return emptyList()).objects()
            .firstOrNull { it.optString("code") == code }?.optString("filename") ?: return emptyList()
        val j = JSONObject(resource(c, "worldradio/$file", refresh) ?: return emptyList())
        val out = ArrayList<Channel>()
        val country = j.getJSONArray("countries").optJSONObject(0) ?: return out
        for (place in country.optJSONArray("places").objects()) for (r in place.optJSONArray("channels").objects()) {
            if (!r.optBoolean("enabled", true)) continue
            // one group per country: a country has hundreds of towns, too many groups for the left column
            out += Channel(r.optString("title"), r.optString("url"), out.size + 1, label, "", "", label)
        }
        return out
    }

    // ------------------------------------------------------------------ files

    /** A decoded .bin resource of the addon's file repository (base64 of raw deflate), cached for a week. */
    private fun resource(c: Context, name: String, refresh: Boolean): String? =
        cached(c, "res_" + safe(name) + ".json", refresh, WEEK) {
            val b64 = Http.get("$FILES/$name")
            val raw = Base64.decode(b64.trim(), Base64.DEFAULT)
            val inf = Inflater(true).apply { setInput(raw) }
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
            }
            inf.end()
            out.toString("UTF-8").takeIf { it.isNotBlank() }
        }

    /** Cached file: downloaded when [refresh], missing, or older than [maxAge] (0 = no age limit). */
    private fun cached(c: Context, name: String, refresh: Boolean, maxAge: Long, fetch: () -> String?): String? {
        val f = File(c.filesDir, name)
        val old = maxAge > 0 && System.currentTimeMillis() - f.lastModified() > maxAge
        if (refresh || !f.exists() || old) {
            runCatching(fetch).getOrNull()?.let { f.writeText(it); return it }
        }
        return if (f.exists()) f.readText() else null
    }

    private fun safe(s: String) = s.replace(Regex("[^\\w-]"), "_")

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
