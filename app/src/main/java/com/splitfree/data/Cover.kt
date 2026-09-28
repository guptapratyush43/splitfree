package com.splitfree.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Finds a landscape photo for a place named in the group ("Goa trip" → Goa)
 * using Wikipedia's free REST API, then stores the URL on the group so every
 * member sees the same banner and nobody looks it up twice.
 */
object Cover {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = mutableSetOf<String>()

    private val filler = setOf(
        "trip", "trips", "tour", "travel", "flat", "house", "home", "room", "roommates", "office", "team", "friends",
        "gang", "the", "and", "with", "to", "in", "at", "of", "for", "group", "family", "party", "weekend", "vacation",
        "holiday", "holidays", "plan", "plans", "lunch", "dinner", "expenses", "expense", "outing", "visit", "my", "our",
        "boys", "girls", "college", "school", "squad", "fun", "ride", "road", "night", "day", "days", "stay", "rent"
    )
    private val placeWords = Regex(
        "city|state|country|town|village|district|island|beach|capital|region|territory|mountain|hill station|lake|" +
            "valley|province|archipelago|metropolis|municipality|resort|national park|union territory|coast|peninsula|desert",
        RegexOption.IGNORE_CASE
    )
    private val notPhotos = Regex("map|flag|seal|logo|emblem|locator|coat_of_arms|coat of arms|symbol|collage|montage|icon|\\.svg", RegexOption.IGNORE_CASE)

    /** Looks up (once) and saves a banner for [g] if its name changed since the last lookup. */
    fun ensure(g: Group) {
        if (g.coverFor == g.name) return
        val key = g.id + "|" + g.name
        synchronized(inFlight) { if (!inFlight.add(key)) return }
        scope.launch {
            try {
                val url = runCatching { find(g.name) }.getOrNull().orEmpty()
                Repo.db.collection("groups").document(g.id).update(mapOf("cover" to url, "coverFor" to g.name))
            } finally {
                synchronized(inFlight) { inFlight.remove(key) }
            }
        }
    }

    /** Place names to try, most specific first: the whole name, word pairs, then single words. */
    private fun candidates(name: String): List<String> {
        val words = name.split(Regex("[^\\p{L}]+")).filter { it.length > 1 && it.lowercase() !in filler }
        if (words.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        out += words.joinToString(" ")
        for (i in 0 until words.size - 1) out += words[i] + " " + words[i + 1]
        words.sortedByDescending { it.length }.forEach { out += it }
        return out.map { w -> w.split(" ").joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) } }.take(6)
    }

    private fun find(name: String): String? {
        for (c in candidates(name)) {
            val title = URLEncoder.encode(c.replace(' ', '_'), "UTF-8")
            val summary = get("https://en.wikipedia.org/api/rest_v1/page/summary/$title") ?: continue
            if (summary.optString("type") != "standard") continue
            val about = summary.optString("description") + " " + summary.optString("extract").take(300)
            if (!placeWords.containsMatchIn(about)) continue
            photo(summary.optJSONObject("titles")?.optString("canonical") ?: title)?.let { return it }
            // Fall back to the article's lead image when it is a wide photo.
            summary.optJSONObject("originalimage")?.let { img ->
                val src = img.optString("source")
                if (img.optInt("width") > img.optInt("height") && !notPhotos.containsMatchIn(src)) return src
            }
        }
        return null
    }

    /** First wide-looking photo in the article, at a banner-friendly size. */
    private fun photo(title: String): String? {
        val media = get("https://en.wikipedia.org/api/rest_v1/page/media-list/${URLEncoder.encode(title, "UTF-8")}") ?: return null
        val items = media.optJSONArray("items") ?: return null
        for (i in 0 until items.length()) {
            val it = items.getJSONObject(i)
            if (it.optString("type") != "image") continue
            val t = it.optString("title")
            if (!t.lowercase().let { n -> n.endsWith(".jpg") || n.endsWith(".jpeg") } || notPhotos.containsMatchIn(t)) continue
            val set = it.optJSONArray("srcset") ?: continue
            if (set.length() == 0) continue
            val src = set.getJSONObject(set.length() - 1).optString("src")
            if (src.isNotBlank()) return if (src.startsWith("//")) "https:$src" else src
        }
        return null
    }

    private fun get(url: String): JSONObject? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "SplitFree/1.3 (Android expense-splitting app)")
            if (c.responseCode != 200) null else JSONObject(c.inputStream.use { String(it.readBytes()) })
        } catch (e: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }
}
