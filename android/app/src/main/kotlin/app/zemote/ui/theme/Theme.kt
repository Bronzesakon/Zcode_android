// ---------------------------------------------------------------------------
//  ZemoteTheme —— 把 design/tokens 里抽出来的 Web 设计令牌装进 Compose 主题
//
//  数据来源：design/tokens/colors.json、typography.json
//    - 上游快照：Reference_web/3.15.3/assets/index-BD2BL4Cc.css
//    - 上游 CSS sha256：40894faf46c4aaf3508873aaa4e808eb1d41343029f6d8aa070f5b6a997ebc2f
//  生成时间：2026-10-16 23:20
//
//  【不要在这里硬编码颜色/字号】令牌本体在 Color.kt / Type.kt / Dimens.kt，
//  它们又来自 design/tokens/*.json；改值请改令牌后重新生成。
//
//  主题口径：明暗两套按快照的**默认主题**解析（`:root,:host` + `.dark`），不套用
//  `.theme-zai-*` 皮肤；Color.kt 里的 ZemoteColors.Light / .Dark 就是这个口径的值。
//  明暗跟随系统设置（`isSystemInDarkTheme()`）。
// ---------------------------------------------------------------------------

package app.zemote.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * 动态取色开关，**必须保持 false**。
 *
 * 动态取色会把 Material You 的壁纸取色灌进 ColorScheme，直接覆盖掉从 Web 快照抽出来
 * 的令牌，一比一复刻就废了。保留这个常量只是为了标注意图，不要在复刻阶段打开。
 */
private const val USE_DYNAMIC_COLOR = false

/**
 * 应用主题入口。
 *
 * 结构：CompositionLocalProvider（令牌）+ MaterialTheme（由同一批令牌派生的
 * ColorScheme 与 Typography）。业务代码优先读 [LocalZemoteColors]（181 个语义令牌
 * 全在里面），Material3 组件走 MaterialTheme.colorScheme —— 后者是前者的子集映射，
 * 目的是让 ripple / TextField 默认色之类的 M3 内部实现不至于用 Material 默认的紫色。
 */
@Composable
fun ZemoteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val zemoteColors = if (darkTheme) ZemoteColors.Dark else ZemoteColors.Light

    val colorScheme = when {
        USE_DYNAMIC_COLOR && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        else -> remember(zemoteColors, darkTheme) { zemoteColorScheme(zemoteColors, darkTheme) }
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
