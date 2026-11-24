package com.vinicius741.webnovelarchiver.feature.settings

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.domain.model.AppSettings
import com.vinicius741.webnovelarchiver.domain.model.SourceDownloadSettings
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.source.SourceProvider
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.ui.BottomSheetHandle
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.SegmentedSelector
import com.vinicius741.webnovelarchiver.ui.Spacing
import com.vinicius741.webnovelarchiver.ui.StepperHandle
import com.vinicius741.webnovelarchiver.ui.StepperSpec
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.bindWhileAttached
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeButton
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.section
import com.vinicius741.webnovelarchiver.ui.showBottomSheet
import com.vinicius741.webnovelarchiver.ui.toast

private val PARALLEL_SPEC =
    StepperSpec(
        step = 1.0,
        min = SettingsValidation.CONCURRENCY_MIN.toDouble(),
        max = SettingsValidation.CONCURRENCY_MAX.toDouble(),
        label = "Parallel sites",
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

/** Download settings' reads and saves, from the repository. */
private class RepositoryDownloadSettingsData(
    private val repository: AppRepository,
) : DownloadSettingsData {
    override fun settings(): AppSettings = repository.settings.get()

    override fun overrides(): Map<String, SourceDownloadSettings> = repository.sourceDownloadSettings.get()

    override suspend fun saveSettings(settings: AppSettings) = repository.settings.save(settings)

    override suspend fun saveOverrides(overrides: Map<String, SourceDownloadSettings>) = repository.sourceDownloadSettings.save(overrides)
}

/** Download settings' model, kept while it is in the history. */
private fun ScreenHost.downloadSettingsModel(): DownloadSettingsScreenModel =
    screenModels.get(AppRoute.DownloadSettings.stableKey) {
        // The process scope: a save on leaving must finish even if the activity goes away.
        DownloadSettingsScreenModel(RepositoryDownloadSettingsData(repository), app.appContainer.applicationScope)
    }

/**
 * Download settings (v3): a named default pace (Gentle / Balanced / Fast), a pace per site, and an
 * Advanced group with parallel sites and the exact delay range (min stays ≤ max), saved as they
 * change by [DownloadSettingsScreenModel]. Steppers and the pace selector patch each other in place;
 * picking a pace, opening Advanced or saving a site's pace rebuilds the page.
 */
internal fun ScreenHost.showDownloadSettings() {
    val host = this
    screen(route = AppRoute.DownloadSettings, title = "Download settings", scrollable = true) {
        // screen() navigates to this route before it prunes the store, so the model is kept.
        val model = downloadSettingsModel()
        val state = model.refresh()
        val draft = state.settings
        capSettingsWidth(this)
        section("Default pace")
        lateinit var minHandle: StepperHandle
        lateinit var maxHandle: StepperHandle
        lateinit var paceSelector: SegmentedSelector
        settingsGroup {
            val caption =
                if (paceIndex(draft.range()) < 0) {
                    "Now ${DownloadPacePlanning.paceLabel(draft.range())} between chapters. Pick a pace to replace it."
                } else {
                    "How long to wait between chapters"
                }
            paceSelector =
                paceSelectorRow(caption, paceIndex(draft.range())) { index ->
                    val range = model.selectPace(index)
                    // Rebuilt so the caption and every stepper show the new pace.
                    post { showDownloadSettings() }
                    minHandle.show(range.minMs.toDouble())
                    maxHandle.show(range.maxMs.toDouble())
                }
        }
        section("Per-site pace")
        settingsGroup {
            SourceRegistry.web().forEach { provider ->
                val override = state.overrides[provider.id]
                val summary = DownloadPacePlanning.overrideSummary(override)
                navRow(
                    if (override != null) R.drawable.wna_tune else R.drawable.wna_globe,
                    provider.name,
                    if (override != null) tinted(summary, ThemeManager.colors.primary) else summary,
                ) { host.showSourcePaceSheet(provider, model) }
            }
        }
        settingsNote("Slow a site down if it starts limiting requests.")
        section("Advanced")
        val advanced =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (state.advancedOpen) View.VISIBLE else View.GONE
            }
        settingsGroup {
            navRow(R.drawable.wna_tune, "Exact delays and parallel sites", if (state.advancedOpen) "Hide" else "Show", chevron = false) {
                model.toggleAdvanced()
            }
        }
        addView(advanced)
        val (delayMinSpec, delayMaxSpec) = delaySpecs(draft.range())
        advanced.settingsGroup {
            groupStepper(
                "Parallel sites",
                "Sites downloaded at the same time",
                (draft.maxParallelSources ?: DEFAULT_PARALLEL_SOURCES).toDouble(),
                PARALLEL_SPEC,
            ) {
                model.setParallelSites(it.toInt())
                null
            }
            minHandle =
                groupStepper("Delay min", "Wait between chapters", draft.downloadDelay.toDouble(), delayMinSpec) { value ->
                    val range = model.setDelayMin(value.toLong())
                    maxHandle.show(range.maxMs.toDouble())
                    paceSelector.select(paceIndex(range))
                    null
                }
            maxHandle =
                groupStepper("Delay max", "Randomized up to this value", draft.range().maxMs.toDouble(), delayMaxSpec) { value ->
                    val range = model.setDelayMax(value.toLong())
                    minHandle.show(range.minMs.toDouble())
                    paceSelector.select(paceIndex(range))
                    null
                }
        }
        bindWhileAttached(scope, model.state) { next -> if (next.buildKey != state.buildKey) showDownloadSettings() }
        // Leaving the screen flushes a change still waiting for the debounce.
        addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit

                override fun onViewDetachedFromWindow(view: View) = model.flush()
            },
        )
    }
}

/** The preset matching [range], or -1 (none highlighted) for a custom range. */
private fun paceIndex(range: PaceRange): Int = DownloadPacePlanning.presets.indexOfFirst { it.range == range }

/** A padded row holding a caption and a Gentle / Balanced / Fast selector (plus [leading] labels). */
private fun LinearLayout.paceSelectorRow(
    caption: String,
    selected: Int,
    leading: List<String> = emptyList(),
    onSelect: (Int) -> Unit,
): SegmentedSelector {
    val labels = leading + DownloadPacePlanning.presets.map { it.label }
    val selector = SegmentedSelector(context, labels, selected) { index -> onSelect(index) }
    addView(
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(Spacing.LG), context.dp(Spacing.MD), context.dp(Spacing.LG), context.dp(Spacing.MD))
            addView(makeText(context, caption, Type.BODY_SMALL, ThemeManager.colors.onSurfaceVariant))
            addView(
                selector.view,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = context.dp(Spacing.SM)
                },
            )
        },
    )
    return selector
}

/**
 * One site's pace: Default / Gentle / Balanced / Fast, with the exact delays under Advanced.
 * Save writes the override (or removes it for Default).
 */
private fun ScreenHost.showSourcePaceSheet(
    provider: SourceProvider,
    model: DownloadSettingsScreenModel,
) {
    val base = model.state.value.range
    val saved = model.state.value.overrides[provider.id]
    var custom = saved != null
    var range = saved?.let { PaceRange(it.delay, maxOf(it.delay, it.delayMax)) } ?: base
    val body = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
    lateinit var minHandle: StepperHandle
    lateinit var maxHandle: StepperHandle
    val (delayMinSpec, delayMaxSpec) = delaySpecs(range)

    // Index 0 is Default; presets follow; -1 (none) is a custom range.
    fun selectedIndex(): Int = if (!custom) 0 else paceIndex(range).let { if (it < 0) -1 else it + 1 }

    lateinit var selector: SegmentedSelector
    makeGroupIn(body) {
        selector =
            paceSelectorRow("Defaults: ${DownloadPacePlanning.paceLabel(base)}", selectedIndex(), leading = listOf("Default")) { index ->
                custom = index > 0
                range = if (custom) DownloadPacePlanning.presets[index - 1].range else base
                minHandle.show(range.minMs.toDouble())
                maxHandle.show(range.maxMs.toDouble())
            }
    }
    val exact =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (selectedIndex() == -1) View.VISIBLE else View.GONE
        }
    body.addView(
        makeText(app, "Advanced: exact delays", Type.LABEL_LARGE, ThemeManager.colors.primary).apply {
            setPadding(app.dp(Spacing.XS), app.dp(Spacing.LG), 0, app.dp(Spacing.SM))
            isClickable = true
            setOnClickListener { exact.visibility = if (exact.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        },
    )
    body.addView(exact)
    makeGroupIn(exact) {
        minHandle =
            groupStepper("Delay min", "Wait between chapters", range.minMs.toDouble(), delayMinSpec) { value ->
                range = DownloadPacePlanning.withMin(range, value.toLong())
                custom = true
                maxHandle.show(range.maxMs.toDouble())
                selector.select(selectedIndex())
                null
            }
        maxHandle =
            groupStepper("Delay max", "Randomized up to this value", range.maxMs.toDouble(), delayMaxSpec) { value ->
                range = DownloadPacePlanning.withMax(range, value.toLong())
                custom = true
                minHandle.show(range.minMs.toDouble())
                selector.select(selectedIndex())
                null
            }
    }

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
                    model.saveSourcePace(provider.id, chosen)
                    toast(
                        if (chosen == null) {
                            "${provider.name} uses the default pace"
                        } else {
                            "${provider.name}: ${DownloadPacePlanning.paceLabel(chosen)}"
                        },
                    )
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
