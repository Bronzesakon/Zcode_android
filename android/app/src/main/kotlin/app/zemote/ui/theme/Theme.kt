// ---------------------------------------------------------------------------
//  ZemoteTheme —— 把 Web 设计令牌装进 Compose 主题
//
//  数据来源：
//    - 3.17.1 语义令牌：design/tokens/generate_zai_theme.py 解析
//      Reference_web/3.17.1/assets/index-BgVjt5KE.css 生成，值在 ZaiColors.kt；
//    - 3.15.3 默认口径：design/tokens/colors.json（ZemoteColors.Light / .Dark），
//      保留不动，只作为历史口径与解析兜底。
//    - 排版 / 布局：design/tokens/typography.json、layout.json（Type.kt / Dimens.kt）。
//
//  主题口径（docs/UI-CONFIRM-3.17.1.md §1.2）：
//    - 跟随系统：系统深色 -> ZemoteColors.ZaiDark，浅色 -> ZemoteColors.ZaiLight；
//    - 手动覆盖：ThemeMode.DARK / .LIGHT（设置页写入 ThemeController）；
//    - 动态取色（Material You 壁纸取色）一律禁用，否则会覆盖网页复刻令牌。
//
//  【不要在这里硬编码颜色/字号】令牌本体在 Color.kt / ZaiColors.kt / Type.kt /
//  Dimens.kt；改值请改令牌后重新生成。
// ---------------------------------------------------------------------------

package app.zemote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

/**
 * 应用主题入口。
 *
 * 结构：CompositionLocalProvider（令牌）+ MaterialTheme（由同一批令牌派生的
 * ColorScheme 与 Typography）。业务代码优先读 [LocalZemoteColors]（181 个语义令牌
 * 全在里面），Material3 组件走 MaterialTheme.colorScheme —— 后者是前者的子集映射，
 * 目的是让 ripple / TextField 默认色之类的 M3 内部实现不至于用 Material 默认的紫色。
 *
 * 明暗决策优先级：
 *  1. [LocalThemeController] 存在时，用它的 [ThemeMode]（设置页手动覆盖）；
 *  2. 否则用 [darkTheme]（默认跟随系统，调用方也可显式传入）。
 *
 * @param darkTheme 未安装 ThemeController 时的明暗兜底；默认跟随系统。
 */
@Composable
fun ZemoteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val controller = LocalThemeController.current
    val mode: ThemeMode? = if (controller != null) controller.mode.collectAsState().value else null

    val effectiveDark = mode?.resolveDark(systemDark) ?: darkTheme
    val zemoteColors = if (effectiveDark) ZemoteColors.ZaiDark else ZemoteColors.ZaiLight

    val colorScheme = remember(zemoteColors, effectiveDark) {
        zemoteColorScheme(zemoteColors, effectiveDark)
    }

    CompositionLocalProvider(
        LocalZemoteColors provides zemoteColors,
        LocalZemotePalette provides ZemotePalette.Default,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = ZemoteMaterialTypography,
            content = content,
        )
    }
}

/**
 * 从语义令牌派生 Material3 [ColorScheme]。
 *
 * 原则：只做「语义令牌 → M3 角色」的映射，不引入任何新颜色、不做调色计算。
 * 网页端没有对应概念的角色（primaryFixed 之类）就近取同一套值，避免 M3 默认的
 * 紫色渗进组件。
 */
private fun zemoteColorScheme(c: ZemoteColors, dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        // ---- primary：网页的品牌色 / 主操作色（zai 口径下就是黑/白这对墨色）----
        primary = c.primary,
        onPrimary = c.primaryForeground,
        primaryContainer = c.selected,
        onPrimaryContainer = c.foreground,
        inversePrimary = c.brand,
        primaryFixed = c.primary,
        primaryFixedDim = c.primary,
        onPrimaryFixed = c.primaryForeground,
        onPrimaryFixedVariant = c.primaryForeground,

        // ---- secondary：次级面 ----
        secondary = c.secondary,
        onSecondary = c.foreground,
        secondaryContainer = c.hover,
        onSecondaryContainer = c.foreground,
        secondaryFixed = c.secondary,
        secondaryFixedDim = c.secondary,
        onSecondaryFixed = c.foreground,
        onSecondaryFixedVariant = c.foreground,

        // ---- tertiary：网页只有两套强调色，第三套复用品牌色 ----
        tertiary = c.brand,
        onTertiary = c.primaryForeground,
        tertiaryContainer = c.accent,
        onTertiaryContainer = c.foreground,
        tertiaryFixed = c.accent,
        tertiaryFixedDim = c.accent,
        onTertiaryFixed = c.foreground,
        onTertiaryFixedVariant = c.foreground,

        // ---- error ----
        error = c.destructive,
        onError = c.destructiveForeground,
        errorContainer = c.destructive,
        onErrorContainer = c.destructiveForeground,

        // ---- 底色 / 墨色 ----
        background = c.background,
        onBackground = c.foreground,
        surface = c.background,
        onSurface = c.foreground,
        surfaceVariant = c.card,
        onSurfaceVariant = c.foregroundSubtle,
        surfaceTint = c.primary,
        inverseSurface = c.foreground,
        inverseOnSurface = c.foregroundInverse,

        // ---- 边框 ----
        outline = c.border,
        outlineVariant = c.borderHover,

        scrim = Color.Black,

        // ---- M3 1.3+ 的 surface 色阶：直接落在网页各层面板色上 ----
        surfaceBright = c.card,
        surfaceDim = c.backgroundAlt,
        surfaceContainerLowest = c.background,
        surfaceContainerLow = c.panel,
        surfaceContainer = c.card,
        surfaceContainerHigh = c.popover,
        surfaceContainerHighest = c.menu,
    )
}
