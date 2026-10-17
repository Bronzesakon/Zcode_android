package app.zemote.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import app.zemote.ui.theme.ZemoteType

/**
 * 主界面：底部双 Tab（对话 / 设置）。
 *
 * 两个 Tab 都是根级内容，切换不产生返回层级（`NavState.replaceRoot`）。
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
            NavigationBar(containerColor = colors.header) {
                NavigationBarItem(
                    selected = tab == MainTab.HOME,
                    onClick = { onTabChange(MainTab.HOME) },
                    icon = { Icon(Icons.Default.Chat, contentDescription = null) },
                    label = { Text("对话", style = ZemoteType.uiXs) },
                    colors = navColors(),
                )
                NavigationBarItem(
                    selected = tab == MainTab.SETTINGS,
                    onClick = { onTabChange(MainTab.SETTINGS) },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("设置", style = ZemoteType.uiXs) },
                    colors = navColors(),
                )
            }
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

@Composable
private fun navColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = LocalZemoteColors.current.foreground,
    selectedTextColor = LocalZemoteColors.current.foreground,
    indicatorColor = LocalZemoteColors.current.selected,
    unselectedIconColor = LocalZemoteColors.current.foregroundSubtle,
    unselectedTextColor = LocalZemoteColors.current.foregroundSubtle,
)
