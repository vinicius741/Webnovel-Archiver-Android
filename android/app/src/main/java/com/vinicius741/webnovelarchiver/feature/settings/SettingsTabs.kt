package com.vinicius741.webnovelarchiver.feature.settings

import android.app.AlertDialog
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import com.vinicius741.webnovelarchiver.R
import com.vinicius741.webnovelarchiver.navigation.AppRoute
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.Btn
import com.vinicius741.webnovelarchiver.ui.Space
import com.vinicius741.webnovelarchiver.ui.ThemeManager
import com.vinicius741.webnovelarchiver.ui.Type
import com.vinicius741.webnovelarchiver.ui.applyAppTheme
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
import kotlinx.coroutines.launch
import java.util.UUID

internal fun ScreenHost.showTabs() {
    rerender = { showTabs() }
    val tabs = TabPlanning.normalizeOrders(repository.tabs.get())
    val colors = ThemeManager.colors
    screen(route = AppRoute.Tabs, title = "Manage tabs", subtitle = SettingsHubPlanning.plural(tabs.size, "tab"), scrollable = true) {
        capSettingsWidth(this)
        settingsNote("Tabs group your library. Novels in a deleted tab become Unassigned.")
        spacer(Space.MD)
        row {
            val name = makeField(context, "", "New tab name", InputType.TYPE_CLASS_TEXT)
            addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            button("Add", Btn.FILLED, R.drawable.wna_add) {
                val next =
                    TabPlanning.create(
                        tabs,
                        name.text.toString(),
                        "tab_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(4)}",
                        System.currentTimeMillis(),
                    )
                if (next.size > tabs.size) {
                    scope.launch {
                        repository.tabs.save(next)
                        showTabs()
                    }
                } else {
                    toast("Type a new tab name first")
                }
            }
        }
        spacer(Space.SM)
        if (tabs.isEmpty()) settingsNote("No tabs yet.")
        val library = repository.library()
        tabs.forEachIndexed { index, tab ->
            val novelCount = library.count { it.tabId == tab.id }
            addView(
                card {
                    setPadding(dp(Space.LG), dp(Space.SM), dp(Space.XS), dp(Space.SM))
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
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
                    tabActionButton(R.drawable.wna_up, "Move ${tab.name} up", enabled = index > 0) {
                        scope.launch {
                            repository.tabs.save(TabPlanning.move(tabs, index, index - 1))
                            showTabs()
                        }
                    }
                    tabActionButton(R.drawable.wna_down, "Move ${tab.name} down", enabled = index < tabs.lastIndex) {
                        scope.launch {
                            repository.tabs.save(TabPlanning.move(tabs, index, index + 1))
                            showTabs()
                        }
                    }
                    tabActionButton(R.drawable.wna_edit, "Rename ${tab.name}") {
                        showRenameTabPrompt(tab.name) { renamed ->
                            scope.launch {
                                repository.tabs.save(TabPlanning.rename(tabs, tab.id, renamed))
                                showTabs()
                            }
                        }
                    }
                    tabActionButton(R.drawable.wna_delete, "Delete ${tab.name}") {
                        val novels = SettingsHubPlanning.plural(novelCount, "novel")
                        confirm("Delete \"${tab.name}\"? $novels will become Unassigned.", confirmLabel = "Delete") {
                            scope.launch {
                                repository.library().forEach { story ->
                                    if (story.tabId == tab.id) {
                                        story.tabId = null
                                        repository.addOrUpdateStory(story)
                                    }
                                }
                                repository.tabs.save(TabPlanning.delete(tabs, tab.id))
                                showTabs()
                            }
                        }
                    }
                }.apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(Space.SM) },
            )
        }
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
    enabled: Boolean = true,
    action: () -> Unit,
) {
    addView(
        makeRowIconButton(context, iconRes, description, ThemeManager.colors.onSurfaceVariant, action).apply {
            isEnabled = enabled
            alpha = if (enabled) 1f else DISABLED_ALPHA
        },
    )
}

private const val TITLE_SP = 15f
private const val DISABLED_ALPHA = 0.3f
