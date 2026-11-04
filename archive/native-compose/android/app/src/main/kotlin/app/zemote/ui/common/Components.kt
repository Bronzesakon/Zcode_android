package app.zemote.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ZemoteRadius
import app.zemote.ui.theme.ZemoteType

/**
 * 设置 / 次级页共用的视觉件。
 *
 * 规格来自 `docs/audit/06-non-chat.md` §5.2：卡片 12dp 圆角 + `card` 底 + 1dp `cardBorder`，
 * 行最小 48dp、左右 16dp / 上下 12dp，标题 `uiBase Medium`、描述 `uiSm subtle`，
 * 行间 1px `border` 分隔线。所有颜色 / 字号 / 圆角都走令牌，不在这里写死数值。
 */

/** 卡片：12dp 圆角 + `card` 底 + 1dp `cardBorder`（网页 `rounded-xl border bg-card`）。 */
@Composable
fun ZemoteCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalZemoteColors.current
    val shape = RoundedCornerShape(ZemoteRadius.xl)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.card)
            .border(1.dp, colors.cardBorder, shape),
        content = content,
    )
}

/** 分组标题：`uiSm Medium foregroundSubtle`，与下方卡片左对齐。 */
@Composable
fun SettingsGroupHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 6.dp),
        style = ZemoteType.uiSm,
        fontWeight = FontWeight.Medium,
        color = LocalZemoteColors.current.foregroundSubtle,
    )
}

/** 行间分隔线：`border` 色，左侧内缩 16dp 对齐文字。 */
@Composable
fun SettingsDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(start = 16.dp),
        color = LocalZemoteColors.current.border,
    )
}

/**
 * 设置行：标题 + 可选描述，右侧控制区，整体可点。
 *
 * 触摸目标由 [defaultMinSize] 保证 ≥48dp；`onClick == null` 时不做点击（纯展示行）。
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    titleColor: Color? = null,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = LocalZemoteColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = ZemoteType.uiBase,
                fontWeight = FontWeight.Medium,
                color = titleColor ?: colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = ZemoteType.uiSm,
                    color = colors.foregroundSubtle,
                )
            }
        }
        if (trailing != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                content = trailing,
            )
        }
    }
}

/** 开关行：与 [SettingsRow] 同一套栅格，右侧是 M3 Switch（行为层保留 M3）。 */
@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
) {
    SettingsRow(
        title = title,
        modifier = modifier,
        description = description,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = zemoteSwitchColors(),
            )
        },
    )
}

/** 枚举 / 文本行右侧的「当前值 + chevron」。 */
@Composable
fun RowScope.SettingsValue(value: String, monospace: Boolean = false) {
    Text(
        text = value,
        style = if (monospace) ZemoteType.uiSm.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) else ZemoteType.uiSm,
        color = LocalZemoteColors.current.foregroundSubtle,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    SettingsChevron()
}

/** 行尾 chevron（16dp，`foregroundSubtlest`）。 */
@Composable
fun SettingsChevron() {
    Icon(
        Icons.Default.ChevronRight,
        contentDescription = null,
        tint = LocalZemoteColors.current.foregroundSubtlest,
        modifier = Modifier.size(16.dp),
    )
}

/** 「桌面端」只读徽标。 */
@Composable
fun ReadOnlyBadge(text: String = "桌面端") {
    val colors = LocalZemoteColors.current
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(colors.surface)
            .padding(horizontal = 6.dp, vertical = 1.dp),
        style = ZemoteType.ui2xs,
        color = colors.foregroundSubtle,
        maxLines = 1,
    )
}

/**
 * 页头：44dp，背景 `header`，底部 1px `border`，背景延伸到状态栏下方。
 *
 * 返回按钮 40dp 触摸区（图标 20dp）；不传 [onBack] 时标题左侧留 16dp。
 */
@Composable
fun PageHeader(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = LocalZemoteColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.header)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(start = if (onBack != null) 4.dp else 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                GhostIconButton(Icons.Default.ArrowBack, "返回", onBack)
            }
            Text(
                text = title,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (onBack != null) 4.dp else 0.dp),
                style = ZemoteType.uiBase,
                fontWeight = FontWeight.Medium,
                color = colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            actions?.invoke(this)
        }
        HorizontalDivider(color = colors.border)
    }
}

/** 40dp 方形图标按钮（行内 / 页头动作）。 */
@Composable
fun GhostIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
) {
    val colors = LocalZemoteColors.current
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(RoundedCornerShape(ZemoteRadius.lg))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = tint ?: colors.foregroundSubtle,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 主操作：8dp 圆角、`primary` 底，视觉高 40dp。 */
@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalZemoteColors.current
    Box(
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(ZemoteRadius.lg))
            .background(if (enabled) colors.primary else colors.primary.copy(alpha = 0.35f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = ZemoteType.uiBase,
            fontWeight = FontWeight.Medium,
            color = colors.primaryForeground.copy(alpha = if (enabled) 1f else 0.5f),
            maxLines = 1,
        )
    }
}

/** 次操作：8dp 圆角、1px `border`、透明底。 */
@Composable
fun OutlineActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val colors = LocalZemoteColors.current
    val contentColor = if (destructive) colors.destructive else colors.foreground
    Box(
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(ZemoteRadius.lg))
            .border(1.dp, colors.border, RoundedCornerShape(ZemoteRadius.lg))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = ZemoteType.uiBase,
            color = contentColor.copy(alpha = if (enabled) 1f else 0.4f),
            maxLines = 1,
        )
    }
}

/**
 * 提示条：错误用 `destructive` 淡底，普通提示用 `surface` 底。
 *
 * 在页面顶部、滚动区之外使用；点「关闭」回调 [onClose]。
 */
@Composable
fun NoticeBar(
    text: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
) {
    val colors = LocalZemoteColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (destructive) colors.destructive.copy(alpha = 0.12f) else colors.surface)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = ZemoteType.uiSm,
            color = if (destructive) colors.destructive else colors.foregroundSubtle,
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(ZemoteRadius.md))
                .clickable(onClick = onClose)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(
                text = "关闭",
                style = ZemoteType.uiSm,
                color = if (destructive) colors.destructive else colors.foregroundSubtle,
            )
        }
    }
}

/** 空态：虚线感的 1px 边框卡片（`border` 半透明），标题 + 描述 + 可选动作。 */
@Composable
fun EmptyState(
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = LocalZemoteColors.current
    val shape = RoundedCornerShape(ZemoteRadius.xl)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, colors.border.copy(alpha = 0.7f), shape)
            .padding(horizontal = 16.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = ZemoteType.uiBase,
            fontWeight = FontWeight.Medium,
            color = colors.foreground,
        )
        if (description != null) {
            Text(
                text = description,
                style = ZemoteType.uiSm,
                color = colors.foregroundSubtle,
            )
        }
        if (action != null) {
            Box(Modifier.padding(top = 4.dp)) { action() }
        }
    }
}

/** 状态点（8dp 圆点）。 */
@Composable
fun StatusDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** 分类图标底：28dp 圆角方块 + 16dp 线性图标（网页 `size-4`）。 */
@Composable
fun ZemoteIconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    tint: Color? = null,
) {
    val colors = LocalZemoteColors.current
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(ZemoteRadius.lg))
            .background(colors.surface),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint ?: colors.foregroundSubtle,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** M3 Switch 的令牌配色：选中 `primary`，未选中 `secondary`。 */
@Composable
fun zemoteSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = LocalZemoteColors.current.primaryForeground,
    checkedTrackColor = LocalZemoteColors.current.primary,
    checkedBorderColor = LocalZemoteColors.current.primary,
    uncheckedThumbColor = LocalZemoteColors.current.foregroundSubtle,
    uncheckedTrackColor = LocalZemoteColors.current.secondary,
    uncheckedBorderColor = LocalZemoteColors.current.border,
)
