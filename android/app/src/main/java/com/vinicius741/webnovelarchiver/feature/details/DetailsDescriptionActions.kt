package com.vinicius741.webnovelarchiver.feature.details

import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.DESCRIPTION_PREVIEW_LENGTH
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.copyToClipboard
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.toast

private const val DESCRIPTION_DOUBLE_TAP_COPY_WINDOW_MS = 300L
private const val COLLAPSED_LINES = 6

/**
 * The synopsis, clamped to six lines with Read more / Read less. Double-tap or long-press copies
 * it. Listen sits in the About header ([addDetailsAbout]).
 */
internal fun ScreenHost.addDescriptionText(
    container: LinearLayout,
    story: Story,
    description: String,
) {
    val canExpand = description.length > DESCRIPTION_PREVIEW_LENGTH || description.count { it == '\n' } >= COLLAPSED_LINES
    var expanded = false
    val copyDescription = {
        copyToClipboard(story.title, description)
        toast("Description copied")
    }
    val descText =
        makeText(app, description, Type.BODY_MEDIUM, ThemeManager.colors.onSurface).apply {
            gravity = Gravity.START
            alpha = DESCRIPTION_ALPHA
            setLineSpacing(dp(Spacing.XS).toFloat(), 1f)
            if (canExpand) {
                maxLines = COLLAPSED_LINES
                ellipsize = TextUtils.TruncateAt.END
            }
            isClickable = true
            isLongClickable = true
            isFocusable = true
            background = selectableRipple(ThemeManager.colors.onSurface)
            var lastTap = 0L
            setOnClickListener {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastTap < DESCRIPTION_DOUBLE_TAP_COPY_WINDOW_MS) copyDescription()
                lastTap = now
            }
            setOnLongClickListener {
                copyDescription()
                true
            }
        }
    container.addView(descText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    if (!canExpand) return
    val toggle: Button = makeButton(app, "Read more", Btn.TEXT) {}
    toggle.setPadding(0, toggle.paddingTop, dp(Spacing.LG), toggle.paddingBottom)
    toggle.setOnClickListener {
        expanded = !expanded
        descText.maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES
        descText.ellipsize = if (expanded) null else TextUtils.TruncateAt.END
        toggle.text = if (expanded) "Read less" else "Read more"
    }
    container.addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
}

private const val DESCRIPTION_ALPHA = 0.9f
