package com.mefistofelix.wltv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Channel logos. The list's own tvg-logo wins; otherwise the icon of the WLTV EPG (epg-guide.com/wltv.xz, what Kodi's
 * IPTV Simple Client shows with "logo from EPG") found by tvg-id, then by channel name; otherwise a tile with the
 * initials. Downloaded images are kept in files/logos, decoded small and held in a memory cache.
 */
class Logos(private val context: Context) {
    private val ui = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(3)
    private val dir = File(context.filesDir, "logos").apply { mkdirs() }
    private val mapFile = File(context.filesDir, "epg_icons.json")
    private val memory = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    @Volatile private var byId: Map<String, String> = emptyMap()
    @Volatile private var byName: Map<String, String> = emptyMap()
    private val failed = HashSet<String>()

    /** Loads the icon map saved last time (fast, before the guide is parsed), then calls [done] on the main thread. */
    fun init(done: () -> Unit) {
        pool.execute {
            runCatching {
                if (mapFile.exists()) {
                    val o = JSONObject(mapFile.readText())
                    byId = o.getJSONObject("id").let { j -> j.keys().asSequence().associateWith { j.getString(it) } }
                    byName = o.getJSONObject("name").let { j -> j.keys().asSequence().associateWith { j.getString(it) } }
                }
            }
            ui.post(done)
        }
    }

    /** Icons of the guide channels ([Epg]), by guide id and by normalized name; saved for the next start. */
    fun setMaps(ids: Map<String, String>, names: Map<String, String>) {
        if (ids.isEmpty()) return
        byId = ids; byName = names
        pool.execute {
            runCatching {
                mapFile.writeText(JSONObject().put("id", JSONObject(ids as Map<*, *>)).put("name", JSONObject(names as Map<*, *>)).toString())
            }
        }
    }

    /**
     * Logo addresses to try, best first: the list's own, the guide's by tvg-id, by name, and finally the guide id that
     * is the longest prefix of the tvg-id (rai3tgrlombardia -> rai3), so regional editions get the network's logo.
     */
    fun urlsOf(ch: Channel): List<String> {
        val out = LinkedHashSet<String>()
        ch.logo.takeIf { it.startsWith("http") }?.let { out += it }
        val id = ch.tvgId.lowercase()
        byId[id]?.let { out += it }
        byName[Search.norm(ch.name)]?.let { out += it }
        byName[Search.norm(ch.tvgId)]?.let { out += it }
        val nid = Search.norm(ch.tvgId.ifEmpty { ch.name })
        (nid.length downTo 4).firstNotNullOfOrNull { byId[nid.substring(0, it)] }?.let { out += it }
        return out.toList()
    }

    /** Shows the logo of [ch] in [view]: a cached logo or the initials at once, then the first logo that loads. */
    fun load(ch: Channel, view: ImageView, heightPx: Int) {
        val urls = synchronized(failed) { urlsOf(ch).filter { it !in failed } }
        val key = ch.key
        view.tag = key
        val hit = urls.firstNotNullOfOrNull { memory.get(it) }
        if (hit != null) {
            view.setImageBitmap(hit); return
        }
        view.setImageBitmap(initials(ch.name, heightPx))
        if (urls.isEmpty()) return
        pool.execute {
            for (url in urls) {
                val bmp = runCatching { fetch(url, heightPx) }.getOrNull()
                if (bmp == null) {
                    synchronized(failed) { failed += url }; continue
                }
                memory.put(url, bmp)
                ui.post { if (view.tag == key) view.setImageBitmap(bmp) }
                return@execute
            }
        }
    }

    private fun fetch(url: String, heightPx: Int): Bitmap? {
        val f = File(dir, md5(url))
        if (!f.exists() || f.length() == 0L) {
            val req = Request.Builder().url(url).header("User-Agent", Http.UA).build()
            Http.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                val tmp = File(dir, f.name + ".tmp")
                tmp.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
                tmp.renameTo(f)
            }
        }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outHeight <= 0) {
            f.delete(); return null
        }
        var sample = 1
        while (o.outHeight / (sample * 2) >= heightPx * 2) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun initials(name: String, h: Int): Bitmap {
        val key = "#" + name
        memory.get(key)?.let { return it }
        val w = h * 4 / 3
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val hue = (name.lowercase().hashCode() and 0x7fffffff) % 360
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.45f, 0.55f)) }
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), h * 0.15f, h * 0.15f, p)
        val words = name.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val text = (if (words.size >= 2) "${words[0][0]}${words[1][0]}" else name.take(2)).uppercase()
        p.color = Color.WHITE
        p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = h * 0.45f
        p.textAlign = Paint.Align.CENTER
        c.drawText(text, w / 2f, h / 2f - (p.descent() + p.ascent()) / 2, p)
        memory.put(key, bmp)
        return bmp
    }

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val EPG = "http://epg-guide.com/wltv.xz"
    }
}
