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
import kotlinx.coroutines.launch
import java.util.UUID

internal fun ScreenHost.showTabs() {
    screen(route = AppRoute.Tabs, title = "Manage Tabs", onBack = { showSettings() }, scrollable = true) {
        val tabs = TabPlanning.normalizeOrders(repository.getTabs())
        text(
            "Tabs group novels on the Library screen. Create one (e.g. \"Reading\", \"Finished\") and assign novels to it.",
            Type.BODY_SMALL,
            ThemeManager.colors.onSurfaceVariant,
        )
        spacer(Space.SM)
        row {
            val name = makeField(context, "", "New tab name", InputType.TYPE_CLASS_TEXT)
            addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            button("Add", Btn.TONAL, R.drawable.wna_add) {
                val next =
                    TabPlanning.create(
                        tabs,
                        name.text.toString(),
                        "tab_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(4)}",
                        System.currentTimeMillis(),
                    )
                if (next.size > tabs.size) {
                    scope.launch {
                        repository.saveTabs(next)
                        showTabs()
                    }
                }
            }
        }
        tabs.forEachIndexed { index, tab ->
            val novelCount = repository.getLibrary().count { it.tabId == tab.id }
            addView(
                card {
                    row {
                        addView(
                            ImageView(context).apply {
                                setImageDrawable(context.tintedIcon(R.drawable.wna_tab, ThemeManager.colors.primary))
                                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                            },
                        )
                        addView(
                            makeText(context, tab.name, Type.TITLE_MEDIUM, ThemeManager.colors.onSurface).apply {
                                setPadding(dp(10), 0, 0, 0)
                            },
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                        )
                        val novelLabel = if (novelCount == 1) "novel" else "novels"
                        addView(
                            makeText(
                                context,
                                "$novelCount $novelLabel",
                                Type.LABEL_MEDIUM,
                                ThemeManager.colors.onSurfaceVariant,
                            ),
                        )
                    }
                    row(gravity = Gravity.END) {
                        tabActionButton(R.drawable.wna_up, "Move ${tab.name} up", enabled = index > 0) {
                            if (index > 0) {
                                scope.launch {
                                    repository.saveTabs(TabPlanning.move(tabs, index, index - 1))
                                    showTabs()
                                }
                            }
                        }
                        tabActionButton(R.drawable.wna_down, "Move ${tab.name} down", enabled = index < tabs.lastIndex) {
                            if (index < tabs.lastIndex) {
                                scope.launch {
                                    repository.saveTabs(TabPlanning.move(tabs, index, index + 1))
                                    showTabs()
                                }
                            }
                        }
                        tabActionButton(R.drawable.wna_edit, "Rename ${tab.name}") {
                            showRenameTabPrompt(tab.name) { renamed ->
                                scope.launch {
                                    repository.saveTabs(TabPlanning.rename(tabs, tab.id, renamed))
                                    showTabs()
                                }
                            }
                        }
                        tabActionButton(R.drawable.wna_delete, "Delete ${tab.name}", tint = ThemeManager.colors.error) {
                            confirm("Delete tab \"${tab.name}\" and move its novels to Unassigned?", confirmLabel = "Delete") {
                                scope.launch {
                                    repository.getLibrary().forEach { story ->
                                        if (story.tabId == tab.id) {
                                            story.tabId = null
                                            repository.addOrUpdateStory(story)
                                        }
                                    }
                                    repository.saveTabs(TabPlanning.delete(tabs, tab.id))
                                    showTabs()
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}

private fun ScreenHost.showRenameTabPrompt(
    tabName: String,
    onSave: (String) -> Unit,
) {
    val input = makeField(app, tabName, "Rename Tab", InputType.TYPE_CLASS_TEXT)
    val content =
        LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(app.dp(Space.XL), app.dp(Space.MD), app.dp(Space.XL), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    val dialog =
        AlertDialog
            .Builder(app)
            .setTitle("Rename Tab")
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
    tint: Int = ThemeManager.colors.primary,
    enabled: Boolean = true,
    action: () -> Unit,
) {
    val size = context.dp(44)
    addView(
        ImageView(context).apply {
            contentDescription = description
            setImageDrawable(context.tintedIcon(iconRes, tint))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(context.dp(Space.SM + 2), context.dp(Space.SM + 2), context.dp(Space.SM + 2), context.dp(Space.SM + 2))
            background = selectableRipple(tint)
            isEnabled = enabled
            isClickable = enabled
            isFocusable = enabled
            alpha = if (enabled) 1f else 0.35f
            if (enabled) setOnClickListener { action() }
        },
        LinearLayout.LayoutParams(size, size).apply {
            if (childCount > 0) marginStart = context.dp(Space.SM)
        },
    )
}
