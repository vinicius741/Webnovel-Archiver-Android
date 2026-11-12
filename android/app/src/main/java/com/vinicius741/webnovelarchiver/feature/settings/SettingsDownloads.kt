package com.vinicius741.webnovelarchiver.feature.settings

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.SwitchCompat
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.domain.model.AppSettings
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.source.SourceProvider
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.ui.BottomSheetHandle
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.StepperHandle
import com.vinicius741.webnovelarchiver.ui.StepperSpec
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.WrapLayout
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeChip
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.section
import com.vinicius741.webnovelarchiver.ui.showBottomSheet
import com.vinicius741.webnovelarchiver.ui.toast
import kotlinx.coroutines.launch

private val PARALLEL_SPEC =
    StepperSpec(
        step = 1.0,
        min = SettingsValidation.CONCURRENCY_MIN.toDouble(),
        max = SettingsValidation.CONCURRENCY_MAX.toDouble(),
        label = "Parallel sources",
    )
private const val DELAY_STEP_MS = 100.0

/** The delay steppers for [range]; their limit grows to fit a larger delay that is already saved. */
private fun delaySpecs(range: PaceRange): Pair<StepperSpec, StepperSpec> {
    val min =
        StepperSpec(
            step = DELAY_STEP_MS,
            min = 0.0,
            max = DownloadPacePlanning.stepperMaxMs(range).toDouble(),
            unit = " ms",
            label = "Delay min",
        )
    return min to min.copy(label = "Delay max")
}

private val EPUB_SPEC =
    StepperSpec(
        step = 10.0,
        min = SettingsValidation.MAX_CHAPTERS_PER_EPUB_MIN.toDouble(),
        max = SettingsValidation.MAX_CHAPTERS_PER_EPUB_MAX.toDouble(),
        label = "Max chapters per EPUB",
    )
private const val SAVE_DEBOUNCE_MS = 400L

/**
 * The newest values not yet written, so a quick return to the page (or the Settings hub) never
 * shows older ones. Cleared once their save finishes, whether or not it succeeded.
 */
private var unsavedDownloadSettings: AppSettings? = null

/** What Download settings shows: a change still being saved, else the saved settings. */
internal fun ScreenHost.downloadSettingsShown(): AppSettings = unsavedDownloadSettings ?: repository.settings.get()

/**
 * Download settings (v3): steppers for parallel sources, the delay range (min stays ≤ max) and EPUB
 * size, saved as they change; each site opens a sheet where it can get its own pace.
 */
internal fun ScreenHost.showDownloadSettings() {
    val host = this
    var draft = downloadSettingsShown()
    val saver =
        DebouncedSaver {
            val toSave = draft
            scope.launch {
                try {
                    repository.settings.save(toSave)
                } finally {
                    if (unsavedDownloadSettings === toSave) unsavedDownloadSettings = null
                }
            }
        }
    val changed = { next: AppSettings ->
        draft = next
        unsavedDownloadSettings = next
        saver.post()
    }
    screen(route = AppRoute.DownloadSettings, title = "Download settings", scrollable = true) {
        capSettingsWidth(this)
        section("Queue")
        lateinit var minHandle: StepperHandle
        lateinit var maxHandle: StepperHandle
        val (delayMinSpec, delayMaxSpec) = delaySpecs(draft.range())
        settingsGroup {
            groupStepper(
                "Parallel sources",
                "Sites downloaded at the same time",
                (draft.maxParallelSources ?: DEFAULT_PARALLEL_SOURCES).toDouble(),
                PARALLEL_SPEC,
            ) {
                changed(draft.copy(maxParallelSources = it.toInt()))
                null
            }
            minHandle =
                groupStepper("Delay min", "Wait between chapters", draft.downloadDelay.toDouble(), delayMinSpec) { value ->
                    val range = DownloadPacePlanning.withMin(draft.range(), value.toLong())
                    changed(draft.copy(downloadDelay = range.minMs, downloadDelayMax = range.maxMs))
                    maxHandle.show(range.maxMs.toDouble())
                    null
                }
            maxHandle =
                groupStepper("Delay max", "Randomized up to this value", draft.range().maxMs.toDouble(), delayMaxSpec) { value ->
                    val range = DownloadPacePlanning.withMax(draft.range(), value.toLong())
                    changed(draft.copy(downloadDelay = range.minMs, downloadDelayMax = range.maxMs))
                    minHandle.show(range.minMs.toDouble())
                    null
                }
        }
        section("EPUB")
        settingsGroup {
            groupStepper("Max chapters per EPUB", "Bigger books are split into volumes", draft.maxChaptersPerEpub.toDouble(), EPUB_SPEC) {
                changed(draft.copy(maxChaptersPerEpub = it.toInt()))
                null
            }
        }
        section("Per-source pace")
        val overrides = repository.sourceDownloadSettings.get()
        settingsGroup {
            SourceRegistry.all().forEach { provider ->
                val override = DownloadPacePlanning.overrideFor(overrides, provider.id, provider.name)
                val summary = DownloadPacePlanning.overrideSummary(override)
                navRow(
                    if (override != null) R.drawable.wna_tune else R.drawable.wna_globe,
                    provider.name,
                    if (override != null) tinted(summary, ThemeManager.colors.primary) else summary,
                ) { host.showSourcePaceSheet(provider) { draft } }
            }
        }
        settingsNote("Slow a site down if it starts limiting requests. Each site can have its own pace.")
        // Leaving the screen flushes a change still waiting for the debounce.
        addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit

                override fun onViewDetachedFromWindow(view: View) = saver.flush()
            },
        )
    }
}

private fun AppSettings.range(): PaceRange = PaceRange(downloadDelay, maxOf(downloadDelay, downloadDelayMax))

/**
 * One site's pace: a "Custom pace for this site" switch, then delay steppers it unlocks, with
 * presets only as optional Quick fill chips. Save writes the override (or removes it).
 */
private fun ScreenHost.showSourcePaceSheet(
    provider: SourceProvider,
    defaults: () -> AppSettings,
) {
    val colors = ThemeManager.colors
    val base = defaults().range()
    val saved = DownloadPacePlanning.overrideFor(repository.sourceDownloadSettings.get(), provider.id, provider.name)
    var custom = saved != null
    var range = saved?.let { PaceRange(it.delay, maxOf(it.delay, it.delayMax)) } ?: base
    val body = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
    val form = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
    lateinit var minHandle: StepperHandle
    lateinit var maxHandle: StepperHandle
    val (delayMinSpec, delayMaxSpec) = delaySpecs(range)
    val chips =
        WrapLayout(app).apply {
            horizontalSpacingDp = Spacing.SM
            verticalSpacingDp = Spacing.SM
        }

    lateinit var switch: SwitchCompat

    fun renderEnabled() {
        form.setRowEnabled(custom)
        // Re-enabling turns both ± back on; re-showing the values disables one at its limit.
        if (custom) {
            minHandle.show(range.minMs.toDouble())
            maxHandle.show(range.maxMs.toDouble())
        }
    }

    fun renderChips() {
        chips.removeAllViews()
        DownloadPacePlanning.presets.forEach { preset ->
            chips.addView(
                makeChip(app, "${preset.label} · ${DownloadPacePlanning.rangeLabel(preset.range)}", preset.range == range && custom) {
                    range = preset.range
                    custom = true
                    switch.isChecked = true
                    renderEnabled()
                    minHandle.show(range.minMs.toDouble())
                    maxHandle.show(range.maxMs.toDouble())
                    renderChips()
                },
            )
        }
    }

    val switchGroup =
        makeGroupIn(body) {
            switch =
                switchRow(
                    "Custom pace for this site",
                    "Defaults: ${DownloadPacePlanning.rangeLabel(base)} delay",
                    custom,
                ) { on ->
                    custom = on
                    renderEnabled()
                    renderChips()
                }
        }
    switchGroup.layoutParams = (switchGroup.layoutParams as LinearLayout.LayoutParams).apply { bottomMargin = app.dp(Spacing.MD) }
    makeGroupIn(form) {
        minHandle =
            groupStepper("Delay min", "Wait between chapters", range.minMs.toDouble(), delayMinSpec) { value ->
                range = DownloadPacePlanning.withMin(range, value.toLong())
                maxHandle.show(range.maxMs.toDouble())
                renderChips()
                null
            }
        maxHandle =
            groupStepper("Delay max", "Randomized up to this value", range.maxMs.toDouble(), delayMaxSpec) { value ->
                range = DownloadPacePlanning.withMax(range, value.toLong())
                minHandle.show(range.minMs.toDouble())
                renderChips()
                null
            }
    }
    body.addView(form)
    body.addView(
        makeText(app, "Quick fill", Type.LABEL_MEDIUM, colors.onSurfaceVariant).apply {
            setPadding(app.dp(Spacing.XS), app.dp(Spacing.LG), 0, app.dp(Spacing.SM))
        },
    )
    body.addView(chips, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    renderChips()
    renderEnabled()

    var sheet: BottomSheetHandle? = null
    val footer =
        LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                makeButton(app, "Cancel", Btn.SURFACE) { sheet?.dismiss() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                makeButton(app, "Save", Btn.FILLED) {
                    val chosen = if (custom) range else null
                    scope.launch {
                        val next =
                            DownloadPacePlanning.withOverride(
                                repository.sourceDownloadSettings.get(),
                                provider.id,
                                provider.name,
                                chosen,
                            )
                        repository.sourceDownloadSettings.save(next)
                        toast(
                            if (chosen ==
                                null
                            ) {
                                "${provider.name} uses the defaults"
                            } else {
                                "${provider.name}: ${DownloadPacePlanning.rangeLabel(chosen)} delay"
                            },
                        )
                        if (navigator.current == AppRoute.DownloadSettings) showDownloadSettings()
                    }
                    sheet?.dismiss()
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = app.dp(Spacing.SM) },
            )
        }
    sheet = showBottomSheet(app, title = provider.name, subtitle = "Download pace for this site", body = body, footer = footer)
}

/** Adds a list group to [parent] (not through the DSL receiver, so sheets can build one). */
private fun makeGroupIn(
    parent: ViewGroup,
    block: LinearLayout.() -> Unit,
): LinearLayout = parent.settingsGroup(block)

/** Coalesces rapid stepper changes (a held ± fires many) into one save after they settle. */
private class DebouncedSaver(
    private val save: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var pending = false
    private val run =
        Runnable {
            pending = false
            save()
        }

    fun post() {
        pending = true
        handler.removeCallbacks(run)
        handler.postDelayed(run, SAVE_DEBOUNCE_MS)
    }

    fun flush() {
        if (!pending) return
        handler.removeCallbacks(run)
        run.run()
    }
}
