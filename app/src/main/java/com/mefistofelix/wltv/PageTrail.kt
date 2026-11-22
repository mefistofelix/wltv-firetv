package com.mefistofelix.wltv

/**
 * ⏪ ⏩ paging that can be undone exactly: a page the other way, from the row the last page landed on, goes back to
 * the row (and height) it came from, whatever headers or a shorter last screen made the forward page skip.
 */
class PageTrail {
    private class Step(val pos: Int, val top: Int, val dir: Int)

    private val trail = ArrayList<Step>()
    private var at = -1

    fun clear() { trail.clear(); at = -1 }

    /** Where a page of [dir] from row [cur] (shown at [top]) goes: row and height, or null when it stays. */
    fun next(cur: Int, top: Int, dir: Int, compute: () -> Int): Pair<Int, Int>? {
        if (cur == at && trail.isNotEmpty() && trail.last().dir == -dir) {
            val s = trail.removeAt(trail.size - 1)
            at = s.pos
            return s.pos to s.top
        }
        if (cur != at) trail.clear()
        val t = compute()
        if (t < 0 || t == cur) return null
        trail += Step(cur, top, dir)
        at = t
        return t to top
    }
}
