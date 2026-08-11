package com.vinicius741.webnovelarchiver.feature.details

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.archive.ArchiveSnapshotPlanning
import com.vinicius741.webnovelarchiver.domain.model.SourceAvailability
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.strokeBg
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Source-health notice that explicitly separates remote availability from preserved local data. */
internal fun ScreenHost.buildSourceAvailabilityNotice(story: Story): LinearLayout {
    val colors = ThemeManager.colors
    val providerName = SourceRegistry.getProvider(story.sourceId, story.sourceUrl)?.name ?: "The source"
    val availability = story.sourceSyncState.availability
    val downloadedLabel =
        "${story.downloadedChapters} downloaded chapter${if (story.downloadedChapters == 1) "" else "s"}"
    val message =
        if (availability == SourceAvailability.not_found) {
            "$providerName no longer returns this fiction. Your local copy remains available here with $downloadedLabel."
        } else {
            "Automated access to $providerName is currently blocked. Your local copy remains available here with $downloadedLabel."
        }
    val accent = if (availability == SourceAvailability.not_found) colors.error else colors.tertiary
    val radius = dp(ThemeManager.current.shapes.cardRadius).toFloat()
    return LinearLayout(app).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(Space.MD), dp(Space.MD), dp(Space.MD), dp(Space.MD))
        background = strokeBg(colors.elevation1, radius, colors.outlineVariant, dp(1))
        layoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(Space.MD)
            }
        addView(
            LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    View(app).apply {
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        background = roundedBg(accent, dp(2).toFloat())
                    },
                    LinearLayout.LayoutParams(dp(4), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                        marginEnd = dp(Space.MD)
                    },
                )
                addView(
                    LinearLayout(app).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(makeText(app, "Your local copy is safe", Type.TITLE_MEDIUM, colors.onSurface))
                        addView(
                            makeText(app, message, Type.BODY_MEDIUM, colors.onSurfaceVariant).apply {
                                setPadding(0, dp(Space.XS), 0, 0)
                            },
                        )
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
            },
        )
        if (availability == SourceAvailability.not_found) {
            this@buildSourceAvailabilityNotice.addArchiveAction(this, story)
        }
    }
}

private fun ScreenHost.addArchiveAction(
    parent: LinearLayout,
    story: Story,
) {
    parent.addView(
        makeUnavailableArchiveButton(story),
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = app.dp(Space.SM)
            marginStart = app.dp(Space.MD)
        },
    )
}

private fun ScreenHost.makeUnavailableArchiveButton(story: Story) =
    makeButton(app, "Archive Local Copy", Btn.OUTLINED, R.drawable.wna_archive) {
        scope.launch {
            val available =
                try {
                    ensureUnavailableArchive(story)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    false
                }
            toast(if (available) "Archived snapshot is available in your library" else "Could not create archived snapshot")
        }
    }

private suspend fun ScreenHost.ensureUnavailableArchive(story: Story): Boolean {
    val eventStarted = story.sourceSyncState.unavailableSince
    val existing =
        repository.library().any { candidate ->
            candidate.archiveOfStoryId == story.id &&
                candidate.archiveReason == ArchiveSnapshotPlanning.SOURCE_UNAVAILABLE_REASON &&
                (eventStarted == null || (candidate.archivedAt ?: Long.MIN_VALUE) >= eventStarted)
        }
    if (existing) return true
    val latest = repository.story(story.id) ?: return false
    repository.commitSyncedStory(
        story = latest,
        archiveSource = latest,
        archiveReason = ArchiveSnapshotPlanning.SOURCE_UNAVAILABLE_REASON,
    ) { current -> current ?: latest }
    return true
}
