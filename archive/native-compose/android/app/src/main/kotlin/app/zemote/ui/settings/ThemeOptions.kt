package app.zemote.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zemote.ui.theme.LocalThemeController
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ThemeMode
import app.zemote.ui.theme.ZemoteRadius
import app.zemote.ui.theme.ZemoteType

/**
 * 主题三态（UI-SPEC §3.2 / UI-CONFIRM H2）。
 *
 * 主题的**唯一**手动入口是「设置 → 外观」；首页不放主题菜单。
 * 标签顺序固定为「跟随系统 / 深色 / 浅色」，与设置页与网页下拉一致。
 */
enum class ThemeOption(val mode: ThemeMode, val label: String) {
    SYSTEM(ThemeMode.SYSTEM, "跟随系统"),
    DARK(ThemeMode.DARK, "深色"),
    LIGHT(ThemeMode.LIGHT, "浅色"),
    ;

    companion object {
        /** 固定展示顺序（分隔条 / 分段控件的排列依据）。 */
        val All: List<ThemeOption> = entries.toList()

        fun of(mode: ThemeMode): ThemeOption = entries.first { it.mode == mode }
    }
}

/**
 * 当前主题模式；[LocalThemeController] 缺席时按「跟随系统」显示。
 *
 * 主题的读写都走 [app.zemote.ui.theme.ThemeController]，设置页不再自己落盘。
 */
@Composable
fun currentThemeMode(): ThemeMode {
    val controller = LocalThemeController.current
    return if (controller == null) {
        ThemeMode.SYSTEM
    } else {
        controller.mode.collectAsStateWithLifecycle().value
    }
}

/**
 * 三态分段控件：`surface` 底槽 + 选中项 `card` 底 + 1px `border`。
 *
 * 触摸目标 ≥36dp（外层 [heightIn]），未选中 `foregroundSubtle`，选中 `foreground`。
 */
@Composable
fun ThemeModeSegmented(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalZemoteColors.current
    val slot = RoundedCornerShape(ZemoteRadius.lg)
    val item = RoundedCornerShape(ZemoteRadius.md)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(slot)
            .background(colors.surface)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThemeOption.All.forEach { option ->
            val isSelected = option.mode == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 36.dp)
                    .clip(item)
                    .then(if (isSelected) Modifier.background(colors.card) else Modifier)
                    .then(
                        if (isSelected) Modifier.border(1.dp, colors.border, item) else Modifier,
                    )
                    .clickable(enabled = enabled) { onSelect(option.mode) }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option.label,
                    style = ZemoteType.uiSm,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    color = when {
                        !enabled -> colors.foregroundSubtlest
                        isSelected -> colors.foreground
                        else -> colors.foregroundSubtle
                    },
                    maxLines = 1,
                )
            }
        }
    }
}
