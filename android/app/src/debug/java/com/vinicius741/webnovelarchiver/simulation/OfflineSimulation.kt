package com.vinicius741.webnovelarchiver.simulation

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.gson.JsonObject
import com.vinicius741.webnovelarchiver.app.AppContainer
import com.vinicius741.webnovelarchiver.app.DevEnvironment
import com.vinicius741.webnovelarchiver.app.ScaledAppTime
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.data.storage.AtomicFileWrites
import com.vinicius741.webnovelarchiver.domain.model.AiSettings
import com.vinicius741.webnovelarchiver.domain.model.AppSettings
import com.vinicius741.webnovelarchiver.source.network.CloudflareBypassInterceptor
import com.vinicius741.webnovelarchiver.source.network.SourceReliabilityCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

internal class OfflineSimulation(
    private val context: Context,
    private val config: SimulationConfig,
) : DevEnvironment() {
    override val storageContext: Context = SimulationContext(context, config.sessionId)
    override val time = ScaledAppTime(config.epochMillis, config.speed)
    private val directory = File(context.filesDir, "dev_simulation/sessions/${config.sessionId}")
    private val journal = SimulationJournal(directory, config, time)
    private val image = context.assets.open("simulation/cover.png").use { it.readBytes() }
    private val replay =
        context.assets.open("simulation/replay.json").reader().use { manifest ->
            SimulationReplay(manifest) { path -> context.assets.open(path).use { it.readBytes() } }
        }
    private val responder = SimulationResponder(config, time, journal, image, replay)

    @Volatile private var observing = false

    override fun sourceClient(reliability: SourceReliabilityCoordinator): OkHttpClient =
        builder().addInterceptor(CloudflareBypassInterceptor(responder, reliability)).addInterceptor(responder).build()

    override fun aiClient(): OkHttpClient = builder().addInterceptor(responder).build()

    private fun builder(): OkHttpClient.Builder = OkHttpClient.Builder().callTimeout(150, TimeUnit.SECONDS).retryOnConnectionFailure(false)

    override suspend fun prepareRepository(repository: AppRepository) {
        if (File(directory, "initialized").exists()) return
        check(repository.library().isEmpty()) { "Uninitialized simulation contains data" }
        repository.settings.save(AppSettings(maxParallelSources = 2, downloadDelay = 500, downloadDelayMax = 500))
        repository.aiSettings.save(
            AiSettings(
                apiKey = if (config.scenario.missingKey) null else "simulation-dummy-key",
                descriptionModel = "simulation/text",
                decisionModel = "simulation/decisions",
                imageModel = "simulation/image",
            ),
        )
        val seedStories = if (config.scenario.action == "sources") emptyList() else SimulationFixtures.forScenario(config.scenario)
        seedStories.forEachIndexed { storyIndex, story ->
            if (storyIndex == 0) {
                story.isArchived = config.scenario.archived
                if (config.scenario.manual) {
                    story.aiContextChapterIndices = mutableListOf(0, 1)
                    story.aiCoverContextChapterIndices = mutableListOf(0, 1)
                }
            }
            repository.addOrUpdateStory(story)
            if (config.scenario.action in setOf("cover", "description", "composite", "none") && storyIndex == 0) {
                story.chapters.take(6).forEachIndexed { index, chapter ->
                    val path = repository.storage.saveChapter(story.id, index, chapter, SimulationFixtures.chapterHtml(chapter.url))
                    repository.markChapterDownloaded(story.id, chapter.id, path, time.nowMillis())
                }
            }
        }
        AtomicFileWrites.writeText(File(directory, "initialized"), "1")
        journal.event("seeded", "library")
        journal.phase("ready")
    }

    override fun onUiReady(
        activity: Activity,
        container: AppContainer,
    ) {
        if (observing) return
        observing = true
        container.applicationScope.launch {
            while (true) {
                journal.summary(SimulationActions.summary(container))
                delay(500)
            }
        }
        if (!config.autoStart) return
        if (journal.autoStarted()) {
            // Only the durable download queue resumes. Generation has no process-death resume contract.
            if (config.scenario.action in setOf("downloads", "composite") && journal.phase() == "interrupted") {
                container.applicationScope.launch {
                    SimulationActions(config, journal, image, replay.storyUrls).resumeDownloads(activity, container)
                }
            }
            return
        }
        journal.markAutoStarted()
        container.applicationScope.launch {
            SimulationActions(config, journal, image, replay.storyUrls).run(activity, container)
        }
    }

    override fun attachIndicator(root: ViewGroup) {
        val height = (24 * root.resources.displayMetrics.density).toInt()
        (root.getChildAt(0).layoutParams as? FrameLayout.LayoutParams)?.let { params ->
            params.bottomMargin = height
            root.getChildAt(0).layoutParams = params
        }
        val indicator =
            TextView(root.context).apply {
                text = "SIMULATION · ${config.scenario.id} · ${config.speed.toInt()}x"
                gravity = Gravity.CENTER
                textSize = 11f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.rgb(114, 54, 8))
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
            }
        root.addView(indicator, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height, Gravity.BOTTOM))

        fun updateInset() {
            val insets = root.rootWindowInsets ?: return
            val bottom =
                androidx.core.view.WindowInsetsCompat
                    .toWindowInsetsCompat(insets, root)
                    .getInsets(
                        androidx.core.view.WindowInsetsCompat.Type
                            .systemBars(),
                    ).bottom
            val params = indicator.layoutParams as FrameLayout.LayoutParams
            if (params.bottomMargin != bottom) {
                params.bottomMargin = bottom
                indicator.layoutParams = params
            }
        }
        root.post { updateInset() }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateInset() }
    }

    override fun showVerification(
        activity: Activity,
        onVerified: () -> Unit,
    ) {
        val inset = (com.vinicius741.webnovelarchiver.ui.Spacing.XL * activity.resources.displayMetrics.density).toInt()
        val root =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(inset, inset * 3, inset, inset)
                setBackgroundColor(Color.rgb(28, 28, 30))
            }
        root.addView(
            TextView(activity).apply {
                text = "Verify source access · Simulation"
                textSize = 22f
                setTextColor(Color.WHITE)
            },
        )
        root.addView(
            TextView(activity).apply {
                text = "Local challenge fixture. No website is opened."
                setTextColor(Color.LTGRAY)
                setPadding(0, inset, 0, inset)
            },
        )
        val verify = Button(activity).apply { text = "Simulate successful verification" }
        val requestedUrl = activity.intent.getStringExtra("cloudflare_solve_url").orEmpty()
        val host = runCatching { java.net.URI(requestedUrl).host }.getOrNull()

        fun verifyNow() {
            if (config.scenario.faults["cloudflare"] == "still-blocked" || host != "www.royalroad.com") {
                verify.text = "Still blocked · close and try again"
                journal.event("verification", "source", "still_blocked")
            } else {
                journal.verify(host)
                journal.phase("running")
                onVerified()
            }
        }
        verify.setOnClickListener { verifyNow() }
        root.addView(verify)
        root.addView(
            Button(activity).apply {
                text = "Cancel"
                setOnClickListener { activity.finish() }
            },
        )
        activity.setContentView(root)
        context.appContainerScope().launch {
            while (!activity.isFinishing && !activity.isDestroyed) {
                if (File(directory, "verify").exists()) {
                    File(directory, "verify").delete()
                    withContext(Dispatchers.Main) { verifyNow() }
                    break
                }
                delay(100)
            }
        }
    }

    private fun Context.appContainerScope() =
        (applicationContext as com.vinicius741.webnovelarchiver.app.WebnovelArchiverApp).container.applicationScope
}
