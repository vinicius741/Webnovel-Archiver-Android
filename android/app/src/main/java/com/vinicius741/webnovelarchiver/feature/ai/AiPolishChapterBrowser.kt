package com.vinicius741.webnovelarchiver.feature.ai

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.vinicius741.webnovelarchiver.ai.AiChapterPolishPlanning
import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeBadge
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeChip
import com.vinicius741.webnovelarchiver.ui.makeSearchField
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.roundCorners
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.toast

/*
 * Searchable, status-filtered chapter browser for Chapter Polish. Replaces the old inline
 * 200-row list (which capped out and re-read the manifest per row): statuses come in precomputed
 * from one manifest read, and only search matches are rendered, so thousand-chapter novels work.
 */

private const val MAX_RENDERED_CHAPTERS = 80

/** Search + status-filter picker over the story's downloaded chapters. */
internal fun ScreenHost.showPolishChapterBrowser(
    story: Story,
    statuses: Map<String, String>,
) {
    val colors = ThemeManager.colors
    val shapes = ThemeManager.shapes
    val downloaded =
        story.chapters
            .withIndex()
            .map { it.index to it.value }
            .filter { it.second.downloaded }

    val dialogView =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(app.dp(24), app.dp(20), app.dp(24), app.dp(12))
            background = roundedBg(colors.surface, app.dp(shapes.dialogRadius).toFloat())
            roundCorners(shapes.dialogRadius.toFloat())
        }
    dialogView.addView(makeText(app, "Polish chapters", Type.TITLE_LARGE, colors.onSurface))
    dialogView.addView(
        makeText(app, "Tap a chapter to polish it or open its polished draft.", Type.BODY_SMALL, colors.onSurfaceVariant).apply {
            setPadding(0, app.dp(Space.XS), 0, app.dp(Space.MD))
        },
    )
    val search = makeSearchField(app, "Search chapters")
    dialogView.addView(search)

    var filter = "all"
    val filterRow = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL }
    dialogView.addView(
        HorizontalScrollView(app).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, app.dp(Space.MD), 0, app.dp(Space.SM))
            addView(filterRow)
        },
    )

    val resultCount =
        makeText(app, "", Type.LABEL_MEDIUM, colors.onSurfaceVariant).apply {
            setPadding(0, 0, 0, app.dp(Space.SM))
        }
    dialogView.addView(resultCount)
    val list = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
    val maxListHeight = minOf(app.dp(380), (app.resources.displayMetrics.heightPixels * 0.48f).toInt())
    dialogView.addView(
        ScrollView(app).apply {
            isFillViewport = true
            addView(list)
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxListHeight),
    )

    var dialogRef: AlertDialog? = null

    fun renderRows() {
        val filtered =
            AiChapterPolishPlanning.filterChapters(downloaded, { id -> statuses[id] }, search.text.toString(), filter)
        resultCount.text = "${filtered.size} ${if (filtered.size == 1) "chapter" else "chapters"}"
        list.removeAllViews()
        filtered.take(MAX_RENDERED_CHAPTERS).forEach { (index, chapter) ->
            list.addView(
                chapterBrowserRow(app, index, chapter, statuses[chapter.id]) {
                    dialogRef?.dismiss()
                    onPolishChapterTapped(story, chapter, ChapterPolishRowStatus.fromTag(statuses[chapter.id]))
                },
            )
        }
        if (filtered.size > MAX_RENDERED_CHAPTERS) {
            list.addView(
                makeText(
                    app,
                    "...and ${filtered.size - MAX_RENDERED_CHAPTERS} more — refine your search",
                    Type.BODY_SMALL,
                    colors.onSurfaceVariant,
                ).apply { setPadding(0, app.dp(Space.SM), 0, app.dp(Space.SM)) },
            )
        } else if (filtered.isEmpty()) {
            list.addView(
                makeText(app, "No chapters match.", Type.BODY_MEDIUM, colors.onSurfaceVariant).apply {
                    setPadding(0, app.dp(Space.LG), 0, app.dp(Space.LG))
                },
            )
        }
    }

    fun renderFilters() {
        filterRow.removeAllViews()
        listOf(
            "All" to "all",
            "Draft ready" to "ready",
            "Flagged" to "flagged",
            "Polished" to "polished",
            "Not polished" to "unpolished",
        ).forEach { (label, tag) ->
            filterRow.addView(
                makeChip(app, label, filter == tag) {
                    filter = tag
                    renderFilters()
                    renderRows()
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = app.dp(Space.SM)
                },
            )
        }
    }

    search.doAfterTextChanged { renderRows() }
    renderRows()
    renderFilters()

    dialogView.addView(
        LinearLayout(app).apply {
            gravity = Gravity.END
            setPadding(0, app.dp(Space.MD), 0, 0)
            addView(makeButton(app, "Close", Btn.TEXT) { dialogRef?.dismiss() })
        },
    )
    dialogRef = AlertDialog.Builder(app).setView(dialogView).create()
    dialogRef.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    dialogRef.show()
}

/** "Chapter N + title" row with the polish-status badge; mirrors the old inline list's behavior. */
private fun chapterBrowserRow(
    context: Context,
    index: Int,
    chapter: Chapter,
    statusTag: String?,
    onTap: () -> Unit,
): LinearLayout {
    val colors = ThemeManager.colors
    val row =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, context.dp(Space.SM), 0, context.dp(Space.SM))
            isClickable = true
            isFocusable = true
            background = selectableRipple(colors.onSurface)
            setOnClickListener { onTap() }
        }
    val textCol =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(makeText(context, "Chapter ${index + 1}", Type.BODY_LARGE, colors.onSurface))
            chapter.title.takeIf { it.isNotBlank() }?.let { title ->
                addView(
                    makeText(context, title, Type.BODY_SMALL, colors.onSurfaceVariant).apply {
                        setPadding(0, context.dp(Space.XS), 0, 0)
                    },
                )
            }
        }
    row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    polishStatusBadge(context, statusTag)?.let { badge -> row.addView(badge) }
    return row
}

private fun polishStatusBadge(
    context: Context,
    statusTag: String?,
): TextView? {
    val colors = ThemeManager.colors
    val (label, bg, fg) =
        when (ChapterPolishRowStatus.fromTag(statusTag)) {
            ChapterPolishRowStatus.Generating -> Triple("Polishing…", colors.tertiaryContainer, colors.onTertiaryContainer)
            ChapterPolishRowStatus.Queued -> Triple("Queued", colors.surfaceVariant, colors.onSurfaceVariant)
            ChapterPolishRowStatus.DraftReady -> Triple("Draft ready", colors.tertiaryContainer, colors.onTertiaryContainer)
            ChapterPolishRowStatus.DraftBlocked -> Triple("Flagged", colors.errorContainer, colors.onErrorContainer)
            ChapterPolishRowStatus.AppliedActive -> Triple("Polished", colors.tertiaryContainer, colors.onTertiaryContainer)
            ChapterPolishRowStatus.AppliedInactive -> Triple("Polished (off)", colors.surfaceVariant, colors.onSurfaceVariant)
            null -> return null
        }
    return makeBadge(context, label, bg, fg)
}

/** Tap behavior shared with the old inline list: existing work opens the comparison screen. */
private fun ScreenHost.onPolishChapterTapped(
    story: Story,
    chapter: Chapter,
    status: ChapterPolishRowStatus?,
) {
    when (status) {
        ChapterPolishRowStatus.DraftReady,
        ChapterPolishRowStatus.DraftBlocked,
        ChapterPolishRowStatus.AppliedActive,
        ChapterPolishRowStatus.AppliedInactive,
        -> showChapterRewritePreview(story.id, chapter.id)
        ChapterPolishRowStatus.Generating -> toast("Already polishing this chapter")
        ChapterPolishRowStatus.Queued -> toast("Already queued for polishing")
        null -> confirmChapterPolish(story, chapter)
    }
}
