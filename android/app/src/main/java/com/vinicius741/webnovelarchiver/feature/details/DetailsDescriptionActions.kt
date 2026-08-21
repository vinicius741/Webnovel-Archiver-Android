package com.vinicius741.webnovelarchiver.feature.details

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.Story
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.DESCRIPTION_PREVIEW_LENGTH
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.copyToClipboard
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.toast
import com.vinicius741.webnovelarchiver.ui.truncateDescription

/**
 * Window (ms) within which two taps on the description copy it to the clipboard (300ms
 * double-press gesture).
 */
private const val DESCRIPTION_DOUBLE_TAP_COPY_WINDOW_MS = 300L

/** Renders the description text (copy gestures + expand/collapse) and its "Listen" button. */
internal fun ScreenHost.addDescriptionTextAndListen(
    descCol: LinearLayout,
    story: Story,
    description: String,
): Button? {
    val canExpand = description.length > DESCRIPTION_PREVIEW_LENGTH
    var expanded = false
    val copyDescription = {
        copyToClipboard(story.title, description)
        toast("Description copied")
    }
    val descText =
        makeText(
            app,
            if (canExpand) truncateDescription(description) else description,
            Type.BODY_MEDIUM,
            ThemeManager.colors.onSurfaceVariant,
        ).apply {
            gravity = Gravity.START
            // Descriptions keep the source's paragraph/line structure (Sources.blockText).
            // Add a touch of inter-line spacing so the \n\n between paragraphs reads as a
            // real gap instead of a single break.
            setLineSpacing(dp(Space.XS).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            // Copy the description on a double-tap (mirrors RN StoryDescription's 300ms
            // double-press → Clipboard.setStringAsync) or on a long press. A ripple gives
            // touch feedback that the text is tappable.
            isClickable = true
            isLongClickable = true
            isFocusable = true
            background = selectableRipple(ThemeManager.colors.onSurface)
            var lastTap = 0L
            setOnClickListener {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastTap < DESCRIPTION_DOUBLE_TAP_COPY_WINDOW_MS) {
                    copyDescription()
                }
                lastTap = now
            }
            setOnLongClickListener {
                copyDescription()
                true
            }
        }
    descCol.addView(descText)
    // Description-TTS engagement lives directly under the text it speaks; the same button becomes
    // Pause / Resume while this story's session is active, and a docked transport bar (added by
    // showDetails) offers prev/next/stop. Hidden behind the truncated preview until expanded —
    // only the full description is worth listening to.
    // makeButton applies a 16dp horizontal padding (Space.LG) meant for chrome buttons. These
    // inline text buttons live flush against the description text, so that padding reads as a
    // stray indent pulling the labels off the screen's content edge. Strip the leading edge
    // (toggle) / trailing edge (Listen) so the labels line up with the description glyphs; keep
    // half on the inner side as a touch pad.
    val padV = dp(Space.SM + 2)
    val padH = dp(Space.LG)
    val listenButton = makeButton(app, "Listen", Btn.TEXT, R.drawable.wna_speaker) { toggleDescriptionTts(story) }
    // Keep the button compact: a default (MATCH_PARENT) width would push the icon to the far
    // edge while the label centers — a wide gap between them.
    listenButton.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    // Docked to the trailing edge: trim the trailing padding so the icon+label reach the
    // content edge like the description text, keep half on the inner side as a touch pad.
    listenButton.setPadding(padH / 2, padV, 0, padV)
    // Group the expand toggle and Listen button on a single row so Listen no longer floats
    // between the description text and the toggle. The toggle (the primary text control) sits
    // at the leading edge; Listen docks to the trailing edge so the two read as a balanced
    // pair of description actions.
    val actionsRow =
        LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
        }
    if (canExpand) {
        listenButton.visibility = View.GONE
        val toggle: Button = makeButton(app, "Read more", Btn.TEXT, 0) {}
        toggle.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        // Start-align the label so its glyphs share the description's leading edge; the
        // trailing padding remains a comfortable touch target.
        toggle.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        toggle.setPadding(0, padV, padH / 2, padV)
        toggle.setOnClickListener {
            expanded = !expanded
            descText.text = if (expanded) description else truncateDescription(description)
            toggle.text = if (expanded) "Read less" else "Read more"
            listenButton.visibility = if (expanded) View.VISIBLE else View.GONE
        }
        actionsRow.addView(toggle)
        // Flexible spacer so Listen docks to the trailing edge; it collapses to nothing while
        // Listen is GONE, leaving "Read more/less" at the leading edge. (Plain View, not
        // android.widget.Space, to avoid clashing with the ui.Space spacing tokens.)
        actionsRow.addView(
            View(app),
            LinearLayout.LayoutParams(0, 1, 1f),
        )
        actionsRow.addView(listenButton)
    } else {
        actionsRow.addView(listenButton)
    }
    descCol.addView(actionsRow)
    return listenButton
}
