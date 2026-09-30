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
        // Photos already on my other groups, so two "Haridwar" groups get different pictures.
        val others = Repo.groups.value.filter { it.id != g.id }
        val used = others.map { it.cover }.filter { it.isNotBlank() }.toSet()
        // Only the group's creator swaps a clashing photo, and only on the newer group.
        val clash = g.cover.isNotBlank() && g.createdBy == Auth.uid &&
            others.any { it.cover == g.cover && (it.createdAt < g.createdAt || (it.createdAt == g.createdAt && it.id < g.id)) }
        // Older random scenery could be a bare horizon: swap it for a hand-picked one.
        val oldScenery = g.createdBy == Auth.uid && g.cover.contains("picsum.photos/seed/")
        if (g.coverFor == g.name && g.cover.isNotBlank() && !clash && !oldScenery) return
        val key = g.id + "|" + g.name
        synchronized(inFlight) { if (!inFlight.add(key)) return }
        scope.launch {
            try {
                // A network failure throws and nothing is saved, so it's tried again next time.
                // No photo for this name: a hand-picked landscape, fixed per group.
                val url = runCatching { find(g.name, used) ?: scenery(g.id, used) }.getOrNull() ?: return@launch
                Repo.db.collection("groups").document(g.id).update(mapOf("cover" to url, "coverFor" to g.name))
            } finally {
                synchronized(inFlight) { inFlight.remove(key) }
            }
        }
    }

    /**
     * Hand-picked Lorem Picsum landscapes with detail across the whole frame, so
     * they still look good cropped to a wide card (no bare horizons or grey skies).
     */
    private val scenic = listOf(
        10, 11, 13, 15, 17, 28, 29, 46, 49, 54, 62, 66, 69, 71, 74, 77, 81, 85, 93, 110, 112, 116,
        118, 124, 127, 128, 142, 155, 162, 164, 174, 179, 182, 188, 191, 193, 197
    )

    /** A scenic photo picked by the group id, skipping ones my other groups already show. */
    private fun scenery(gid: String, used: Set<String>): String {
        val urls = scenic.map { "https://picsum.photos/id/$it/1280/640" }
        val start = Math.floorMod(gid.hashCode(), urls.size)
        return (urls.indices).map { urls[(start + it) % urls.size] }.firstOrNull { it !in used } ?: urls[start]
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

    private fun find(name: String, used: Set<String>): String? {
        for (c in candidates(name)) {
            val title = URLEncoder.encode(c.replace(' ', '_'), "UTF-8")
            val summary = get("https://en.wikipedia.org/api/rest_v1/page/summary/$title") ?: continue
            if (summary.optString("type") != "standard") continue
            val about = summary.optString("description") + " " + summary.optString("extract").take(300)
            if (!placeWords.containsMatchIn(about)) continue
            photo(summary.optJSONObject("titles")?.optString("canonical") ?: title, used)?.let { return it }
            // Fall back to the article's lead image when it is a wide photo.
            summary.optJSONObject("originalimage")?.let { img ->
                val src = img.optString("source")
                if (img.optInt("width") > img.optInt("height") && !notPhotos.containsMatchIn(src) && src !in used) return src
            }
        }
        return null
    }

    /** First photo in the article that no other group of mine uses, at a banner-friendly size. */
    private fun photo(title: String, used: Set<String>): String? {
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
            val url = if (src.startsWith("//")) "https:$src" else src
            if (src.isNotBlank() && url !in used) return url
        }
        return null
    }

    /** JSON for [url], null when the page doesn't exist; throws when the network fails. */
    private fun get(url: String): JSONObject? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "SplitFree/4.1 (Android expense-splitting app)")
            when (c.responseCode) {
                200 -> JSONObject(c.inputStream.use { String(it.readBytes()) })
                in 500..599 -> throw java.io.IOException("Wikipedia is busy")
                else -> null
            }
        } finally {
            c.disconnect()
        }
    }
}
