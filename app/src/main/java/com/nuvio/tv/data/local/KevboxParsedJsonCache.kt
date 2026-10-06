package com.nuvio.tv.data.local

/*
 * KevBox FORK DIVERGENCE (KevBox-only file; upstream has no copy, so merges never conflict here).
 *
 * WatchedItemsPreferences keeps the watched list as a Set of JSON strings in one DataStore entry.
 * Upstream's observeAllItems ran Gson over every string on every emission of that store (including
 * emissions caused only by the delta cursor or push timestamp changing), once per reader. With a
 * 3,000+ item history that was the top app-level CPU cost on a TV (simpleperf, 2026-10-06).
 *
 * This keeps the objects parsed for the previous call, keyed by their exact JSON, so each update
 * parses only the strings that are new and every reader gets the same objects. Safe to share because
 * WatchedItem is an immutable data class. Entries that fail to parse are skipped, as upstream did.
 *
 * Calls are serialised: at app start many screens subscribe at once (isWatched() makes a new reader
 * per call), and without the lock each one found the cache empty and parsed the whole list itself.
 * The work is CPU-bound and runs on Dispatchers.Default, so a short wait beats a duplicate parse.
 *
 * Used from two marked spots in WatchedItemsPreferences (observeAllItems, getAllItems). On an
 * upstream merge: if upstream moves watched items out of the JSON string set (Room, proto, ...),
 * drop this file and the markers instead of porting it.
 */
internal class KevboxParsedJsonCache<T : Any>(
    private val parse: (String) -> T?
) {
    private val lock = Any()
    private var previous: Map<String, T> = emptyMap()

    /** Parses [raw] in iteration order, reusing objects from the previous call for unchanged strings. */
    fun parseAll(raw: Set<String>): List<T> = synchronized(lock) {
        val cached = previous
        val next = HashMap<String, T>(raw.size * 2)
        val out = ArrayList<T>(raw.size)
        for (json in raw) {
            val item = cached[json] ?: parse(json) ?: continue
            next[json] = item
            out += item
        }
        // Readers of different profiles may swap this back and forth; that only costs a re-parse.
        previous = next
        out
    }
}
