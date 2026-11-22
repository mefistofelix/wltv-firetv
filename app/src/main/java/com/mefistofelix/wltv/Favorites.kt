package com.mefistofelix.wltv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Favourite channels, kept whole (name, URL, logo, folder…) in SharedPreferences: a favourite stays in the list and
 * keeps playing even when the list it came from is switched off. Newest last. Keyed like the stats ([Channel.key]).
 */
class Favorites(context: Context) {
    private val prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)
    private val list = ArrayList<Channel>()

    init {
        runCatching {
            val a = JSONArray(prefs.getString("channels", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                list += Channel(
                    o.optString("name"), o.optString("url"), o.optInt("number", 0), o.optString("group"),
                    o.optString("logo"), o.optString("tvgId"), o.optString("source"),
                )
            }
        }
    }

    fun all(): List<Channel> = list.toList()
    fun has(ch: Channel) = list.any { it.key == ch.key }

    /** Adds or removes [ch]; true = it is now a favourite. */
    fun toggle(ch: Channel): Boolean {
        val on = !has(ch)
        if (on) list += ch else list.removeAll { it.key == ch.key }
        save()
        return on
    }

    /** The stored copy follows the list when the channel is loaded again (new logo, renamed). */
    fun refresh(loaded: List<Channel>) {
        val byKey = loaded.associateBy { it.key }
        var changed = false
        for (i in list.indices) byKey[list[i].key]?.let { if (it != list[i]) { list[i] = it; changed = true } }
        if (changed) save()
    }

    private fun save() {
        val a = JSONArray()
        for (c in list) a.put(JSONObject().put("name", c.name).put("url", c.url).put("number", c.number).put("group", c.group)
            .put("logo", c.logo).put("tvgId", c.tvgId).put("source", c.source))
        prefs.edit().putString("channels", a.toString()).apply()
    }
}
