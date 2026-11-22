package com.mefistofelix.wltv

import java.text.Normalizer

/**
 * Matching of spoken/typed channel and programme names. Dictation gives "rai uno", "canale cinque", "la sette d",
 * "venti", so number words become digits and spaces, accents and punctuation are dropped before comparing.
 * [Query] is prepared once per search; [Query.score] is cheap enough for thousands of rows (no edit distance unless
 * the lengths are close).
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
    private val filler = setOf("metti", "guarda", "vai", "apri", "canale", "sul", "accendi", "cerca", "trova")
    private val marks = Regex("\\p{M}+")
    private val split = Regex("[^a-z0-9]+")

    private fun words(s: String): List<String> {
        val plain = marks.replace(Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD), "")
        return plain.split(split).filter { it.isNotEmpty() }.map { numbers[it] ?: it }
    }

    fun norm(s: String) = words(s).joinToString("")

    class Query(text: String) {
        private val w = words(text)
        val q = w.joinToString("")
        private val core = w.filterNot { it in filler }.joinToString("").ifEmpty { q }
        private val num = q.toIntOrNull()
        val empty get() = q.isEmpty()

        /**
         * 0 = no match; higher is better. [name], [title] (programme) and [folder] (source, group, category) already
         * normalized with [norm]. A folder match ranks below any name or title match.
         */
        fun score(name: String, number: Int?, title: String?, folder: String? = null): Int {
            var s = 0
            if (num != null && number == num) s = 100
            s = maxOf(s, match(name, q), if (core != q) match(name, core) - 5 else 0)
            if (title != null && q.length >= 3) s = maxOf(s, match(title, q) - 8, if (core != q) match(title, core) - 12 else 0)
            if (folder != null && q.length >= 3 && s == 0 && (folder.contains(q) || folder.contains(core))) s = 20
            return s
        }
    }

    private fun match(n: String, q: String): Int = when {
        q.isEmpty() -> 0
        n == q -> 95
        n.startsWith(q) -> 80 - (n.length - q.length).coerceAtMost(20)
        n.contains(q) -> 60 - (n.length - q.length).coerceAtMost(20)
        q.length >= 4 && kotlin.math.abs(n.length - q.length) <= q.length / 4 && distance(n, q) <= q.length / 4 -> 40
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
