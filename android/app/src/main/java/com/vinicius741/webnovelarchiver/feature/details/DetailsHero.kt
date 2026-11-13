package com.vinicius741.webnovelarchiver.feature.details

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.SourceAvailability
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.ReleaseCadencePlanning
import com.vinicius741.webnovelarchiver.domain.story.RemovedChapterPlanning
import com.vinicius741.webnovelarchiver.domain.story.StoryActionGuards
import com.vinicius741.webnovelarchiver.domain.story.StoryBookmarkPlanning
import com.vinicius741.webnovelarchiver.download.DownloadDetailsPlanning
import com.vinicius741.webnovelarchiver.feature.ai.AiControlsTab
import com.vinicius741.webnovelarchiver.feature.downloads.showQueue
import com.vinicius741.webnovelarchiver.feature.downloads.startSourceCheck
import com.vinicius741.webnovelarchiver.feature.downloads.storySourceCheck
import com.vinicius741.webnovelarchiver.feature.story.StoryTab
import com.vinicius741.webnovelarchiver.feature.story.syncStory
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.navigation.StoryOperationKind
import com.vinicius741.webnovelarchiver.ui.BlurredBackdropView
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.ChapterCoverageBar
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.activeCoverSource
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeFullWidthButton
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.strokeBg
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/*
 * The story hero: cover, title and pills (DetailsHeader.kt), the release forecast with Sync, the
 * notices, operation progress, the live download banner, the contextual primary action (DetailsCta.kt),
 * the quick actions and the offline coverage card. It leads the Overview tab, and on the inner
 * screen it stays in its own pane beside every tab.
 */

/** The hero and the slots live download events patch in place. */
internal class StoryHero(
    val view: View,
    val coverage: CoverageCard?,
    val bannerSlot: LinearLayout,
    val ctaSlot: LinearLayout,
)

internal fun ScreenHost.buildStoryHero(
    story: Story,
    tab: StoryTab,
    twoPane: Boolean,
): StoryHero {
    val operation = storyOperation?.takeIf { it.storyId == story.id }
    val isBusy = operation != null
    val jobs = repository.queue().filter { it.storyId == story.id }
    val summary = DownloadDetailsPlanning.summarizeStoryDownload(jobs)
    val start = dp(if (twoPane) Spacing.LG + 4 else Spacing.LG)
    val column =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(start, dp(Spacing.LG), dp(Spacing.LG), dp(Spacing.SM))
        }
    column.addView(buildHeroTop(story))
    if (StoryActionGuards.canSync(story)) column.addView(buildForecastBanner(story, operation?.kind == StoryOperationKind.SYNC, isBusy))
    addHeroNotices(column, story, tab)
    if (operation != null && operation.kind != StoryOperationKind.AI_COVER && operation.kind != StoryOperationKind.AI_DESCRIPTION) {
        // Stable slot: progress ticks patch in place instead of rebuilding the page.
        makeStoryOperationSlot(app, operation).also {
            detailsOperationSlot = it
            column.addView(it)
        }
    }
    val bannerSlot = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
    // Seeded with the pacing now: the live observer repaints the banner only when the pacing changes.
    renderHeroDownloadBanner(bannerSlot, story, summary, currentPacingStatus(story.id))
    column.addView(bannerSlot)
    val ctaSlot = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
    renderDetailsCta(ctaSlot, story, summary, isBusy)
    column.addView(ctaSlot)
    column.addView(buildQuickActions(story, operation?.kind, isBusy))
    val hasEpub = !story.epubPaths.isNullOrEmpty() || !story.epubPath.isNullOrBlank()
    if (story.epubStale == true && hasEpub) column.addView(buildStaleEpubNotice(story, isBusy))
    val coverage = if (story.chapters.isNotEmpty()) CoverageCard(this, story) else null
    coverage?.let { column.addView(it.view) }
    val root =
        FrameLayout(app).apply {
            val backdrop = BlurredBackdropView(app, fadeTo = ThemeManager.colors.background)
            backdrop.loadSource(activeCoverSource(story))
            addView(backdrop, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(BACKDROP_HEIGHT_DP)))
            addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    return StoryHero(root, coverage, bannerSlot, ctaSlot)
}

/** Archived, source-health and removed-chapter notices, and the waiting AI cover. */
private fun ScreenHost.addHeroNotices(
    column: LinearLayout,
    story: Story,
    tab: StoryTab,
) {
    if (story.isArchived == true) {
        // Only a legacy archive whose startup fold failed reaches here; the fold retries each start.
        column.addView(
            makeText(
                app,
                "Old archive copy: read-only until it merges into its novel",
                Type.LABEL_MEDIUM,
                ThemeManager.colors.tertiary,
            ).apply {
                setPadding(0, dp(Spacing.SM), 0, dp(Spacing.SM))
            },
        )
    }
    if (story.isArchived != true && story.sourceSyncState.availability != SourceAvailability.available) {
        column.addView(buildSourceAvailabilityNotice(story))
    }
    val hiddenThrough = repository.getDisplayPreferences().removedChaptersNoticeHiddenThrough?.get(story.id)
    if (RemovedChapterPlanning.showsRemovedNotice(story, hiddenThrough)) column.addView(buildRemovedChaptersNotice(story))
    if (tab == StoryTab.AI) return
    val aiOperation = aiOperation?.takeIf { it.storyId == story.id }
    if (aiOperation?.kind == StoryOperationKind.AI_COVER) {
        makeStoryOperationSlot(app, aiOperation).also {
            detailsAiOperationSlot = it
            column.addView(it)
        }
    } else if (story.id in repository.aiCoverUnseen.value || aiControlsScreenState.coverDrafts[story.id] != null) {
        // A finished background cover waits for a decision; lead straight to it.
        column.addView(
            makeFullWidthButton(app, "Review new AI cover", Btn.TONAL, R.drawable.wna_auto_awesome, dp(Spacing.SM)) {
                aiControlsScreenState.tabs[story.id] = AiControlsTab.COVER
                switchStoryTab(story.id, StoryTab.AI)
            }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(Spacing.SM) },
        )
    }
}

/**
 * Next-chapter forecast with a Sync button, or "Checking source…" while a sync runs. The copy
 * depends on the clock, so it re-renders whenever the window regains focus or the view reattaches.
 */
private fun ScreenHost.buildForecastBanner(
    story: Story,
    syncing: Boolean,
    isBusy: Boolean,
): View {
    val colors = ThemeManager.colors
    val radius = dp(FORECAST_RADIUS_DP).toFloat()
    val icon = ImageView(app)
    val text = makeText(app, "", Type.BODY_SMALL, colors.onSurfaceVariant).apply { setLineSpacing(0f, LINE_SPACING) }
    val row =
        LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(Spacing.MD), dp(6), dp(6), dp(6))
            addView(icon, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(Spacing.MD - 2) })
            addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            layoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(Spacing.LG)
                    bottomMargin = dp(Spacing.MD + 2)
                }
        }
    if (syncing) {
        row.addView(
            ProgressBar(app).apply { indeterminateTintList = ColorStateList.valueOf(colors.primary) },
            LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(Spacing.MD) },
        )
    } else {
        row.addView(buildForecastSyncButton(story, isBusy))
    }

    fun render() {
        val now = System.currentTimeMillis()
        val forecast = ReleaseCadencePlanning.forecast(story, now)
        val tint =
            when (forecast) {
                is ReleaseCadencePlanning.Forecast.Due, is ReleaseCadencePlanning.Forecast.MaybeOut -> ForecastTint.DUE
                is ReleaseCadencePlanning.Forecast.Late -> ForecastTint.LATE
                else -> ForecastTint.QUIET
            }
        text.text =
            when {
                syncing -> "Checking source for new chapters…"
                forecast != null -> ReleaseCadencePlanning.describe(forecast, now)
                story.lastChapterSyncAt != null -> "Synced ${formatAgo(story.lastChapterSyncAt!!, now)} · no release pattern yet"
                else -> "Sync to check the source for new chapters"
            }
        val accent = if (tint == ForecastTint.LATE) colors.secondary else colors.primary
        text.setTextColor(
            when (tint) {
                ForecastTint.DUE -> colors.onPrimaryContainer
                ForecastTint.LATE -> colors.onSecondaryContainer
                ForecastTint.QUIET -> colors.onSurfaceVariant
            },
        )
        val iconRes = if (tint == ForecastTint.LATE) R.drawable.wna_schedule else R.drawable.wna_event
        icon.setImageDrawable(app.tintedIcon(iconRes, if (tint == ForecastTint.QUIET) colors.onSurfaceVariant else accent))
        row.background =
            if (tint == ForecastTint.DUE) {
                strokeBg(colors.primary and DUE_FILL_MASK, radius, colors.primary and DUE_STROKE_MASK, dp(1))
            } else {
                strokeBg(colors.elevation1, radius, colors.line, dp(1))
            }
    }
    render()
    val onFocus = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus -> if (hasFocus) render() }
    row.addOnAttachStateChangeListener(
        object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.viewTreeObserver.addOnWindowFocusChangeListener(onFocus)
                render()
            }

            override fun onViewDetachedFromWindow(v: View) {
                v.viewTreeObserver.removeOnWindowFocusChangeListener(onFocus)
            }
        },
    )
    return row
}

private enum class ForecastTint { DUE, LATE, QUIET }

private fun ScreenHost.buildForecastSyncButton(
    story: Story,
    isBusy: Boolean,
): View {
    val colors = ThemeManager.colors
    val radius = dp(18).toFloat()
    val label = if (story.sourceSyncState.availability != SourceAvailability.available) "Check again" else "Sync"
    return LinearLayout(app).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(36)
        setPadding(dp(Spacing.MD - 2), 0, dp(Spacing.MD), 0)
        background = ripple(roundedBg(colors.elevation3, radius), radius, colors.onSurface)
        isClickable = !isBusy
        isFocusable = !isBusy
        alpha = if (isBusy) DISABLED_ALPHA else 1f
        contentDescription = "Sync chapters"
        addView(
            ImageView(app).apply {
                setImageDrawable(app.tintedIcon(R.drawable.wna_refresh, colors.onSurface))
            },
            LinearLayout.LayoutParams(dp(16), dp(16)),
        )
        addView(
            makeText(app, label, Type.LABEL_LARGE, colors.onSurface).apply {
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setPadding(dp(Spacing.XS), 0, 0, 0)
            },
        )
        if (!isBusy) setOnClickListener { repository.story(story.id)?.let { syncStory(it) } }
    }
}

/** Sync · EPUB · Select · Audio, each a tinted square with its label under it. */
private fun ScreenHost.buildQuickActions(
    story: Story,
    running: StoryOperationKind?,
    isBusy: Boolean,
): View {
    val row =
        LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(Spacing.LG)
                    bottomMargin = dp(6)
                }
        }
    val canSync = StoryActionGuards.canSync(story) && !isBusy
    row.addQuickAction("Sync", R.drawable.wna_refresh, canSync, spinning = running == StoryOperationKind.SYNC) {
        repository.story(story.id)?.let { syncStory(it) }
    }
    row.addQuickAction("EPUB", R.drawable.wna_menu_book, true, spinning = running == StoryOperationKind.EPUB) { showEpubSheet(story.id) }
    row.addQuickAction("Select", R.drawable.wna_checklist, StoryActionGuards.canQueueDownloads(story)) { showChapterSelection(story.id) }
    row.addQuickAction("Audio", R.drawable.wna_graphic_eq, true) { switchStoryTab(story.id, StoryTab.AUDIO) }
    return row
}

private fun LinearLayout.addQuickAction(
    label: String,
    icon: Int,
    enabled: Boolean,
    spinning: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = ThemeManager.colors
    val radius = context.dp(QUICK_BOX_RADIUS_DP).toFloat()
    val box =
        FrameLayout(context).apply {
            background = strokeBg(colors.elevation2, radius, colors.line, context.dp(1))
            if (spinning) {
                addView(
                    ProgressBar(context).apply { indeterminateTintList = ColorStateList.valueOf(colors.primary) },
                    FrameLayout.LayoutParams(context.dp(20), context.dp(20), Gravity.CENTER),
                )
            } else {
                addView(
                    ImageView(context).apply { setImageDrawable(context.tintedIcon(icon, colors.primary)) },
                    FrameLayout.LayoutParams(context.dp(22), context.dp(22), Gravity.CENTER),
                )
            }
        }
    val item =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, context.dp(Spacing.XS), 0, context.dp(Spacing.XS))
            isClickable = enabled
            isFocusable = enabled
            alpha = if (enabled) 1f else DISABLED_ALPHA
            contentDescription = label
            background = ripple(roundedBg(android.graphics.Color.TRANSPARENT, radius), radius, colors.onSurface)
            addView(box, LinearLayout.LayoutParams(context.dp(QUICK_BOX_WIDTH_DP), context.dp(QUICK_BOX_HEIGHT_DP)))
            addView(
                makeText(context, label, Type.LABEL_MEDIUM, colors.onSurfaceVariant).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, context.dp(6), 0, 0)
                },
            )
            if (enabled) setOnClickListener { onClick() }
        }
    addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
}

/** "210 / 210 offline", the ivory bookmark chip, and the coverage bar; opens the Chapters tab. */
internal class CoverageCard(
    host: ScreenHost,
    story: Story,
) {
    private val context = host.app
    private val count = makeText(context, "", Type.TITLE_SMALL, ThemeManager.colors.onSurface)
    private val bookmark = makeText(context, "", Type.LABEL_MEDIUM, ThemeManager.colors.bookmark)
    private val bar = ChapterCoverageBar(context)
    val view: View

    init {
        val colors = ThemeManager.colors
        val radius = context.dp(COVERAGE_RADIUS_DP).toFloat()
        bookmark.setCompoundDrawablesRelativeWithIntrinsicBounds(
            context.tintedIcon(R.drawable.wna_bookmark, colors.bookmark)?.apply { setBounds(0, 0, context.dp(14), context.dp(14)) },
            null,
            null,
            null,
        )
        bookmark.compoundDrawablePadding = context.dp(2)
        val header =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(count)
                addView(makeText(context, "offline", Type.BODY_SMALL, colors.onSurfaceVariant).apply { setPadding(context.dp(6), 0, 0, 0) })
                addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                addView(bookmark)
                addView(
                    ImageView(
                        context,
                    ).apply { setImageDrawable(context.tintedIcon(R.drawable.wna_chevron_right, colors.onSurfaceVariant)) },
                    LinearLayout.LayoutParams(context.dp(18), context.dp(18)).apply { marginStart = context.dp(Spacing.XS) },
                )
            }
        view =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(Spacing.MD + 2), context.dp(Spacing.MD), context.dp(Spacing.MD + 2), context.dp(Spacing.MD + 2))
                background = ripple(strokeBg(colors.elevation1, radius, colors.line, context.dp(1)), radius, colors.onSurface)
                isClickable = true
                isFocusable = true
                addView(header)
                addView(
                    bar,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bar.layoutParams.height).apply {
                        topMargin =
                            context.dp(Spacing.SM + 2)
                    },
                )
                setOnClickListener { host.switchStoryTab(story.id, StoryTab.CHAPTERS) }
                layoutParams =
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = context.dp(Spacing.MD)
                        bottomMargin = context.dp(Spacing.SM)
                    }
            }
        render(story)
    }

    fun render(story: Story) {
        count.text = "${"%,d".format(story.downloadedChapters)} / ${"%,d".format(story.chapters.size)}"
        val bookmarkIndex = story.lastReadChapterId?.let { id -> story.chapters.indexOfFirst { it.id == id } } ?: -1
        bookmark.text = if (bookmarkIndex >= 0) "Ch. ${bookmarkIndex + 1}" else ""
        bookmark.visibility = if (bookmarkIndex >= 0) View.VISIBLE else View.GONE
        bar.bind(StoryBookmarkPlanning.downloadedFlags(story), StoryBookmarkPlanning.bookmarkFraction(story))
        view.contentDescription =
            "${story.downloadedChapters} of ${story.chapters.size} chapters offline" +
            (if (bookmarkIndex >= 0) ", bookmark at chapter ${bookmarkIndex + 1}" else "") + ". Tap to see chapters."
    }
}

/** The live download banner (gold card) while chapters download, pause or end with failures; empty otherwise. */
internal fun ScreenHost.renderHeroDownloadBanner(
    slot: LinearLayout,
    story: Story,
    summary: DownloadDetailsPlanning.StoryDownloadSummary,
    pacing: com.vinicius741.webnovelarchiver.download.DownloadPacingUiStatus? = null,
) {
    slot.removeAllViews()
    if (!shouldShowDetailsBanner(summary)) return
    val colors = ThemeManager.colors
    val radius = dp(ThemeManager.shapes.cardRadius).toFloat()
    val card =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(Spacing.MD + 2), dp(Spacing.MD), dp(Spacing.MD + 2), 0)
            background =
                GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(colors.primaryContainer and BANNER_TOP_MASK, colors.elevation1),
                ).apply {
                    cornerRadius = radius
                    setStroke(dp(1), colors.primary and DUE_STROKE_MASK)
                }
            addView(
                makeDownloadProgressBanner(
                    app,
                    summary,
                    pacing,
                    sourceCheck = storySourceCheck(story.id),
                    onVerify = { startSourceCheck(it.providerId, it.sampleUrl) },
                ) { showQueue() },
            )
        }
    slot.addView(
        card,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(Spacing.MD)
        },
    )
}

/** A date in the device's medium style ("Nov 12, 2026"). */
internal fun formatSourceDate(value: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(Date(value))

/** "today", "yesterday", "3 days ago", or the date. */
internal fun formatAgo(
    timestamp: Long,
    now: Long = System.currentTimeMillis(),
): String {
    val days = ((now - timestamp).coerceAtLeast(0L) / DAY_MS).toInt()
    return when {
        days == 0 -> "today"
        days == 1 -> "yesterday"
        days < WEEK_DAYS -> "$days days ago"
        else -> formatSourceDate(timestamp)
    }
}

private const val BACKDROP_HEIGHT_DP = 300
private const val FORECAST_RADIUS_DP = 14
private const val QUICK_BOX_WIDTH_DP = 52
private const val QUICK_BOX_HEIGHT_DP = 44
private const val QUICK_BOX_RADIUS_DP = 16
private const val COVERAGE_RADIUS_DP = 16
private const val LINE_SPACING = 1.1f
internal const val DISABLED_ALPHA = 0.45f
private const val DUE_FILL_MASK = 0x1AFFFFFF
private const val DUE_STROKE_MASK = 0x40FFFFFF
private const val BANNER_TOP_MASK = 0xCCFFFFFF.toInt()
private const val DAY_MS = 24L * 60L * 60L * 1000L
private const val WEEK_DAYS = 7
