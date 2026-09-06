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
 * Registers the caller and fetches-or-creates the key's mutex under one monitor, so eviction can
 * never drop a mutex that is locked or about to be acquired (R29).
 */
internal fun NetworkClient.acquirePageLock(cacheKey: String): kotlinx.coroutines.sync.Mutex =
    synchronized(reusablePageLocks) {
        acquiringPageLockCounts.merge(cacheKey, 1, Int::plus)
        reusablePageLocks.getOrPut(cacheKey) { kotlinx.coroutines.sync.Mutex() }
    }

/** Pairs with [acquirePageLock]: unregisters after the lock scope ends, then evicts idle locks. */
internal fun NetworkClient.releasePageLock(cacheKey: String) {
    synchronized(reusablePageLocks) {
        val remaining = acquiringPageLockCounts.computeIfPresent(cacheKey) { _, count -> count - 1 }
        if (remaining == null || remaining <= 0) acquiringPageLockCounts.remove(cacheKey)
    }
    evictIdlePageLocks()
}

/**
 * Drops per-key coalescing state whose page is gone. Runs under the lock-map monitor and skips
 * keys that are locked or still acquiring, so eviction can never orphan a mutex a caller holds
 * or is about to lock — duplicate concurrent fetches for the same key remain impossible (R29).
 */
internal fun NetworkClient.evictIdlePageLocks() {
    synchronized(reusablePageLocks) {
        reusablePageLocks.entries.removeIf { (key, lock) ->
            !lock.isLocked && !acquiringPageLockCounts.containsKey(key) && !reusablePages.containsKey(key)
        }
    }
}
