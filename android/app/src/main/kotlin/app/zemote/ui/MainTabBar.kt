package app.zemote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.zemote.ui.nav.MainTab
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ZemoteType

/**
 * 底部双 Tab（对话 / 设置）——自绘的紧凑栏，不用 M3 `NavigationBar`。
 *
 * 规格：
 * - 背景 `header`，顶部 1px `border`，高度 48dp + 导航栏安全区；
 * - 去掉 M3 的胶囊指示器与默认 80dp 高度；
 * - 选中「网页 selected 语义」：`bg-selected` 圆角底 + `foreground` 文字/图标，
 *   未选中透明底 + `foregroundSubtle`；图标 18dp、文案 `uiSm`、间距 6dp；
 * - 点击区仍以 `selectable` 暴露 Tab 语义（无障碍 / ripple 行为层保留）。
 */
@Composable
fun MainTabBar(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalZemoteColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.header)
            .navigationBarsPadding(),
    ) {
        HorizontalDivider(color = colors.border)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            mainTabSpecs(selected).forEach { spec ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(
                            selected = spec.selected,
                            role = Role.Tab,
                            onClick = { onSelect(spec.tab) },
                        ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = spec.tab.icon(),
                            contentDescription = null,
                            tint = if (spec.selected) colors.foreground else colors.foregroundSubtle,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = spec.label,
                            style = ZemoteType.uiSm,
                            fontWeight = if (spec.selected) FontWeight.Medium else FontWeight.Normal,
                            color = if (spec.selected) colors.foreground else colors.foregroundSubtle,
                        )
                    }
                    // 选中的唯一视觉标记：底部 18×2dp 短线。去掉整块胶囊底，避免 Material 导航栏观感。
                    if (spec.selected) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 2.dp)
                                .width(18.dp)
                                .height(2.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(colors.foreground),
                        )
                    }
                }
            }
        }
    }
}

private fun MainTab.icon(): ImageVector = when (this) {
    MainTab.HOME -> Icons.Outlined.ChatBubbleOutline
    MainTab.SETTINGS -> Icons.Outlined.Settings
}
