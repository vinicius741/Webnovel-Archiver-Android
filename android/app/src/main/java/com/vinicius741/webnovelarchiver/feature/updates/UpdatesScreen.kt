package com.vinicius741.webnovelarchiver.feature.updates

import android.content.res.ColorStateList
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.domain.story.StoryBookmarkPlanning
import com.vinicius741.webnovelarchiver.feature.details.showDetails
import com.vinicius741.webnovelarchiver.feature.library.showLibrary
import com.vinicius741.webnovelarchiver.feature.reader.showReader
import com.vinicius741.webnovelarchiver.feature.story.queueDownload
import com.vinicius741.webnovelarchiver.navigation.InFlightStorySync
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.navigation.UpdateFollowSelectionState
import com.vinicius741.webnovelarchiver.navigation.UpdateTrackerScreenState
import com.vinicius741.webnovelarchiver.ui.AppBarAction
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.applyCheckBoxTint
import com.vinicius741.webnovelarchiver.ui.button
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.coverImage
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.fullButton
import com.vinicius741.webnovelarchiver.ui.makeCard
import com.vinicius741.webnovelarchiver.ui.makeEmptyState
import com.vinicius741.webnovelarchiver.ui.makeSearchField
import com.vinicius741.webnovelarchiver.ui.makeSelectableCardRow
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.row
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.section
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.size
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

private const val UPDATE_SYNC_CONCURRENCY = 3

internal fun ScreenHost.showUpdates() {
    activeStory = null
    rerender = { showUpdates() }

    // Seed from the repository's cached library rather than re-parsing every story JSON on each
    // render (Audit Rec 1). The full Updates reactivity refactor is Rec 5 (deferred); this only
    // swaps the initial seed to the in-memory snapshot.
    val stories = repository.library()
    val followedIds = UpdateTrackerPlanning.normalizeFollowedIds(stories, storage.getUpdateFollowedStoryIds())
    if (followedIds != storage.getUpdateFollowedStoryIds()) storage.saveUpdateFollowedStoryIds(followedIds)
    val followedStories = UpdateTrackerPlanning.followedStories(stories, followedIds)
    val syncedUpdatedChapterIds = updateTrackerScreenState.syncedUpdatedChapterIds
    val updatedStoryCount = UpdateTrackerPlanning.updatedStoryCount(followedStories, syncedUpdatedChapterIds)
    val updatedChapterCount = UpdateTrackerPlanning.updatedChapterCount(followedStories, syncedUpdatedChapterIds)

    screen(
        title = "Updates",
        subtitle = "${followedStories.size} followed · $updatedChapterCount new",
        onBack = { showLibrary() },
        actions = listOf(AppBarAction(R.drawable.wna_check, "Choose novels") { showUpdateFollowSelection() }),
        scrollable = true,
    ) {
        if (stories.isEmpty()) {
            addView(
                makeEmptyState(
                    context,
                    title = "Your library is empty",
                    message = "Import stories before setting up update tracking.",
                    iconRes = R.drawable.wna_menu_book,
                    actionLabel = "Back to Library",
                    onAction = { showLibrary() },
                ),
            )
            return@screen
        }

        var progressLabel: TextView? = null
        var progressBar: ProgressBar? = null

        fun updateProgressUi() {
            val state = updateTrackerScreenState
            progressLabel?.text = state.progressText()
            progressBar?.apply {
                visibility = if (state.syncing) View.VISIBLE else View.GONE
                max = state.total.coerceAtLeast(1)
                progress = state.completed.coerceAtMost(max)
            }
        }

        addView(
            card {
                text("Following ${followedStories.size} novel${plural(followedStories.size)}", Type.TITLE_MEDIUM)
                text(
                    "$updatedStoryCount novel${plural(
                        updatedStoryCount,
                    )} with $updatedChapterCount updated chapter${plural(updatedChapterCount)}",
                    Type.BODY_MEDIUM,
                    ThemeManager.colors.onSurfaceVariant,
                )
                progressLabel =
                    makeText(
                        context,
                        updateTrackerScreenState.progressText(),
                        Type.BODY_SMALL,
                        ThemeManager.colors.onSurfaceVariant,
                    ).apply {
                        setPadding(0, dp(Space.SM), 0, 0)
                    }
                addView(progressLabel)
                progressBar =
                    ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                        progressTintList = ColorStateList.valueOf(ThemeManager.colors.primary)
                        progressBackgroundTintList = ColorStateList.valueOf(ThemeManager.colors.outlineVariant)
                        visibility = if (updateTrackerScreenState.syncing) View.VISIBLE else View.GONE
                        layoutParams =
                            LinearLayout
                                .LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    dp(6),
                                ).apply {
                                    topMargin = dp(Space.SM)
                                }
                    }
                addView(progressBar)
                fullButton(
                    label = if (updateTrackerScreenState.syncing) "Syncing..." else "Sync Followed Novels",
                    variant = Btn.FILLED,
                    icon = R.drawable.wna_refresh,
                    enabled = followedStories.isNotEmpty() && !updateTrackerScreenState.syncing,
                    topMarginDp = Space.LG,
                ) {
                    syncFollowedUpdates(::updateProgressUi)
                }
            },
        )
        updateProgressUi()

        updateTrackerScreenState.errors.takeIf { it.isNotEmpty() }?.let { errors ->
            addView(
                card {
                    text("${errors.size} sync error${plural(errors.size)}", Type.TITLE_MEDIUM, ThemeManager.colors.error)
                    errors.forEach { (storyId, message) ->
                        val title = stories.firstOrNull { it.id == storyId }?.title ?: storyId
                        text("$title: $message", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant)
                    }
                },
            )
        }

        if (followedStories.isEmpty()) {
            addView(
                makeEmptyState(
                    context,
                    title = "Choose novels to follow",
                    message = "Pick the ongoing novels you want to check together.",
                    iconRes = R.drawable.wna_refresh,
                    actionLabel = "Choose novels",
                    onAction = { showUpdateFollowSelection() },
                ),
            )
            return@screen
        }

        if (updatedChapterCount == 0) {
            addView(
                makeEmptyState(
                    context,
                    title = "No updated chapters",
                    message = "Sync your followed novels to check whether anything new is available.",
                    iconRes = R.drawable.wna_check,
                ),
            )
        } else {
            section("Updated Chapters")
            followedStories
                .filter { UpdateTrackerPlanning.updatedChapters(it, syncedUpdatedChapterIds[it.id]).isNotEmpty() }
                .forEach { story ->
                    addView(makeUpdatedStoryCard(story, syncedUpdatedChapterIds[story.id]))
                }
        }
    }
}

internal fun ScreenHost.showUpdateFollowSelection() {
    activeStory = null
    rerender = { showUpdateFollowSelection() }
    val stories = repository.library().sortedBy { it.title.lowercase() }
    val selected = storage.getUpdateFollowedStoryIds().toMutableSet()
    selected.retainAll(stories.map { it.id }.toSet())
    storage.saveUpdateFollowedStoryIds(selected.toList())
    val followState = updateFollowSelectionState

    screen(
        title = "Follow Updates",
        subtitle = "${selected.size} selected",
        onBack = { showUpdates() },
        scrollable = true,
    ) {
        if (stories.isEmpty()) {
            addView(
                makeEmptyState(
                    context,
                    title = "Your library is empty",
                    message = "Import stories before setting up update tracking.",
                    iconRes = R.drawable.wna_menu_book,
                ),
            )
            return@screen
        }

        row {
            button("Select All", Btn.TONAL) {
                // Select All respects the active search: only currently visible (filtered) novels
                // are added, so a search followed by "Select All" selects just the matches.
                val visibleIds = filterFollowStories(stories, followState.query).map { it.id }.toSet()
                selected.addAll(visibleIds)
                storage.saveUpdateFollowedStoryIds(selected.toList())
                showUpdateFollowSelection()
            }
            button("Clear", Btn.OUTLINED) {
                // Clear mirrors Select All: only removes what's currently visible so a search can
                // scope a bulk deselect without touching filtered-out novels.
                val visibleIds = filterFollowStories(stories, followState.query).map { it.id }.toSet()
                selected.removeAll(visibleIds)
                storage.saveUpdateFollowedStoryIds(selected.toList())
                showUpdateFollowSelection()
            }
        }

        // Filtered list container. Declared before the search field/toggle so their callbacks can
        // reference renderList() (a local fun) which rebuilds only this container in place — keeping
        // the EditText focused and the cursor intact while typing. Mirrors the Library screen's
        // single-tab TextWatcher pattern.
        val list =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }

        fun renderList() {
            list.removeAllViews()
            val filtered = filterFollowStories(stories, followState.query)
            if (filtered.isEmpty()) {
                list.addView(
                    makeEmptyState(
                        context,
                        title = "No matches",
                        message =
                            if (followState.query.isBlank()) {
                                "No novels to show."
                            } else {
                                "No novels match \"${followState.query}\"."
                            },
                        iconRes = R.drawable.wna_search,
                    ),
                )
                return
            }
            filtered.forEach { story ->
                list.addView(
                    makeFollowRow(story, story.id in selected, followState.showCovers) { checked ->
                        if (checked) selected.add(story.id) else selected.remove(story.id)
                        storage.saveUpdateFollowedStoryIds(selected.toList())
                    },
                )
            }
        }

        // Search field. Seeded from followState so a config-change re-render keeps the typed query.
        val search =
            makeSearchField(context, "Search novels").apply {
                setText(followState.query)
                addTextChangedListener(
                    object : TextWatcher {
                        override fun beforeTextChanged(
                            s: CharSequence?,
                            start: Int,
                            count: Int,
                            after: Int,
                        ) = Unit

                        override fun onTextChanged(
                            s: CharSequence?,
                            start: Int,
                            before: Int,
                            count: Int,
                        ) {
                            followState.query = s?.toString().orEmpty()
                            renderList()
                        }

                        override fun afterTextChanged(s: Editable?) = Unit
                    },
                )
            }
        addView(search)

        addView(makeShowCoversToggleRow(followState) { renderList() })

        addView(list)
        renderList()
    }
}

private fun ScreenHost.makeUpdatedStoryCard(
    story: Story,
    chapterIds: List<String>?,
): LinearLayout =
    makeCard(app).apply {
        layoutParams =
            LinearLayout
                .LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = dp(Space.MD)
                }
        row {
            val titleColumn =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        makeText(context, story.title, Type.TITLE_MEDIUM, ThemeManager.colors.onSurface).apply {
                            maxLines = 2
                            ellipsize = TextUtils.TruncateAt.END
                        },
                    )
                    addView(makeText(context, "by ${story.author}", Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant))
                }
            addView(titleColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            button("Open", Btn.TEXT) { showDetails(story.id) }
        }
        UpdateTrackerPlanning.updatedChapters(story, chapterIds).forEach { update ->
            addView(makeUpdatedChapterRow(story, update.index, update.chapter))
        }
    }

private fun ScreenHost.makeUpdatedChapterRow(
    story: Story,
    index: Int,
    chapter: Chapter,
): View {
    val bookmarked = story.lastReadChapterId == chapter.id
    val colors = ThemeManager.colors
    return LinearLayout(app).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        background = selectableRipple(colors.onSurface)
        setPadding(dp(Space.MD), dp(Space.SM), dp(Space.SM), dp(Space.SM))
        setOnClickListener { showReader(story.id, chapter.id) }
        layoutParams =
            LinearLayout
                .LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(Space.XS)
                }

        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    makeText(context, chapter.title, Type.BODY_MEDIUM, colors.onSurface).apply {
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                    },
                )
                addView(makeText(context, "Chapter ${index + 1}", Type.BODY_SMALL, colors.onSurfaceVariant))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            ImageView(context).apply {
                contentDescription = if (bookmarked) "Clear bookmark" else "Bookmark chapter"
                setImageDrawable(
                    context.tintedIcon(
                        if (bookmarked) R.drawable.wna_bookmark else R.drawable.wna_bookmark_outline,
                        if (bookmarked) colors.primary else colors.onSurfaceVariant,
                    ),
                )
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(Space.SM), dp(Space.SM), dp(Space.SM), dp(Space.SM))
                background = selectableRipple(colors.onSurface)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    toggleUpdatedChapterBookmark(story.id, chapter.id)
                }
            },
            LinearLayout.LayoutParams(dp(44), dp(44)),
        )
    }
}

private fun ScreenHost.toggleUpdatedChapterBookmark(
    storyId: String,
    chapterId: String,
) {
    val latest = storage.getStory(storyId) ?: return
    val chapter = latest.chapters.firstOrNull { it.id == chapterId } ?: return
    val wasBookmarked = latest.lastReadChapterId == chapterId
    storage.addOrUpdateStory(StoryBookmarkPlanning.withBookmark(latest, chapterId, toggleExisting = true))
    repository.publishDownloadState(libraryChanged = true, queueChanged = false)
    toast(if (wasBookmarked) "Bookmark cleared" else "Bookmarked ${chapter.title}")
    showUpdates()
}

@Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
private fun ScreenHost.syncFollowedUpdates(onProgress: () -> Unit) {
    val state = updateTrackerScreenState
    if (state.syncing) return toast("Sync already running")
    val stories = repository.library()
    val toSync = UpdateTrackerPlanning.syncableFollowedStories(stories, storage.getUpdateFollowedStoryIds())
    if (toSync.isEmpty()) return toast("Choose at least one syncable novel")
    state.reset(toSync.size)
    onProgress()
    val renderedRoot = frame.getChildAt(0)
    scope.launch {
        UpdateTrackerPlanning.syncBatches(toSync, UPDATE_SYNC_CONCURRENCY).forEach { batch ->
            coroutineScope {
                batch
                    .map { story ->
                        launch {
                            syncFollowedUpdateStory(story, state, renderedRoot, onProgress)
                        }
                    }.joinAll()
            }
        }
        withContext(Dispatchers.Main) {
            state.finish()
            repository.publishDownloadState(libraryChanged = true, queueChanged = true)
            if (renderedRoot.parent === frame) showUpdates()
        }
    }
}

@Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
private suspend fun ScreenHost.syncFollowedUpdateStory(
    story: Story,
    state: UpdateTrackerScreenState,
    renderedRoot: View,
    onProgress: () -> Unit,
) {
    state.inFlight[story.id] = InFlightStorySync(story.title)
    try {
        if (renderedRoot.parent === frame) onProgress()
        val existingBeforeSync = withContext(Dispatchers.IO) { storage.getStory(story.id) }
        val synced =
            withContext(Dispatchers.IO) {
                syncEngine.fetchOrSync(
                    story.sourceUrl,
                    story.tabId,
                    refreshPatreonStats = false,
                ) { message ->
                    app.runOnUiThread {
                        state.inFlight[story.id]?.status = message
                        if (renderedRoot.parent === frame) onProgress()
                    }
                }
            }
        repository.publishDownloadState(libraryChanged = true, queueChanged = false)
        state.syncedUpdatedChapterIds[story.id] = synced.pendingNewChapterIds.orEmpty().distinct()
        queuePendingNewDownloads(existingBeforeSync, synced)
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        Timber.w(error, "Batch update sync failed for %s", story.id)
        state.errors[story.id] = error.message ?: "Sync failed"
    } finally {
        state.inFlight.remove(story.id)
        state.completed += 1
        if (renderedRoot.parent === frame) onProgress()
    }
}

private fun ScreenHost.queuePendingNewDownloads(
    existingBeforeSync: Story?,
    synced: Story,
) {
    if (existingBeforeSync == null || synced.pendingNewChapterIds.isNullOrEmpty()) return
    val pending = synced.pendingNewChapterIds.orEmpty().toSet()
    val indexes =
        synced.chapters.mapIndexedNotNull { index, chapter ->
            if (chapter.id in pending && !chapter.downloaded) index else null
        }
    if (indexes.isNotEmpty()) queueDownload(synced, indexes)
}

private fun UpdateTrackerScreenState.progressText(): String {
    if (!syncing) return "Ready to check followed novels."
    // completed + inFlight.size can momentarily exceed total near the end (a story finishes and
    // increments completed before its inFlight entry is removed); clamp so the label never shows a
    // number greater than total.
    val active = (completed + inFlight.size).coerceAtMost(total)
    val current = inFlight.values.firstOrNull()
    return if (current == null) {
        "Syncing $active/$total..."
    } else {
        "Syncing $active/$total: ${current.title} · ${current.status}"
    }
}

private fun plural(count: Int): String = if (count == 1) "" else "s"

/**
 * Case-insensitive title/author substring filter for the Follow Updates list. Mirrors
 * [com.vinicius741.webnovelarchiver.feature.library.LibraryQuery]'s text predicate: a blank query
 * returns every story. The incoming list is assumed already sorted (by title) — filtering keeps
 * that order so the visible list doesn't reshuffle as the user types.
 */
private fun filterFollowStories(
    stories: List<Story>,
    query: String,
): List<Story> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return stories
    return stories.filter { story ->
        story.title.contains(trimmed, ignoreCase = true) ||
            story.author.contains(trimmed, ignoreCase = true)
    }
}

/**
 * The "Show covers" toggle row. A tappable surface with a label, subtitle, and trailing themed
 * CheckBox. Tapping flips the in-memory toggle, persists it to DisplayPreferences (so the choice
 * survives app restarts), then calls [onChanged] so the caller can re-render the list in place.
 */
private fun ScreenHost.makeShowCoversToggleRow(
    followState: UpdateFollowSelectionState,
    onChanged: () -> Unit,
): LinearLayout {
    val t = ThemeManager.current
    val radiusPx = app.dp(t.shapes.cardRadius).toFloat()
    val cb =
        CheckBox(app).apply {
            isChecked = followState.showCovers
            applyCheckBoxTint()
            setOnCheckedChangeListener { _, checked ->
                followState.showCovers = checked
                storage.saveDisplayPreferences(
                    storage.getDisplayPreferences().copy(showCoversOnUpdates = checked),
                )
                onChanged()
            }
        }
    val textCol =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                TextView(app).apply {
                    text = "Show covers"
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Type.TITLE_SMALL.size())
                    typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(t.colors.onSurface)
                    includeFontPadding = false
                },
            )
            addView(
                TextView(app).apply {
                    text = "Display each novel's cover thumbnail"
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Type.BODY_SMALL.size())
                    setTextColor(t.colors.onSurfaceVariant)
                    setPadding(0, app.dp(2), 0, 0)
                    includeFontPadding = false
                },
            )
        }
    return LinearLayout(app).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(app.dp(Space.MD), app.dp(Space.MD), app.dp(Space.LG), app.dp(Space.MD))
        background = roundedBg(t.colors.elevation1, radiusPx)
        layoutParams =
            LinearLayout
                .LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = app.dp(Space.SM)
                    bottomMargin = app.dp(Space.MD)
                }
        addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(cb)
        // Tapping the row (not just the checkbox) toggles.
        isClickable = true
        isFocusable = true
        setOnClickListener { cb.isChecked = !cb.isChecked }
    }
}

/**
 * One novel row in the Follow Updates list. When [showCovers] is false this is exactly
 * [makeSelectableCardRow] (the compact, cover-less look). When true, a leading 80×120 cover
 * thumbnail — built by the shared [coverImage] helper, so a missing coverUrl renders the same
 * branded placeholder the Library list uses — is inserted at the far left of the row.
 */
private fun ScreenHost.makeFollowRow(
    story: Story,
    isSelected: Boolean,
    showCovers: Boolean,
    onToggle: (Boolean) -> Unit,
): LinearLayout {
    val row =
        makeSelectableCardRow(
            app,
            title = story.title,
            subtitle = "by ${story.author}",
            selected = isSelected,
            onToggle = onToggle,
        )
    if (showCovers) {
        val cover = coverImage(story, widthDp = 80, heightDp = 120, tapToOpen = false)
        // coverImage already sets a right margin (Space.LG); insert it as the first child so the
        // cover sits to the left of the checkbox + text column.
        row.addView(cover, 0)
    }
    return row
}
