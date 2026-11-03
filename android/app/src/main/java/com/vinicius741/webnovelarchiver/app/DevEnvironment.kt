package com.vinicius741.webnovelarchiver.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.source.network.SourceReliabilityCoordinator
import okhttp3.OkHttpClient

/** Variant-specific debug implementation; release contains only the inert factory. */
internal abstract class DevEnvironment {
    abstract val storageContext: Context
    abstract val time: AppTime

    abstract fun sourceClient(reliability: SourceReliabilityCoordinator): OkHttpClient

    abstract fun aiClient(): OkHttpClient

    abstract suspend fun prepareRepository(repository: AppRepository)

    abstract fun onUiReady(
        activity: Activity,
        container: AppContainer,
    )

    abstract fun attachIndicator(root: ViewGroup)

    abstract fun showVerification(
        activity: Activity,
        onVerified: () -> Unit,
    )

    companion object {
        @Volatile var requested: Boolean = false
            internal set

        @Volatile internal var active: DevEnvironment? = null

        fun blocksExternalIntent(intent: Intent?): Boolean =
            requested && intent?.action == Intent.ACTION_VIEW &&
                intent.data?.scheme?.lowercase() in setOf("http", "https", "market")
    }
}
