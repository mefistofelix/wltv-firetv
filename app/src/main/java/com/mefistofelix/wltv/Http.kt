package com.mefistofelix.wltv

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** HTTP helpers shaped after the addon's `requests` calls (browser headers, regex scraping). */
object Http {
    const val BROWSER_VERSION = "136.0.0.0"
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$BROWSER_VERSION Safari/537.36"

    /** In-memory cookies, like one requests.Session shared by every resolver. */
    private val cookieJar = object : CookieJar {
        private val store = HashMap<String, MutableMap<String, Cookie>>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (c in cookies) store.getOrPut(c.domain) { LinkedHashMap() }[c.name] = c
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            store.values.flatMap { it.values }.filter { it.matches(url) }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .cookieJar(cookieJar)
        .build()

    /** For the few hosts the addon reads with verify=False (La7 token, Red Bull, Antenna Sud). */
    private val insecure: OkHttpClient by lazy {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), SecureRandom()) }
        client.newBuilder().sslSocketFactory(ctx.socketFactory, tm).hostnameVerifier { _, _ -> true }.build()
    }

    /** utils.getBrowserHeaders(host=...) */
    fun browser(host: String = ""): MutableMap<String, String> {
        val h = linkedMapOf(
            "User-Agent" to UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
        )
        if (host.isNotEmpty()) {
            h["Origin"] = host.trimEnd('/')
            h["Referer"] = host.trimEnd('/') + "/"
        }
        return h
    }

    class Response(val code: Int, val body: String, val finalUrl: String)

    fun request(
        url: String, headers: Map<String, String>? = null, method: String = "GET",
        json: String? = null, form: Map<String, String>? = null, verify: Boolean = true,
    ): Response {
        val b = Request.Builder().url(url)
        for ((k, v) in headers ?: browser()) if (v.isNotEmpty()) b.header(k, v)
        when {
            json != null -> b.method(if (method == "GET") "POST" else method, json.toRequestBody("application/json".toMediaType()))
            form != null -> b.post(FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build())
            method != "GET" -> b.method(method, ByteArray(0).toRequestBody(null))
        }
        (if (verify) client else insecure).newCall(b.build()).execute().use { r ->
            return Response(r.code, r.body?.string() ?: "", r.request.url.toString())
        }
    }

    fun get(url: String, headers: Map<String, String>? = null, verify: Boolean = true) =
        request(url, headers, verify = verify).body

    fun getJson(url: String, headers: Map<String, String>? = null, verify: Boolean = true) =
        JSONObject(get(url, headers, verify))

    fun getJsonArray(url: String, headers: Map<String, String>? = null) = JSONArray(get(url, headers))

    fun postJson(url: String, body: String, headers: Map<String, String>? = null) =
        JSONObject(request(url, headers, "POST", json = body).body)

    fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>? = null) =
        request(url, headers, "POST", form = form).body

    /** scrapers.findSingleMatch: first group of the first match (DOTALL), "" when nothing matches. */
    fun match(data: String, pattern: String): String {
        val m = Regex(pattern, RegexOption.DOT_MATCHES_ALL).find(data) ?: return ""
        return if (m.groupValues.size > 1) m.groupValues[1] else m.value
    }

    /** helper/researcher.GetLink: the usual places a page puts its video source. */
    fun findLink(data: String): String {
        for (p in listOf(
            """<video.*?src="([^"]+)""",
            """<source\s+type="application/x-mpegURL"\s+src="([^"]+)"""",
            """<source\s?src="([^"]+)""",
            """source\s?src="([^"]+)""",
            """"source":\s?"([^"]+)""",
            """source\s?:\s?"([^"]+)""",
        )) {
            val u = match(data, p)
            if (u.isNotEmpty()) return u
        }
        return ""
    }

    fun guid(s: String) = match(s.lowercase(), "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")

    fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    fun dec(s: String): String = java.net.URLDecoder.decode(s, "UTF-8")
}
