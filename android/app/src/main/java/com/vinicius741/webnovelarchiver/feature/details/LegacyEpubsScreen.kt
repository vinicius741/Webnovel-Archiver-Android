package com.vinicius741.webnovelarchiver.feature.details

import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.epub.EpubSelection
import com.vinicius741.webnovelarchiver.feature.library.showLibrary
import com.vinicius741.webnovelarchiver.feature.story.openFile
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeEmptyState
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.size
import com.vinicius741.webnovelarchiver.ui.strokeBg
import com.vinicius741.webnovelarchiver.ui.toast
import com.vinicius741.webnovelarchiver.ui.confirm
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Surfaces every `.epub` file physically stored for a story, so files abandoned by write-only
 * EPUB regeneration (see [com.vinicius741.webnovelarchiver.epub.EpubEngine.generate]) can be
 * reviewed and reclaimed. Files are grouped into two sections:
 *  - **Current EPUBs** — still referenced by [com.vinicius741.webnovelarchiver.domain.model.Story.epubPaths],
 *    i.e. what the "Read EPUB" button opens. These may be viewed but not deleted here (deleting a
 *    referenced file would dangle the active EPUB; deleting is blocked at both the UI and
 *    [com.vinicius741.webnovelarchiver.data.storage.AppStorage.deleteEpubFile] boundaries).
 *  - **Leftover files** — present on disk but no longer referenced. These are safe to delete.
 *
 * Every row offers **See** (opens the EPUB via the existing FileProvider path) and, for leftovers,
 * **Delete** (confirm then remove from disk).
 */
internal fun ScreenHost.showLegacyEpubs(storyId: String) {
    val story = storage.getStory(storyId) ?: return showLibrary()

    val onDisk = storage.listEpubs(storyId)
    // story.epubPaths may hold absolute OR relative paths (relative is the on-disk norm after
    // migrateChapterPaths relativizes them). Resolve each to an absolute File via the storage layer
    // so the comparison against on-disk absolute paths is apples-to-apples — otherwise a stored
    // "epubs/<id>/x.epub" would never match "/data/.../epubs/<id>/x.epub" and every file would read
    // as a leftover.
    val referenced =
        (story.epubPaths?.filter { it.isNotBlank() } ?: listOfNotNull(story.epubPath))
            .mapNotNull { storage.resolveAbsolutePath(it)?.absolutePath }
            .toSet()
    val current = onDisk.filter { it.absolutePath in referenced }
    // Leftovers are shown newest-first (inverse of listEpubs' oldest-first order) so the most
    // recently abandoned files — usually the ones a user just regenerated past — surface at the top
    // for cleanup. Current EPUBs keep oldest-first to match the "Read EPUB" reading order.
    val leftover = onDisk.filter { it.absolutePath !in referenced }.asReversed()

    screen(title = "EPUB Files", subtitle = story.title, onBack = { showDetails(story.id) }) {
        if (onDisk.isEmpty()) {
            addView(
                makeEmptyState(
                    context,
                    title = "No EPUB files",
                    message = "Generate an EPUB from the details screen to see it here.",
                    iconRes = R.drawable.wna_book_open,
                ),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
            return@screen
        }

        val list =
            RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context)
                itemAnimator = null
                clipToPadding = false
                setPadding(0, context.dp(Space.SM), 0, context.dp(Space.SM))
            }
        list.adapter = LegacyEpubsAdapter(current, leftover, onSee = { openFile(it.absolutePath) }, onDelete = { file -> deleteEpub(storyId, file) })
        addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }
}

/** Confirms and deletes a leftover EPUB, then re-renders the screen so counts and rows update. */
private fun ScreenHost.deleteEpub(
    storyId: String,
    file: File,
) {
    val name = EpubSelection.displayNameForPath(file.absolutePath)
    confirm(
        message = "Delete \"$name\"? This leftover file will be removed permanently.",
        confirmLabel = "Delete",
    ) {
        if (storage.deleteEpubFile(storyId, file.absolutePath)) {
            toast("Deleted")
        } else {
            toast("Could not delete file")
        }
        showLegacyEpubs(storyId)
    }
}

/** A single file shown in the list, tagged with whether the story still references it. */
internal data class LegacyEpubItem(
    val file: File,
    val isReferenced: Boolean,
)

private const val VIEW_TYPE_SECTION = 0
private const val VIEW_TYPE_FILE = 1

internal class LegacyEpubsAdapter(
    current: List<File>,
    leftover: List<File>,
    private val onSee: (File) -> Unit,
    private val onDelete: (File) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    // Build a flat list of rows: a section header followed by its files. Sections with no files are
    // omitted (so a story with only referenced files shows just the Current section, and vice-versa).
    private val rows: List<Row> =
        buildList {
            if (current.isNotEmpty()) {
                add(Row.Section("Current EPUBs", current.size))
                current.forEach { add(Row.File(LegacyEpubItem(it, isReferenced = true))) }
            }
            if (leftover.isNotEmpty()) {
                add(Row.Section("Leftover files", leftover.size))
                leftover.forEach { add(Row.File(LegacyEpubItem(it, isReferenced = false))) }
            }
        }

    private sealed interface Row {
        data class Section(
            val title: String,
            val count: Int,
        ) : Row

        data class File(val item: LegacyEpubItem) : Row
    }

    override fun getItemViewType(position: Int): Int =
        when (rows[position]) {
            is Row.Section -> VIEW_TYPE_SECTION
            is Row.File -> VIEW_TYPE_FILE
        }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder {
        val context = parent.context
        return when (viewType) {
            VIEW_TYPE_SECTION -> SectionHolder(buildSectionHeader(context))
            else -> FileHolder(buildFileRow(context))
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {
        when (val row = rows[position]) {
            is Row.Section -> (holder as SectionHolder).bind(row.title, row.count)
            is Row.File -> (holder as FileHolder).bind(row.item, onSee, onDelete)
        }
    }

    private fun buildSectionHeader(context: android.content.Context): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams =
                RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

    private fun buildFileRow(context: android.content.Context): LinearLayout {
        val radius = context.dp(ThemeManager.shapes.cardRadius).toFloat()
        val title =
            TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.TITLE_SMALL.size())
                typeface = Typeface.create(typeface, Typeface.BOLD)
                setTextColor(ThemeManager.colors.onSurface)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
            }
        val subtitle =
            TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.BODY_SMALL.size())
                setTextColor(ThemeManager.colors.onSurfaceVariant)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
            }
        // Dedicated line for the file's created/modified timestamp (same source listEpubs sorts by),
        // kept on its own row so the Active/Leftover · size subtitle never has to truncate it on
        // narrow screens.
        val created =
            TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.BODY_SMALL.size())
                setTextColor(ThemeManager.colors.onSurfaceVariant)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
            }
        // See is always available (both referenced and leftover files can be opened). Delete is added
        // per-row in bind() for leftovers only — referenced files must not be deletable here.
        val seeButton =
            makeButton(context, "See", Btn.TEXT, R.drawable.wna_book_open) { }
        val deleteButton = makeButton(context, "Delete", Btn.TEXT, R.drawable.wna_delete) { }

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = context.dp(64)
            setPadding(context.dp(Space.MD), context.dp(Space.SM), context.dp(Space.MD), context.dp(Space.SM))
            isClickable = false
            isFocusable = false
            layoutParams =
                RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = context.dp(Space.SM)
                }
            // Card surface matching ChapterSelectionAdapter's leftover/neutral rows.
            background = ripple(roundedBg(ThemeManager.colors.elevation1, radius), radius, ThemeManager.colors.onSurface)
            addView(title)
            addView(
                subtitle,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = context.dp(Space.XS)
                },
            )
            addView(
                created,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = context.dp(Space.XS)
                },
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    addView(seeButton)
                    addView(
                        deleteButton,
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            marginStart = context.dp(Space.XS)
                        },
                    )
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = context.dp(Space.XS)
                },
            )
            tag = FileHolderTags(title, subtitle, created, seeButton, deleteButton)
        }
    }

    private class FileHolderTags(
        val title: TextView,
        val subtitle: TextView,
        val created: TextView,
        val seeButton: android.widget.Button,
        val deleteButton: android.widget.Button,
    )

    internal class SectionHolder(val root: LinearLayout) : RecyclerView.ViewHolder(root) {
        fun bind(
            title: String,
            count: Int,
        ) {
            root.removeAllViews()
            val colors = ThemeManager.colors
            root.addView(
                TextView(root.context).apply {
                    text = "$title ($count)"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.LABEL_MEDIUM.size())
                    typeface = Typeface.create(typeface, Typeface.BOLD)
                    setTextColor(colors.primary)
                    setPadding(root.context.dp(Space.MD), root.context.dp(Space.SM), 0, root.context.dp(Space.XS))
                },
            )
        }
    }

    internal class FileHolder(val root: LinearLayout) : RecyclerView.ViewHolder(root) {
        @Suppress("UNCHECKED_CAST")
        private val tags: FileHolderTags get() = root.tag as FileHolderTags

        fun bind(
            item: LegacyEpubItem,
            onSee: (File) -> Unit,
            onDelete: (File) -> Unit,
        ) {
            val colors = ThemeManager.colors
            val radius = root.context.dp(ThemeManager.shapes.cardRadius).toFloat()
            val file = item.file
            val tags = tags
            tags.title.text = EpubSelection.displayNameForPath(file.absolutePath)
            val badge = if (item.isReferenced) "Active" else "Leftover"
            tags.subtitle.text = "$badge · ${formatBytes(file.length())}"
            tags.created.text = "Created ${formatEpubDate(file.lastModified())}"
            // Referenced files keep the neutral card; leftovers get an outlined surface so they read
            // as actionable clean-up candidates (mirrors ChapterSelectionAdapter's selected stroke).
            root.background =
                if (item.isReferenced) {
                    ripple(roundedBg(colors.elevation1, radius), radius, colors.onSurface)
                } else {
                    ripple(strokeBg(colors.elevation1, radius, colors.outline, root.context.dp(1)), radius, colors.onSurface)
                }
            tags.seeButton.setOnClickListener { onSee(file) }
            // Delete is only wired/enabled for leftover files. Referenced files must never be deleted
            // from here — that would dangle the story's active EPUB.
            if (item.isReferenced) {
                tags.deleteButton.visibility = android.view.View.GONE
                tags.deleteButton.setOnClickListener(null)
            } else {
                tags.deleteButton.visibility = android.view.View.VISIBLE
                tags.deleteButton.setOnClickListener { onDelete(file) }
            }
            root.contentDescription = "${tags.title.text}, $badge"
        }
    }
}

/** Human-readable file size for the row subtitle (e.g. "1.4 MB", "640 KB"). File-local helper. */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1 -> String.format("%.1f MB", mb)
        kb >= 1 -> String.format("%d KB", kb.toInt())
        else -> "$bytes B"
    }
}

/**
 * Formats the EPUB's last-modified timestamp as "MMM d, yyyy · h:mm a" (e.g. "Jul 4, 2026 · 3:45 PM")
 * for the row. Uses the same [DateTimeFormatter]/system-zone approach as [formatPatreonDate].
 * File-local helper.
 */
private fun formatEpubDate(timestampMillis: Long): String {
    val instant = Instant.ofEpochMilli(timestampMillis)
    val datePart = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US).format(instant.atZone(ZoneId.systemDefault()))
    val timePart = DateTimeFormatter.ofPattern("h:mm a", Locale.US).format(instant.atZone(ZoneId.systemDefault()))
    return "$datePart · $timePart"
}
