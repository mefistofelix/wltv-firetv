package com.mefistofelix.wltv

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Self-update without an app store, same scheme as FireBro. [MANIFEST_URL] (file in the public repository
 * mefistofelix/firetv-apps, whose releases `wltv-N` hold the APKs; both written by scripts/publish-update.sh)
 * describes the newest build: `{"versionCode": N, "versionName": "0.1.N", "url": "<apk>", "sha256": "<hex>", "size": bytes}`.
 * A newer build is offered in a dialog; the APK is downloaded to the cache, checked against the SHA-256 and handed to
 * the system installer through a PackageInstaller session, whose own screen asks for the final "Installa" (apps
 * cannot install silently). Android refuses an APK not signed with the same key.
 */
class Updater(private val ctx: Context) {
    class Release(val code: Int, val name: String, val url: String, val sha256: String, val size: Long)

    private val prefs = ctx.getSharedPreferences("update", Context.MODE_PRIVATE)
    @Volatile private var busy = false

    val installedCode: Int = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
    } catch (e: Exception) { 0 }

    /** Reads the manifest in the background; [found] runs on that thread with a newer build (null: none / failed). */
    fun check(found: (Release?) -> Unit) {
        if (busy) return
        // several TVs on one network: at most one check every MIN_INTERVAL_MS, whatever triggers it
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("checkedAt", 0) in 0 until MIN_INTERVAL_MS) return
        prefs.edit().putLong("checkedAt", now).apply()
        thread(name = "update-check") {
            val r = try {
                val req = Request.Builder().url(MANIFEST_URL).header("User-Agent", "WLTV")
                    .header("Cache-Control", "no-cache").build()
                val text = Http.client.newCall(req).execute().use { resp ->
                    if (resp.code != 200) throw IllegalStateException("HTTP ${resp.code}")
                    resp.body!!.string()
                }
                val j = JSONObject(text)
                Release(j.getInt("versionCode"), j.optString("versionName"), j.getString("url"),
                    j.getString("sha256").lowercase(), j.optLong("size", -1))
            } catch (e: Exception) {
                Log.w(TAG, "update check failed: $e")
                null
            }
            found(if (r != null && r.code > installedCode) r else null)
        }
    }

    /** True when the user said "Più tardi" to this build less than [SNOOZE_MS] ago. */
    fun snoozed(r: Release) = prefs.getInt("snoozeCode", 0) == r.code &&
        System.currentTimeMillis() - prefs.getLong("snoozeAt", 0) < SNOOZE_MS

    fun snooze(r: Release) = prefs.edit().putInt("snoozeCode", r.code).putLong("snoozeAt", System.currentTimeMillis()).apply()

    /**
     * Downloads [r] and opens the system installer. [progress] gets 0..100 (on the download thread), [failed] a
     * message. The installer's answer comes back to [activity] as an intent with action [ACTION_RESULT].
     */
    fun install(activity: Activity, r: Release, progress: (Int) -> Unit, failed: (String) -> Unit) {
        if (busy) return
        busy = true
        thread(name = "update-download") {
            try {
                val apk = File(ctx.cacheDir, "update.apk")
                download(r, apk, progress)
                commit(activity, apk)
            } catch (e: Exception) {
                Log.w(TAG, "update failed: $e")
                failed(e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false
            }
        }
    }

    private fun download(r: Release, out: File, progress: (Int) -> Unit) {
        val md = MessageDigest.getInstance("SHA-256")
        val req = Request.Builder().url(r.url).header("User-Agent", "WLTV").build()
        Http.client.newCall(req).execute().use { resp ->
            if (resp.code != 200) throw IllegalStateException("HTTP ${resp.code}")
            val body = resp.body!!
            val total = if (r.size > 0) r.size else body.contentLength()
            body.byteStream().use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var shown = -1
                    while (true) {
                        val k = input.read(buf)
                        if (k < 0) break
                        o.write(buf, 0, k)
                        md.update(buf, 0, k)
                        done += k
                        if (done > MAX_BYTES) throw IllegalStateException("file troppo grande")
                        val p = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                        if (p != shown) { shown = p; progress(p) }
                    }
                }
            }
        }
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        if (hex != r.sha256) {
            out.delete()
            throw IllegalStateException("file arrivato danneggiato")
        }
    }

    private fun commit(activity: Activity, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        params.setSize(apk.length())
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            apk.inputStream().use { input ->
                s.openWrite("base.apk", 0, apk.length()).use { o ->
                    input.copyTo(o, 64 * 1024)
                    s.fsync(o)
                }
            }
            val back = Intent(activity, activity.javaClass).setAction(ACTION_RESULT)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
            // read by Relauncher once the new build is in place: reopen WLTV (only after our own update)
            prefs.edit().putLong(RELAUNCH_AT, System.currentTimeMillis()).commit()
            s.commit(PendingIntent.getActivity(activity, id, back, flags).intentSender)
        }
        apk.delete()
    }

    companion object {
        private const val TAG = "WLTV"
        const val ACTION_RESULT = "com.mefistofelix.wltv.UPDATE_RESULT"
        const val MANIFEST_URL = "https://raw.githubusercontent.com/mefistofelix/firetv-apps/main/wltv.json"
        private const val SNOOZE_MS = 12L * 3600 * 1000
        private const val MIN_INTERVAL_MS = 2 * 60_000L
        private const val MAX_BYTES = 100L * 1024 * 1024
        private const val RELAUNCH_AT = "relaunchAt"
        private const val RELAUNCH_WINDOW_MS = 15 * 60_000L

        /** True (once) when an update started by WLTV in the last minutes has just been installed. */
        fun takeRelaunch(ctx: Context): Boolean {
            val p = ctx.getSharedPreferences("update", Context.MODE_PRIVATE)
            val at = p.getLong(RELAUNCH_AT, 0)
            if (at == 0L) return false
            p.edit().remove(RELAUNCH_AT).commit()
            return System.currentTimeMillis() - at in 0 until RELAUNCH_WINDOW_MS
        }

        /**
         * Handles the installer's answer; returns a message for the user, or null when nothing needs saying.
         * A "user action" answer carries the installer's confirmation screen, which is opened here.
         */
        fun handleResult(activity: Activity, intent: Intent): String? {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            return when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (confirm == null) "Installazione non avviata"
                    else try {
                        activity.startActivity(confirm)
                        null
                    } catch (e: Exception) {
                        "Installazione non avviata: $e"
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> null // the process is replaced, nothing to show
                PackageInstaller.STATUS_FAILURE_ABORTED -> "Aggiornamento annullato"
                else -> "Aggiornamento non riuscito: " +
                    (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "errore $status")
            }
        }
    }
}
