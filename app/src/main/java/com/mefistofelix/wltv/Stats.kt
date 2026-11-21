package com.mefistofelix.wltv

import android.content.Context
import org.json.JSONObject

/** Seconds watched per channel ([Channel.key]), kept in SharedPreferences; feeds the "Più guardati" group. */
class Stats(context: Context) {
    private val prefs = context.getSharedPreferences("stats", Context.MODE_PRIVATE)
    private val seconds: MutableMap<String, Long> = HashMap()
    private var dirty = 0

    init {
        runCatching {
            val o = JSONObject(prefs.getString("seconds", "{}")!!)
            for (k in o.keys()) seconds[k] = o.optLong(k)
        }
    }

    fun add(ch: Channel, s: Long) {
        seconds[ch.key] = (seconds[ch.key] ?: 0L) + s
        if (++dirty >= 30) save()
    }

    fun of(ch: Channel): Long = seconds[ch.key] ?: 0L

    /** Channels of [all] watched at least a minute, most watched first. */
    fun top(all: List<Channel>, max: Int = 30): List<Channel> =
        all.filter { of(it) >= 60 }.sortedByDescending { of(it) }.take(max)

    fun clear() {
        seconds.clear(); save()
    }

    fun save() {
        dirty = 0
        prefs.edit().putString("seconds", JSONObject(seconds as Map<*, *>).toString()).apply()
    }

    companion object {
        fun format(s: Long): String {
            val m = s / 60
            return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
        }
    }
}
