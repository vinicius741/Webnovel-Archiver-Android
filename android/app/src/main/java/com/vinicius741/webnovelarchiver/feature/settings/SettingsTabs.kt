package com.vinicius741.webnovelarchiver.feature.settings

import android.app.AlertDialog
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.widget.doAfterTextChanged
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.domain.model.Tab
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.applyAppTheme
import com.vinicius741.webnovelarchiver.ui.bindWhileAttached
import com.vinicius741.webnovelarchiver.ui.button
import com.vinicius741.webnovelarchiver.ui.card
import com.vinicius741.webnovelarchiver.ui.confirm
import com.vinicius741.webnovelarchiver.ui.dp
import com.vinicius741.webnovelarchiver.ui.makeField
import com.vinicius741.webnovelarchiver.ui.makeText
import com.vinicius741.webnovelarchiver.ui.row
import com.vinicius741.webnovelarchiver.ui.screen
import com.vinicius741.webnovelarchiver.ui.selectableRipple
import com.vinicius741.webnovelarchiver.ui.spacer
import com.vinicius741.webnovelarchiver.ui.text
import com.vinicius741.webnovelarchiver.ui.tintedIcon
import com.vinicius741.webnovelarchiver.ui.toast
import java.util.UUID

/** Manage tabs' reads and saves, from the repository. */
private class RepositoryTabsData(
    private val repository: AppRepository,
) : TabsData {
    override fun tabs(): List<Tab> = repository.tabs.get()

    override fun novelCounts(): Map<String, Int> =
        repository
            .library()
            .mapNotNull { it.tabId }
            .groupingBy { it }
            .eachCount()

    override suspend fun saveTabs(tabs: List<Tab>) = repository.tabs.save(tabs)

    override suspend fun unassign(tabId: String) {
        repository.library().forEach { story ->
            if (story.tabId == tabId) {
                story.tabId = null
                repository.addOrUpdateStory(story)
            }
        }
    }

    override fun newTabId(): String = "tab_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(4)}"

    override fun now(): Long = System.currentTimeMillis()
}

/** Manage tabs' model, kept while it is in the history. */
private fun ScreenHost.tabsModel(): TabsScreenModel =
    screenModels.get(AppRoute.Tabs.stableKey) {
        TabsScreenModel(RepositoryTabsData(repository))
    }

/** Manage tabs, built from [TabsScreenModel]'s state; a change of the tabs or their counts rebuilds it. */
internal fun ScreenHost.showTabs() {
    val colors = ThemeManager.colors
    // The subtitle counts the tabs, so the state is read before screen(). The model is kept: screen()
    // puts this route in the history before it prunes the store.
    val model = tabsModel()
    val state = model.refresh()
    val tabs = state.tabs
    screen(route = AppRoute.Tabs, title = "Manage tabs", subtitle = SettingsHubPlanning.plural(tabs.size, "tab"), scrollable = true) {
        capSettingsWidth(this)
        settingsNote("Tabs group your library. Drag a tab by its handle to reorder. Novels in a deleted tab become Unassigned.")
        spacer(Space.MD)
        row {
            val name = makeField(context, model.newTabName, "New tab name", InputType.TYPE_CLASS_TEXT)
            name.doAfterTextChanged { model.setNewTabName(it?.toString().orEmpty()) }
            addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            button("Add", Btn.FILLED, R.drawable.wna_add) {
                if (!model.addTab()) toast("Type a new tab name first")
            }
        }
        spacer(Space.SM)
        if (tabs.isEmpty()) settingsNote("No tabs yet.")
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val reorder = DragReorderList(list) { from, to -> model.move(from, to) }
        tabs.forEachIndexed { index, tab ->
            val novelCount = state.novelCounts[tab.id] ?: 0
            val row =
                list
                    .card {
                        setPadding(dp(Space.XS), dp(Space.SM), dp(Space.XS), dp(Space.SM))
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        val handle =
                            makeRowIconButton(context, R.drawable.wna_drag_handle, "Drag to reorder ${tab.name}", colors.faint) {}.apply {
                                background = null
                                isClickable = false
                            }
                        addView(handle)
                        addView(
                            LinearLayout(context).apply {
                                orientation = LinearLayout.VERTICAL
                                addView(makeText(context, tab.name, Type.TITLE_SMALL, colors.onSurface).apply { textSize = TITLE_SP })
                                addView(
                                    makeText(
                                        context,
                                        SettingsHubPlanning.plural(novelCount, "novel"),
                                        Type.BODY_SMALL,
                                        colors.onSurfaceVariant,
                                    ).apply {
                                        setPadding(0, dp(2), 0, 0)
                                    },
                                )
                            },
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                        )
                        tabActionButton(R.drawable.wna_edit, "Rename ${tab.name}") {
                            showRenameTabPrompt(tab.name) { renamed -> model.rename(tab.id, renamed) }
                        }
                        tabActionButton(R.drawable.wna_delete, "Delete ${tab.name}") {
                            val novels = SettingsHubPlanning.plural(novelCount, "novel")
                            confirm(
                                "Delete \"${tab.name}\"? $novels will become Unassigned.",
                                confirmLabel = "Delete",
                            ) { model.delete(tab.id) }
                        }
                        reorder.attach(this, handle)
                    }.apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(Space.SM) }
            // Screen readers get move actions in place of the drag.
            if (index > 0) {
                ViewCompat.addAccessibilityAction(row, "Move ${tab.name} up") { _, _ ->
                    model.move(index, index - 1)
                    true
                }
            }
            if (index < tabs.lastIndex) {
                ViewCompat.addAccessibilityAction(row, "Move ${tab.name} down") { _, _ ->
                    model.move(index, index + 1)
                    true
                }
            }
            list.addView(row)
        }
        addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bindWhileAttached(scope, model.state) { next -> if (next != state) showTabs() }
    }
}

private fun ScreenHost.showRenameTabPrompt(
    tabName: String,
    onSave: (String) -> Unit,
) {
    val input = makeField(app, tabName, "Tab name", InputType.TYPE_CLASS_TEXT)
    val content =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(app.dp(Space.XL), app.dp(Space.MD), app.dp(Space.XL), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    val dialog =
        AlertDialog
            .Builder(app)
            .setTitle("Rename tab")
            .setView(content)
            .setPositiveButton("Save") { _, _ -> onSave(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .create()
    dialog.show()
    dialog.applyAppTheme()
}

private fun LinearLayout.tabActionButton(
    iconRes: Int,
    description: String,
    action: () -> Unit,
) {
    addView(makeRowIconButton(context, iconRes, description, ThemeManager.colors.onSurfaceVariant, action))
}

private const val TITLE_SP = 15f
