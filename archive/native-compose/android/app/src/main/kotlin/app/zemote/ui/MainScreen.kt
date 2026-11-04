package app.zemote.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.zemote.ui.chat.TaskItem
import app.zemote.ui.chat.WorkspaceItem
import app.zemote.ui.home.HomeScreen
import app.zemote.ui.home.HomeUiState
import app.zemote.ui.nav.MainTab
import app.zemote.ui.settings.SettingsCategory
import app.zemote.ui.settings.SettingsHomeScreen
import app.zemote.ui.theme.LocalZemoteColors

/**
 * 主界面：底部双 Tab（对话 / 设置）。
 *
 * 两个 Tab 都是根级内容，切换不产生返回层级（`NavState.replaceRoot`）。
 * 底栏是自绘的 [MainTabBar]（48dp + 安全区，1px 上边框，选中 `selected` 底），
 * 不用 M3 `NavigationBar` 的默认高度与胶囊指示器。
 *
 * ## 状态栏
 *
 * `contentWindowInsets = 0`：内容一直铺到状态栏下面，由各页自己的头部
 * `statusBarsPadding()` 决定文字位置。这样头部背景能延伸进状态栏（沉浸式），
 * 而不是让 Scaffold 在顶部留一条底色带。
 */
@Composable
fun MainScreen(
    tab: MainTab,
    homeState: HomeUiState,
    version: String,
    updateAvailable: Boolean,
    onTabChange: (MainTab) -> Unit,
    onRefresh: () -> Unit,
    onToggleWorkspace: (String) -> Unit,
    onOpenTask: (WorkspaceItem, TaskItem) -> Unit,
    onNewConversation: (WorkspaceItem) -> Unit,
    onDismissError: () -> Unit,
    onPin: (WorkspaceItem, TaskItem, Boolean) -> Unit,
    onArchive: (WorkspaceItem, TaskItem) -> Unit,
    onDelete: (WorkspaceItem, TaskItem) -> Unit,
    onOpenPairing: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenCategory: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalZemoteColors.current

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            MainTabBar(selected = tab, onSelect = onTabChange)
        },
    ) { padding ->
        val contentModifier = Modifier.padding(bottom = padding.calculateBottomPadding())
        when (tab) {
            MainTab.HOME -> HomeScreen(
                state = homeState,
                onRefresh = onRefresh,
                onToggleWorkspace = onToggleWorkspace,
                onOpenTask = onOpenTask,
                onNewConversation = onNewConversation,
                onDismissError = onDismissError,
                onPin = onPin,
                onArchive = onArchive,
                onDelete = onDelete,
                onOpenPairing = onOpenPairing,
                modifier = contentModifier,
            )

            MainTab.SETTINGS -> SettingsHomeScreen(
                version = version,
                updateAvailable = updateAvailable,
                onOpenAbout = onOpenAbout,
                onOpenCategory = onOpenCategory,
                modifier = contentModifier,
            )
        }
    }
}
