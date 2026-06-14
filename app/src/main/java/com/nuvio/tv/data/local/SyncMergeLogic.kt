package com.nuvio.tv.data.local

import com.nuvio.tv.domain.model.SavedLibraryItem
import com.nuvio.tv.domain.model.WatchedItem

/**
 * rev 4 Option B — pure, side-effect-free merge helpers extracted from the *Preferences classes so the
 * union-on-first-pull logic is JVM-unit-testable (no DataStore / gson / Android).
 */

/**
 * First-pull-aware snapshot merge for watched_items.
 * @param lastSuccessfulPushMs > 0 ⇒ device has pushed ⇒ keep today's behavior (preserve only local watched
 *        after the last push). <= 0 ⇒ never pushed.
 * @param unionWhenNeverSynced when never-synced, true ⇒ UNION all local not in remote (the restore-path fix);
 *        false ⇒ preserve NOTHING (today's pure replace — used by every non-restore caller, e.g. TraktViewModel).
 * @return (merged list, whether any local item was preserved — drives the post-pull push).
 */
fun unionWatchedSnapshot(
    current: List<WatchedItem>,
    remote: List<WatchedItem>,
    lastSuccessfulPushMs: Long,
    unionWhenNeverSynced: Boolean,
): Pair<List<WatchedItem>, Boolean> {
    val deduped = LinkedHashMap<Triple<String, Int?, Int?>, WatchedItem>()
    remote.forEach { deduped[Triple(it.contentId, it.season, it.episode)] = it } // remote wins on overlap
    val neverSynced = lastSuccessfulPushMs <= 0L
    var preserved = false
    current.forEach { local ->
        val key = Triple(local.contentId, local.season, local.episode)
        if (key !in deduped) {
            val keep = if (neverSynced) unionWhenNeverSynced else local.watchedAt > lastSuccessfulPushMs
            if (keep) {
                deduped[key] = local
                preserved = true
            }
        }
    }
    return deduped.values.toList() to preserved
}

/**
 * Snapshot merge for library.
 * @param preserveLocal true ⇒ UNION local-only saved titles with remote (the restore-path fix; safe to apply
 *        on every pull because the cloud library upsert never deletes — local ⊆ cloud in steady state, so union
 *        ≡ replace except for not-yet-pushed local-only titles); false ⇒ replace (remote only).
 * @return (merged list, whether any local-only item was preserved — drives the forced post-merge push).
 */
fun unionLibrarySnapshot(
    current: List<SavedLibraryItem>,
    remote: List<SavedLibraryItem>,
    preserveLocal: Boolean,
): Pair<List<SavedLibraryItem>, Boolean> {
    val deduped = LinkedHashMap<Pair<String, String>, SavedLibraryItem>()
    remote.forEach { deduped[it.id to it.type.lowercase()] = it }
    var preserved = false
    if (preserveLocal) {
        current.forEach { local ->
            val key = local.id to local.type.lowercase()
            if (key !in deduped) {
                deduped[key] = local
                preserved = true
            }
        }
    }
    return deduped.values.toList() to preserved
}
