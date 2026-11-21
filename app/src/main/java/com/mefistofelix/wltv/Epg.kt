package com.mefistofelix.wltv

import android.content.Context
import android.util.Xml
import okhttp3.Request
import org.tukaani.xz.XZInputStream
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** One programme of the guide. Times in epoch milliseconds. */
class Prog(val start: Long, val stop: Long, val title: String, val category: String, val desc: String) {
    fun isOn(t: Long) = t in start until stop
    fun progress(t: Long) = if (stop > start) ((t - start) * 1000 / (stop - start)).toInt().coerceIn(0, 1000) else 0
}

/**
 * The WLTV programme guide: XMLTV at http://epg-guide.com/wltv.xz (utils.EPG_URL, ~22k programmes, ~500 channels),
 * downloaded at most every 12 hours (the addon: once a day; 429 = banned for a while) and kept in files/epg.xz.
 * It is parsed in a background thread; only the programmes around now (from 1 h ago to 30 h ahead) are kept.
 * Channels are matched by tvg-id, then by display name. Also the source of the channel icons ([Logos]).
 */
class Epg(private val context: Context) {
    private val file = File(context.filesDir, "epg.xz")

    @Volatile var progs: Map<String, List<Prog>> = emptyMap(); private set
    @Volatile var iconById: Map<String, String> = emptyMap(); private set
    @Volatile var iconByName: Map<String, String> = emptyMap(); private set
    @Volatile private var idByName: Map<String, String> = emptyMap()
    @Volatile var parsedAt = 0L; private set

    /** Downloads when needed and parses; blocking, call off the main thread. True when something new was parsed. */
    fun update(force: Boolean = false): Boolean {
        val age = System.currentTimeMillis() - file.lastModified()
        var downloaded = false
        if (force || !file.exists() || age > 12 * 3600_000L) downloaded = runCatching { download() }.getOrDefault(false)
        // re-parse when the file changed or the kept window is getting old
        if (!file.exists()) return false
        if (!downloaded && parsedAt != 0L && System.currentTimeMillis() - parsedAt < 3 * 3600_000L) return false
        return runCatching { parse(); true }.getOrDefault(false)
    }

    private fun download(): Boolean {
        val req = Request.Builder().url(URL).header("User-Agent", "WLTV ${Http.UA}").build()
        Http.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return false
            val tmp = File(file.path + ".tmp")
            tmp.outputStream().use { r.body!!.byteStream().copyTo(it) }
            // a ban answers with a small non-XZ page: keep the old guide then
            if (tmp.length() < 1000 || tmp.inputStream().use { it.read() } != 0xFD) {
                tmp.delete(); return false
            }
            tmp.renameTo(file)
            return true
        }
    }

    private fun parse() {
        val now = System.currentTimeMillis()
        val from = now - 3600_000L
        val to = now + 30 * 3600_000L
        val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        val p = Xml.newPullParser()
        p.setInput(XZInputStream(file.inputStream().buffered(64 * 1024)).buffered(64 * 1024).reader(Charsets.UTF_8))
        val pr = HashMap<String, ArrayList<Prog>>()
        val icons = HashMap<String, String>()
        val iconNames = HashMap<String, String>()
        val names = HashMap<String, String>()
        var chId = ""
        var chNames = ArrayList<String>()
        var chIcon = ""
        // programme being read
        var pCh = ""; var pStart = 0L; var pStop = 0L; var keep = false
        var title = ""; var cat = ""; var desc = ""
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) when (p.name) {
                "channel" -> { chId = p.getAttributeValue(null, "id").orEmpty().lowercase(); chNames = ArrayList(); chIcon = "" }
                "display-name" -> if (pCh.isEmpty()) chNames += p.nextText()
                "icon" -> if (pCh.isEmpty()) chIcon = p.getAttributeValue(null, "src").orEmpty()
                "programme" -> {
                    pCh = p.getAttributeValue(null, "channel").orEmpty().lowercase()
                    pStart = time(fmt, p.getAttributeValue(null, "start"))
                    pStop = time(fmt, p.getAttributeValue(null, "stop"))
                    keep = pStop > from && pStart < to
                    title = ""; cat = ""; desc = ""
                }
                "title" -> if (keep && title.isEmpty()) title = p.nextText().trim()
                "category" -> if (keep && cat.isEmpty()) cat = p.nextText().trim()
                "desc" -> if (keep && desc.isEmpty()) desc = p.nextText().trim().replace(" - EPG by epg-guide.com", "").take(400)
            } else if (ev == XmlPullParser.END_TAG) when (p.name) {
                "channel" -> {
                    if (chIcon.isNotEmpty()) icons[chId] = chIcon
                    for (n in chNames) {
                        val k = Search.norm(n)
                        names.putIfAbsent(k, chId)
                        if (chIcon.isNotEmpty()) iconNames.putIfAbsent(k, chIcon)
                    }
                }
                "programme" -> {
                    if (keep && title.isNotEmpty()) pr.getOrPut(pCh) { ArrayList() } += Prog(pStart, pStop, title, cat, desc)
                    pCh = ""; keep = false
                }
            }
            ev = p.next()
        }
        for (l in pr.values) l.sortBy { it.start }
        progs = pr; iconById = icons; iconByName = iconNames; idByName = names
        parsedAt = now
    }

    private fun time(fmt: SimpleDateFormat, s: String?): Long = runCatching { fmt.parse(s!!)!!.time }.getOrDefault(0L)

    /** Guide id of [ch]: its tvg-id when the guide has it, else the guide channel with the same name. */
    fun idOf(ch: Channel): String? {
        val id = ch.tvgId.lowercase()
        if (id.isNotEmpty() && progs.containsKey(id)) return id
        return idByName[Search.norm(ch.name)]?.takeIf { progs.containsKey(it) }
            ?: idByName[Search.norm(ch.tvgId)]?.takeIf { progs.containsKey(it) }
    }

    fun now(ch: Channel, t: Long = System.currentTimeMillis()): Prog? =
        idOf(ch)?.let { id -> progs[id]?.firstOrNull { it.isOn(t) } }

    fun next(ch: Channel, t: Long = System.currentTimeMillis()): Prog? =
        idOf(ch)?.let { id -> progs[id]?.firstOrNull { it.start >= t } }

    companion object {
        const val URL = "http://epg-guide.com/wltv.xz"
        private val hm = SimpleDateFormat("HH:mm", Locale.ITALY)
        fun hhmm(t: Long): String = synchronized(hm) { hm.format(t) }
    }
}
