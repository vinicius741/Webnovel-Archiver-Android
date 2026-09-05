package com.vinicius741.webnovelarchiver.source.network

/*
 * Prepared/reusable page cache admission + eviction (R29), split out of [NetworkClient] to keep
 * that class inside its file-size budget.
 */

/**
 * Bounded admission (R29): expired entries are dropped and the map is capped, so abandoned
 * preflights cannot accumulate unused HTML for a whole session.
 */
internal fun NetworkClient.admitPreparedPage(
    key: String,
    page: NetworkClient.PreparedPage,
) {
    val now = nowMillis()
    preparedPages.entries.filter { it.value.expiresAt <= now }.forEach { preparedPages.remove(it.key, it.value) }
    while (preparedPages.size >= NetworkClient.MAX_PREPARED_PAGES) {
        preparedPages.entries.minByOrNull { it.value.expiresAt }?.let { oldest ->
            preparedPages.remove(oldest.key, oldest.value)
        } ?: break
    }
    preparedPages[key] = page
}

/**
 * Drops per-key coalescing state whose page is gone; never evicts a lock a caller currently
 * holds, so duplicate concurrent fetches for the same key remain impossible (R29).
 */
internal fun NetworkClient.evictIdlePageLocks() {
    reusablePageLocks.entries.removeIf { (key, lock) -> !lock.isLocked && !reusablePages.containsKey(key) }
}
