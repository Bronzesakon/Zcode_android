package app.zemote.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.zemote.ui.common.ReadOnlyBadge
import app.zemote.ui.common.SettingsChevron
import app.zemote.ui.common.SettingsDivider
import app.zemote.ui.common.SettingsGroupHeader
import app.zemote.ui.common.SettingsRow
import app.zemote.ui.common.StatusDot
import app.zemote.ui.common.ZemoteCard
import app.zemote.ui.common.ZemoteIconTile
import app.zemote.ui.theme.LocalThemeController
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ZemoteType

/**
 * 设置 Tab 的首页。
 *
 * 信息层次按 UI-SPEC §3 重排：**连接 / 外观 / 通知 / 关于与更新**，其余桌面端分类
 * 按「基础 / 智能体能力 / 数据与统计」分组跟在后面。
 *
 * 主题（跟随系统 / 深色 / 浅色）就放在「外观」卡片里，直接读写
 * [LocalThemeController]；首页不放任何主题入口（UI-CONFIRM H2）。
 */
@Composable
fun SettingsHomeScreen(
    version: String,
    updateAvailable: Boolean,
    onOpenAbout: () -> Unit,
    onOpenCategory: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalZemoteColors.current
    val controller = LocalThemeController.current
    val mode = currentThemeMode()

    val remoteGroups = SettingsGroup.entries.filter { it != SettingsGroup.LOCAL }
    val localCategories = setOf(
        SettingsCategory.CONNECTION,
        SettingsCategory.APPEARANCE,
        SettingsCategory.NOTIFICATIONS,
    )

    LazyColumn(
        modifier = modifier.fillMaxSize().background(colors.background),
    ) {
        item(key = "header") { SettingsHeader(version = version) }

        item(key = "local") {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SettingsGroupHeader("本机")

                ZemoteCard {
                    SettingsRow(
                        title = "连接",
                        description = "当前电脑、重新连接与断开",
                        leading = { ZemoteIconTile(Icons.Default.Computer) },
                        onClick = { onOpenCategory(SettingsCategory.CONNECTION) },
                        trailing = { SettingsChevron() },
                    )
                }

                // 外观：主题三态直接在这里切换，字号等其余外观项进二级页。
                ZemoteCard {
                    SettingsRow(
                        title = "外观",
                        description = "主题：${ThemeOption.of(mode).label}",
                        leading = { ZemoteIconTile(Icons.Default.Brush) },
                        onClick = { onOpenCategory(SettingsCategory.APPEARANCE) },
                        trailing = { SettingsChevron() },
                    )
                    Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                        ThemeModeSegmented(
                            selected = mode,
                            onSelect = { controller?.setMode(it) },
                            enabled = controller != null,
                        )
                    }
                    SettingsDivider()
                    SettingsRow(
                        title = "界面字号与代码字号",
                        description = "正文倍率、代码字号、代码预览",
                        onClick = { onOpenCategory(SettingsCategory.APPEARANCE) },
                        trailing = { SettingsChevron() },
                    )
                }

                ZemoteCard {
                    SettingsRow(
                        title = "通知",
                        description = "完成、出错、等待授权；流体云状态",
                        leading = { ZemoteIconTile(Icons.Default.Bolt) },
                        onClick = { onOpenCategory(SettingsCategory.NOTIFICATIONS) },
                        trailing = { SettingsChevron() },
                    )
                }

                ZemoteCard {
                    SettingsRow(
                        title = "关于与更新",
                        description = "Zemote $version · " +
                            if (updateAvailable) "发现新版本" else "已是最新",
                        leading = { ZemoteIconTile(Icons.Default.SystemUpdate) },
                        onClick = onOpenAbout,
                        trailing = {
                            if (updateAvailable) {
                                StatusDot(color = colors.destructive, size = 6.dp)
                            }
                            SettingsChevron()
                        },
                    )
                }
            }
        }

        remoteGroups.forEach { group ->
            val categories = SettingsCategory.entries.filter {
                it.group == group && it !in localCategories
            }
            if (categories.isEmpty()) return@forEach
            item(key = "group-${group.name}-header") {
                Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp)) {
                    SettingsGroupHeader(group.title)
                }
            }
            item(key = "group-${group.name}") {
                Box(Modifier.padding(horizontal = 12.dp)) {
                    ZemoteCard {
                        categories.forEachIndexed { index, category ->
                            if (index > 0) SettingsDivider()
                            CategoryRow(
                                category = category,
                                onClick = { onOpenCategory(category) },
                            )
                        }
                    }
                }
            }
        }

        item(key = "bottom-space") { Spacer(Modifier.height(24.dp)) }
    }
}

/** 页头：54dp、`header` 底、1px 下边框，背景延伸到状态栏下方。 */
@Composable
private fun SettingsHeader(version: String) {
    val colors = LocalZemoteColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.header)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "设置",
                modifier = Modifier.weight(1f),
                style = ZemoteType.uiLg,
                fontWeight = FontWeight.Medium,
                color = colors.foreground,
                maxLines = 1,
            )
            Text(
                text = "v$version",
                style = ZemoteType.uiXs,
                color = colors.foregroundSubtle,
                maxLines = 1,
            )
        }
        HorizontalDivider(color = colors.border)
    }
}

@Composable
private fun CategoryRow(category: SettingsCategory, onClick: () -> Unit) {
    SettingsRow(
        title = category.title,
        description = category.subtitle,
        leading = { ZemoteIconTile(category.icon) },
        onClick = onClick,
        trailing = {
            if (category.readOnly) ReadOnlyBadge()
            SettingsChevron()
        },
    )
}
