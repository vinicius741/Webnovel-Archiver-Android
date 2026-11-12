package com.vinicius741.webnovelarchiver.feature.settings

import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.core.widget.doAfterTextChanged
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.tts.TtsVoicePlanning
import com.vinicius741.webnovelarchiver.tts.VoiceInfo
import com.vinicius741.webnovelarchiver.ui.BottomSheetHandle
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeChip
import com.vinicius741.webnovelarchiver.ui.makeSearchField
import com.vinicius741.webnovelarchiver.ui.makeSheetRow
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.showBottomSheet

/** The voice picker as a v3 sheet: search, language chips, and the engine's voices (the chosen one checked). */
internal fun ScreenHost.showTtsVoiceSheet(
    voices: List<VoiceInfo>,
    selectedIdentifier: String?,
    onSelected: (VoiceInfo?) -> Unit,
) {
    val colors = ThemeManager.colors
    val body = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
    var sheet: BottomSheetHandle? = null
    val search = makeSearchField(app, "Search voices")
    body.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    val filterRow = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL }
    body.addView(
        HorizontalScrollView(app).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, app.dp(Spacing.MD), 0, app.dp(Spacing.SM))
            addView(filterRow)
        },
    )
    val count =
        makeText(
            app,
            "",
            Type.LABEL_MEDIUM,
            colors.onSurfaceVariant,
        ).apply { setPadding(app.dp(Spacing.SM), 0, 0, app.dp(Spacing.XS)) }
    body.addView(count)
    val results = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
    body.addView(results)
    var languageCode: String? = null
    val languageCodes = TtsVoicePlanning.languageCodes(voices)

    fun pick(voice: VoiceInfo?) {
        sheet?.dismiss()
        onSelected(voice)
    }

    fun renderResults() {
        val filtered = TtsVoicePlanning.filter(voices, search.text.toString(), languageCode)
        count.text = if (filtered.size == 1) "1 voice" else "${filtered.size} voices"
        results.removeAllViews()
        if (languageCode == null && search.text.isBlank()) {
            results.addView(
                makeSheetRow(
                    app,
                    "Engine default",
                    "The voice the speech engine picks",
                    R.drawable.wna_record_voice,
                    checked =
                        selectedIdentifier == null,
                ) {
                    pick(null)
                },
            )
        }
        filtered.forEach { voice ->
            results.addView(
                makeSheetRow(
                    app,
                    voice.name,
                    "${TtsVoicePlanning.languageLabel(voice.language)} · ${voice.language} · ${TtsVoicePlanning.voiceMetadataLabel(voice)}",
                    R.drawable.wna_record_voice,
                    checked = voice.identifier == selectedIdentifier,
                ) { pick(voice) },
            )
        }
        if (filtered.isEmpty()) {
            results.addView(
                makeText(app, "No voices match your search.", Type.BODY_MEDIUM, colors.onSurfaceVariant).apply {
                    setPadding(app.dp(Spacing.SM), app.dp(Spacing.LG), 0, app.dp(Spacing.LG))
                },
            )
        }
    }

    fun renderFilters() {
        filterRow.removeAllViews()
        (listOf<String?>(null) + languageCodes).forEach { code ->
            filterRow.addView(
                makeChip(app, code?.let(TtsVoicePlanning::languageFilterLabel) ?: "All", languageCode == code) {
                    languageCode = code
                    renderFilters()
                    renderResults()
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = app.dp(Spacing.SM)
                },
            )
        }
    }

    search.doAfterTextChanged { renderResults() }
    renderFilters()
    renderResults()
    sheet = showBottomSheet(app, title = "Voice", subtitle = "Search by voice, language, or locale", body = body)
}
