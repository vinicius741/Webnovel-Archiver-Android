package com.vinicius741.webnovelarchiver.ui

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.TtsVoicePlanning
import com.vinicius741.webnovelarchiver.tts.VoiceInfo

/** Applies the active app theme to framework AlertDialogs after they have been shown. */
internal fun AlertDialog.applyAppTheme() {
    val colors = ThemeManager.colors
    val radius = context.dp(ThemeManager.shapes.dialogRadius).toFloat()

    window?.setBackgroundDrawable(roundedBg(colors.surface, radius))
    getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(colors.primary)
    getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(colors.primary)
    getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(colors.primary)
    findViewById<TextView>(android.R.id.message)?.setTextColor(colors.onSurface)

    val titleId = context.resources.getIdentifier("alertTitle", "id", "android")
    if (titleId != 0) findViewById<TextView>(titleId)?.setTextColor(colors.onSurface)
}

/**
 * Custom options sheet matching the Library sort-by dialog styling:
 * rounded surface, title, selectable rows with ripple, and a text Cancel button.
 */
internal fun ScreenHost.showStyledOptionsDialog(
    title: String,
    options: List<Pair<String, () -> Unit>>,
) {
    val colors = ThemeManager.colors
    val shapes = ThemeManager.shapes
    val radiusPx = app.dp(shapes.dialogRadius).toFloat()

    val dialogView =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(app.dp(24), app.dp(20), app.dp(24), app.dp(12))
            background = roundedBg(colors.surface, radiusPx)
            roundCorners(shapes.dialogRadius.toFloat())
        }

    dialogView.addView(makeText(app, title, Type.TITLE_LARGE, colors.onSurface))
    dialogView.addView(
        View(app).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, app.dp(16))
        },
    )

    var dialogRef: AlertDialog? = null

    options.forEach { (label, action) ->
        val row =
            LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, app.dp(12), 0, app.dp(12))
                isClickable = true
                isFocusable = true
                background = selectableRipple(colors.onSurface)
            }
        val text = makeText(app, label, Type.BODY_LARGE, colors.onSurface)
        row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.setOnClickListener {
            dialogRef?.dismiss()
            action()
        }
        dialogView.addView(row)
    }

    val cancelButton = makeButton(app, "Cancel", Btn.TEXT) { dialogRef?.dismiss() }
    dialogView.addView(
        LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, app.dp(8), 0, 0)
            addView(cancelButton)
        },
    )

    dialogRef =
        AlertDialog
            .Builder(app)
            .setView(dialogView)
            .create()
    dialogRef.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    dialogRef.show()
}

/** Bounded, searchable voice picker. The filter strip and footer remain visible while results scroll. */
internal fun ScreenHost.showTtsVoiceDialog(
    voices: List<VoiceInfo>,
    selectedIdentifier: String?,
    onSelected: (VoiceInfo?) -> Unit,
) {
    val colors = ThemeManager.colors
    val shapes = ThemeManager.shapes
    val dialogView =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(app.dp(24), app.dp(20), app.dp(24), app.dp(12))
            background = roundedBg(colors.surface, app.dp(shapes.dialogRadius).toFloat())
            roundCorners(shapes.dialogRadius.toFloat())
        }
    dialogView.addView(makeText(app, "TTS Voice", Type.TITLE_LARGE, colors.onSurface))
    dialogView.addView(
        makeText(app, "Search by voice, language, or locale", Type.BODY_SMALL, colors.onSurfaceVariant).apply {
            setPadding(0, app.dp(Space.XS), 0, app.dp(Space.SM))
        },
    )
    val search = makeSearchField(app, "Search voices")
    dialogView.addView(search)

    val filterRow = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL }
    dialogView.addView(
        HorizontalScrollView(app).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, app.dp(Space.SM), 0, app.dp(Space.SM))
            addView(filterRow)
        },
    )

    val resultCount = makeText(app, "", Type.LABEL_MEDIUM, colors.onSurfaceVariant)
    dialogView.addView(resultCount)
    val results = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
    val maxResultsHeight = minOf(app.dp(380), (app.resources.displayMetrics.heightPixels * 0.48f).toInt())
    dialogView.addView(
        ScrollView(app).apply {
            isFillViewport = true
            addView(results)
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxResultsHeight),
    )

    var dialogRef: AlertDialog? = null
    var languageCode: String? = null
    val languageCodes = TtsVoicePlanning.languageCodes(voices)

    fun voiceRow(
        title: String,
        subtitle: String?,
        isSelected: Boolean,
        action: () -> Unit,
    ): LinearLayout =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, app.dp(10), 0, app.dp(10))
            isClickable = true
            isFocusable = true
            background = selectableRipple(colors.onSurface)
            addView(makeText(app, if (isSelected) "✓  $title" else title, Type.BODY_LARGE, colors.onSurface))
            subtitle?.let {
                addView(makeText(app, it, Type.BODY_SMALL, colors.onSurfaceVariant).apply { setPadding(0, app.dp(2), 0, 0) })
            }
            setOnClickListener {
                dialogRef?.dismiss()
                action()
            }
        }

    fun renderResults() {
        val filtered = TtsVoicePlanning.filter(voices, search.text.toString(), languageCode)
        resultCount.text = "${filtered.size} ${if (filtered.size == 1) "voice" else "voices"}"
        results.removeAllViews()
        if (languageCode == null && search.text.isBlank()) {
            results.addView(voiceRow("System default", "Use Android's preferred voice", selectedIdentifier == null) { onSelected(null) })
            results.addView(makeDivider(app))
        }
        filtered.forEach { voice ->
            results.addView(
                voiceRow(
                    voice.name,
                    "${TtsVoicePlanning.languageLabel(voice.language)} · ${voice.language} · ${TtsVoicePlanning.voiceMetadataLabel(voice)}",
                    voice.identifier == selectedIdentifier,
                ) { onSelected(voice) },
            )
        }
        if (filtered.isEmpty()) {
            results.addView(
                makeText(app, "No voices match your search.", Type.BODY_MEDIUM, colors.onSurfaceVariant).apply {
                    setPadding(0, app.dp(Space.LG), 0, app.dp(Space.LG))
                },
            )
        }
    }

    fun renderFilters() {
        filterRow.removeAllViews()

        fun addFilter(
            code: String?,
            label: String,
        ) {
            filterRow.addView(
                makeChip(app, label, languageCode == code) {
                    languageCode = code
                    renderFilters()
                    renderResults()
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = app.dp(Space.SM)
                },
            )
        }
        addFilter(null, "All")
        languageCodes.forEach { addFilter(it, TtsVoicePlanning.languageFilterLabel(it)) }
    }

    search.addTextChangedListener(
        object : android.text.TextWatcher {
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
            ) = renderResults()

            override fun afterTextChanged(s: android.text.Editable?) = Unit
        },
    )
    renderFilters()
    renderResults()

    dialogView.addView(
        LinearLayout(app).apply {
            gravity = Gravity.END
            setPadding(0, app.dp(Space.SM), 0, 0)
            addView(makeButton(app, "Cancel", Btn.TEXT) { dialogRef?.dismiss() })
        },
    )
    dialogRef = AlertDialog.Builder(app).setView(dialogView).create()
    dialogRef.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    dialogRef.show()
}
