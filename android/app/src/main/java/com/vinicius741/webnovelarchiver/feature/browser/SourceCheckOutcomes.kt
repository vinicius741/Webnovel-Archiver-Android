package com.vinicius741.webnovelarchiver.feature.browser

import android.content.Context
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.download.DownloadForegroundService
import com.vinicius741.webnovelarchiver.download.DownloadQueueOrderPlanning
import com.vinicius741.webnovelarchiver.download.SourceCheckOutcome
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * In-memory results of interactive site checks, keyed by provider id. The Downloads screen and the
 * novel page turn them into the "Verified" and "Still blocked" card states; nothing is persisted.
 */
internal object SourceCheckOutcomes {
    private val mutable = MutableStateFlow<Map<String, SourceCheckOutcome>>(emptyMap())
    val state: StateFlow<Map<String, SourceCheckOutcome>> = mutable

    /** The site whose check the user opened from a card, until it passes or they come back. */
    private var awaitingProviderId: String? = null

    fun launched(providerId: String) {
        awaitingProviderId = providerId
    }

    fun verified(
        providerId: String,
        resumedChapters: Int,
        nowMillis: Long,
    ) {
        if (awaitingProviderId == providerId) awaitingProviderId = null
        mutable.update { it + (providerId to SourceCheckOutcome(verified = true, resumedChapters, nowMillis)) }
    }

    /** Back in the app without a passed check: that site's card switches to "Still blocked". */
    fun returnedToApp(nowMillis: Long) {
        val providerId = awaitingProviderId ?: return
        awaitingProviderId = null
        mutable.update { it + (providerId to SourceCheckOutcome(verified = false, resumedChapters = 0, nowMillis)) }
    }
}

/**
 * Runs when the solver passes a site's check, whichever surface opened it (card, notification, or
 * a sync retry): chapters that failed as blocked resume without a manual Retry, and the site's
 * deferred chapters start now instead of at the next recheck.
 */
internal fun resumeDownloadsAfterSourceCheck(
    context: Context,
    url: String,
) {
    val providerId = SourceRegistry.getProvider(url)?.id ?: return
    SourceCheckOutcomes.verified(providerId, resumedChapters = 0, nowMillis = context.appContainer.time.nowMillis())
    val appContext = context.applicationContext
    val container = appContext.appContainer
    container.applicationScope.launch {
        runCatching {
            var resumed = 0
            container.repository.updateQueue { jobs ->
                val resume =
                    DownloadQueueOrderPlanning.resumeAfterVerification(jobs, providerId) { job ->
                        SourceRegistry.getProvider(job.sourceId, job.chapter.url)?.id
                    }
                resumed = resume.resumedChapters
                resume.jobs
            }
            SourceCheckOutcomes.verified(providerId, resumed, container.time.nowMillis())
            // The service stops once nothing is unfinished, which is the case when every chapter
            // failed as blocked. The solver is still in the foreground here, so starting is allowed.
            if (resumed > 0) withContext(Dispatchers.Main) { DownloadForegroundService.start(appContext) }
        }.onFailure { Timber.w(it, "Could not resume downloads after a source check") }
    }
}
