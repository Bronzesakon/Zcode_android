package app.zemote.ui

import app.zemote.ui.nav.MainTab

/** 底部 Tab 的选中语义（对应网页 `bg-selected` + `text-foreground` / `text-foreground-subtle`）。 */
enum class TabEmphasis { SELECTED, UNSELECTED }

/**
 * 底部 Tab 的呈现数据。
 *
 * 与 Compose 无关，纯值：Tab 顺序、文案与选中态都在这里决定，Colour 由界面层按
 * [emphasis] 取令牌（选中 `foreground` + `selected` 底，未选中 `foregroundSubtle`）。
 */
data class MainTabSpec(
    val tab: MainTab,
    val label: String,
    val emphasis: TabEmphasis,
) {
    val selected: Boolean get() = emphasis == TabEmphasis.SELECTED
}

/**
 * 底栏两个 Tab 的固定顺序与选中态。
 *
 * 顺序与 `NavState` 的根 Tab 一致：对话在左、设置在右。
 */
fun mainTabSpecs(selected: MainTab): List<MainTabSpec> = listOf(
    MainTabSpec(MainTab.HOME, "对话", selected.emphasisOf(MainTab.HOME)),
    MainTabSpec(MainTab.SETTINGS, "设置", selected.emphasisOf(MainTab.SETTINGS)),
)

private fun MainTab.emphasisOf(selected: MainTab): TabEmphasis =
    if (this == selected) TabEmphasis.SELECTED else TabEmphasis.UNSELECTED
