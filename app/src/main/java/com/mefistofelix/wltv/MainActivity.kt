package com.mefistofelix.wltv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.util.concurrent.Executors

/**
 * Single activity: fullscreen player, info banner, and the panel (groups | rows, search field, buttons).
 * The panel has two modes: channels (rows = channels with the programme on air under the name) and the TV guide
 * (rows = programmes on air or coming next, whatever the channel; OK plays the channel showing it).
 * Remote, video in front: ▲▼ / CH± zap, OK or ◀ = channels, ▶ or ⏩ = guide, Menu = channels + re-download,
 * long Menu = search (voice: mic of the Fire TV keyboard), digits = channel number, ⏯ = reload.
 */
@OptIn(UnstableApi::class)
class MainActivity : Activity() {
    private val tag = "WLTV"
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val net = Executors.newSingleThreadExecutor()
    private val epgExec = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("main", Context.MODE_PRIVATE) }
    private lateinit var stats: Stats
    private lateinit var logos: Logos
    private lateinit var epg: Epg

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var bannerBox: LinearLayout
    private lateinit var bannerLogo: ImageView
    private lateinit var bannerTitle: TextView
    private lateinit var bannerProg: TextView
    private lateinit var bannerTime: TextView
    private lateinit var bannerBar: ProgressBar
    private lateinit var bannerNext: TextView
    private lateinit var panel: LinearLayout
    private lateinit var header: TextView
    private lateinit var status: TextView
    private lateinit var search: SearchField
    private lateinit var groupList: ListView
    private lateinit var channelList: ListView
    private lateinit var listButton: Button
    private lateinit var modeButton: Button

    /** A row of the right column: a channel, or (guide mode) a programme of that channel. */
    private class Row(val ch: Channel, val prog: Prog?, val guide: Boolean = false)
    private class Group(val label: String, val rows: List<Row>, val indent: Boolean = false)

    private var channels: List<Channel> = emptyList()
    private var sourceTitles: List<String> = emptyList()
    private var guideMode = false
    private var groups: List<Group> = emptyList()
    private var shown: List<Row> = emptyList()
    private var current: Channel? = null
    private var playGen = 0
    private var retriedWithoutMime = false
    private var lastStream: Stream? = null
    private var digits = ""
    private var menuLongHandled = false
    private var backAt = 0L
    private var groupPos = 0

    private val groupAdapter = GroupAdapter()
    private val rowAdapter = RowAdapter()

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stats = Stats(this)
        logos = Logos(this)
        epg = Epg(this)
        buildUi()
        logos.init { rowAdapter.notifyDataSetChanged() }
        player = ExoPlayer.Builder(this).build()
        playerView.player = player
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = onError(error)
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) info(current)
            }
        })
        ui.postDelayed(tick, 1000)
        ui.postDelayed(minute, 60_000)
        loadList(refresh = false)
        updateEpg()
    }

    override fun onStop() {
        super.onStop()
        stats.save()
        player.pause()
    }

    override fun onStart() {
        super.onStart()
        if (current != null && player.playbackState != Player.STATE_IDLE) {
            player.seekToDefaultPosition(); player.play()
        }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        player.release()
        io.shutdownNow(); net.shutdownNow(); epgExec.shutdownNow()
        super.onDestroy()
    }

    /** Watch time: one second for each second the player is actually playing. */
    private val tick = object : Runnable {
        override fun run() {
            current?.let { if (player.isPlaying) stats.add(it, 1) }
            ui.postDelayed(this, 1000)
        }
    }

    /** Every minute: programmes move on (rows, progress bars), and the guide is refreshed when it gets old. */
    private val minute = object : Runnable {
        override fun run() {
            if (panel.visibility == View.VISIBLE) rowAdapter.notifyDataSetChanged()
            if (bannerBox.visibility == View.VISIBLE) info(current, keepOpen = false, restart = false)
            updateEpg()
            ui.postDelayed(this, 60_000)
        }
    }

    private fun updateEpg(force: Boolean = false) {
        epgExec.execute {
            if (!epg.update(force)) return@execute
            ui.post {
                logos.setMaps(epg.iconById, epg.iconByName)
                if (panel.visibility == View.VISIBLE) refreshGroups()
                if (bannerBox.visibility == View.VISIBLE) info(current, restart = false)
            }
        }
    }

    // ------------------------------------------------------------------ list loading

    /** Loads every selected source (cache first; a source older than a day, or [refresh], is downloaded again). */
    private fun loadList(refresh: Boolean, then: (() -> Unit)? = null) {
        val ids = Lists.selected(this)
        status(if (refresh) "Aggiorno ${ids.size} elenchi…" else "Caricamento elenchi…")
        net.execute {
            val order = Lists.catalog(this, false).values.flatten().map { it.id }
            val sorted = ids.sortedBy { order.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }
            val all = ArrayList<Channel>()
            val titles = ArrayList<String>()
            var failed = 0
            for ((i, id) in sorted.withIndex()) {
                if (sorted.size > 3) ui.post { status("Carico elenchi ${i + 1}/${sorted.size}…") }
                val stale = !refresh && System.currentTimeMillis() - prefs.getLong("loaded_$id", 0) > 24 * 3600_000L
                var list = Lists.load(this, id, refresh || stale)
                if (list == null && !(refresh || stale)) list = Lists.load(this, id, true)
                if (list == null) {
                    failed++; continue
                }
                if (refresh || stale) prefs.edit().putLong("loaded_$id", System.currentTimeMillis()).apply()
                titles += list.first().source
                all += list
            }
            val unique = all.distinctBy { it.key }
            ui.post {
                if (unique.isEmpty()) {
                    status("Elenchi non disponibili: controlla la connessione e premi ≡ per riprovare")
                    showPanel()
                    return@post
                }
                setChannels(unique, titles)
                val note = if (failed > 0) " ($failed elenchi non raggiungibili)" else ""
                status((if (refresh) "Elenchi aggiornati: " else "") + "${unique.size} canali$note")
                if (current == null) {
                    val last = prefs.getString("last", null)
                    val ch = channels.firstOrNull { it.key == last }
                    if (ch != null) play(ch) else showPanel()
                }
                then?.invoke()
            }
        }
    }

    private fun setChannels(list: List<Channel>, titles: List<String>) {
        channels = list
        sourceTitles = titles
        val name = when (titles.size) {
            0 -> ""
            1 -> titles[0]
            else -> "${titles[0]} + ${titles.size - 1}"
        }
        header.text = "WLTV  ·  $name"
        listButton.text = "Elenchi (${titles.size})"
        current = current?.let { c -> channels.firstOrNull { it.key == c.key } ?: c }
        refreshGroups()
    }

    private fun channelRows(list: List<Channel>) = list.map { Row(it, null) }

    /** Left column. Channels: most watched, all, then per source and category. Guide: on air, next, categories. */
    private fun refreshGroups() {
        val keep = groups.getOrNull(groupPos)?.label?.substringBefore("  ")
        val g = ArrayList<Group>()
        if (!guideMode) {
            val top = stats.top(channels)
            if (top.isNotEmpty()) g += Group(TOP, channelRows(top))
            g += Group(ALL, channelRows(channels))
            val multi = sourceTitles.size > 1
            for (src in sourceTitles) {
                val mine = if (multi) channels.filter { it.source == src } else channels
                val cats = mine.groupBy { it.group }
                if (multi) g += Group("▸ $src", channelRows(mine))
                if (!multi || cats.size > 1) for ((cat, list) in cats) g += Group(cat, channelRows(list), indent = multi)
            }
        } else {
            val now = System.currentTimeMillis()
            val onAir = ArrayList<Row>()
            val next = ArrayList<Row>()
            for (ch in channels.distinctBy { epg.idOf(it) ?: it.key }) {
                epg.now(ch, now)?.let { onAir += Row(ch, it, guide = true) }
                epg.next(ch, now)?.let { if (it.start - now < 4 * 3600_000L) next += Row(ch, it, guide = true) }
            }
            onAir.sortBy { it.prog!!.title.lowercase() }
            next.sortBy { it.prog!!.start }
            g += Group(ON_AIR, onAir)
            g += Group(NEXT, next)
            onAir.filter { it.prog!!.category.isNotEmpty() }.groupBy { it.prog!!.category.replaceFirstChar { c -> c.uppercase() } }
                .toSortedMap().forEach { (cat, rows) -> g += Group(cat, rows) }
            if (onAir.isEmpty()) status(if (epg.parsedAt == 0L) "Guida TV in caricamento…" else "Nessun programma in guida per questi canali")
        }
        groups = g
        groupAdapter.notifyDataSetChanged()
        val pos = g.indexOfFirst { it.label == keep }.takeIf { it >= 0 } ?: 0
        // setSelection would scroll the row to the top and hide the groups above it (★ Più guardati)
        val row = groupList.getChildAt(0)?.height?.takeIf { it > 0 } ?: dp(44)
        groupList.setSelectionFromTop(pos, if (pos < 6) pos * row else 0)
        showGroup(pos)
    }

    private fun showGroup(pos: Int) {
        groupPos = pos
        if (search.text.isNotEmpty()) return
        shown = groups.getOrNull(pos)?.rows ?: emptyList()
        rowAdapter.notifyDataSetChanged()
        val cur = shown.indexOfFirst { it.ch.key == current?.key }
        channelList.setSelection(if (cur >= 0) cur else 0)
    }

    private fun setMode(guide: Boolean) {
        if (guideMode == guide) return
        guideMode = guide
        modeButton.text = if (guide) "Canali" else "Guida TV"
        search.setText("")
        groupPos = 0
        groups = emptyList()
        refreshGroups()
        if (guide) status(if (epg.parsedAt == 0L) "Guida TV in caricamento…" else "Programmi in onda e prossimi")
        else status("${channels.size} canali")
    }

    // ------------------------------------------------------------------ playback

    private fun play(ch: Channel) {
        if (current?.key != ch.key) stats.save()
        current = ch
        prefs.edit().putString("last", ch.key).apply()
        val gen = ++playGen
        info(ch, "Caricamento…", keepOpen = true)
        player.stop()
        io.execute {
            val r = runCatching { Resolver.resolve(ch.url) }
            ui.post {
                if (gen != playGen) return@post
                r.onSuccess { start(it) }.onFailure {
                    Log.w(tag, "resolve ${ch.url}", it)
                    info(ch, "Non disponibile: ${it.message ?: it.javaClass.simpleName}", keepOpen = true)
                }
            }
        }
    }

    private fun start(s: Stream, forceMime: Boolean = true) {
        lastStream = s
        retriedWithoutMime = !forceMime
        if (s.url.isEmpty() && s.external != null) {
            info(current, "Apro l'app YouTube")
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.external))) }
                .onFailure { message("App YouTube non trovata") }
            return
        }
        Log.i(tag, "play ${s.url} drm=${s.licenseUrl != null}")
        val headers = HashMap(s.headers)
        val ua = headers.remove("User-Agent") ?: headers.remove("user-agent") ?: Http.UA
        val ds = OkHttpDataSource.Factory(Http.client).setUserAgent(ua).setDefaultRequestProperties(headers)
        val drm = DefaultDrmSessionManagerProvider().apply { setDrmHttpDataSourceFactory(ds) }
        val item = MediaItem.Builder().setUri(s.url)
        if (forceMime) mimeOf(s.url)?.let { item.setMimeType(it) }
        if (!s.licenseUrl.isNullOrEmpty()) {
            item.setDrmConfiguration(
                MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                    .setLicenseUri(s.licenseUrl).setLicenseRequestHeaders(s.licenseHeaders).build()
            )
        }
        val source = DefaultMediaSourceFactory(ds).setDrmSessionManagerProvider(drm).createMediaSource(item.build())
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
    }

    /** Most live links have no telling extension (redirectors, tokens): treat them as HLS unless they look otherwise. */
    private fun mimeOf(url: String): String? {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.contains(".mpd") || url.contains("format=mpd", true) -> MimeTypes.APPLICATION_MPD
            path.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
            Regex("\\.(mp3|aac|mp4|m4a|ogg|ts|flv)$").containsMatchIn(path) -> null
            path.contains("/stream") && !path.contains("playlist") -> null
            else -> MimeTypes.APPLICATION_M3U8
        }
    }

    private fun onError(e: PlaybackException) {
        Log.w(tag, "player error ${e.errorCodeName}", e)
        if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition(); player.prepare(); return
        }
        val s = lastStream
        // the HLS guess was wrong (plain audio/video stream): let ExoPlayer sniff the format
        if (s != null && !retriedWithoutMime && e.errorCode in listOf(
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            )
        ) {
            start(s, forceMime = false); return
        }
        val why = when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "il server rifiuta il flusso (geoblocco o link scaduto)"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "connessione non riuscita"
            in 6000..6999 -> "DRM non accettato"
            else -> e.errorCodeName
        }
        info(current, "Errore: $why", keepOpen = true)
    }

    private fun zap(step: Int) {
        val visible = shown.map { it.ch }.distinctBy { it.key }
        val list = if (visible.any { it.key == current?.key }) visible else channels
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.key == current?.key }
        play(list[((if (i < 0) 0 else i + step) % list.size + list.size) % list.size])
    }

    // ------------------------------------------------------------------ keys

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 1) {
                    menuLongHandled = true
                    openSearch()
                }
            } else if (event.action == KeyEvent.ACTION_UP) {
                if (!menuLongHandled) {
                    showPanel()
                    loadList(refresh = true)
                }
                menuLongHandled = false
            }
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val panelOpen = panel.visibility == View.VISIBLE
        if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 && !search.hasFocus()) {
            digit(code - KeyEvent.KEYCODE_0); return true
        }
        when (code) {
            KeyEvent.KEYCODE_CHANNEL_UP -> { zap(-1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(1); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_GUIDE -> {
                setMode(!(panelOpen && guideMode)); showPanel(); return true
            }
        }
        if (!panelOpen) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { zap(1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_LEFT -> {
                    setMode(false); showPanel(); return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { setMode(true); showPanel(); return true }
                KeyEvent.KEYCODE_INFO -> { info(current); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { current?.let { play(it) }; return true }
                KeyEvent.KEYCODE_BACK -> {
                    if (bannerBox.visibility == View.VISIBLE) {
                        bannerBox.visibility = View.GONE; return true
                    }
                    val now = System.currentTimeMillis()
                    if (now - backAt < 2500) finish() else {
                        backAt = now; message("Premi ancora Indietro per uscire")
                    }
                    return true
                }
            }
        } else if (code == KeyEvent.KEYCODE_BACK) {
            if (current != null) hidePanel() else finish()
            return true
        } else if (code == KeyEvent.KEYCODE_DPAD_DOWN && search.hasFocus()) {
            // the text field keeps ▼ for its cursor: go to the buttons below
            hideKeyboard(); modeButton.requestFocus(); return true
        } else if (code == KeyEvent.KEYCODE_DPAD_LEFT && channelList.hasFocus()) {
            // ListView would pick the group row next to the focused row: keep the group being shown
            val keep = groupPos
            groupList.requestFocus()
            ui.post {
                val v = groupList.getChildAt(keep - groupList.firstVisiblePosition)
                groupList.setSelectionFromTop(keep, v?.top ?: 0)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private val digitTimeout = Runnable {
        val n = digits.toIntOrNull()
        digits = ""
        val ch = channels.firstOrNull { it.number == n }
        if (ch != null) play(ch) else message("Canale $n non presente")
    }

    private fun digit(d: Int) {
        digits += d
        message(digits, keepOpen = true)
        ui.removeCallbacks(digitTimeout)
        ui.postDelayed(digitTimeout, 1500)
    }

    // ------------------------------------------------------------------ panel

    private fun showPanel() {
        if (panel.visibility != View.VISIBLE) {
            panel.visibility = View.VISIBLE
            bannerBox.visibility = View.GONE
            refreshGroups()
        }
        // after the layout pass: a list that has just been filled does not keep a focus requested now
        channelList.requestFocus()
        panel.post { if (!search.hasFocus() && panel.findFocus() == null) channelList.requestFocus() }
    }

    private fun hidePanel() {
        hideKeyboard()
        panel.visibility = View.GONE
    }

    private fun openSearch() {
        showPanel()
        search.requestFocus()
        panel.post { search.requestFocus() }
        ui.postDelayed({
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
    }

    /** Channels by name, number or title of the programme on air. */
    private fun onSearchText(q: String) {
        if (q.isBlank()) {
            status("${channels.size} canali")
            showGroup(groupPos); return
        }
        val now = System.currentTimeMillis()
        shown = Search.find(channels, q) { epg.now(it, now)?.title }.map { Row(it, null) }
        rowAdapter.notifyDataSetChanged()
        channelList.setSelection(0)
        status(if (shown.isEmpty()) "Nessun canale o programma per “$q”" else "${shown.size} risultati per “$q”")
    }

    /** IME "Go" (also sent after dictation): play the best match straight away. */
    private fun onSearchGo() {
        val best = shown.firstOrNull() ?: return
        hideKeyboard()
        search.setText("")
        play(best.ch)
        hidePanel()
    }

    // ------------------------------------------------------------------ list chooser

    /**
     * Sections → lists of sources with checkboxes. No confirm buttons: every tick is saved at once, Back goes up
     * one level, and leaving the sections reloads the channels if anything changed.
     */
    private fun chooseLists() {
        status("Scarico gli elenchi disponibili…")
        net.execute {
            val cat = Lists.catalog(this, true)
            ui.post {
                status("")
                val before = Lists.selected(this)
                sectionsDialog(cat, before.toMutableSet(), before)
            }
        }
    }

    private fun sectionsDialog(cat: Map<String, List<Lists.Source>>, chosen: MutableSet<String>, before: Set<String>) {
        val sections = Lists.SECTIONS.filter { cat[it]?.isNotEmpty() == true }
        val labels = sections.map { s ->
            val n = cat[s]!!.count { it.id in chosen }
            "$s  ·  ${cat[s]!!.size} elenchi" + if (n > 0) "  ·  $n attivi" else ""
        } + ("URL personalizzato…" + if (Lists.CUSTOM in chosen) "  ·  attivo" else "")
        var next = false // a sub-dialog opens: not leaving yet
        AlertDialog.Builder(this)
            .setTitle("Elenchi canali (${chosen.size} attivi)")
            .setItems(labels.map { it as CharSequence }.toTypedArray()) { _, which ->
                next = true
                if (which < sections.size) sourcesDialog(cat, sections[which], chosen, before) else askCustomUrl(cat, chosen, before)
            }
            .setOnDismissListener { if (!next && chosen != before) reloadSources() }
            .show()
    }

    /** First row ticks or clears the whole section; each tick is saved right away. */
    private fun sourcesDialog(cat: Map<String, List<Lists.Source>>, section: String, chosen: MutableSet<String>, before: Set<String>) {
        val items = cat[section]!!
        fun allOn() = items.all { it.id in chosen }
        val labels = listOf<CharSequence>("Seleziona tutti / deseleziona tutti") + items.map { it.title }
        val checked = BooleanArray(items.size + 1) { if (it == 0) allOn() else items[it - 1].id in chosen }
        lateinit var dialog: AlertDialog
        dialog = AlertDialog.Builder(this)
            .setTitle("$section (${items.count { it.id in chosen }}/${items.size})")
            .setMultiChoiceItems(labels.toTypedArray(), checked) { _, which, on ->
                // AlertDialog re-reads [checked] for rows scrolled into view: keep it in step with the list view
                if (which == 0) {
                    if (on) items.forEach { chosen += it.id } else items.forEach { chosen -= it.id }
                    for (i in items.indices) {
                        checked[i + 1] = on; dialog.listView.setItemChecked(i + 1, on)
                    }
                } else {
                    if (on) chosen += items[which - 1].id else chosen -= items[which - 1].id
                    checked[0] = allOn(); dialog.listView.setItemChecked(0, checked[0])
                }
                Lists.setSelected(this, chosen)
                dialog.setTitle("$section (${items.count { it.id in chosen }}/${items.size})")
            }
            .setOnDismissListener { sectionsDialog(cat, chosen, before) }
            .create()
        dialog.show()
    }

    /** Address of an M3U list: saved when the dialog is left (Back or the keyboard's Go); empty = off. */
    private fun askCustomUrl(cat: Map<String, List<Lists.Source>>, chosen: MutableSet<String>, before: Set<String>) {
        lateinit var dialog: AlertDialog
        val field = EditText(this).apply {
            setText(Lists.customUrl(this@MainActivity))
            hint = "https://…/lista.m3u (vuoto = nessuna)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine()
            setOnEditorActionListener { _, _, _ -> dialog.dismiss(); true }
        }
        dialog = AlertDialog.Builder(this).setTitle("Indirizzo di una lista M3U").setView(field)
            .setOnDismissListener {
                val u = field.text.toString().trim()
                if (u.startsWith("http")) {
                    Lists.setCustomUrl(this, u); chosen += Lists.CUSTOM
                } else chosen -= Lists.CUSTOM
                Lists.setSelected(this, chosen)
                sectionsDialog(cat, chosen, before)
            }
            .create()
        dialog.show()
    }

    private fun reloadSources() {
        search.setText("")
        groupPos = 0
        groups = emptyList()
        loadList(refresh = false) { channelList.requestFocus() }
    }

    private fun resetStats() {
        AlertDialog.Builder(this).setTitle("Azzerare i canali più guardati?")
            .setPositiveButton("Azzera") { _, _ -> stats.clear(); refreshGroups(); status("Statistiche azzerate") }
            .setNegativeButton("Annulla", null).show()
    }

    // ------------------------------------------------------------------ banner

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun status(s: String) {
        status.text = s
    }

    private val hideBanner = Runnable { bannerBox.visibility = View.GONE }

    /**
     * Top-right box for [ch]: logo, number and name, the programme on air with its start and end time and a progress
     * bar, and the next one. [note] adds a line (loading, errors). [restart] re-arms the auto-hide timer.
     */
    private fun info(ch: Channel?, note: String? = null, keepOpen: Boolean = false, restart: Boolean = true) {
        if (ch == null) {
            note?.let { message(it, keepOpen) }; return
        }
        bannerLogo.visibility = View.VISIBLE
        logos.load(ch, bannerLogo, dp(60))
        bannerTitle.text = "${ch.number}  ${ch.name}"
        val now = System.currentTimeMillis()
        val p = epg.now(ch, now)
        val n = epg.next(ch, now)
        val lines = ArrayList<String>()
        if (p != null) {
            bannerProg.text = p.title
            bannerProg.visibility = View.VISIBLE
            val left = (p.stop - now) / 60_000
            bannerTime.text = "${Epg.hhmm(p.start)} – ${Epg.hhmm(p.stop)}   ·   " + if (left > 0) "mancano $left min" else "sta finendo"
            bannerTime.visibility = View.VISIBLE
            bannerBar.progress = p.progress(now)
            bannerBar.visibility = View.VISIBLE
        } else {
            bannerProg.visibility = View.GONE; bannerTime.visibility = View.GONE; bannerBar.visibility = View.GONE
        }
        if (n != null) lines += "Dopo, alle ${Epg.hhmm(n.start)}: ${n.title}"
        note?.let { lines += it }
        bannerNext.text = lines.joinToString("\n")
        bannerNext.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
        showBanner(keepOpen, restart)
    }

    /** The box with a plain message and no channel. */
    private fun message(text: String, keepOpen: Boolean = false) {
        bannerLogo.visibility = View.GONE
        bannerTitle.text = text
        bannerProg.visibility = View.GONE; bannerTime.visibility = View.GONE; bannerBar.visibility = View.GONE
        bannerNext.visibility = View.GONE
        showBanner(keepOpen, true)
    }

    private fun showBanner(keepOpen: Boolean, restart: Boolean) {
        if (panel.visibility == View.VISIBLE) return
        bannerBox.visibility = View.VISIBLE
        if (restart) {
            ui.removeCallbacks(hideBanner)
            ui.postDelayed(hideBanner, if (keepOpen) 10_000 else 6_000)
        }
    }

    // ------------------------------------------------------------------ views

    private fun focusBg(): StateListDrawable {
        fun shape(fill: Int, stroke: Int) = GradientDrawable().apply {
            setColor(fill); cornerRadius = dp(6).toFloat(); if (stroke != 0) setStroke(dp(3), stroke)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(Color.WHITE, Color.parseColor("#FF8A00")))
            addState(intArrayOf(), shape(Color.parseColor("#33FFFFFF"), 0))
        }
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setSingleLine()
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        background = focusBg()
        setPadding(dp(12), dp(6), dp(12), dp(6))
        minWidth = 0; minimumWidth = 0
        setOnFocusChangeListener { v, f -> (v as Button).setTextColor(if (f) Color.BLACK else Color.WHITE) }
        setTextColor(Color.WHITE)
        setOnClickListener { onClick() }
    }

    /** Orange highlight only in the column that has focus; the other one keeps a faint mark on its row. */
    private fun listView() = ListView(this).apply {
        selector = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                setColor(Color.parseColor("#FF8A00")); cornerRadius = dp(6).toFloat(); setStroke(dp(2), Color.WHITE)
            })
            addState(intArrayOf(), GradientDrawable().apply {
                setColor(Color.parseColor("#26FFFFFF")); cornerRadius = dp(6).toFloat()
            })
        }
        setDrawSelectorOnTop(false)
        divider = null
        isVerticalScrollBarEnabled = true
        itemsCanFocus = false
        isFocusable = true
    }

    private fun bar(heightDp: Int) = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressTintList = ColorStateList.valueOf(Color.parseColor("#2FA8FF"))
        progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#55FFFFFF"))
        minimumHeight = dp(heightDp)
    }

    private fun text(sp: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setSingleLine()
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setKeepContentOnPlayerReset(false)
            // keys go through dispatchKeyEvent; the player (and its hidden controller) must never take focus
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))

        // info banner, bottom: logo | channel, programme, times + progress, next
        bannerBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(Color.parseColor("#DD0B0F14")); cornerRadius = dp(12).toFloat() }
            setPadding(dp(18), dp(14), dp(22), dp(14))
            visibility = View.GONE
        }
        bannerLogo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        bannerBox.addView(bannerLogo, LinearLayout.LayoutParams(dp(80), dp(60)).apply { rightMargin = dp(18) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bannerTitle = text(22f, bold = true)
        bannerProg = text(19f)
        bannerTime = text(14f, Color.parseColor("#9AD4FF"))
        bannerBar = bar(5)
        bannerNext = text(14f, Color.parseColor("#BBBBBB")).apply { isSingleLine = false; maxLines = 3 }
        col.addView(bannerTitle)
        col.addView(bannerProg, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        col.addView(bannerTime, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        col.addView(bannerBar, LinearLayout.LayoutParams(-1, dp(5)).apply { topMargin = dp(4) })
        col.addView(bannerNext, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        bannerBox.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        val bw = (resources.displayMetrics.widthPixels * 0.62).toInt()
        root.addView(bannerBox, FrameLayout.LayoutParams(bw, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(32) })

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E60B0F14"))
            setPadding(dp(24), dp(20), dp(24), dp(16))
        }
        header = text(22f, bold = true)
        panel.addView(header)
        search = SearchField(this).apply {
            hint = "Cerca canale o programma: OK e poi il microfono"
            setHintTextColor(Color.parseColor("#88FFFFFF"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = InputType.TYPE_CLASS_TEXT
            background = GradientDrawable().apply { setColor(Color.parseColor("#22FFFFFF")); cornerRadius = dp(6).toFloat() }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnFocusChangeListener { v, f ->
                (v.background as GradientDrawable).setStroke(if (f) dp(3) else 0, Color.parseColor("#FF8A00"))
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = onSearchText(s?.toString().orEmpty())
            })
            setOnEditorActionListener { _, _, _ -> onSearchGo(); true }
            onBack = { hideKeyboard(); channelList.requestFocus() }
        }
        panel.addView(search, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modeButton = button("Guida TV") { setMode(!guideMode); channelList.requestFocus() }
        listButton = button("Elenchi") { chooseLists() }
        val gap = { LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) } }
        buttons.addView(modeButton)
        buttons.addView(listButton, gap())
        buttons.addView(button("Cerca") { openSearch() }, gap())
        buttons.addView(button("Aggiorna") { loadList(refresh = true); updateEpg(force = true) }, gap())
        buttons.addView(button("Azzera visti") { resetStats() }, gap())
        panel.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        status = text(14f, Color.parseColor("#9AD4FF"))
        panel.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val cols = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        groupList = listView().apply {
            adapter = groupAdapter
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    // the user moving through the groups leaves the search results
                    if (groupList.hasFocus() && search.text.isNotEmpty()) search.setText("")
                    showGroup(pos)
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            setOnItemClickListener { _, _, _, _ -> channelList.requestFocus() }
        }
        channelList = listView().apply {
            adapter = rowAdapter
            // API 28: a focus requested before the panel's first layout is handed to the default view on layout;
            // without this it is the search field, and focusing it pops up the Fire TV keyboard
            if (android.os.Build.VERSION.SDK_INT >= 26) isFocusedByDefault = true
            setOnItemClickListener { _, _, pos, _ ->
                shown.getOrNull(pos)?.let { play(it.ch); hidePanel() }
            }
        }
        cols.addView(groupList, LinearLayout.LayoutParams(0, -1, 0.36f))
        cols.addView(channelList, LinearLayout.LayoutParams(0, -1, 0.64f).apply { leftMargin = dp(14) })
        panel.addView(cols, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })

        root.addView(panel, FrameLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.56).toInt(), -1, Gravity.START))
        // hidden until needed: a visible panel at start would give the search field the initial focus (and the keyboard)
        panel.visibility = View.GONE
        setContentView(root)
    }

    /** Left column rows: label and, right-aligned, how many channels or programmes it holds. */
    private inner class GroupAdapter : BaseAdapter() {
        override fun getCount() = groups.size
        override fun getItem(p: Int): Any = p
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
            val row = (convert as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(text(17f), LinearLayout.LayoutParams(0, -2, 1f))
                addView(text(15f, Color.parseColor("#AAAAAA")), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
            }
            val g = groups[p]
            row.setPadding(if (g.indent) dp(28) else dp(12), dp(9), dp(12), dp(9))
            (row.getChildAt(0) as TextView).text = g.label
            (row.getChildAt(1) as TextView).text = g.rows.size.toString()
            return row
        }
    }

    /**
     * Right column rows. Channel: logo | "N  Name" and, under it, the programme on air with its times and a thin
     * progress bar. Programme (guide): logo | programme title and, under it, channel, times and what is left.
     */
    private inner class RowAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(p: Int): Any = p
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
            val row = (convert as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(6), dp(12), dp(6))
                addView(ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER },
                    LinearLayout.LayoutParams(dp(52), dp(39)).apply { rightMargin = dp(12) })
                val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                col.addView(text(18f))
                col.addView(text(14f, Color.parseColor("#C8C8C8")))
                col.addView(bar(3), LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(3) })
                addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            }
            val r = shown[p]
            val col = row.getChildAt(1) as LinearLayout
            val t1 = col.getChildAt(0) as TextView
            val t2 = col.getChildAt(1) as TextView
            val pb = col.getChildAt(2) as ProgressBar
            logos.load(r.ch, row.getChildAt(0) as ImageView, dp(39))
            val now = System.currentTimeMillis()
            if (r.guide && r.prog != null) {
                val pr = r.prog
                t1.text = pr.title
                t2.text = if (pr.isOn(now)) "${Epg.hhmm(pr.start)}–${Epg.hhmm(pr.stop)} · ancora ${(pr.stop - now) / 60_000}'  ·  ${r.ch.name}"
                else "alle ${Epg.hhmm(pr.start)} (tra ${(pr.start - now) / 60_000}')  ·  ${r.ch.name}"
                t2.visibility = View.VISIBLE
                pb.visibility = if (pr.isOn(now)) View.VISIBLE else View.GONE
                pb.progress = pr.progress(now)
            } else {
                val top = groups.getOrNull(groupPos)?.label == TOP && search.text.isEmpty()
                t1.text = "${r.ch.number}   ${r.ch.name}" + if (top) "   · " + Stats.format(stats.of(r.ch)) else ""
                val pr = epg.now(r.ch, now)
                if (pr != null) {
                    t2.text = "${pr.title}  ·  ${Epg.hhmm(pr.start)}–${Epg.hhmm(pr.stop)}"
                    t2.visibility = View.VISIBLE
                    pb.visibility = View.VISIBLE
                    pb.progress = pr.progress(now)
                } else {
                    t2.visibility = View.GONE
                    pb.visibility = View.GONE
                }
            }
            return row
        }
    }

    companion object {
        const val TOP = "★ Più guardati"
        const val ALL = "Tutti i canali"
        const val ON_AIR = "In onda ora"
        const val NEXT = "Tra poco"
    }
}

/** Search box: Back closes the Fire TV keyboard (it does not close by itself) and leaves the field. */
class SearchField(context: Context) : EditText(context) {
    var onBack: (() -> Unit)? = null

    /** Back is taken here, before the keyboard and the activity: down is swallowed (it would close the panel). */
    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onBack?.invoke()
            return true
        }
        return super.onKeyPreIme(keyCode, event)
    }
}
