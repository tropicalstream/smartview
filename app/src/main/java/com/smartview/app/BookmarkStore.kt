package com.smartview.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Voice-addressable bookmarks. Each bookmark carries relevant keywords (derived
 * from the page title + domain) so the user can open or delete it by saying a
 * keyword — "open hacker news", "delete weather bookmark".
 */
data class Bookmark(val url: String, val title: String, val keywords: List<String>)

class BookmarkStore(context: Context) {
    private val prefs = context.getSharedPreferences("smartview_bookmarks", Context.MODE_PRIVATE)

    fun all(): List<Bookmark> {
        val raw = prefs.getString("bookmarks", "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Bookmark(
                    o.getString("url"),
                    o.optString("title", o.getString("url")),
                    (0 until o.getJSONArray("keywords").length()).map { k ->
                        o.getJSONArray("keywords").getString(k)
                    }
                )
            }
        }.getOrDefault(emptyList())
    }

    fun add(url: String, title: String): Bookmark {
        val bm = Bookmark(url, title.ifBlank { url }, deriveKeywords(url, title))
        val list = all().filter { it.url != url } + bm
        save(list)
        return bm
    }

    fun deleteByKeyword(spoken: String): Bookmark? {
        val target = bestMatch(spoken) ?: return null
        save(all().filter { it.url != target.url })
        return target
    }

    fun deleteByUrl(url: String) = save(all().filter { it.url != url })

    /** Score bookmarks by keyword overlap with the spoken words; null if weak. */
    /**
     * @param minScore raise for the BARE-utterance path. An explicit
     *   "open <x>" can trust a 3-point match, but a whole spoken sentence
     *   accumulates stray +1s and used to teleport the user to a bookmark
     *   mid-sentence — which also destroys the injected page-agent. Observed:
     *   "summarise the news article on this page" matched a Hacker News
     *   bookmark purely on "new" appearing inside "news".
     * @param requireExact bare path additionally demands at least one WHOLE
     *   keyword hit, so accumulation alone can never navigate.
     */
    fun bestMatch(spoken: String, minScore: Int = 3, requireExact: Boolean = false): Bookmark? {
        val words = tokenize(spoken)
        if (words.isEmpty()) return null
        var best: Bookmark? = null
        var bestScore = 0
        var bestExact = false
        for (bm in all()) {
            var score = 0
            var exact = false
            for (kw in bm.keywords) {
                for (w in words) {
                    if (kw == w) { score += 3; exact = true }
                    // Short substrings are noise, not evidence: "new" inside
                    // "news" scored the same as a real partial match.
                    else if (w.length >= 5 && (kw.contains(w) || w.contains(kw))) score += 1
                }
            }
            if (score > bestScore) { bestScore = score; best = bm; bestExact = exact }
        }
        if (bestScore < minScore) return null
        if (requireExact && !bestExact) return null
        return best
    }

    private fun save(list: List<Bookmark>) {
        val arr = JSONArray()
        list.forEach { bm ->
            arr.put(
                JSONObject()
                    .put("url", bm.url)
                    .put("title", bm.title)
                    .put("keywords", JSONArray(bm.keywords))
            )
        }
        prefs.edit().putString("bookmarks", arr.toString()).apply()
    }

    companion object {
        private val STOPWORDS = setOf(
            "the", "a", "an", "and", "or", "of", "for", "to", "in", "on", "at", "is",
            "are", "with", "by", "from", "home", "page", "official", "site", "website",
            "welcome", "www", "com", "org", "net", "io", "html", "index"
        )

        fun tokenize(s: String): List<String> =
            s.lowercase().split(Regex("[^a-z0-9]+"))
                .filter { it.length >= 3 && it !in STOPWORDS }

        /** Relevant keywords: domain core + top title tokens. */
        fun deriveKeywords(url: String, title: String): List<String> {
            val out = LinkedHashSet<String>()
            runCatching {
                val host = android.net.Uri.parse(url).host.orEmpty()
                host.removePrefix("www.").split('.')
                    .filter { it.length >= 3 && it !in STOPWORDS }
                    .take(2).forEach { out.add(it) }
            }
            tokenize(title).take(6).forEach { out.add(it) }
            return out.toList().take(8)
        }
    }
}
