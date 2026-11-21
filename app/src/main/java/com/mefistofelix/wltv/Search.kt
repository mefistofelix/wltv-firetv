package com.mefistofelix.wltv

import java.text.Normalizer

/**
 * Matching of spoken/typed channel names. Dictation gives "rai uno", "canale cinque", "la sette d",
 * "venti", so number words become digits and spaces, accents and punctuation are dropped before comparing.
 */
object Search {
    private val numbers = mapOf(
        "zero" to "0", "uno" to "1", "una" to "1", "due" to "2", "tre" to "3", "quattro" to "4", "cinque" to "5",
        "sei" to "6", "sette" to "7", "otto" to "8", "nove" to "9", "dieci" to "10", "undici" to "11",
        "dodici" to "12", "tredici" to "13", "quattordici" to "14", "quindici" to "15", "sedici" to "16",
        "diciassette" to "17", "diciotto" to "18", "diciannove" to "19", "venti" to "20", "ventuno" to "21",
        "ventidue" to "22", "ventitre" to "23", "ventiquattro" to "24", "trenta" to "30", "quaranta" to "40",
        "cinquanta" to "50", "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
    )
    // words a user may say around the name: "metti", "canale", "su"
    private val filler = setOf("metti", "guarda", "vai", "apri", "canale", "sul", "accendi")

    private fun words(s: String): List<String> {
        val plain = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return plain.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.map { numbers[it] ?: it }
    }

    fun norm(s: String) = words(s).joinToString("")

    /** Channels matching [query], best first. A bare number also matches the channel number. */
    fun find(all: List<Channel>, query: String): List<Channel> {
        val qw = words(query)
        if (qw.isEmpty()) return emptyList()
        val q = qw.joinToString("")
        val qCore = qw.filterNot { it in filler }.joinToString("").ifEmpty { q }
        val num = q.toIntOrNull()
        val scored = ArrayList<Pair<Channel, Int>>()
        for (ch in all) {
            val n = norm(ch.name)
            val s = maxOf(
                if (num != null && ch.number == num) 100 else 0,
                score(n, q),
                if (qCore != q) score(n, qCore) - 5 else 0,
            )
            if (s > 0) scored += ch to s
        }
        return scored.sortedWith(compareByDescending<Pair<Channel, Int>> { it.second }.thenBy { it.first.number }).map { it.first }
    }

    private fun score(n: String, q: String): Int = when {
        q.isEmpty() -> 0
        n == q -> 95
        n.startsWith(q) -> 80 - (n.length - q.length).coerceAtMost(20)
        n.contains(q) -> 60 - (n.length - q.length).coerceAtMost(20)
        q.length >= 4 && distance(n, q) <= q.length / 4 -> 40
        q.length >= 4 && n.length > q.length && distance(n.substring(0, q.length), q) <= q.length / 4 -> 30
        else -> 0
    }

    private fun distance(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]
            d[0] = i
            for (j in 1..b.length) {
                val t = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = t
            }
        }
        return d[b.length]
    }
}
