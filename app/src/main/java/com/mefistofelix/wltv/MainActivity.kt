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
 * Single activity: fullscreen player, info banner, and the panel: a settings column on the left of the screen
 * (lists, refresh, reset stats, exit) and, on the right, the Canali / Guida TV tabs, the search field with ✕ and ONE
 * list: favourites and most watched on top, then every channel under small folder dividers ("source › group"); each
 * row also names its folder. The guide view lists programmes on air or coming next (OK plays the channel).
 * Search: OK on the keyboard only filters (focus on the first result); the filter stays until Back or ✕, which put the
 * whole list back on the row the user was on before searching. Channels and guide keep their own search and place.
 * Remote, video in front: ▲▼ / CH± zap, OK or ◀ = panel, long OK = favourite on/off, ▶ or ⏩ = guide, Menu = search
 * (voice: mic of the Fire TV keyboard), long Menu = re-download, digits = channel number, ⏯ = reload.
 * In the list: long OK = favourite on/off (kept whole, see [Favorites]), ◀ = settings, Menu = search field ⇄ list,
 * ⏪ ⏩ = page up / down, ⏯ = start / end, Back = reset the search, then close.
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
    private lateinit var favs: Favorites
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
    private lateinit var panel: FrameLayout
    private lateinit var header: TextView
    private lateinit var listTitle: TextView
    private lateinit var status: TextView
    private lateinit var search: SearchField
    private lateinit var channelList: ListView
    private lateinit var listButton: Button
    private lateinit var tabs: LinearLayout
    private lateinit var tabChannels: Button
    private lateinit var tabGuide: Button
    private lateinit var resetButton: Button
    private lateinit var settings: LinearLayout
    /** Settings button focused last: ◀ from the list goes back to it. */
    private var lastSetting: View? = null
    /** Index in [items] of the row the user is on, and its height on screen. */
    private var chanPos = 0
    private var chanTop = 0

    /** A channel row, or (guide mode) a programme of that channel; [block]: 0 = its folder, [FAV] / [TOP] = copy on top. */
    private class Row(val ch: Channel, val prog: Prog?, val guide: Boolean = false, val block: Int = 0) {
        val copy get() = block != 0
    }
    /** A small divider of the list: a folder (source › group), the most watched block, the results. */
    private class Header(val label: String, val count: Int)
    /** Where the user is in the list, kept across rebuilds (search, refresh, guide updates). */
    private data class Pos(val ch: String, val block: Int = 0, val start: Long? = null)

    private var channels: List<Channel> = emptyList()
    private var sourceTitles: List<String> = emptyList()
    private var guideMode = false
    /** The list: [Header] and [Row] items. */
    private var items: List<Any> = emptyList()
    private var current: Channel? = null
    private var playGen = 0
    private var retriedWithoutMime = false
    private var lastStream: Stream? = null
    private var digits = ""
    private var menuLongHandled = false
    /** OK pressed with the video in front: a long press toggles the favourite, a short one opens the panel. */
    private var okDown = false
    private var okLongHandled = false
    private var backAt = 0L
    private var guideRows: List<Row> = emptyList()
    /** The row the user is on; [anchor] = the row they were on when the search started. */
    private var sel: Pos? = null
    private var anchor: Pos? = null
    private var anchorTop = 0
    /** The result selected when the results came; another one selected = the user moved through the results. */
    /** Set while the search text is changed by the app (mode switch, reset): no new search. */
    private var restoring = false
    /** Row to select when the results come back after a mode switch. */
    private var restoreSel: Pos? = null
    /** Search and place of the other mode (channels / guide), each kept while the user is in the other one. */
    private class ModeState(var query: String = "", var sel: Pos? = null, var top: Int = 0, var anchor: Pos? = null, var anchorTop: Int = 0)
    private val modeStates = arrayOf(ModeState(), ModeState())
    /** Active search: score by row key ([hitKey]); null = no search. */
    private var hits: Map<String, Int>? = null
    private var searchGen = 0
    private val searchExec = Executors.newSingleThreadExecutor()
    /** Normalized names and titles, filled by the search thread only. */
    private val normNames = HashMap<String, String>()

    private val rowAdapter = RowAdapter()

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stats = Stats(this)
        favs = Favorites(this)
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
        updater = Updater(this)
        loadList(refresh = false)
        updateEpg()
        refreshLauncherEntry()
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

    override fun onResume() {
        super.onResume()
        checkUpdate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Updater.ACTION_RESULT) {
            progressDialog?.dismiss()
            progressDialog = null
            Updater.handleResult(this, intent)?.let { message(it, keepOpen = true) }
        }
    }

    // ------------------------------------------------------------------ self-update (Updater.kt)

    private lateinit var updater: Updater
    private var updateDialog: AlertDialog? = null
    private var progressDialog: AlertDialog? = null

    /** At every start and return to the front (at most every 2 minutes): a newer build is offered in a dialog. */
    private fun checkUpdate() {
        updater.check { r ->
            if (r == null || updater.snoozed(r)) return@check
            ui.post { offerUpdate(r) }
        }
    }

    private fun offerUpdate(r: Updater.Release) {
        if (isDestroyed || isFinishing || updateDialog?.isShowing == true) return
        val size = if (r.size > 0) " (" + String.format(java.util.Locale.ITALY, "%.1f", r.size / 1048576.0) + " MB)" else ""
        val d = AlertDialog.Builder(this)
            .setTitle("Aggiornamento disponibile")
            .setMessage("È disponibile Fire TV Live ${r.name}$size. Installata: 0.1.${updater.installedCode}.\n\n" +
                "Dopo lo scaricamento si apre la schermata di installazione di Fire TV: conferma con «Installa».")
            .setPositiveButton("Installa") { _, _ -> startUpdate(r) }
            .setNegativeButton("Più tardi") { _, _ -> updater.snooze(r) }
            .create()
        d.setOnShowListener { d.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus() }
        d.setOnCancelListener { updater.snooze(r) }
        updateDialog = d
        d.show()
    }

    private fun startUpdate(r: Updater.Release) {
        if (android.os.Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            askInstallPermission(r)
            return
        }
        val note = text(16f).apply { setPadding(dp(24), dp(16), dp(24), dp(8)); text = "Scaricamento… 0%" }
        val d = AlertDialog.Builder(this).setTitle("Fire TV Live ${r.name}").setView(note).setCancelable(false).create()
        d.show()
        updater.install(this, r,
            progress = { p -> ui.post { note.text = "Scaricamento… $p%" } },
            failed = { msg -> ui.post { d.dismiss(); message("Aggiornamento non riuscito: $msg", keepOpen = true) } })
        progressDialog = d // closed when the installer answers (onNewIntent) or on failure
    }

    /** Fire OS 7+ (Android 8+): installing needs the "Installa app sconosciute" switch for WLTV, once. */
    private fun askInstallPermission(r: Updater.Release) {
        val d = AlertDialog.Builder(this)
            .setTitle("Permesso per installare")
            .setMessage("Per aggiornarsi Fire TV Live deve poter installare app. Serve una volta sola:\n\n" +
                "Impostazioni → La mia Fire TV → Opzioni sviluppatore → Installa app sconosciute → Fire TV Live: ATTIVO\n\n" +
                "Poi torna qui e premi di nuovo «Installa».")
            .setPositiveButton("Apri impostazioni") { _, _ ->
                val tries = listOf(
                    Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES", Uri.parse("package:$packageName")),
                    Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES"),
                    Intent(android.provider.Settings.ACTION_SETTINGS),
                )
                if (tries.none { i -> runCatching { startActivity(i) }.isSuccess }) {
                    message("Apri le impostazioni dalla schermata Home di Fire TV", keepOpen = true)
                }
            }
            .setNegativeButton("Più tardi") { _, _ -> updater.snooze(r) }
            .create()
        d.setOnShowListener { d.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus() }
        d.show()
    }

    /**
     * Fire OS 6 only: its Appstore loses the launcher tile of a sideloaded app at every update and draws it again
     * only when told the package was added. Done once per installed version (as in FireBro).
     */
    private fun refreshLauncherEntry() {
        if (android.os.Build.VERSION.SDK_INT > 25) return
        runCatching {
            if (prefs.getInt("launcherEntry", 0) == updater.installedCode) return
            prefs.edit().putInt("launcherEntry", updater.installedCode).apply()
            sendBroadcast(Intent("com.amazon.tv.action.PACKAGE_ADDED", Uri.parse("package:$packageName")).setPackage("com.amazon.venezia"))
        }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        player.release()
        io.shutdownNow(); net.shutdownNow(); epgExec.shutdownNow(); searchExec.shutdownNow()
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
                if (panel.visibility == View.VISIBLE) rebuild()
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
                    val ch = pool().firstOrNull { it.key == last }
                    if (ch != null) play(ch) else showPanel()
                }
                then?.invoke()
            }
        }
    }

    private fun setChannels(list: List<Channel>, titles: List<String>) {
        channels = list
        favs.refresh(list)
        byKey = list.associateBy { it.key }
        sourceTitles = titles
        val name = when (titles.size) {
            0 -> ""
            1 -> titles[0]
            else -> "${titles[0]} + ${titles.size - 1}"
        }
        header.text = "Fire TV Live ${updater.installedCode}"
        listName = name
        updateTitle()
        listButton.text = "Elenchi (${titles.size})"
        current = current?.let { c -> channels.firstOrNull { it.key == c.key } ?: c }
        rebuild()
    }

    private var byKey: Map<String, Channel> = emptyMap()

    /** Loaded channels plus the favourites whose list is off: what search and the guide look through. */
    private fun pool(): List<Channel> {
        val extra = favs.all().filter { it.key !in byKey }
        return if (extra.isEmpty()) channels else channels + extra
    }

    /** Long OK on a channel (list, or the video in front): in or out of the favourites, saved at once. */
    private fun toggleFavorite(ch: Channel) {
        val on = favs.toggle(ch)
        val note = if (on) "★ Aggiunto ai preferiti: ${ch.name}" else "Tolto dai preferiti: ${ch.name}"
        if (panel.visibility == View.VISIBLE) {
            status(note)
            rebuild()
        } else info(ch, note)
    }

    private var listName = ""
    private fun updateTitle() {
        listTitle.text = listName
    }

    /** Folder of a channel as shown on its divider and its row: "source › group" when several lists are on. */
    private fun folderOf(ch: Channel): String {
        val g = ch.group.ifBlank { "Altri" }
        return if (sourceTitles.size > 1 && ch.source.isNotEmpty()) "${ch.source} › $g" else g
    }

    /** What a row shows as its place: the folder, and for programmes their category. */
    private fun metaOf(r: Row): String {
        val cat = r.prog?.category?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
        return if (cat != null) "$cat  ·  ${folderOf(r.ch)}" else folderOf(r.ch)
    }

    private fun hitKey(r: Row) = if (r.guide) "${r.ch.key}\u0000${r.prog?.start}" else r.ch.key
    private fun posOf(r: Row) = Pos(r.ch.key, r.block, r.prog?.start)

    /**
     * The list. Channels: most watched (copies), then every channel under its folder divider, in list order.
     * Guide: programmes on air (by title), then the ones starting within 4 hours (by time).
     * A search replaces it with the matches, best first, under one divider.
     */
    private fun buildItems(): List<Any> {
        val out = ArrayList<Any>()
        val h = hits
        if (!guideMode) {
            if (h != null) {
                val rows = pool().filter { it.key in h }.sortedByDescending { h[it.key] }
                out += Header(if (rows.isEmpty()) "Nessun risultato" else RESULTS, rows.size)
                rows.forEach { out += Row(it, null) }
                return out
            }
            val fav = favs.all().map { f -> byKey[f.key] ?: f }
            if (fav.isNotEmpty()) {
                out += Header(FAVS, fav.size)
                fav.forEach { out += Row(it, null, block = FAV_BLOCK) }
            }
            val top = stats.top(channels)
            if (top.isNotEmpty()) {
                out += Header(TOP, top.size)
                top.forEach { out += Row(it, null, block = TOP_BLOCK) }
            }
            val byFolder = LinkedHashMap<String, MutableList<Channel>>()
            // a numbered list (DTT) stays in number order under one divider; its group shows on each row
            for (c in channels) byFolder.getOrPut(if (c.lcn) "\u0000" + c.source else folderOf(c)) { ArrayList() } += c
            for ((f, list) in byFolder) {
                val numbered = f.startsWith("\u0000")
                val label = if (numbered) f.substring(1).ifBlank { "Canali" } else f
                out += Header(label, list.size)
                (if (numbered) list.sortedBy { it.number } else list).forEach { out += Row(it, null) }
            }
            return out
        }
        val now = System.currentTimeMillis()
        val onAir = ArrayList<Row>()
        val next = ArrayList<Row>()
        for (ch in pool().distinctBy { epg.idOf(it) ?: it.key }) {
            epg.now(ch, now)?.let { onAir += Row(ch, it, guide = true) }
            epg.next(ch, now)?.let { if (it.start - now < 4 * 3600_000L) next += Row(ch, it, guide = true) }
        }
        onAir.sortBy { it.prog!!.title.lowercase() }
        next.sortBy { it.prog!!.start }
        guideRows = onAir + next
        if (h != null) {
            val rows = guideRows.filter { hitKey(it) in h }.sortedByDescending { h[hitKey(it)] }
            out += Header(if (rows.isEmpty()) "Nessun risultato" else RESULTS, rows.size)
            out.addAll(rows)
            return out
        }
        if (onAir.isEmpty()) status(if (epg.parsedAt == 0L) "Guida TV in caricamento…" else "Nessun programma in guida per questi canali")
        if (onAir.isNotEmpty()) { out += Header(ON_AIR, onAir.size); out.addAll(onAir) }
        if (next.isNotEmpty()) { out += Header(NEXT, next.size); out.addAll(next) }
        return out
    }

    /** Index of [p] in the list: the same row, else the same channel in the same block, else the channel anywhere. */
    private fun indexOf(p: Pos?): Int {
        if (p == null) return -1
        var sameBlock = -1
        var any = -1
        for ((i, it) in items.withIndex()) {
            val r = it as? Row ?: continue
            if (r.ch.key != p.ch) continue
            if (r.block == p.block && r.prog?.start == p.start) return i
            if (sameBlock < 0 && r.block == p.block) sameBlock = i
            if (any < 0) any = i
        }
        return if (sameBlock >= 0) sameBlock else any
    }

    private fun firstRow(from: Int = 0) = (from until items.size).firstOrNull { items[it] is Row } ?: -1

    /** Rebuilds the list and selects [target] (default: where the user is; else the channel playing) at height [top]. */
    private fun rebuild(target: Pos? = sel, top: Int = chanTop, toCurrent: Boolean = true) {
        items = buildItems()
        rowAdapter.notifyDataSetChanged()
        var i = indexOf(target)
        if (i < 0 && toCurrent) i = indexOf(current?.let { Pos(it.key) })
        if (i < 0) i = firstRow()
        if (i < 0) return
        chanPos = i
        chanTop = top
        sel = posOf(items[i] as Row)
        channelList.setSelectionFromTop(i, top)
    }

    /**
     * Channels ⇄ guide (the tabs on top of the list). Each mode keeps its own search, place, and place before the
     * search: coming back finds them as they were left.
     */
    private fun setMode(guide: Boolean) {
        if (guideMode == guide) return
        modeStates[if (guideMode) 1 else 0].apply {
            query = search.text.toString(); sel = this@MainActivity.sel; top = chanTop
            anchor = this@MainActivity.anchor; anchorTop = this@MainActivity.anchorTop
        }
        guideMode = guide
        updateTabs()
        updateTitle()
        val st = modeStates[if (guide) 1 else 0]
        ui.removeCallbacks(searchRun)
        searchGen++
        hits = null
        anchor = st.anchor; anchorTop = st.anchorTop
        sel = st.sel; chanTop = st.top
        restoring = true; search.setText(st.query); restoring = false
        resetButton.visibility = if (st.query.isEmpty()) View.GONE else View.VISIBLE
        if (st.query.isBlank()) {
            rebuild(sel ?: current?.let { Pos(it.key) }, if (sel != null) chanTop else dp(60))
            if (guide) status(if (epg.parsedAt == 0L) "Guida TV in caricamento…" else "Programmi in onda e prossimi")
            else status("${channels.size} canali")
        } else {
            items = emptyList(); rowAdapter.notifyDataSetChanged()
            if (guide) items = buildItems() // fills guideRows for the search
            restoreSel = st.sel
            searchRun.run()
        }
    }

    /** The two tabs: the mode on show is filled, the other plain; the focused one gets the white focus look. */
    private fun updateTabs() {
        for ((b, on) in listOf(tabChannels to !guideMode, tabGuide to guideMode)) {
            b.background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                    setColor(Color.WHITE); cornerRadius = dp(6).toFloat(); setStroke(dp(3), Color.parseColor("#FF8A00"))
                })
                addState(intArrayOf(), GradientDrawable().apply {
                    setColor(Color.parseColor(if (on) "#3D5370" else "#1AFFFFFF")); cornerRadius = dp(6).toFloat()
                    if (on) setStroke(dp(2), Color.parseColor("#FF8A00"))
                })
            }
            b.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            b.text = (if (on) "● " else "") + if (b === tabChannels) "Canali" else "Guida TV (programmi)"
        }
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

    /** ▲▼ with the video in front: the channels of the list as it is (results while searching), not the copies on top. */
    private fun zap(step: Int) {
        val visible = items.mapNotNull { (it as? Row)?.takeIf { r -> !r.copy }?.ch }.distinctBy { it.key }
        val list = if (visible.any { it.key == current?.key }) visible else channels
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.key == current?.key }
        play(list[((if (i < 0) 0 else i + step) % list.size + list.size) % list.size])
    }

    // ------------------------------------------------------------------ keys

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val panelOpen = panel.visibility == View.VISIBLE
        if (code == KeyEvent.KEYCODE_MENU) {
            // short: video → search; in the panel it goes between the search field and the list. Long: re-download
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 1) {
                    menuLongHandled = true
                    showPanel()
                    loadList(refresh = true)
                }
            } else if (event.action == KeyEvent.ACTION_UP) {
                if (!menuLongHandled) {
                    if (panelOpen && search.hasFocus()) {
                        hideKeyboard(); focusChannels()
                    } else openSearch()
                }
                menuLongHandled = false
            }
            return true
        }
        if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && (!panelOpen || okDown)) {
            // video in front: short OK = channels, long OK = favourite on / off for the channel playing
            when (event.action) {
                KeyEvent.ACTION_DOWN -> when (event.repeatCount) {
                    0 -> { okDown = true; okLongHandled = false }
                    1 -> { okLongHandled = true; current?.let { toggleFavorite(it) } }
                }
                KeyEvent.ACTION_UP -> {
                    if (okDown && !okLongHandled && !panelOpen) showPanel()
                    okDown = false
                }
            }
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 && !search.hasFocus()) {
            digit(code - KeyEvent.KEYCODE_0); return true
        }
        when (code) {
            KeyEvent.KEYCODE_CHANNEL_UP -> { zap(-1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(1); return true }
            KeyEvent.KEYCODE_GUIDE -> { setMode(!(panelOpen && guideMode)); showPanel(); return true }
        }
        if (!panelOpen) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { zap(1); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { setMode(false); showPanel(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { setMode(true); showPanel(); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { openSearch(); return true }
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
            return super.dispatchKeyEvent(event)
        }
        // panel open (the search field takes its own Back in SearchField.onKeyPreIme)
        val inList = channelList.hasFocus()
        val inSettings = settings.hasFocus()
        when {
            code == KeyEvent.KEYCODE_BACK -> {
                when {
                    inSettings -> focusChannels()
                    hits != null || search.text.isNotEmpty() -> { resetSearch(); focusChannels() }
                    current != null -> hidePanel()
                    else -> finish()
                }
                return true
            }
            code == KeyEvent.KEYCODE_DPAD_DOWN && search.hasFocus() -> {
                // the text field keeps ▼ for its cursor: back to the row the user was on
                hideKeyboard(); focusChannels(); return true
            }
            code == KeyEvent.KEYCODE_DPAD_LEFT && inList -> { focusSettings(); return true }
            code == KeyEvent.KEYCODE_DPAD_RIGHT && inSettings -> { focusChannels(); return true }
            code == KeyEvent.KEYCODE_DPAD_LEFT && inSettings -> return true
            code == KeyEvent.KEYCODE_MEDIA_REWIND && inList -> { page(-1); return true }
            code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD && inList -> { page(1); return true }
            code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && inList -> { startOrEnd(); return true }
            code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { setMode(!guideMode); focusChannels(); return true }
        }
        return super.dispatchKeyEvent(event)
    }

    /*
     * A ListView taking focus picks the row nearest to the view the focus comes from, which loses the user's place.
     * This puts the focus back on the row (and at the height) it was left on.
     */
    private fun focusChannels() {
        if (firstRow() < 0) {
            search.requestFocus(); return
        }
        val keep = chanPos.coerceIn(0, items.size - 1)
        val top = channelList.getChildAt(keep - channelList.firstVisiblePosition)?.top ?: chanTop
        channelList.requestFocus()
        ui.post { channelList.setSelectionFromTop(keep, top) }
    }

    private fun focusSettings() {
        val v = lastSetting ?: settings.getChildAt(1)
        v?.requestFocus()
    }

    private fun lastRow() = items.indices.lastOrNull { items[it] is Row } ?: -1

    /** Selects row [i] of the list (focused) and remembers it at once (several key presses can come before the callback). */
    private fun moveTo(i: Int, top: Int) {
        if (i < 0) return
        chanPos = i; chanTop = top
        sel = posOf(items[i] as Row)
        channelList.setSelectionFromTop(i, top)
    }

    /** ⏪ ⏩ in the list: one screen up / down. */
    private fun page(step: Int) {
        val n = (channelList.lastVisiblePosition - channelList.firstVisiblePosition).coerceAtLeast(1)
        var t = (chanPos + step * n).coerceIn(0, items.size - 1)
        if (items[t] !is Row) t = if (step > 0) firstRow(t).takeIf { it >= 0 } ?: lastRow() else (t downTo 0).firstOrNull { items[it] is Row } ?: firstRow()
        moveTo(t, chanTop)
    }

    /** ⏯ in the list: to the first row, or to the last one when already on the first. */
    private fun startOrEnd() {
        val first = firstRow()
        if (chanPos <= first) moveTo(lastRow(), channelList.height - dp(70)) else moveTo(first, 0)
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

    /** Opens on the row the user left, or on the channel playing if they zapped away from it meanwhile. */
    private fun showPanel() {
        if (panel.visibility != View.VISIBLE) {
            panel.visibility = View.VISIBLE
            bannerBox.visibility = View.GONE
            val c = current
            val target = if (c != null && sel?.ch != c.key) Pos(c.key, block = sel?.block ?: 0) else sel
            rebuild(target, if (target == sel) chanTop else dp(60))
        }
        // after the layout pass: a list that has just been filled does not keep a focus requested now
        if (!search.hasFocus() && !settings.hasFocus() && !tabs.hasFocus()) {
            channelList.requestFocus()
            panel.post { if (panel.findFocus() == null) focusChannels() }
        }
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

    /** Typing: wait for a pause, then search off the main thread (thousands of rows; dictation sends many updates). */
    private fun onSearchText(q: String) {
        if (restoring) return
        resetButton.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
        ui.removeCallbacks(searchRun)
        ui.postDelayed(searchRun, if (q.isBlank()) 0 else 250)
    }

    private val searchRun = Runnable {
        val text = search.text.toString()
        val gen = ++searchGen
        if (text.isBlank()) {
            resetSearch()
            return@Runnable
        }
        val guide = guideMode
        val rows = if (guide) guideRows else pool().map { Row(it, null) }
        val folders = rows.map { metaOf(it) }
        searchExec.execute {
            val query = Search.Query(text)
            val now = System.currentTimeMillis()
            val found = HashMap<String, Int>()
            for ((i, r) in rows.withIndex()) {
                val name = normNames.getOrPut(r.ch.name) { Search.norm(r.ch.name) }
                val title = (if (guide) r.prog else epg.now(r.ch, now))?.title?.let { t -> normNames.getOrPut(t) { Search.norm(t) } }
                val folder = normNames.getOrPut(folders[i]) { Search.norm(folders[i]) }
                val s = query.score(name, r.ch.number, title, folder)
                if (s > 0) found[hitKey(r)] = s
            }
            ui.post {
                if (gen != searchGen || search.text.toString() != text || guide != guideMode) return@post
                showResults(found)
                status(if (found.isEmpty()) "Nessun risultato per “$text”  ·  Indietro o ✕ = tutto l'elenco"
                else "${found.size} risultati per “$text”  ·  Indietro o ✕ = tutto l'elenco")
                if (focusResults) {
                    focusResults = false
                    focusChannels()
                }
            }
        }
    }

    /**
     * The results replace the list, on the first one (or, coming back to a mode, on the row the user had left).
     * The place in the whole list is kept in [anchor] for the reset.
     */
    private fun showResults(found: Map<String, Int>) {
        if (hits == null && anchor == null) {
            anchor = sel; anchorTop = chanTop
        }
        hits = found
        val back = restoreSel
        restoreSel = null
        if (back != null) rebuild(back, chanTop) else {
            rebuild(null, 0, toCurrent = false)
        }
    }

    /** Reset (Back, ✕, empty text): the whole list again, on the row the user was on before searching. */
    private fun resetSearch() {
        if (hits == null && search.text.isEmpty()) return
        ui.removeCallbacks(searchRun)
        searchGen++
        val target = anchor
        val top = anchorTop
        hits = null
        anchor = null
        if (search.text.isNotEmpty()) {
            restoring = true; search.setText(""); restoring = false
        }
        resetButton.visibility = View.GONE
        rebuild(target, top)
        status(if (guideMode) "Programmi in onda e prossimi" else "${channels.size} canali")
    }

    /** Keyboard "Go"/OK: the keyboard closes and the focus goes to the first result; nothing starts playing. */
    private var focusResults = false

    private fun onSearchGo() {
        hideKeyboard()
        if (search.text.isBlank()) {
            focusChannels(); return
        }
        focusResults = true
        ui.removeCallbacks(searchRun)
        searchRun.run()
    }

    /** Dictation commits whole words at once: when it settles, close the keyboard and show the results. */
    private val dictationDone = Runnable {
        if (!search.hasFocus()) return@Runnable
        hideKeyboard()
        focusResults = true
        if (hits != null) focusChannels()
    }

    // ------------------------------------------------------------------ list chooser

    /** Search, place, and place before the search of the lists screen, kept between one opening and the next. */
    private var listsQuery = ""
    private var listsSel: String? = null
    private var listsAnchor: String? = null

    private fun chooseLists() {
        status("Scarico gli elenchi disponibili…")
        net.execute {
            val cat = Lists.catalog(this, true)
            ui.post {
                status("")
                ListsManager(cat).show()
            }
        }
    }

    /**
     * Every list in ONE screen: the active ones on top, then the sections (Liste WLTV, TV dal mondo, Webcam, Radio)
     * each with a "Tutti" row, and the custom URL. OK ticks a row and saves at once (no confirm button). ☰ or ▲ from
     * the top = search (dictation too); Back with a search clears it and goes back to the row the user was on, Back
     * again closes and reloads the channels if anything changed.
     */
    private inner class ListsManager(private val cat: Map<String, List<Lists.Source>>) {
        private val before = Lists.selected(this@MainActivity)
        private val chosen = before.toMutableSet()
        private val sources = Lists.SECTIONS.flatMap { cat[it].orEmpty() }
        private val byId = sources.associateBy { it.id }
        private val norms = sources.associate { it.id to Search.norm(it.title + " " + it.section) }
        private val dialog = android.app.Dialog(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
        private val title = text(19f, bold = true)
        private val field = SearchField(this@MainActivity)
        private val list = listView()
        /** Rows: String = divider, ALL_PREFIX + section = "Tutti", Source, ACTIVE_PREFIX + id = copy on top, CUSTOM_ROW. */
        private var rows: List<Any> = emptyList()
        private var selKey: String? = null
        private var anchorKey: String? = null
        private val reset = button("✕ Azzera ricerca") { clearSearch() }.apply { visibility = View.GONE }
        private var query = ""

        private fun key(r: Any): String = when (r) {
            is Lists.Source -> r.id
            else -> r.toString()
        }

        private fun visible(s: Lists.Source) = query.isEmpty() || norms[s.id]!!.contains(query)

        private fun build() {
            val out = ArrayList<Any>()
            if (query.isEmpty()) {
                val on = chosen.filter { it != Lists.CUSTOM }.mapNotNull { byId[it] }
                if (on.isNotEmpty()) {
                    out += "Attivi  ·  ${on.size}"
                    on.forEach { out += ACTIVE_PREFIX + it.id }
                }
            }
            for (s in Lists.SECTIONS) {
                val mine = cat[s].orEmpty().filter { visible(it) }
                if (mine.isEmpty()) continue
                out += "$s  ·  ${cat[s].orEmpty().count { it.id in chosen }}/${cat[s].orEmpty().size} attivi"
                if (mine.size > 1) out += ALL_PREFIX + s
                out.addAll(mine)
            }
            if (query.isEmpty() || Search.norm("url personalizzato m3u").contains(query)) {
                out += "Altro"
                out += CUSTOM_ROW
            }
            rows = out
            adapter.notifyDataSetChanged()
            title.text = "Elenchi canali  ·  ${chosen.size} attivi" + if (query.isNotEmpty()) "  ·  ricerca “${field.text}”" else ""
        }

        /** After a rebuild the same row stays selected (or the same list in its section, or the first row). */
        private fun select(k: String?) {
            var i = rows.indexOfFirst { key(it) == k }
            if (i < 0 && k != null) i = rows.indexOfFirst { key(it) == k.removePrefix(ACTIVE_PREFIX) }
            if (i < 0) i = rows.indexOfFirst { it !is String }
            if (i < 0) return
            val top = list.selectedView?.top ?: dp(60)
            list.setSelectionFromTop(i, top)
            selKey = key(rows[i])
        }

        private fun toggle(r: Any) {
            when {
                r is Lists.Source -> if (!chosen.remove(r.id)) chosen += r.id
                r is String && r.startsWith(ACTIVE_PREFIX) -> {
                    // switched off from the "Attivi" block: stay there, on the next active list
                    val at = rows.indexOf(r)
                    chosen -= r.removePrefix(ACTIVE_PREFIX)
                    Lists.setSelected(this@MainActivity, chosen)
                    build()
                    val next = (at until rows.size).firstOrNull { rows[it].let { x -> x is String && x.startsWith(ACTIVE_PREFIX) } }
                    select(next?.let { key(rows[it]) } ?: selKey)
                    return
                }
                r is String && r.startsWith(ALL_PREFIX) -> {
                    val mine = cat[r.removePrefix(ALL_PREFIX)].orEmpty().filter { visible(it) }
                    if (mine.all { it.id in chosen }) mine.forEach { chosen -= it.id } else mine.forEach { chosen += it.id }
                }
                r == CUSTOM_ROW -> {
                    askCustomUrl { on -> if (on) chosen += Lists.CUSTOM else chosen -= Lists.CUSTOM; Lists.setSelected(this@MainActivity, chosen); build(); select(selKey) }
                    return
                }
                else -> return
            }
            Lists.setSelected(this@MainActivity, chosen)
            build()
            select(selKey)
        }

        private fun isOn(r: Any): Boolean = when {
            r is Lists.Source -> r.id in chosen
            r is String && r.startsWith(ACTIVE_PREFIX) -> true
            r is String && r.startsWith(ALL_PREFIX) -> cat[r.removePrefix(ALL_PREFIX)].orEmpty().filter { visible(it) }.all { it.id in chosen }
            r == CUSTOM_ROW -> Lists.CUSTOM in chosen
            else -> false
        }

        private fun label(r: Any): String = when {
            r is Lists.Source -> r.title
            r is String && r.startsWith(ACTIVE_PREFIX) -> byId[r.removePrefix(ACTIVE_PREFIX)]?.let { Lists.label(it) } ?: r
            r is String && r.startsWith(ALL_PREFIX) -> "Tutti" + if (query.isNotEmpty()) " (quelli trovati)" else ""
            r == CUSTOM_ROW -> "URL personalizzato di una lista M3U…"
            else -> r.toString()
        }

        private fun focusList() {
            val i = rows.indexOfFirst { key(it) == selKey }.takeIf { it >= 0 } ?: rows.indexOfFirst { !isHeader(it) }
            list.requestFocus()
            if (i >= 0) ui.post { list.setSelectionFromTop(i, list.selectedView?.top ?: dp(60)) }
        }

        /** Reset (Back, ✕): every list again, on the row the user was on before searching. */
        private fun clearSearch() {
            val back = anchorKey ?: selKey
            anchorKey = null
            field.setText("") // the watcher rebuilds
            select(back)
            focusList()
        }

        private fun moveTo(i: Int, top: Int) {
            if (i < 0) return
            selKey = key(rows[i])
            list.setSelectionFromTop(i, top)
        }

        /** ⏪ ⏩: one screen up / down. */
        private fun page(step: Int) {
            val cur = list.selectedItemPosition.coerceAtLeast(0)
            val n = (list.lastVisiblePosition - list.firstVisiblePosition).coerceAtLeast(1)
            var t = (cur + step * n).coerceIn(0, rows.size - 1)
            if (isHeader(rows[t])) t = (if (step > 0) (t until rows.size) else (t downTo 0)).firstOrNull { !isHeader(rows[it]) } ?: cur
            moveTo(t, list.selectedView?.top ?: 0)
        }

        /** ⏯: first row, or the last one when already on the first. */
        private fun startOrEnd() {
            val first = rows.indexOfFirst { !isHeader(it) }
            val last = rows.indexOfLast { !isHeader(it) }
            if (list.selectedItemPosition <= first) moveTo(last, list.height - dp(60)) else moveTo(first, 0)
        }

        private val adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(p: Int): Any = rows[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getViewTypeCount() = 2
            override fun getItemViewType(p: Int) = if (isHeader(rows[p])) 1 else 0
            override fun areAllItemsEnabled() = false
            override fun isEnabled(p: Int) = rows.getOrNull(p)?.let { !isHeader(it) } ?: false
            override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
                val r = rows[p]
                if (isHeader(r)) {
                    val t = (convert as? TextView) ?: text(12f, Color.parseColor("#FFB25C"), bold = true).apply {
                        setPadding(dp(10), dp(12), dp(10), dp(3))
                    }
                    t.text = r.toString()
                    return t
                }
                val row = (convert as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    addView(View(context), LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(14) })
                    addView(text(17f), LinearLayout.LayoutParams(0, -2, 1f))
                }
                row.getChildAt(0).background = CheckDrawable(isOn(r), resources.displayMetrics.density)
                (row.getChildAt(1) as TextView).apply {
                    text = label(r)
                    setTypeface(null, if (r is String && r.startsWith(ALL_PREFIX)) Typeface.BOLD else Typeface.NORMAL)
                }
                return row
            }
        }

        private fun isHeader(r: Any) = r is String && !r.startsWith(ACTIVE_PREFIX) && !r.startsWith(ALL_PREFIX) && r != CUSTOM_ROW

        fun show() {
            val box = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(18), dp(22), dp(14))
                background = GradientDrawable().apply { setColor(Color.parseColor("#F20B0F14")); cornerRadius = dp(10).toFloat() }
            }
            box.addView(title)
            field.apply {
                hint = "Cerca elenco o paese  (☰ dal telecomando)"
                setHintTextColor(Color.parseColor("#88FFFFFF"))
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setSingleLine()
                imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                background = GradientDrawable().apply { setColor(Color.parseColor("#22FFFFFF")); cornerRadius = dp(6).toFloat() }
                setPadding(dp(12), dp(7), dp(12), dp(7))
                setOnFocusChangeListener { v, f ->
                    (v.background as GradientDrawable).setStroke(if (f) dp(3) else 0, Color.parseColor("#FF8A00"))
                }
                setOnClickListener {
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this, 0)
                }
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        val q = Search.norm(s?.toString().orEmpty())
                        if (q == query) return
                        if (query.isEmpty() && anchorKey == null) anchorKey = selKey
                        query = q
                        build()
                        select(null) // on the first result
                        reset.visibility = if (field.text.isEmpty()) View.GONE else View.VISIBLE
                    }
                })
                setOnEditorActionListener { _, _, _ ->
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
                    focusList(); true
                }
                onBack = {
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
                    if (rows.none { !isHeader(it) }) clearSearch() else focusList()
                }
            }
            val fieldRow = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            fieldRow.addView(field, LinearLayout.LayoutParams(0, -2, 1f))
            fieldRow.addView(reset, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
            box.addView(fieldRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            box.addView(text(12f, Color.parseColor("#9AD4FF")).apply {
                text = "OK: attiva/disattiva (salvato subito)  ·  ☰ ricerca ⇄ elenco  ·  ⏪ ⏩ pagina  ·  ⏯ inizio/fine  ·  Indietro: annulla la ricerca, poi chiude"
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            list.adapter = adapter
            list.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (list.hasFocus()) rows.getOrNull(pos)?.let { selKey = key(it) }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            list.setOnItemClickListener { _, _, pos, _ ->
                rows.getOrNull(pos)?.let { selKey = key(it); toggle(it) }
            }
            box.addView(list, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
            dialog.setContentView(box)
            dialog.setOnKeyListener { _, code, e ->
                when {
                    code == KeyEvent.KEYCODE_MENU -> {
                        if (e.action == KeyEvent.ACTION_UP) {
                            if (field.hasFocus()) {
                                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(field.windowToken, 0)
                                focusList()
                            } else {
                                field.requestFocus()
                                ui.postDelayed({
                                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
                                }, 150)
                            }
                        }
                        true
                    }
                    list.hasFocus() && e.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_MEDIA_REWIND -> { page(-1); true }
                    list.hasFocus() && e.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { page(1); true }
                    list.hasFocus() && e.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { startOrEnd(); true }
                    code == KeyEvent.KEYCODE_BACK -> {
                        if (e.action == KeyEvent.ACTION_UP) {
                            if (field.text.isNotEmpty()) clearSearch() else dialog.dismiss()
                        }
                        true
                    }
                    code == KeyEvent.KEYCODE_DPAD_DOWN && field.hasFocus() && e.action == KeyEvent.ACTION_DOWN -> {
                        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(field.windowToken, 0)
                        focusList(); true
                    }
                    else -> false
                }
            }
            dialog.setOnDismissListener {
                // the search and the place stay for the next time the screen is opened
                listsQuery = field.text.toString(); listsSel = selKey; listsAnchor = anchorKey
                if (chosen != before) reloadSources()
            }
            build()
            // start on the first active list (or the first row)
            anchorKey = listsAnchor
            if (listsQuery.isNotEmpty()) field.setText(listsQuery)
            select(listsSel ?: rows.firstOrNull { it is String && it.startsWith(ACTIVE_PREFIX) }?.let { key(it) })
            dialog.show()
            dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.62).toInt(), (resources.displayMetrics.heightPixels * 0.92).toInt())
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            list.requestFocus()
        }
    }

    /** Address of an M3U list: saved when the dialog is left (Back or the keyboard's Go); empty = off. */
    private fun askCustomUrl(done: (Boolean) -> Unit) {
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
                if (u.startsWith("http")) Lists.setCustomUrl(this, u)
                done(u.startsWith("http"))
            }
            .create()
        dialog.show()
    }

    private fun reloadSources() {
        resetSearch()
        loadList(refresh = false) { focusChannels() }
    }

    private fun resetStats() {
        AlertDialog.Builder(this).setTitle("Azzerare i canali più guardati?")
            .setPositiveButton("Azzera") { _, _ -> stats.clear(); rebuild(sel?.copy(block = 0)); status("Statistiche azzerate") }
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

    /**
     * Highlight of the focused row: lighter slate with a thick orange frame, so white text stays readable; a list
     * without focus keeps a faint mark on its row.
     */
    private fun listView() = ListView(this).apply {
        selector = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                setColor(Color.parseColor("#3D5370")); cornerRadius = dp(6).toFloat(); setStroke(dp(3), Color.parseColor("#FF8A00"))
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

        // panel: settings column at the left edge, the channel list at the right edge, the video between them
        panel = FrameLayout(this)
        val box = { GradientDrawable().apply { setColor(Color.parseColor("#E60B0F14")); cornerRadius = dp(10).toFloat() } }

        settings = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = box()
            setPadding(dp(16), dp(20), dp(16), dp(16))
        }
        header = text(20f, bold = true)
        settings.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        listButton = button("Elenchi") { chooseLists() }
        val item = { LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) } }
        for (b in listOf(
            listButton,
            button("Aggiorna") { loadList(refresh = true); updateEpg(force = true) },
            button("Azzera visti") { resetStats() },
            button("Esci") { finish() },
        )) {
            val own = b.onFocusChangeListener
            b.setOnFocusChangeListener { v, f -> own?.onFocusChange(v, f); if (f) lastSetting = v }
            settings.addView(b, item())
        }
        settings.addView(text(12f, Color.parseColor("#888888")).apply {
            isSingleLine = false; maxLines = 12
            text = "▶ o Indietro: torna all'elenco\n\nNell'elenco:\nOK tenuto: ★ preferito sì/no\n☰ ricerca ⇄ elenco\n⏪ ⏩ pagina su / giù\n⏯ inizio / fine elenco\n◀ queste impostazioni\nIndietro: annulla la ricerca"
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        panel.addView(settings, FrameLayout.LayoutParams(dp(210), -1, Gravity.START).apply {
            setMargins(dp(16), dp(16), 0, dp(16))
        })

        val chan = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = box()
            setPadding(dp(20), dp(18), dp(20), dp(12))
        }
        // top row: the two views as tabs (the one on show is filled), then the lists on
        tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tabChannels = button("Canali") { setMode(false); focusChannels() }
        tabGuide = button("Guida TV (programmi)") { setMode(true); focusChannels() }
        tabs.addView(tabChannels, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        tabs.addView(tabGuide, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(14) })
        listTitle = text(13f, Color.parseColor("#AAAAAA")).apply { gravity = Gravity.END }
        tabs.addView(listTitle, LinearLayout.LayoutParams(0, -2, 1f))
        chan.addView(tabs)
        updateTabs()
        search = SearchField(this).apply {
            hint = "Cerca canale, programma o cartella  (☰ dal telecomando)"
            setHintTextColor(Color.parseColor("#88FFFFFF"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setSingleLine()
            // same setup as FireBro's command bar, where the Fire TV keyboard dictation works: no suggestions
            // (dictation would arrive as composing text) and no full-screen extract editor
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            background = GradientDrawable().apply { setColor(Color.parseColor("#22FFFFFF")); cornerRadius = dp(6).toFloat() }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnFocusChangeListener { v, f ->
                (v.background as GradientDrawable).setStroke(if (f) dp(3) else 0, Color.parseColor("#FF8A00"))
            }
            setOnClickListener {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this, 0)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    // dictation commits whole words at once; typing adds one character at a time
                    ui.removeCallbacks(dictationDone)
                    if (count - before >= 4) ui.postDelayed(dictationDone, 1500)
                }
                override fun afterTextChanged(s: Editable?) = onSearchText(s?.toString().orEmpty())
            })
            setOnEditorActionListener { _, actionId, event ->
                val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER
                if (enter && event!!.action != KeyEvent.ACTION_DOWN) return@setOnEditorActionListener true
                if (enter || actionId != EditorInfo.IME_ACTION_UNSPECIFIED) onSearchGo()
                true
            }
            // Back: keyboard away and back to the list (search kept); with no result to go to, the search is cleared
            onBack = {
                hideKeyboard()
                if (firstRow() < 0) resetSearch()
                if (firstRow() >= 0) focusChannels() else if (current != null) hidePanel() else finish()
            }
        }
        // search field and, while a search is on, ✕ to clear it (back to the place before the search)
        val searchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        searchRow.addView(search, LinearLayout.LayoutParams(0, -2, 1f))
        resetButton = button("✕ Azzera ricerca") { resetSearch(); focusChannels() }.apply { visibility = View.GONE }
        searchRow.addView(resetButton, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        chan.addView(searchRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        status = text(13f, Color.parseColor("#9AD4FF"))
        chan.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        channelList = listView().apply {
            adapter = rowAdapter
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    // remember where the user is, to come back here from the search, the settings or a rebuild
                    if (!channelList.hasFocus()) return
                    val r = items.getOrNull(pos) as? Row ?: return
                    chanPos = pos; chanTop = v?.top ?: 0
                    sel = posOf(r)
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            // API 28: a focus requested before the panel's first layout is handed to the default view on layout;
            // without this it is the search field, and focusing it pops up the Fire TV keyboard
            if (android.os.Build.VERSION.SDK_INT >= 26) isFocusedByDefault = true
            // long OK on a row: favourite on / off (the place in the list stays)
            setOnItemLongClickListener { _, _, pos, _ ->
                (items.getOrNull(pos) as? Row)?.let { toggleFavorite(it.ch) }
                true
            }
            setOnItemClickListener { _, v, pos, _ ->
                val r = items.getOrNull(pos) as? Row ?: return@setOnItemClickListener
                chanPos = pos; chanTop = v?.top ?: 0; sel = posOf(r)
                play(r.ch); hidePanel()
            }
        }
        chan.addView(channelList, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
        val cw = (resources.displayMetrics.widthPixels * 0.58).toInt()
        panel.addView(chan, FrameLayout.LayoutParams(cw, -1, Gravity.END).apply { setMargins(0, dp(16), dp(16), dp(16)) })

        root.addView(panel, FrameLayout.LayoutParams(-1, -1))
        // hidden until needed: a visible panel at start would give the search field the initial focus (and the keyboard)
        panel.visibility = View.GONE
        setContentView(root)
    }

    /**
     * The list. Divider: small folder label and its size. Channel: logo | "N  Name" with its folder on the right and,
     * under it, the programme on air with its times and a thin progress bar. Programme (guide): logo | title with
     * category and folder, and under it channel, times and what is left.
     */
    private inner class RowAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int): Any = items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(p: Int) = if (items[p] is Header) 1 else 0
        override fun areAllItemsEnabled() = false
        override fun isEnabled(p: Int) = items.getOrNull(p) is Row

        override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
            val it = items[p]
            if (it is Header) {
                val v = (convert as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(10), dp(12), dp(3))
                    addView(text(12f, Color.parseColor("#FFB25C"), bold = true), LinearLayout.LayoutParams(-2, -2))
                    addView(View(context).apply { setBackgroundColor(Color.parseColor("#44FFB25C")) },
                        LinearLayout.LayoutParams(0, dp(1), 1f).apply { leftMargin = dp(10); rightMargin = dp(10) })
                    addView(text(12f, Color.parseColor("#AAAAAA")), LinearLayout.LayoutParams(-2, -2))
                }
                (v.getChildAt(0) as TextView).text = it.label
                (v.getChildAt(2) as TextView).text = it.count.toString()
                return v
            }
            val row = (convert as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(6), dp(12), dp(6))
                addView(ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER },
                    LinearLayout.LayoutParams(dp(52), dp(39)).apply { rightMargin = dp(12) })
                val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                line.addView(text(18f), LinearLayout.LayoutParams(0, -2, 1f))
                line.addView(text(12f, Color.parseColor("#9A9A9A")).apply { maxWidth = dp(240) },
                    LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
                col.addView(line)
                col.addView(text(14f, Color.parseColor("#C8C8C8")))
                col.addView(bar(3), LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(3) })
                addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            }
            val r = it as Row
            val col = row.getChildAt(1) as LinearLayout
            val line = col.getChildAt(0) as LinearLayout
            val t1 = line.getChildAt(0) as TextView
            val meta = line.getChildAt(1) as TextView
            val t2 = col.getChildAt(1) as TextView
            val pb = col.getChildAt(2) as ProgressBar
            logos.load(r.ch, row.getChildAt(0) as ImageView, dp(39))
            meta.text = if (r.block == TOP_BLOCK) "${Stats.format(stats.of(r.ch))}  ·  ${folderOf(r.ch)}" else metaOf(r)
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
                t1.text = (if (favs.has(r.ch)) "★ " else "") + "${r.ch.number}   ${r.ch.name}"
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
        const val TOP = "◆ Più guardati"
        const val ON_AIR = "In onda ora"
        const val NEXT = "Tra poco"
        const val RESULTS = "Risultati"
        const val FAVS = "★ Preferiti"
        const val FAV_BLOCK = 2
        const val TOP_BLOCK = 1
        const val ACTIVE_PREFIX = "\u0001"
        const val ALL_PREFIX = "\u0002"
        const val CUSTOM_ROW = "\u0003custom"
    }
}

/** Checkbox of the lists screen: empty white square, or green square with a white tick. */
class CheckDrawable(private val on: Boolean, private val density: Float) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: android.graphics.Canvas) {
        val b = bounds
        val w = 2 * density
        val r = android.graphics.RectF(b.left + w / 2, b.top + w / 2, b.right - w / 2, b.bottom - w / 2)
        if (on) {
            paint.style = android.graphics.Paint.Style.FILL
            paint.color = Color.parseColor("#2FB45A")
            canvas.drawRoundRect(r, 3 * density, 3 * density, paint)
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = 2.6f * density
            paint.strokeCap = android.graphics.Paint.Cap.ROUND
            paint.strokeJoin = android.graphics.Paint.Join.ROUND
            paint.color = Color.WHITE
            val p = android.graphics.Path()
            p.moveTo(r.left + r.width() * 0.22f, r.top + r.height() * 0.52f)
            p.lineTo(r.left + r.width() * 0.43f, r.top + r.height() * 0.72f)
            p.lineTo(r.left + r.width() * 0.80f, r.top + r.height() * 0.30f)
            canvas.drawPath(p, paint)
        } else {
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = w
            paint.color = Color.parseColor("#CCFFFFFF")
            canvas.drawRoundRect(r, 3 * density, 3 * density, paint)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
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
