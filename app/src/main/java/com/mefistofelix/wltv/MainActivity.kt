package com.mefistofelix.wltv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
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
import android.widget.LinearLayout
import android.widget.ListView
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
 * Single activity: fullscreen player plus the channel panel (groups | channels, search field, list buttons).
 * Remote: ▲▼ / CH± zap, OK opens the panel, Menu opens it and re-downloads the list (in the panel: re-download),
 * long Menu = search with the keyboard (voice: mic button of the Fire TV keyboard), digits = channel number.
 */
@OptIn(UnstableApi::class)
class MainActivity : Activity() {
    private val tag = "WLTV"
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val net = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("main", Context.MODE_PRIVATE) }
    private lateinit var stats: Stats

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var banner: TextView
    private lateinit var panel: LinearLayout
    private lateinit var header: TextView
    private lateinit var status: TextView
    private lateinit var search: SearchField
    private lateinit var groupList: ListView
    private lateinit var channelList: ListView
    private lateinit var listButton: Button

    private var channels: List<Channel> = emptyList()
    private var groups: List<String> = emptyList()
    private var shown: List<Channel> = emptyList()
    private var current: Channel? = null
    private var playGen = 0
    private var retriedWithoutMime = false
    private var lastStream: Stream? = null
    private var digits = ""
    private var menuLongHandled = false
    private var backAt = 0L
    private var groupPos = 0

    private val groupAdapter = RowAdapter { pos -> groups[pos] }
    private val channelAdapter = RowAdapter { pos ->
        val ch = shown[pos]
        val sec = if (groups.getOrNull(groupList.selectedItemPosition) == TOP) "   · " + Stats.format(stats.of(ch)) else ""
        "${ch.number}   ${ch.name}$sec"
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stats = Stats(this)
        buildUi()
        player = ExoPlayer.Builder(this).build()
        playerView.player = player
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = onError(error)
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) banner(current?.let { "${it.number}  ${it.name}" })
            }
        })
        ui.postDelayed(tick, 1000)
        loadList(refresh = false)
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
        io.shutdownNow(); net.shutdownNow()
        super.onDestroy()
    }

    /** Watch time: one second for each second the player is actually playing. */
    private val tick = object : Runnable {
        override fun run() {
            current?.let { if (player.isPlaying) stats.add(it, 1) }
            ui.postDelayed(this, 1000)
        }
    }

    // ------------------------------------------------------------------ list loading

    private fun loadList(refresh: Boolean, then: (() -> Unit)? = null) {
        val id = Lists.selected(this)
        status("Caricamento elenco…")
        net.execute {
            var list = Lists.load(this, id, refresh)
            // first start offline-cache miss, or a cache older than a day: try the server once
            val stale = !refresh && System.currentTimeMillis() - prefs.getLong("loaded_$id", 0) > 24 * 3600_000L
            if (list == null || stale) Lists.load(this, id, true)?.let { list = it }
            val title = Lists.title(this, id)
            ui.post {
                if (list == null) {
                    status("Elenco non disponibile: controlla la connessione e premi ≡ per riprovare")
                    showPanel()
                    return@post
                }
                if (refresh || stale) prefs.edit().putLong("loaded_$id", System.currentTimeMillis()).apply()
                setChannels(list!!, title)
                status(if (refresh) "Elenco aggiornato: ${list!!.size} canali" else "${list!!.size} canali")
                if (current == null) {
                    val last = prefs.getString("last", null)
                    val ch = channels.firstOrNull { it.key == last }
                    if (ch != null) play(ch) else showPanel()
                }
                then?.invoke()
            }
        }
    }

    private fun setChannels(list: List<Channel>, title: String) {
        channels = list
        header.text = "WLTV  ·  $title"
        listButton.text = "Elenco: $title"
        current = current?.let { c -> channels.firstOrNull { it.key == c.key } ?: c }
        refreshGroups()
    }

    private fun refreshGroups() {
        val keep = groups.getOrNull(groupList.selectedItemPosition)
        val g = ArrayList<String>()
        if (stats.top(channels).isNotEmpty()) g += TOP
        g += ALL
        channels.map { it.group }.distinct().forEach { g += it }
        groups = g
        groupAdapter.size = g.size
        val pos = g.indexOf(keep).takeIf { it >= 0 } ?: 0
        // setSelection would scroll the row to the top and hide the groups above it (★ Più guardati)
        val row = groupList.getChildAt(0)?.height?.takeIf { it > 0 } ?: dp(44)
        groupList.setSelectionFromTop(pos, if (pos < 6) pos * row else 0)
        showGroup(pos)
    }

    private fun showGroup(pos: Int) {
        groupPos = pos
        if (search.text.isNotEmpty()) return
        shown = when (groups.getOrNull(pos)) {
            TOP -> stats.top(channels)
            ALL, null -> channels
            else -> channels.filter { it.group == groups[pos] }
        }
        channelAdapter.size = shown.size
        val cur = shown.indexOfFirst { it.key == current?.key }
        channelList.setSelection(if (cur >= 0) cur else 0)
    }

    // ------------------------------------------------------------------ playback

    private fun play(ch: Channel) {
        if (current?.key != ch.key) stats.save()
        current = ch
        prefs.edit().putString("last", ch.key).apply()
        val gen = ++playGen
        banner("${ch.number}  ${ch.name}\nCaricamento…", keep = true)
        player.stop()
        io.execute {
            val r = runCatching { Resolver.resolve(ch.url) }
            ui.post {
                if (gen != playGen) return@post
                r.onSuccess { start(it) }.onFailure {
                    Log.w(tag, "resolve ${ch.url}", it)
                    banner("${ch.number}  ${ch.name}\nNon disponibile: ${it.message ?: it.javaClass.simpleName}", keep = true)
                }
            }
        }
    }

    private fun start(s: Stream, forceMime: Boolean = true) {
        lastStream = s
        retriedWithoutMime = !forceMime
        if (s.url.isEmpty() && s.external != null) {
            banner("${current?.name}: apro l'app YouTube")
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.external))) }
                .onFailure { banner("App YouTube non trovata", keep = true) }
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
        banner("${current?.number}  ${current?.name}\nErrore: $why", keep = true)
    }

    private fun zap(step: Int) {
        val list = if (shown.any { it.key == current?.key }) shown else channels
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
        }
        if (!panelOpen) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { zap(1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> { showPanel(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { current?.let { play(it) }; return true }
                KeyEvent.KEYCODE_BACK -> {
                    val now = System.currentTimeMillis()
                    if (now - backAt < 2500) finish() else {
                        backAt = now; banner("Premi ancora Indietro per uscire")
                    }
                    return true
                }
            }
        } else if (code == KeyEvent.KEYCODE_BACK) {
            if (current != null) hidePanel() else finish()
            return true
        } else if (code == KeyEvent.KEYCODE_DPAD_LEFT && channelList.hasFocus()) {
            // ListView would pick the group row next to the focused channel: keep the group being shown
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
        if (ch != null) play(ch) else banner("Canale $n non presente")
    }

    private fun digit(d: Int) {
        digits += d
        banner(digits, keep = true)
        ui.removeCallbacks(digitTimeout)
        ui.postDelayed(digitTimeout, 1500)
    }

    // ------------------------------------------------------------------ panel

    private fun showPanel() {
        if (panel.visibility != View.VISIBLE) {
            panel.visibility = View.VISIBLE
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

    private fun onSearchText(q: String) {
        if (q.isBlank()) {
            status("${channels.size} canali")
            showGroup(groupList.selectedItemPosition.coerceAtLeast(0)); return
        }
        shown = Search.find(channels, q)
        channelAdapter.size = shown.size
        channelList.setSelection(0)
        status(if (shown.isEmpty()) "Nessun canale per “$q”" else "${shown.size} risultati per “$q”")
    }

    /** IME "Go" (also sent after dictation): play the best match straight away. */
    private fun onSearchGo() {
        val best = shown.firstOrNull() ?: return
        hideKeyboard()
        search.setText("")
        play(best)
        hidePanel()
    }

    private fun chooseList() {
        status("Scarico gli elenchi disponibili…")
        net.execute {
            val lists = Lists.index(this, true)
            ui.post {
                status("")
                val names = lists.map { it.title } + "URL personalizzato…"
                val sel = Lists.selected(this)
                AlertDialog.Builder(this)
                    .setTitle("Elenco canali")
                    .setSingleChoiceItems(names.map { it as CharSequence }.toTypedArray(), lists.indexOfFirst { it.id == sel }.let { if (it < 0 && sel == Lists.CUSTOM) names.size - 1 else it }) { d, which ->
                        d.dismiss()
                        if (which == lists.size) askCustomUrl() else switchList(lists[which].id)
                    }
                    .setNegativeButton("Annulla", null)
                    .show()
            }
        }
    }

    private fun askCustomUrl() {
        val field = EditText(this).apply {
            setText(Lists.customUrl(this@MainActivity))
            hint = "https://…/lista.m3u"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        AlertDialog.Builder(this).setTitle("Indirizzo della lista M3U").setView(field)
            .setPositiveButton("Usa") { _, _ ->
                val u = field.text.toString().trim()
                if (u.startsWith("http")) {
                    Lists.setCustomUrl(this, u); switchList(Lists.CUSTOM)
                }
            }
            .setNegativeButton("Annulla", null).show()
    }

    private fun switchList(id: String) {
        Lists.select(this, id)
        search.setText("")
        groupList.setSelection(0)
        loadList(refresh = true) { channelList.requestFocus() }
    }

    private fun resetStats() {
        AlertDialog.Builder(this).setTitle("Azzerare i canali più guardati?")
            .setPositiveButton("Azzera") { _, _ -> stats.clear(); refreshGroups(); status("Statistiche azzerate") }
            .setNegativeButton("Annulla", null).show()
    }

    // ------------------------------------------------------------------ views

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun status(s: String) {
        status.text = s
    }

    private val hideBanner = Runnable { banner.visibility = View.GONE }

    private fun banner(text: String?, keep: Boolean = false) {
        if (text == null) return
        banner.text = text
        banner.visibility = View.VISIBLE
        ui.removeCallbacks(hideBanner)
        ui.postDelayed(hideBanner, if (keep) 8000 else 3500)
    }

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
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        background = focusBg()
        setPadding(dp(14), dp(6), dp(14), dp(6))
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

        banner = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { setColor(Color.parseColor("#CC101820")); cornerRadius = dp(10).toFloat() }
            setPadding(dp(18), dp(10), dp(18), dp(10))
            visibility = View.GONE
        }
        root.addView(banner, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(28), dp(36), 0) })

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E60B0F14"))
            setPadding(dp(24), dp(20), dp(24), dp(16))
        }
        header = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); typeface = Typeface.DEFAULT_BOLD
        }
        panel.addView(header)
        search = SearchField(this).apply {
            hint = "Cerca canale: OK e poi il microfono della tastiera"
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
        listButton = button("Elenco") { chooseList() }
        buttons.addView(listButton)
        buttons.addView(button("Cerca") { openSearch() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        buttons.addView(button("Aggiorna") { loadList(refresh = true) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        buttons.addView(button("Azzera più visti") { resetStats() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        panel.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        status = TextView(this).apply { setTextColor(Color.parseColor("#9AD4FF")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) }
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
            adapter = channelAdapter
            // API 28: a focus requested before the panel's first layout is handed to the default view on layout;
            // without this it is the search field, and focusing it pops up the Fire TV keyboard
            if (android.os.Build.VERSION.SDK_INT >= 26) isFocusedByDefault = true
            setOnItemClickListener { _, _, pos, _ ->
                shown.getOrNull(pos)?.let { play(it); hidePanel() }
            }
        }
        cols.addView(groupList, LinearLayout.LayoutParams(0, -1, 0.36f))
        cols.addView(channelList, LinearLayout.LayoutParams(0, -1, 0.64f).apply { leftMargin = dp(16) })
        panel.addView(cols, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })

        root.addView(panel, FrameLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.52).toInt(), -1, Gravity.START))
        // hidden until needed: a visible panel at start would give the search field the initial focus (and the keyboard)
        panel.visibility = View.GONE
        setContentView(root)
    }

    /** Text rows for both columns; [label] gives the text of a position. */
    private inner class RowAdapter(private val label: (Int) -> String) : BaseAdapter() {
        var size = 0
            set(v) { field = v; notifyDataSetChanged() }

        override fun getCount() = size
        override fun getItem(p: Int): Any = p
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
            val t = (convert as? TextView) ?: TextView(this@MainActivity).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setPadding(dp(12), dp(9), dp(12), dp(9))
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
            }
            t.text = label(p)
            return t
        }
    }

    companion object {
        const val TOP = "★ Più guardati"
        const val ALL = "Tutti i canali"
    }
}

/** Search box: Back closes the Fire TV keyboard (it does not close by itself) and leaves the field. */
class SearchField(context: Context) : EditText(context) {
    var onBack: (() -> Unit)? = null

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            onBack?.invoke(); return true
        }
        return super.onKeyPreIme(keyCode, event)
    }
}
