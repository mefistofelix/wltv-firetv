package com.mefistofelix.wltv

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * What the player needs: the manifest URL, the HTTP headers for it and, for Widevine channels, the license
 * server with its headers (the Kodi addon's BroadcasterResult with LicenseKey "url|headers|R{SSM}|R").
 * [external] is a URI to hand to another app (YouTube) when no stream URL could be found.
 */
data class Stream(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val licenseUrl: String? = null,
    val licenseHeaders: Map<String, String> = emptyMap(),
    val external: String? = null,
)

class ResolveException(msg: String) : Exception(msg)

/**
 * Kotlin port of the broadcaster modules of the Kodi addon plugin.video.wltvhelper 1.2.14 (with hotfix 121418),
 * https://worldlivetv.github.io/repo/. A list entry `plugin://plugin.video.wltvhelper/play/<broadcaster>/<id>`
 * is resolved by the function with the broadcaster's name, exactly like player.play() imports `broadcaster.<name>`.
 * Plain http(s) links are played as they are (Kodi `url|Header=value` suffixes become headers).
 * Network calls: run off the main thread.
 */
object Resolver {
    private const val PLUGIN = "plugin://plugin.video.wltvhelper/play/"

    fun resolve(link: String): Stream {
        val l = link.trim()
        return when {
            l.startsWith(PLUGIN) -> {
                val rest = l.removePrefix(PLUGIN)
                val b = rest.substringBefore('/')
                val id = Http.dec(rest.substringAfter('/', "").replace("+", "%2B"))
                plugin(b, id)
            }
            // plugin://plugin.video.wltvhelper/webcam/skylinewebcams/<country>/<region>/<city>/<cam> (default.py playWebcam)
            l.startsWith("plugin://plugin.video.wltvhelper/webcam/") ->
                skyline(l.removePrefix("plugin://plugin.video.wltvhelper/webcam/").substringAfter('/'))
            l.startsWith("plugin://plugin.video.twitch/") -> twitch(Http.match(l, "channel_name=([^&]+)"))
            l.startsWith("plugin://plugin.video.youtube/") -> ytVideo(Http.match(l, "video_id=([^&]+)"))
            l.startsWith("http", true) -> direct(l)
            else -> throw ResolveException("Link non supportato")
        }.also { if (it.url.isEmpty() && it.external == null) throw ResolveException("Nessun flusso trovato") }
    }

    private fun plugin(b: String, id: String): Stream = when (b) {
        "rai" -> rai(id)
        "rainewsrg" -> raiNewsRg(id)
        "mediaset" -> mediaset(id)
        "la7" -> la7(id)
        "discovery" -> discovery(id)
        "sky" -> sky(id)
        "yt" -> yt(id)
        "pluto" -> pluto(id)
        "rakuten" -> rakuten(id)
        "sam" -> sam(id)
        "wedotv" -> wedotv(id)
        "xdevel" -> xdevel(id)
        "saiuz" -> saiuz(id)
        "generics" -> generics(id)
        "sciscione" -> sciscione(id)
        "sportitalia" -> sportitalia(id)
        "telelombardia" -> telelombardia(id)
        "dailymotion" -> dailymotion(id)
        "smtv" -> smtv(id)
        "rsi" -> rsi(id)
        "premio" -> premio(id)
        "olympics" -> olympics(id)
        "mediasport" -> mediasport(id)
        "tv2000" -> tv2000()
        "supertennis" -> supertennis()
        "redbull" -> redbull()
        "sole24" -> sole24()
        "gamberorosso" -> gamberorosso()
        "legaseriea" -> legaSerieA()
        "antennasud" -> antennaSud(id)
        "vimeo" -> vimeo(id)
        "bozz" -> bozz(id)
        "castr" -> castr(id)
        "adtv" -> adtv(id)
        "twitch" -> twitch(id)
        else -> throw ResolveException("Broadcaster '$b' non supportato")
    }

    // ---------------------------------------------------------------- direct links

    /** `url|User-Agent=x&Referer=y` (Kodi syntax) -> headers; Rai relinker links get the real manifest. */
    private fun direct(link: String): Stream {
        val url = link.substringBefore('|')
        val headers = LinkedHashMap<String, String>()
        if (link.contains('|')) {
            for (p in link.substringAfter('|').split('&')) {
                val k = p.substringBefore('=').trim()
                if (k.isNotEmpty() && p.contains('=')) headers[k] = Http.dec(p.substringAfter('='))
            }
        }
        if (url.contains("relinkerServlet")) {
            val base = url.replace(Regex("[&?]output=\\d+"), "")
            return raiRelinker(if (base.contains('?')) base else "$base?")
        }
        return Stream(url, headers)
    }

    // ---------------------------------------------------------------- Rai

    private fun raiRelinker(url: String): Stream {
        val j = Http.getJson("$url&output=63&forceUserAgent=raiplayappletv", Http.browser())
        val video = j.optJSONArray("video")?.optString(0).orEmpty()
        val lics = j.optJSONObject("licence_server_map")?.optJSONArray("drmLicenseUrlValues")
        var lic: String? = null
        val lh = LinkedHashMap<String, String>()
        if (lics != null) for (i in 0 until lics.length()) {
            val o = lics.getJSONObject(i)
            if (o.optString("drm") == "WIDEVINE") {
                val full = o.optString("licenceUrl")
                lic = full.substringBefore('?')
                lh.putAll(Http.browser("https://www.raiplay.it"))
                lh["Accept"] = "application/octet-stream"
                lh["Content-Type"] = "application/octet-stream"
                lh["nv-authorizations"] = Http.dec(Http.match(full, "[?&]Authorization=([^&]+)"))
            }
        }
        return Stream(video, mapOf("User-Agent" to Http.UA), lic, lh)
    }

    private fun rai(search: String): Stream {
        val url = runCatching {
            val editors = Http.getJson("https://www.replaytvmhp.rai.it/hbbtv/launcher/RemoteControl/resources/srvConfig.json")
                .getJSONObject("Configuration").getJSONObject("Editors").getJSONArray("Editor")
            val ed = (0 until editors.length()).map { editors.getJSONObject(it) }
                .first { it.optString("name").equals(search, true) }
            val services = ed.getJSONArray("Services")
            val svc = (0 until services.length()).map { services.getJSONObject(it) }.first { it.optString("delivery") == "3" }
            val list = svc.opt("Service")
            val first = if (list is JSONArray) list.getJSONObject(0) else list as JSONObject
            first.getString("url")
        }.getOrElse {
            val contents = Http.getJson("https://www.raiplay.it/dirette.json", Http.browser()).getJSONArray("contents")
            (0 until contents.length()).map { contents.getJSONObject(it) }
                .first { it.optString("channel") == search }.getJSONObject("video").getString("content_url")
        }
        return raiRelinker(url)
    }

    private fun raiNewsRg(search: String): Stream {
        val region = search.substringBefore('$')
        val data = Http.get("https://www.rainews.it/tgr/$region/notiziari")
        val ctx = Http.match(data, """WashiContext.*parse\("(.*?)"\);\s+</script""")
        var url: String
        if (ctx.isNotEmpty()) {
            val j = JSONObject(ctx.replace("\\\"", "\""))
            val item = j.getJSONObject("itemToPlay")
            url = if (item.optString("data_type") == "diretta") item.optString("content_url") else {
                // not live now: newest news edition of the archive (the addon asks the user which one)
                val fasce = j.getJSONArray("fasce")
                val archive = (0 until fasce.length()).map { fasce.getJSONObject(it) }
                    .first { it.optString("title") == "Archivio" }.getJSONArray("contents")
                archive.getJSONObject(0).getJSONArray("cards").getJSONObject(0).getString("content_url")
            }
        } else url = Http.match(data, """data-mediaurl="([^"]+)""")
        if (url.isEmpty()) return Stream("")
        val video = Http.getJson("$url&output=47", Http.browser()).optJSONArray("video")?.optString(0).orEmpty()
        return Stream(video, mapOf("User-Agent" to Http.UA))
    }

    // ---------------------------------------------------------------- Mediaset

    private const val MS_HOST = "https://mediasetinfinity.mediaset.it"
    private const val MS_API = "https://api-ott-prod-fe.mediaset.net"
    private var msToken: Triple<String, String, String>? = null // appName, sid, beToken
    private var msTokenAt = 0L

    private fun mediasetLogin(): Triple<String, String, String> {
        msToken?.let { if (System.currentTimeMillis() - msTokenAt < 6 * 3600_000L) return it }
        val h = Http.browser(MS_HOST)
        val page = Http.get(MS_HOST, h)
        val appName = Http.match(page, """"app-name" content="([^"]+)""").ifEmpty { "web//mediasetplay-web/5.1.4-1" }
        val r = Http.postJson(
            "$MS_API/PROD/play/idm/anonymous/login/v2.0",
            JSONObject().put("appName", appName).put("client_id", UUID.randomUUID().toString()).toString(), h
        ).getJSONObject("response")
        return Triple(appName, r.getString("sid"), r.getString("beToken")).also { msToken = it; msTokenAt = System.currentTimeMillis() }
    }

    private fun mediaset(search: String): Stream {
        val h = Http.browser(MS_HOST)
        val callSign = if (search.startsWith("$")) search.substring(1) else {
            val e = Http.getJson(
                "https://feed.entertainment.tv.theplatform.eu/f/PR1GhC/mediaset-prod-all-stations-v2?sort=shortTitle|asc&fields=guid,callSign,title,tuningInstruction&form=cjson&httpError=true", h
            ).getJSONArray("entries")
            (0 until e.length()).map { e.getJSONObject(it) }.first { it.optString("title") == search }.getString("callSign")
        }
        val (appName, sid, token) = mediasetLogin()
        val ah = HashMap(h).apply {
            put("Authorization", "Bearer $token"); put("Accept", "application/json, text/plain, */*")
        }
        val body = JSONObject().put("channelCode", callSign).put("streamType", "LIVE").put("delivery", "Streaming")
            .put("createDevice", "true").put("overrideAppName", appName).toString()
        val sel = Http.postJson("$MS_API/PROD/play/playback/check/v2.0?sid=$sid", body, ah)
            .getJSONObject("response").getJSONObject("mediaSelector")
        sel.put("auth", token)
        val base = sel.getString("url")
        val qs = sel.keys().asSequence().filter { it != "url" }.joinToString("&") { "$it=${sel.get(it)}" }
        val smil = Http.get("$base?$qs", h)
        val ref = Http.match(smil, """(<ref\b[^>]*>)""")
        var url = Http.match(ref, """src="([^"]+)"""").replace("&amp;", "&")
        val encrypted = ref.contains("commonEncryption")
        val pid = Http.match(smil, """\|pid=([^|"]+)""")
        if (url.contains("theplatform")) url = Http.request(url, h).finalUrl
        val sh = mapOf("User-Agent" to Http.UA)
        if (url.contains(".mpd") && encrypted && pid.isNotEmpty()) {
            val lic = "https://widevine.entitlement.theplatform.eu/wv/web/ModularDrm/getRawWidevineLicense?releasePid=$pid&account=http://access.auth.theplatform.com/data/Account/2702976343&schema=1.0&token=$token"
            return Stream(url, sh, lic, HashMap(h).apply { put("Accept", "*/*"); put("Content-Type", "application/octet-stream") })
        }
        return Stream(url, sh)
    }

    // ---------------------------------------------------------------- La7

    private fun la7(search: String): Stream {
        val host = "https://www.la7play.it"
        val path = mapOf("La7" to "/dirette/la7", "La7c" to "/dirette/la7-cinema", "La7d" to "/dirette/la7-cinema")[search]
            ?: return Stream("")
        val h = Http.browser(host)
        val data = Http.get(host + path, h).replace("\\\"", "\"").replace("\\u0026", "&").replace("\\/", "/")
        val url = Http.match(data, """"mainLiveUrl":"([^"]+)"""").substringBefore('|')
        val endpoint = Http.match(data, """"drmTokenEndpoint":"([^"]+)"""")
        val contentId = Http.match(data, """"drmContentId":"([^"]+)"""").ifEmpty { "1" }
        if (url.isEmpty() || endpoint.isEmpty()) return Stream("")
        h["Accept"] = "*/*"
        val th = HashMap(h).apply { put("dnt", "1") }
        val pre = Http.getJson("$endpoint?id=$contentId&contentType=LIVE", th, verify = false).getString("preAuthToken")
        val lh = HashMap(h).apply {
            put("content-type", "application/octet-stream"); put("preAuthorization", pre)
            put("Origin", host); put("Referer", "$host/")
        }
        return Stream(url, h, "https://la7.prod.conax.cloud/widevine/license?d=${System.currentTimeMillis() / 1000}", lh)
    }

    // ---------------------------------------------------------------- Discovery (Warner Bros. Discovery Italia)

    private data class Disco(val id: Int, val code: String, val host: String)

    private val discoChannels = mapOf(
        "nove" to Disco(3, "nove", "nove.tv"),
        "realtime" to Disco(2, "realtime", "realtime.it"),
        "discovery" to Disco(12, "discoverychannelit", "discovery.it"),
        "dmax" to Disco(4, "dmaxit", "dmax.it"),
        "giallo" to Disco(27, "giallo", "giallotv.it"),
        "turbo" to Disco(11, "motortrend", "discoveryturbo.it"),
        "motortrend" to Disco(11, "motortrend", "discoveryturbo.it"),
        "hgtv" to Disco(13, "hgtvit", "homegardentv.it"),
        "foodnetwork" to Disco(6, "foodnetwork", "foodnetwork.it"),
        "k2" to Disco(24, "k2", "k2tv.it"),
        "frisbee" to Disco(26, "frisbee", "frisbeetv.it"),
    )

    private fun discovery(search: String): Stream {
        val ch = discoChannels[search.replace(" ", "").lowercase()] ?: throw ResolveException("Canale Discovery sconosciuto")
        val h = Http.browser("https://${ch.host}")
        var base = "https://public.aurora.enhanced.live"
        var realm = "it"
        var channelId = ch.id.toString()
        if (ch.code != "k2" && ch.code != "frisbee") {
            val pl = Http.getJson("$base/site/configurations/?include=default&filter[environment]=${ch.code}&v=2", h)
                .getJSONObject("settings").getJSONObject("site").getJSONObject("simulcast")
            base = pl.getString("sonicEndpoint"); realm = pl.getString("sonicRealm"); channelId = pl.get("channelId").toString()
        }
        h["X-Device-Info"] = "STONEJS/1 (Unknown/Unknown; Windows/NT 10.0; Unknown)"
        h["X-disco-params"] = "realm=$realm"
        h["X-disco-client"] = "WEB:UNKNOWN:wbdatv:2.1.9"
        h["Accept"] = "*/*"
        val tk = Http.getJson("$base/token?realm=$realm", h).getJSONObject("data").getJSONObject("attributes").getString("token")
        h["Authorization"] = "Bearer $tk"
        val body = """{"deviceInfo":{"adBlocker":false,"drmSupported":true,"hdrCapabilities":["SDR"],"hwDecodingCapabilities":[],"soundCapabilities":["STEREO"]},"wisteriaProperties":{"device":{"browser":{"name":"chrome","version":"${Http.BROWSER_VERSION}"},"type":"desktop"},"platform":"desktop"},"channelId":"$channelId"}"""
        val st = Http.postJson("$base/playback/v3/channelPlaybackInfo", body, h)
            .getJSONObject("data").getJSONObject("attributes").getJSONArray("streaming")
        val all = (0 until st.length()).map { st.getJSONObject(it) }
        val s = all.firstOrNull { it.optString("type") == "hls" } ?: all.first()
        return Stream(s.getString("url"), Http.browser("https://www.discoveryplus.com"))
    }

    // ---------------------------------------------------------------- Sky

    private fun sky(search: String): Stream {
        val id = mapOf("Sky TG24" to 1, "Cielo" to 2, "TV8" to 7, "Sport24" to 4)[search] ?: return Stream("")
        return Stream(Http.getJson("https://video.sky.it/api/v1/getLivestream?id=$id&isMobile=false").optString("streaming_url"))
    }

    // ---------------------------------------------------------------- YouTube

    private const val YT_COOKIE = "SOCS=CAISNQgDEitib3FfaWRlbnRpdHlmcm9udGVuZHVpc2VydmVyXzIwMjQxMTI0LjA4X3AwGgJpdCACGgYIgJS5ugY"

    private fun yt(id: String): Stream {
        val parts = id.split('/')
        val type = parts[0]
        val ch = parts.getOrElse(1) { "" }
        val name = parts.getOrElse(2) { "" }
        val h = Http.browser().apply { put("Cookie", YT_COOKIE) }
        val videoId = when (type) {
            "l" -> Http.match(Http.get("https://www.youtube.com/$ch/live", h), """window\['ytCommand'\].*?"videoId":"([^"]+)"""")
                .ifEmpty { Http.match(Http.get("https://www.youtube.com/$ch/live", h), """"videoId":"([^"]+)"""") }
            "c" -> ytChannelLive(ch, name, h)
            else -> ch
        }
        if (videoId.isEmpty()) throw ResolveException("Nessuna diretta YouTube in corso")
        return ytVideo(videoId)
    }

    private fun ytChannelLive(ch: String, name: String, h: Map<String, String>): String {
        val path = if (ch.startsWith("@")) "/$ch" else "/channel/$ch"
        val data = Http.get("https://www.youtube.com$path/streams", h)
        val json = Http.match(data, """ytInitialData\s?=\s?(.*?);<""")
        if (json.isEmpty()) return ""
        val want = name.replace(" ", "").lowercase()
        val found = ArrayList<JSONObject>()
        fun walk(o: Any?) {
            when (o) {
                is JSONObject -> {
                    o.optJSONObject("videoRenderer")?.let { found += it }
                    for (k in o.keys()) walk(o.opt(k))
                }
                is JSONArray -> for (i in 0 until o.length()) walk(o.opt(i))
            }
        }
        walk(JSONObject(json))
        for (vr in found) {
            val live = vr.optJSONArray("thumbnailOverlays")?.toString()?.contains("\"LIVE\"") == true
            if (!live) continue
            val title = vr.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text").orEmpty()
            if (want.isEmpty() || want == "0" || title.replace(" ", "").lowercase().contains(want)) return vr.optString("videoId")
        }
        return ""
    }

    /** HLS manifest of a live video from the watch page; otherwise the video is handed to the YouTube app. */
    private fun ytVideo(videoId: String): Stream {
        val h = Http.browser().apply { put("Cookie", YT_COOKIE) }
        val hls = runCatching {
            val page = Http.get("https://www.youtube.com/watch?v=$videoId", h)
            Http.match(page, """"hlsManifestUrl":"([^"]+)"""").replace("\\u0026", "&").replace("\\/", "/")
        }.getOrDefault("")
        return if (hls.isNotEmpty()) Stream(hls, mapOf("User-Agent" to Http.UA))
        else throw ResolveException("diretta su YouTube, non riproducibile qui")
    }

    // ---------------------------------------------------------------- SkylineWebcams (broadcaster/skylinewebcams.py)

    private fun skyline(path: String): Stream {
        val host = "www.skylinewebcams.com"
        val h = Http.browser(host).apply { put("Accept", "text/html,application/xhtml+xml,application/xml") }
        val data = Http.get("https://$host/it/webcam/$path.html", h)
        // the addon reads source:'…'; the page now has source: "livee.m3u8?a=…"
        val src = Http.match(data, """source:\s*['"]([^'"]*m3u8[^'"]*)['"]""")
        if (src.isNotEmpty()) {
            val url = if (src.startsWith("http")) src else "https://hd-auth.skylinewebcams.com/" + src.replace("livee.", "live.")
            return Stream(url, Http.browser("https://$host"))
        }
        val yt = Http.match(data, """videoId:\s*['"](.*?)['"]""")
        if (yt.isNotEmpty()) return ytVideo(yt)
        return Stream("")
    }

    // ---------------------------------------------------------------- Twitch

    private fun twitch(login: String): Stream {
        val q = "query PlaybackAccessToken_Template(\$login: String!, \$isLive: Boolean!, \$vodID: ID!, \$isVod: Boolean!, \$playerType: String!) {  streamPlaybackAccessToken(channelName: \$login, params: {platform: \"web\", playerBackend: \"mediaplayer\", playerType: \$playerType}) @include(if: \$isLive) {    value    signature    __typename  }  videoPlaybackAccessToken(id: \$vodID, params: {platform: \"web\", playerBackend: \"mediaplayer\", playerType: \$playerType}) @include(if: \$isVod) {    value    signature    __typename  }}"
        val body = JSONObject().put("operationName", "PlaybackAccessToken_Template").put("query", q)
            .put("variables", JSONObject().put("isLive", true).put("login", login).put("isVod", false).put("vodID", "").put("playerType", "site"))
        val h = Http.browser("https://www.twitch.tv").apply { put("Client-ID", "kimne78kx3ncx6brgo4mv6wki5h1ko") }
        val t = Http.postJson("https://gql.twitch.tv/gql", body.toString(), h)
            .getJSONObject("data").optJSONObject("streamPlaybackAccessToken") ?: throw ResolveException("Canale Twitch non in onda")
        val url = "https://usher.ttvnw.net/api/channel/hls/${login.lowercase()}.m3u8?allow_source=true&allow_audio_only=true" +
            "&fast_bread=true&p=${(Math.random() * 999999).toInt()}&sig=${t.getString("signature")}&token=${Http.enc(t.getString("value"))}"
        return Stream(url, mapOf("User-Agent" to Http.UA))
    }

    // ---------------------------------------------------------------- FAST channel platforms

    private fun pluto(search: String): Stream {
        val h = Http.browser()
        val uuid = UUID.randomUUID().toString().replace("-", "")
        val chs = Http.getJsonArray("https://api.pluto.tv/v2/channels.json?deviceId=$uuid", h)
        val chId = (0 until chs.length()).map { chs.getJSONObject(it) }.first { it.optString("slug") == search }.getString("_id")
        val page = Http.get("https://pluto.tv/live-tv/$chId", h)
        val appVersion = Http.match(page, """name="app_version" content="([^"]+)""").ifEmpty { Http.match(page, """name="appVersion" content="([^"]+)""") }
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val now = Date()
        val qs = listOf(
            "appName" to "web", "appVersion" to appVersion, "deviceVersion" to "138.0.0", "deviceModel" to "web",
            "deviceMake" to "chrome", "deviceType" to "web", "clientID" to UUID.randomUUID().toString(),
            "clientModelNumber" to "1.0.0", "channelSlug" to chId, "serverSideAds" to "false",
            "drmCapabilities" to "widevine%3AL3", "blockingMode" to "", "notificationVersion" to "1",
            "appLaunchCount" to "2", "lastAppLaunchDate" to iso.format(Date(now.time - 20 * 60_000)), "clientTime" to iso.format(now),
        ).joinToString("&") { "${it.first}=${it.second}" }
        val j = Http.getJson("https://boot.pluto.tv/v4/start?$qs", h)
        val server = j.getJSONObject("servers").getString("stitcher")
        val path = j.getJSONArray("EPG").getJSONObject(0).getJSONObject("stitched").getJSONArray("paths").getJSONObject(0).getString("path")
        val url = "$server/v2$path?${j.getString("stitcherParams")}&jwt=${j.getString("sessionToken")}&masterJWTPassthrough=true"
        return Stream(url, h)
    }

    private fun rakuten(search: String): Stream {
        val id = search.substringBefore('$')
        val lang = search.substringAfter('$', "").ifEmpty { "ITA" }
        val q = "classification_id=36&device_identifier=web&device_stream_audio_quality=2.0&device_stream_hdr_type=NONE" +
            "&device_stream_video_quality=FHD&disable_dash_legacy_packages=False&locale=it&market_code=it"
        val body = JSONObject().put("audio_language", lang).put("audio_quality", "2.0").put("classification_id", "36")
            .put("content_id", id).put("content_type", "live_channels").put("device_serial", "not implemented")
            .put("player", "web:HLS-NONE:NONE").put("strict_video_quality", false).put("subtitle_language", "MIS")
            .put("video_type", "stream")
        val j = Http.postJson("https://gizmo.rakuten.tv/v3/avod/streamings?$q", body.toString(), Http.browser("https://rakuten.tv"))
        return Stream(j.getJSONObject("data").getJSONArray("stream_infos").getJSONObject(0).getString("url"))
    }

    private fun sam(search: String) = Stream(
        "https://jmp2.uk/stvp-$search",
        mapOf("User-Agent" to "HbbTV/1.2.1 (+DRM;Samsung;SmartTV2016;T-JZMDEUC-1243.2;;) WebKit"),
    )

    private fun wedotv(search: String): Stream {
        val host = "https://www.wedotv.com"
        val dt = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val h = Http.browser().apply { put("Referer", "$host/it-it/channels/"); put("Cookie", "language=it; country=it") }
        val chs = Http.getJson("$host/api/getEpg.php?offset=0&limit=100&date=$dt&genre=all", h).getJSONArray("channels")
        val ch = (0 until chs.length()).map { chs.getJSONObject(it) }.firstOrNull { it.opt("id").toString() == search }
        return Stream(ch?.optString("hls").orEmpty())
    }

    // ---------------------------------------------------------------- regional / small broadcasters

    private fun xdevel(search: String): Stream {
        val parts = search.split('$')
        if (parts.size > 1) {
            val api = "https://share.xdevel.com/api"
            val page = String(android.util.Base64.decode(parts[1], android.util.Base64.DEFAULT))
            val h = Http.browser()
            val key0 = Http.match(Http.get(page, h), """xdevel.*?key=([^&]+)""")
            val dt = System.currentTimeMillis()
            h["Referer"] = "$api/?platform=streamsolution&get=player&key=$key0&ver=5"
            val key = Http.getJson("$api/?platform=streamsolution&get=playersettings&key=$key0&rdm=$dt&preview=0", h)
                .getJSONObject("channels").getJSONObject("0").getString("key")
            val urls = Http.getJson("$api/?platform=streamsolution&get=streamingsettings&key=$key&rdm=$dt", h)
                .getJSONObject("params").getJSONObject("playurls")
            return Stream(urls.getJSONArray("m3u8").getString(0))
        }
        val host = "https://play.xdevel.com"
        var h = Http.browser()
        val cfg = JSONObject(Http.match(Http.get("$host/$search", h), """PLAYER_CONFIG\s+=\s+(\{.+?\});\s+"""))
        val station = cfg.get("defaultStation").toString()
        val channel = cfg.getJSONObject("stations").getJSONObject(station).getJSONObject("channel")
        var url = channel.getJSONArray("sources").getJSONObject(0).getString("source")
        if (channel.optBoolean("useWAS")) {
            h["Referer"] = "$host/$search/$station"
            val was = Http.getJson("$host/was", h).getString("was")
            h = Http.browser(host)
            url = "$url?wmsAuthSign=$was"
        }
        return Stream(url, h)
    }

    private fun saiuz(search: String): Stream {
        val host = "https://www.radiosaiuz.it"
        val page = Http.get("$host/$search.html")
        val iframe = Http.match(page, """<iframe.*?src="([^"]+)"""")
        val data = Http.get(iframe)
        var url = Http.match(data, """<video\b[^>]*>[\s\S]*?<source[^>]*\bsrc="([^"]+)"[^>]*>[\s\S]*?</video>""")
        val base = Http.match(url, """^(https?://[^/]+)""")
        if (data.contains("/stream/play.m3u8")) url = "$base/stream/play.m3u8"
        val h = Http.browser().apply { put("Referer", "$host/"); put("Origin", base) }
        return Stream(url, h)
    }

    private fun sciscione(search: String) = Stream(
        Http.match(Http.get("https://www.grupposciscione.it/$search/"), """data-video-source="\[\{source:'(.*?)'""")
    )

    private fun sportitalia(search: String): Stream {
        val host = "https://sportitalia.it/"
        val name = mapOf("sihd" to "Sportitalia HD", "calcio" to "Solo Calcio", "primaveratv" to "Primavera TV", "laziostyle" to "Lazio Style")[search]
            ?: return Stream("")
        val h = Http.browser(host)
        val page = Http.match(Http.get(host, h), """<a[^>]+href="([^"]+)"[^>]*?>\s*<img[^>]+alt="${Regex.escape(name)}"[^>]*?>\s*</a>""")
        if (page.isEmpty()) return Stream("")
        val data = Http.get(page, h)
        val url = Http.match(data, """file:\s*"([^"]+)"""").ifEmpty { Http.match(data, """STREAM_URL\s=\s*"([^"]+)"""") }
        return Stream(url, h)
    }

    private fun telelombardia(search: String): Stream {
        val host = "https://telelombardia.video"
        val h = Http.browser()
        Http.get(host, h)
        val idx = "$host/video/index"
        val token = Http.match(Http.get(idx, h), """name="csrf-token"\scontent="([^"]+)"""")
        val list = JSONObject(Http.postForm("$host/list/get-evidence-list", mapOf("rel" to "0", "_csrf" to token), h))
            .optJSONArray("evidenceVideoList") ?: JSONArray()
        val ch = (0 until list.length()).map { list.getJSONObject(it) }
            .first { it.optString("channel_name").replace(" ", "").lowercase() == search.lowercase() }
        h["Referer"] = idx
        val data = Http.get("$host/video/viewlivestreaming?rel=${ch.opt("files_id")}&cntr=0", h)
        return Stream(Http.match(data, """videoPlayerBab.*?src="(.*?)"""").trim())
    }

    private fun dailymotion(search: String): Stream {
        val h = Http.browser("https://geo.dailymotion.com")
        val q = Http.getJson("https://www.dailymotion.com/player/metadata/video/$search", h)
            .getJSONObject("qualities").getJSONArray("auto")
        val url = (0 until q.length()).map { q.getJSONObject(it) }.firstOrNull { it.optString("type") == "application/x-mpegURL" }?.optString("url")
        return Stream(url.orEmpty(), h)
    }

    private fun smtv(search: String): Stream {
        val ch = mapOf("SMRTV" to 0, "SMRTVS" to 1)[search] ?: return Stream("")
        val data = Http.get("https://catchup.acdsolutions.it/jstag/videoplayerLiveFluid/TV?ch=$ch&eID=livePlayerPageElement&vID=666666666&autoPlay=true")
        return Stream(Http.match(data, """src\s?=\s?"([^"]+)"""))
    }

    private fun rsi(search: String): Stream {
        val h = Http.browser("https://www.rsi.ch")
        val items = Http.getJson("https://www.rsi.ch/play/v3/api/rsi/production/tv-livestreams").getJSONArray("data")
        val item = (0 until items.length()).map { items.getJSONObject(it) }.first { it.optString("title").equals(search, true) }
        val media = Http.getJson("https://il.srgssr.ch/integrationlayer/3.0/mediaComposition/byUrn/${item.getString("livestreamUrn")}.json?onlyChapters=false&vector=portalplay", h)
        val res = media.getJSONArray("chapterList").getJSONObject(0).getJSONArray("resourceList")
        val all = (0 until res.length()).map { res.getJSONObject(it) }
        // DASH + Widevine plays on Android; the HLS variant is FairPlay-protected
        val r = all.firstOrNull { it.optString("protocol") == "DASH" } ?: all.first { it.optString("protocol") == "HLS" }
        val drm = r.optJSONArray("drmList")?.let { d -> (0 until d.length()).map { d.getJSONObject(it) }.firstOrNull { it.optString("type") == "WIDEVINE" } }
        val lh = HashMap(h).apply { put("Accept", "*/*"); put("Content-Type", "application/octet-stream") }
        return Stream(r.getString("url"), h, drm?.optString("licenseUrl"), lh)
    }

    private fun premio(search: String): Stream {
        val host = "https://player.premio.link"
        return Stream(Http.match(Http.get("$host/watch/?l=$search", Http.browser(host)), """<source\ssrc="(.*?)""""))
    }

    private fun olympics(search: String): Stream {
        val host = "https://olympics.com"
        val chs = Http.getJsonArray("$host/it/api/v1/d3vp/epgchannels/linear/live-channels")
        val src = (0 until chs.length()).map { chs.getJSONObject(it) }.first { it.optString("slug") == search }.getString("src")
        val tok = Http.get("$host/tokenGenerator?url=$src&domain=https://ott-dai-oc.akamaized.net&_ts=${System.currentTimeMillis()}").trim()
        return Stream(tok.trim('"'), mapOf("User-Agent" to Http.UA))
    }

    private fun mediasport(search: String): Stream {
        val page = when (search) {
            "extra" -> "https://mschannel.tv/extra"
            "acisport" -> "https://www.msmotor.tv/aci"
            else -> "https://$search.tv"
        }
        return Stream(Http.match(Http.get(page), """<source\ssrc="([^"]+)"\stype="application/x-mpeg"""))
    }

    private fun tv2000(): Stream {
        val xml = Http.get("https://data.meride.tv/proxy/bulkproxynew/embedproxy_bulk.php/4Y3ZMmvcvo/play2000/desktop/preroll-play2000/f4m/default/aHR0cHM6Ly93d3cucGxheTIwMDAuaXQvKg==")
        val video = Http.match(xml, """<video\b[^>]*>(.*?)</video>""")
        return Stream(Http.match(video, """<default>\s*(?:<!\[CDATA\[)?\s*(.*?)\s*(?:\]\]>)?\s*</default>"""))
    }

    private fun supertennis(): Stream {
        val host = "https://supertennis.tv/"
        val suffix = Http.match(Http.get("${host}live-streaming"), """live-streaming([^"]+)""")
        val js = Http.get("${host}Areas/Supertennis/Scripts/live-streaming$suffix")
        return Stream(Http.match(js, """manifestUrl\s*=\s*"([^"]+)""""), mapOf("User-Agent" to Http.UA))
    }

    private fun redbull(): Stream {
        val h = Http.browser("https://www.redbull.com").apply { put("Accept", "*/*") }
        val tok = Http.getJson("https://api.redbull.tv/v3/session?os_family=http", h, verify = false).getString("token")
        return Stream("https://dms.redbull.tv/v3/linear-borb/$tok/playlist.m3u8")
    }

    /** Brightcove player embedded in a page (Sole 24 Ore, Gambero Rosso). */
    private fun brightcove(data: String, h: MutableMap<String, String>): Stream {
        val videoId = Http.match(data, """data-video-id="([^"]+)""")
        val account = Http.match(data, """data-account="([^"]+)""")
        val player = Http.match(data, """data-player="([^"]+)""")
        val pk = Http.match(Http.get("https://players.brightcove.net/$account/${player}_default/index.min.js", h), """policyKey:"([^"]+)""")
        h["Accept"] = "application/json;pk=$pk"
        val src = Http.getJson("https://edge.api.brightcove.com/playback/v1/accounts/$account/videos/$videoId", h)
            .getJSONArray("sources").getJSONObject(0).getString("src")
        h["Accept"] = "*/*"
        return Stream(src, h)
    }

    private fun sole24(): Stream {
        val ld = Http.match(Http.get("https://s24ore.it/flussotv"), """<script type="application/ld\+json">([^<]+)""")
        val embed = JSONObject(ld).getJSONObject("video").getString("embedUrl")
        val h = Http.browser("https://stream24.ilsole24ore.com")
        val inner = Http.match(Http.get(embed, h), """<div class="videoFullscreenIframe[^"]*">\s*<iframe[^>]*src="([^"]+)"""")
        return brightcove(Http.get(inner, h), h)
    }

    private fun gamberorosso(): Stream {
        val h = Http.browser("https://gamberorosso.tv")
        return brightcove(Http.get("https://gamberorosso.tv/live/", h), h)
    }

    private fun legaSerieA(): Stream {
        val host = "https://www.legaseriea.it"
        val h = Http.browser(host)
        val data = Http.get("$host/radiotv", h)
        val base = Http.match(data, """DIVA_VIDEO_PLAYER_SETTINGS_BASE_URL[\\]*"\s*[:\\]+\s*[\\]*"([^"\\]+)""")
        val wf = Http.match(data, """divaVideoWorkflow[\\]*"\s*[:\\]+\s*[\\]*"([^"\\]+)""")
        val vid = Http.match(data, """videoId[\\]*"\s*[:\\]+\s*[\\]*"([^"\\]+)""")
        val settings = JSONObject(Http.get("$base/$wf/html5", h).trimStart('﻿'))
        val cfgUrl = settings.getJSONObject("videoData").getString("videoDataPath").replace("{v.id}", vid)
        val ent = settings.getJSONObject("entitlementCheck").getString("entitlementUrl")
        val srcs = Http.getJson(cfgUrl, h).getJSONArray("sources")
        val dash = (0 until srcs.length()).map { srcs.getJSONObject(it) }.first { it.optString("format") == "DASH" && it.optString("name") == "Desktop-DASH" }
        val sid = UUID.randomUUID().toString()
        val body = JSONObject().put("Type", 1).put("VideoId", vid).put("VideoSource", dash.getString("uri")).put("VideoKind", "live")
            .put("AssetState", "2").put("PlayerType", "HTML5").put("VideoSourceFormat", "DASH").put("VideoSourceName", dash.getString("name"))
            .put("DRMType", "").put("AuthType", "Open").put("SessionId", sid).put("PlaybackSessionId", UUID.randomUUID().toString().uppercase())
            .put("Other", "$sid|web_browser").put("User", "")
        return Stream(Http.postJson(ent, body.toString(), h).getString("ContentUrl"), h)
    }

    private fun antennaSud(search: String): Stream {
        val host = "https://www.antennasud.com"
        val pages = mapOf(
            "as" to "$host/streaming", "92" to "$host/streaming-92", "sport" to "$host/streaming-sport",
            "sport2" to "$host/streaming-sport-2", "sport3" to "$host/streaming-as-sport-3", "sport4" to "$host/streaming-as-sport-4",
            "sport5" to "$host/streaming-as-sport-5", "tr" to "https://www.teleregionecolor.com/teleregione-live-stream",
        )
        val h = Http.browser(host)
        val iframe = Http.match(Http.get(pages[search] ?: return Stream(""), h, verify = false), """<div.*?<iframe.*?src="([^"]+)""")
        return Stream(Http.findLink(Http.get(iframe, h, verify = false)), h)
    }

    private fun vimeo(search: String): Stream {
        val type = search.substringBefore('$').lowercase()
        val id = search.substringAfter('$')
        val h = Http.browser("https://vimeo.com")
        if (type == "l") {
            val cfg = Http.getJson("https://vimeo.com/live_event/$id/status").optJSONObject("next_live_clip")?.optString("config_no_autoplay").orEmpty()
            if (cfg.isEmpty()) throw ResolveException("Diretta Vimeo non in onda")
            return Stream(vimeoHls(Http.getJson(cfg)), h)
        }
        val videoId = if (type == "e") Http.match(Http.get("https://vimeo.com/event/$id/embed/"), """data-clip-id="([^"]+)"""") else id
        return Stream(vimeoHls(Http.getJson("https://player.vimeo.com/video/$videoId/config", h)), h)
    }

    private fun vimeoHls(cfg: JSONObject): String {
        val hls = cfg.getJSONObject("request").getJSONObject("files").getJSONObject("hls")
        val cdn = hls.optString("default_cdn").ifEmpty { hls.optString("fastly_live", "akamai_live") }
        val cdns = hls.getJSONObject("cdns")
        return (cdns.optJSONObject(cdn) ?: cdns.getJSONObject(cdns.keys().next())).getString("url")
    }

    private fun bozz(search: String): Stream {
        for (host in listOf("https://ssh101.com/", "https://v2.ssh101.com/")) {
            val data = runCatching { Http.get("${host}securelive/index.php?id=$search") }.getOrDefault("")
            val url = Http.match(data, """source\s?src="([^"]+)""").ifEmpty { Http.match(data, """x-mpegurl",\ssrc:\s"([^"]+)""") }
            if (url.isNotEmpty()) return Stream(url, Http.browser().apply { put("Referer", host) })
        }
        return Stream("")
    }

    private fun castr(search: String): Stream {
        val h = Http.browser("https://player.castr.com")
        return Stream(Http.match(Http.get("https://player.castr.com/$search", h), """window\.__streamUrl\s*=\s*"([^"]+)""""), h)
    }

    private fun adtv(search: String) = Stream(
        Http.getJson("https://adtv.ae/api/biz/live/playinfo?channelExternalId=${Http.enc(search)}", mapOf("User-Agent" to Http.UA))
            .optJSONObject("response")?.optString("playingUrl").orEmpty()
    )

    // ---------------------------------------------------------------- generics.py

    private fun generics(search: String): Stream {
        val parts = search.lowercase().split('$')
        val p = parts.drop(1)
        val h = Http.browser()
        return when (parts[0]) {
            "bike" -> {
                val hh = Http.browser("https://bikechannel.it")
                Stream(Http.match(Http.get("https://bikechannel.it/live-tv.html", hh), """<source\s+type="application/x-mpegURL"\s+src="([^"]+)""""), hh)
            }
            "goldtv" -> {
                val page = when (p.firstOrNull()) { "sat" -> "gold-tivu-sat"; "lcn" -> "gold-tv-lcn-11"; else -> "gold-tv-italia" }
                Stream(Http.findLink(Http.get("https://www.goldtv.it/canali/$page", Http.browser("https://www.goldtv.it"))))
            }
            "ciaousa" -> {
                val hh = Http.browser("https://ciaousa.tv").apply {
                    put("Accept", "application/json"); put("device_type", "web"); put("X-REQUEST-TYPE", "web"); put("X-LANGUAGE-CODE", "it")
                }
                Stream(Http.getJson("https://api.ciaousa.tv/mediaview/api/v1/live/ciao-usa-tv", hh).optJSONObject("response")?.optString("hls_url").orEmpty())
            }
            "acisport" -> {
                val f = Http.match(Http.get("https://www.acisport.it/aci-sport-tv.asp", h), """iframe\ssrc="([^"]+)""")
                val p2 = Http.match(Http.get(f, h), """\)\.src="([^"]+)""")
                Stream(Http.getJson("$p2/json", h).optString("n3CDNPlaylist"))
            }
            "cilentano" -> if (p.isEmpty()) {
                val token = Http.match(Http.get("https://cilentano.it", h), """token=([^"]+)""")
                Stream(Http.getJson("https://player-backend.restream.io/public/videos/$token", h).optString("videoUrlHls"))
            } else {
                val f = Http.match(Http.get("https://cilentano.it/cilentano-tv-canale-${p[0]}/", h), """<iframe.*?litespeed-src="([^"]+)" style""")
                Stream(Http.match(Http.get(f, h), """source\s?src="([^"]+)"""))
            }
            "koper" -> {
                val m = Http.getJson("https://api.rtvslo.si/ava/getLiveStream/tv.kp1?client_id=82013fb3a531d5414f478747c1aca622", h)
                    .getJSONObject("response").getJSONArray("mediaFiles").getJSONObject(0)
                Stream(m.optString("streamer") + m.optString("file"))
            }
            "bandwtv" -> {
                val f = Http.match(Http.get("https://www.bandw.tv/", h), """<iframe src="([^"]+)"""")
                Stream(Http.match(Http.get(f, h), """src: '([^']+)'"""))
            }
            "laq" -> vimeo("l$" + Http.match(Http.get("https://www.aqbox.tv/streaming.php", h), """src="[^"]*/event/(\d+)"""))
            "canale58" -> wimFromUrl("https://www.canale58.com/", "live")
            "oggisalerno" -> wimFromUrl("https://www.tvoggisalerno.it/", "live")
            "telenuova" -> {
                val page = Http.match(Http.get("https://telenuova.tv/", h), """<a\s+href="([^"]+)"[^>]*>\s*DIRETTA TV\s*</a>""")
                wimByChId(Http.guid(page), "live")
            }
            "tvkappa" -> {
                val hh = Http.browser("https://www.tvkappa.it").apply { put("X-Requested-With", "XMLHttpRequest") }
                val data = Http.postForm(
                    "https://www.tvkappa.it/manager/includer.php",
                    mapOf("version" to "225-325", "__ca" to "33", "f" to "getPage", "page" to "popup/${p.firstOrNull()}.xml", "lang" to "it"), hh
                )
                wimFromData(data, "cast")
            }
            "rtccalabria" -> Stream(Http.match(Http.get("https://www.mediastreaming.it/c3/player.htm", h), """"source":\s?"([^"]+)"""))
            "primatvnapoli" -> {
                val raw = Http.match(Http.get("https://www.primatvnapoli.com/", h), """<div[^>]*class="[^"]*player[^"]*"[^>]*>\s*<div[^>]*data-item="([^"]*)"[^>]*>""")
                val j = JSONObject(android.text.Html.fromHtml(raw).toString())
                val s = j.optJSONArray("sources") ?: JSONArray()
                Stream((0 until s.length()).map { s.getJSONObject(it) }.firstOrNull { it.optString("type").contains("mpegurl") }?.optString("src").orEmpty())
            }
            "50canale" -> restream(Http.match(Http.get("https://50canale.tv/", Http.browser("https://50canale.tv/")), """<iframe[^>]*src="[^"]*\?token=([^"&]+)"""))
            "noitv" -> restream(Http.match(Http.get("https://noitv.it/live", Http.browser("https://noitv.it/")), """<iframe[^>]*src="[^"]*\?token=([^"&]+)"""))
            "rete8", "rete8sport" -> Stream(Http.findLink(Http.get("https://zerounocaststreaming.it:2020/VideoPlayer/${p.firstOrNull()}", h)))
            "telebari" -> Stream(Http.findLink(Http.get("https://www.telebari.it/player_html5/clap.html", h)))
            "teleromadue" -> Stream(Http.findLink(Http.get("https://www.teleromadue.it/", h)))
            "cittaceleste" -> {
                val home = Http.get("https://cittaceleste.tv", h).lines().joinToString("")
                val page = Http.match(home, """location\.href='([^']*)';[^>]*>[\s\S]*?Live Streaming""")
                Stream(Http.findLink(Http.get("https://cittaceleste.tv$page", h)).replace("\n", ""))
            }
            "sardegnalive" -> {
                val vid = Http.match(Http.get("https://www.sardegnalive.net/webtv", h), """<iframe[^>]*?livepush[^>]*?/([^/"]+)"""")
                Stream(Http.getJson("https://watch-api.livepush.io/v1/playback/$vid").optString("hls_url"), Http.browser("https://livepush.io/"))
            }
            "dacast" -> {
                val data = Http.get("https://iframe.dacast.com/b/${p[0]}/c/${p[1]}")
                val cid = Http.match(data, """<script\s?id="([^"]+)""")
                Stream(Http.getJson("https://playback.dacast.com/content/access?contentId=$cid&provider=universe").optString("hls"))
            }
            "kaloopy" -> {
                val hh = Http.browser("https://www.distro.tv")
                val j = Http.getJson("https://tv.jsrdn.com/tv_v5/show.php?name=kaloopy", hh)
                val key = j.getJSONObject("topics").getJSONArray("shows").get(0).toString()
                val url = j.getJSONObject("shows").getJSONObject(key).getJSONArray("seasons").getJSONObject(0)
                    .getJSONArray("episodes").getJSONObject(0).getJSONObject("content").optString("url")
                Stream(url, hh)
            }
            "teletruria" -> webtools("https://www.teletruria.it")
            "7goldemiliaromagna" -> webtools("https://www.7goldemiliaromagna.it/diretta-7gold/")
            "videolina" -> {
                val hh = Http.browser("https://www.videolina.it")
                Stream(Http.findLink(Http.get("https://www.videolina.it/live", hh)), hh)
            }
            "trcmodena" -> {
                val hh = Http.browser("https://trcmodena.it")
                val f = Http.match(Http.get("https://trcmodena.it/diretta-streaming", hh), """<iframe\b(?=[^>]*\breferrerpolicy="unsafe-url")[^>]*\bsrc="([^"]+)"""")
                val base = Http.match(f, """^(https?://[^/]+)""")
                val dest = Http.match(f, """/destinations/([^/]+)""")
                val chn = Http.match(f, """/channels/([^/?]+)""")
                hh["Referer"] = "$base/"; hh["Origin"] = base
                Stream(Http.getJson("https://api.selftv.video/destinations/$dest/channels/$chn?include=user", hh).getJSONObject("playback").optString("hls"), hh)
            }
            else -> throw ResolveException("Canale '${parts[0]}' non supportato")
        }
    }

    private fun webtools(page: String): Stream {
        val h = Http.browser(page)
        val info = Http.match(Http.get(page, h), """<iframe\s+src="([^"]+)"""")
        val data = Http.get(info, h)
        val reqHost = Http.match(data, """requestHost:\s*'([^']+)'""")
        val j = JSONObject(Http.match(data, """contentInfo:\s*(\{[\s\S]*?\}),(?=\s*skinId)"""))
        val url = "https://${j.optString("host")}/live/${j.optString("liveSourceID")}/${j.optString("contentID")}/playlist.m3u8"
        return Stream(url, Http.browser(reqHost))
    }

    private fun restream(token: String): Stream {
        val h = Http.browser()
        return Stream(Http.getJson("https://player-backend.restream.io/public/videos/$token?instant=true", h).optString("videoUrlHls"), h)
    }

    private fun wimFromUrl(page: String, type: String) = wimFromData(Http.get(page), type)

    private fun wimFromData(data: String, type: String): Stream {
        val page = Http.match(data, """<iframe.*?src=".*?wim.tv.*?/([^"]+)"""")
        val id = if (page.isNotEmpty()) Http.guid(page) else Http.match(data, """src=[^?]+\?cast=([^\\]+)""")
        return wimByChId(id, type)
    }

    private fun wimByChId(chId: String, type: String): Stream {
        val host = "https://platform.wim.tv"
        val h = Http.browser().apply { put("Referer", "$host/embed/?$type=$chId"); put("Authorization", "Basic d3d3Og==") }
        val token = JSONObject(Http.postForm("$host/wimtv-server/oauth/token", mapOf("grant_type" to "client_credentials"), h)).getString("access_token")
        h["Accept-Language"] = "it"; h["Authorization"] = "Bearer $token"; h["X-Wimtv-timezone"] = "3600000"
        h["Origin"] = host; h["Accept"] = "application/json"; h["X-Requested-With"] = "XMLHttpRequest"
        val j = Http.postJson("$host/wimtv-server/api/public/$type/channel/$chId/play", "{}", h)
        return Stream(j.getJSONArray("srcs").getJSONObject(0).getString("uniqueStreamer"))
    }
}
