package app.zemote.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ZemoteRadius
import app.zemote.ui.theme.ZemoteType

/**
 * 设置 Tab 的首页。
 *
 * 顶部是一张醒目横条（关于与更新），下面按分组列出全部一级分类；
 * 每个分类进入二级页（UI-SPEC §3）。
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

    LazyColumn(
        modifier = modifier.fillMaxSize().background(colors.background).statusBarsPadding(),
    ) {
        item(key = "about-banner") {
            AboutBanner(
                version = version,
                updateAvailable = updateAvailable,
                onClick = onOpenAbout,
            )
        }

        SettingsGroup.entries.forEach { group ->
            val categories = SettingsCategory.entries.filter { it.group == group }
            if (categories.isEmpty()) return@forEach
            item(key = "group-${group.name}") {
                Text(
                    text = group.title,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
                    style = ZemoteType.uiXs,
                    fontWeight = FontWeight.Medium,
                    color = colors.foregroundSubtlest,
                )
            }
            items(categories, key = { it.id }) { category ->
                CategoryRow(category = category, onClick = { onOpenCategory(category) })
            }
        }

        item(key = "bottom-space") { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun AboutBanner(
    version: String,
    updateAvailable: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalZemoteColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.primary)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.primaryForeground.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.SystemUpdate,
                contentDescription = null,
                tint = colors.primaryForeground,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        ) {
            Text(
                text = "Zemote $version",
                style = ZemoteType.uiLg,
                fontWeight = FontWeight.Medium,
                color = colors.primaryForeground,
                maxLines = 1,
            )
            Text(
                text = if (updateAvailable) "发现新版本，点击查看" else "关于与更新 · 已是最新",
                style = ZemoteType.uiSm,
                color = colors.primaryForeground.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = colors.primaryForeground.copy(alpha = 0.75f),
        )
    }
}

@Composable
private fun CategoryRow(category: SettingsCategory, onClick: () -> Unit) {
    val colors = LocalZemoteColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                category.icon,
                contentDescription = null,
                tint = colors.foregroundSubtle,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = category.title,
                    style = ZemoteType.uiBase,
                    color = colors.foreground,
                    fontWeight = FontWeight.Medium,
                )
                if (category.readOnly) {
                    Text(
                        text = "桌面端",
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(colors.surface)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                        style = ZemoteType.ui2xs,
                        color = colors.foregroundSubtle,
                    )
                }
            }
            Text(
                text = category.subtitle,
                style = ZemoteType.uiSm,
                color = colors.foregroundSubtle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = colors.foregroundSubtlest,
            modifier = Modifier.size(18.dp),
        )
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 62.dp),
        color = colors.border.copy(alpha = 0.5f),
    )
}
