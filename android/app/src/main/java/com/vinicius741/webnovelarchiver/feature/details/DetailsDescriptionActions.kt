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

private const val DESCRIPTION_DOUBLE_TAP_COPY_WINDOW_MS = 300L

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
            // Slight line spacing so the \n\n paragraph breaks read as a real gap.
            setLineSpacing(dp(Space.XS).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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
    // Listen stays hidden until expanded — only the full description is worth hearing. makeButton's
    // 16dp horizontal padding is meant for chrome buttons; strip the outer edge so these inline
    // labels align with the description text, keeping half inside as a touch pad.
    val padV = dp(Space.SM + 2)
    val padH = dp(Space.LG)
    val listenButton = makeButton(app, "Listen", Btn.TEXT, R.drawable.wna_speaker) { toggleDescriptionTts(story) }
    // WRAP_CONTENT so icon and label don't split to opposite edges.
    listenButton.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    listenButton.setPadding(padH / 2, padV, 0, padV)
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
        toggle.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        toggle.setPadding(0, padV, padH / 2, padV)
        toggle.setOnClickListener {
            expanded = !expanded
            descText.text = if (expanded) description else truncateDescription(description)
            toggle.text = if (expanded) "Read less" else "Read more"
            listenButton.visibility = if (expanded) View.VISIBLE else View.GONE
        }
        actionsRow.addView(toggle)
        // Flexible spacer pushing Listen to the trailing edge. Plain View, not
        // android.widget.Space, to avoid clashing with the ui.Space tokens.
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
