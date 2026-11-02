// ---------------------------------------------------------------------------
//  由 design/tokens/typography.json 机械翻译的 Web 字体令牌（Android / Compose 侧）
//
//  数据来源：design/tokens/typography.json
//    - 3.17.1 复核：Reference_web/3.17.1/assets/index-BgVjt5KE.css
//      `--ui-font-size:14px`、`--text-ui-*` 档位与 3.15.3 一致，未改值。
//    - 上游快照：Reference_web/3.15.3/assets/index-BD2BL4Cc.css
//    - 上游 CSS sha256：40894faf46c4aaf3508873aaa4e808eb1d41343029f6d8aa070f5b6a997ebc2f
//
//  【不要直接改这个文件】改值请改 design/tokens/typography.json 后重新生成。
//
//  口径说明：
//    1. 字号阶梯用的是应用自有的 font_size_ui_scale（基准 --ui-font-size:14px），
//       不是 Tailwind 的 --text-xs/sm/base。3.17.1 实测 ui 档位：
//       10 / 12 / 13 / 14 / 16 / 18 px；另仍有 ui-2xs = 9px（保留兼容，见 ui2xs）。
//       全部写成 sp —— sp 会跟随系统字号缩放，这是 Android 上正确的无障碍行为；
//       写 dp 反而会让「跟随系统字号」失效。
//    2. 这套 ui-* 档位在 CSS 里没有配对的 --text-*--line-height，行高是继承的，
//       继承值来自 Tailwind preflight 的 html,:host{line-height:1.5}，所以这里按
//       字号 × 1.5 折算（与 typography.json line_height.normal 一致）。
//    3. 字体族不硬塞 Inter / Segoe UI：--font-sans 是纯通用栈
//       (ui-sans-serif, system-ui, sans-serif, ...)，不含任何 CJK 字体，且快照里
//       没有随应用发布的 Inter 字体文件（Inter 只在 index.html 的 bootstrap 内联
//       <style> 里出现，见 typography.json font_family.body_effective_override，
//       inter_available=false）。Android 上对应做法就是用系统默认字体族 —— 它本身
//       就会正确回落到 CJK。等宽用 FontFamily.Monospace（--font-mono 显式列了 CJK
//       家族，Android 的 monospace 通用族同样会经系统 fallback 命中 CJK）。
//       见 typography.json cjk_notes.compose_recommendation。
//    4. 字重只有 4 档被真实定义：normal 400 / medium 500 / semibold 600 / bold 700。
// ---------------------------------------------------------------------------

package app.zemote.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 网页排版基准（3.17.1 实测）：正文 14px、行高 1.5。 */
object ZemoteFontSize {
    /** --ui-font-size = 14px（正文基准） */
    const val BaseSp = 14f

    /** html,:host { line-height: 1.5 } */
    const val LineHeightMultiplier = 1.5f
}

/** 快照里真正被定义的 4 档字重（typography.json font_weight）。 */
object ZemoteFontWeight {
    val Normal = FontWeight.Normal // 400
    val Medium = FontWeight.Medium // 500
    val Semibold = FontWeight.SemiBold // 600
    val Bold = FontWeight.Bold // 700
}

/**
 * 应用自有字号阶梯（typography.json font_size_ui_scale）。
 *
 * 每个档位都是 `calc(var(--ui-font-size) ± Npx)`，基准 --ui-font-size = 14px，
 * 且该变量全表只声明一次（没有 media / dark / density 覆盖）。
 */
@Immutable
data class ZemoteTypography(
    /** --text-ui-2xs，calc(--ui-font-size - 5px) = 9px */
    val ui2xs: TextStyle,
    /** --text-ui-xs，calc(--ui-font-size - 4px) = 10px */
    val uiXs: TextStyle,
    /** --text-ui-sm，calc(--ui-font-size - 2px) = 12px */
    val uiSm: TextStyle,
    /** --text-ui-caption，calc(--ui-font-size - 1px) = 13px */
    val uiCaption: TextStyle,
    /** --text-ui-base，var(--ui-font-size) = 14px */
    val uiBase: TextStyle,
    /** --text-ui-lg，calc(--ui-font-size + 2px) = 16px */
    val uiLg: TextStyle,
    /** --text-ui-xl，calc(--ui-font-size + 4px) = 18px */
    val uiXl: TextStyle,
    /** --text-mobile-input-safe = 16px（移动端输入框防 iOS 自动缩放，不随 ui 基准缩放） */
    val mobileInputSafe: TextStyle,
    /** 代码 / 终端：字号继承正文（preflight `code,kbd,samp,pre{font-size:1em}`），只换等宽族。 */
    val mono: TextStyle,
)

/** 单例令牌实例。字号不随明暗主题变化（typography.json light_dark_relationship）。 */
val ZemoteType: ZemoteTypography = run {
    val sans = FontFamily.Default // 系统默认：Android 上会正确回落 CJK
    val mono = FontFamily.Monospace
    // Tailwind preflight: html,:host{line-height:1.5}，ui-* 档位无配对行高 -> 继承 1.5
    fun ui(px: Float) = TextStyle(
        fontFamily = sans,
        fontWeight = ZemoteFontWeight.Normal,
        fontSize = px.sp,
        lineHeight = (px * ZemoteFontSize.LineHeightMultiplier).sp,
    )

    ZemoteTypography(
        ui2xs = ui(9f),
        uiXs = ui(10f),
        uiSm = ui(12f),
        uiCaption = ui(13f),
        uiBase = ui(ZemoteFontSize.BaseSp),
        uiLg = ui(16f),
        uiXl = ui(18f),
        mobileInputSafe = ui(16f),
        mono = ui(ZemoteFontSize.BaseSp).copy(fontFamily = mono),
    )
}

/**
 * 派生给 Material3 用的 [Typography]。
 *
 * M3 的 15 个语义角色映射到同一套 ui-* 档位，让 M3 组件（默认正文 bodyLarge、
 * 按钮 labelLarge 等）落在 Web 的实际字号上，而不是 M3 默认的 16/14/11。
 * 字号只做档位归并，不引入新数值。
 */
val ZemoteMaterialTypography: Typography = run {
    val t = ZemoteType
    fun TextStyle.w(weight: FontWeight) = copy(fontWeight = weight)

    Typography(
        // 网页端没有 display 级字号，统一归到最大的 ui-xl。
        displayLarge = t.uiXl.w(ZemoteFontWeight.Semibold),
        displayMedium = t.uiXl.w(ZemoteFontWeight.Semibold),
        displaySmall = t.uiLg.w(ZemoteFontWeight.Semibold),
        headlineLarge = t.uiXl.w(ZemoteFontWeight.Semibold),
        headlineMedium = t.uiLg.w(ZemoteFontWeight.Semibold),
        headlineSmall = t.uiLg.w(ZemoteFontWeight.Medium),
        titleLarge = t.uiLg.w(ZemoteFontWeight.Semibold),
        titleMedium = t.uiBase.w(ZemoteFontWeight.Medium),
        titleSmall = t.uiSm.w(ZemoteFontWeight.Medium),
        bodyLarge = t.uiBase,
        bodyMedium = t.uiSm,
        bodySmall = t.uiCaption,
        labelLarge = t.uiBase.w(ZemoteFontWeight.Medium),
        labelMedium = t.uiSm.w(ZemoteFontWeight.Medium),
        labelSmall = t.uiCaption.w(ZemoteFontWeight.Medium),
    )
}
