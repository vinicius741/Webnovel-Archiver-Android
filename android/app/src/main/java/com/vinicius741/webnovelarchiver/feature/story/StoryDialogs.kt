package com.vinicius741.webnovelarchiver.feature.story

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.EpubConfig
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.feature.details.showDetails
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.*
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.ZoomableImageView
import com.vinicius741.webnovelarchiver.ui.applyAppTheme
import com.vinicius741.webnovelarchiver.ui.copyToClipboard
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.loadImage
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.ripple
import com.vinicius741.webnovelarchiver.ui.roundedBg
import com.vinicius741.webnovelarchiver.ui.scroll
import com.vinicius741.webnovelarchiver.ui.size
import com.vinicius741.webnovelarchiver.ui.styledCheckBox
import com.vinicius741.webnovelarchiver.ui.styledDialogField
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import com.vinicius741.webnovelarchiver.ui.toast

internal fun ScreenHost.showEpubConfigDialog(story: Story) {
    if (story.chapters.isEmpty()) return toast("No chapters available")
    val current =
        story.epubConfig ?: EpubConfig(
            maxChaptersPerEpub = storage.getSettings().maxChaptersPerEpub,
            rangeStart = 1,
            rangeEnd = story.chapters.size,
            startAfterBookmark = false,
        )
    val view =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(12))
        }
    val maxChapters = styledDialogField(current.maxChaptersPerEpub.toString(), "Max chapters per EPUB", InputType.TYPE_CLASS_NUMBER)
    val rangeStart = styledDialogField(current.rangeStart.toString(), "From chapter", InputType.TYPE_CLASS_NUMBER)
    val rangeEnd =
        styledDialogField(
            current.rangeEnd
                .coerceAtLeast(1)
                .coerceAtMost(story.chapters.size)
                .toString(),
            "To chapter",
            InputType.TYPE_CLASS_NUMBER,
        )
    val startAfterBookmark =
        CheckBox(app).apply {
            text = "Start after bookmark"
            isChecked = current.startAfterBookmark && story.lastReadChapterId != null
            isEnabled = story.lastReadChapterId != null
        }
    styledCheckBox(startAfterBookmark)
    view.addView(maxChapters)
    view.addView(rangeStart)
    view.addView(rangeEnd)
    view.addView(startAfterBookmark)
    // DL2: explain why "Start after bookmark" is disabled when there is no bookmark.
    if (story.lastReadChapterId == null) {
        view.addView(
            makeText(
                app,
                "Set a bookmark by reading a chapter to enable \"Start after bookmark.\"",
                Type.BODY_SMALL,
                ThemeManager.colors.onSurfaceVariant,
            ).apply {
                setPadding(0, dp(4), 0, 0)
            },
        )
    }
    view.addView(
        makeText(
            app,
            "Downloaded chapters: ${story.chapters.count { it.downloaded }}. EPUB generation includes only downloaded chapters in range.",
            Type.BODY_SMALL,
            ThemeManager.colors.onSurfaceVariant,
        ).apply {
            setPadding(0, dp(8), 0, 0)
        },
    )

    val dialog =
        AlertDialog
            .Builder(app)
            .setTitle("EPUB Settings")
            .setView(scroll(view))
            .setPositiveButton("Save") { _, _ ->
                val max =
                    maxChapters.text
                        .toString()
                        .toIntOrNull()
                        ?.coerceIn(10, 1000) ?: 150
                val start =
                    rangeStart.text
                        .toString()
                        .toIntOrNull()
                        ?.coerceIn(1, story.chapters.size) ?: 1
                val end =
                    rangeEnd.text
                        .toString()
                        .toIntOrNull()
                        ?.coerceIn(start, story.chapters.size) ?: story.chapters.size
                val config =
                    EpubConfig(
                        maxChaptersPerEpub = max,
                        rangeStart = start,
                        rangeEnd = end,
                        startAfterBookmark = startAfterBookmark.isChecked && story.lastReadChapterId != null,
                    )
                story.epubConfig = config
                storage.addOrUpdateStory(story)
                toast("EPUB settings saved")
                showDetails(story.id)
            }.setNegativeButton("Cancel", null)
            .create()
    dialog.show()
    dialog.applyAppTheme()
}

internal fun ScreenHost.showDescriptionDialog(
    title: String,
    description: String,
) {
    val view =
        makeText(app, description, Type.BODY_MEDIUM, ThemeManager.colors.onSurface).apply {
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
    val dialog =
        AlertDialog
            .Builder(app)
            .setTitle(title)
            .setView(scroll(view))
            .setPositiveButton("Copy") { _, _ ->
                copyToClipboard("Story description", description)
                toast("Description copied")
            }.setNegativeButton("Close", null)
            .create()
    dialog.show()
    dialog.applyAppTheme()
}

internal fun ScreenHost.showCoverDialog(story: Story) {
    val url = story.coverUrl?.takeIf { it.isNotBlank() } ?: return
    val dialog =
        Dialog(app).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
        }
    val image =
        ZoomableImageView(app).apply {
            contentDescription = "${story.title} cover"
            setBackgroundColor(Color.BLACK)
        }
    loadImage(url, image)

    val closeButton =
        ImageView(app).apply {
            contentDescription = "Close"
            setImageDrawable(app.tintedIcon(R.drawable.wna_close, Color.WHITE))
            scaleType = ImageView.ScaleType.CENTER
            isClickable = true
            isFocusable = true
            background =
                ripple(
                    roundedBg(Color.TRANSPARENT, dp(24).toFloat()),
                    dp(24).toFloat(),
                    0x33FFFFFF,
                )
            setOnClickListener { dialog.dismiss() }
        }
    val title =
        TextView(app).apply {
            text = story.title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Type.TITLE_MEDIUM.size())
            typeface = Typeface.create(typeface, Typeface.BOLD)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
    val topBar =
        LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(8), dp(12))
            setBackgroundColor(Color.argb(196, 0, 0, 0))
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                closeButton,
                LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                    marginStart = dp(12)
                },
            )
        }
    val root =
        FrameLayout(app).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                image,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                topBar,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP,
                ),
            )
        }

    dialog.setContentView(root)
    dialog.show()
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.BLACK))
        setDimAmount(0.82f)
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        statusBarColor = Color.BLACK
        navigationBarColor = Color.BLACK
    }
}
