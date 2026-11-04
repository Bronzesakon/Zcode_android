// ---------------------------------------------------------------------------
//  由 design/tokens/layout.json 机械翻译的 Web 布局令牌（Android / Compose 侧）
//
//  数据来源：design/tokens/layout.json
//    - 3.17.1 复核：Reference_web/3.17.1/assets/index-BgVjt5KE.css
//      `--spacing:4px`、radius 4/6/8/12/16 与 3.15.3 一致，未改值。
//    - 上游快照：Reference_web/3.15.3/assets/index-BD2BL4Cc.css
//    - 上游 CSS sha256：40894faf46c4aaf3508873aaa4e808eb1d41343029f6d8aa070f5b6a997ebc2f
//
//  【不要直接改这个文件】改值请改 design/tokens/layout.json 后重新生成。
//
//  口径说明：
//    1. 间距只有一个基准：--spacing = 0.25rem = 4px。所有间距工具类都是
//       calc(var(--spacing) * N)，所以这里同时给 Unit / of(N) 和常用档位的具名常量。
//    2. 布局令牌不随明暗主题变化（layout.json light_dark_relationship）。
//    3. 圆角没有 --radius-full：网页端 .rounded-full{border-radius:3.40282e38px}
//       是 calc(infinity * 1px) 的浮点渲染结果，不是真实尺寸，Compose 侧对应
//       CircleShape（见 ZemoteRadius.full），不要写成巨大的 dp 值。
//    4. 动效时长/缓动只是目标值：网页的 cubic-bezier 与 Compose 的
//       CubicBezierEasing 语义一致，但 transition 的默认 0.15s 对应 Compose 动画
//       的 spec 时长，实际手感仍以 1:1 复刻为准。
//    5. 高度两档（3.17.1）：顶栏 h-11 = 44dp；可点行 min-h-12 = 48dp（Android
//       最小触摸目标）。
// ---------------------------------------------------------------------------

package app.zemote.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ===========================================================================
//  间距：唯一基准 --spacing = 0.25rem = 4px
// ===========================================================================
object ZemoteSpacing {
    /** --spacing = 0.25rem = 4px，整个应用的间距基数。 */
    val Unit: Dp = 4.dp

    /** 对应 CSS 的 calc(var(--spacing) * multiplier)。 */
    fun of(multiplier: Float): Dp = Unit * multiplier

    /** 对应 CSS 的 calc(var(--spacing) * steps)。 */
    fun of(steps: Int): Dp = Unit * steps

    val space0: Dp = 0.dp // 0
    val space0_5: Dp = of(0.5f) // 2
    val space0_75: Dp = of(0.75f) // 3
    val space1: Dp = of(1) // 4
    val space1_25: Dp = of(1.25f) // 5
    val space1_5: Dp = of(1.5f) // 6
    val space2: Dp = of(2) // 8
    val space2_5: Dp = of(2.5f) // 10
    val space3: Dp = of(3) // 12
    val space3_5: Dp = of(3.5f) // 14
    val space4: Dp = of(4) // 16
    val space4_5: Dp = of(4.5f) // 18
    val space5: Dp = of(5) // 20
    val space6: Dp = of(6) // 24
    val space7: Dp = of(7) // 28
    val space8: Dp = of(8) // 32
    val space9: Dp = of(9) // 36
    val space10: Dp = of(10) // 40
    val space11: Dp = of(11) // 44
    val space12: Dp = of(12) // 48
    val space14: Dp = of(14) // 56
    val space16: Dp = of(16) // 64
    val space18: Dp = of(18) // 72
    val space20: Dp = of(20) // 80
    val space24: Dp = of(24) // 96
    val space32: Dp = of(32) // 128
    val space40: Dp = of(40) // 160
    val space48: Dp = of(48) // 192
}

// ===========================================================================
//  高度：网页 h-11 / min-h-12 两个档位
// ===========================================================================
object ZemoteHeight {
    /** h-11 = 44dp：对话页顶栏 / 紧凑工具条。 */
    val Control: Dp = 44.dp

    /** min-h-12 = 48dp：可点行、列表项（对齐 Android 最小触摸目标）。 */
    val Touch: Dp = 48.dp
}

// ===========================================================================
//  圆角：--radius-xs/sm/md/lg/xl/2xl/3xl = 2/4/6/8/12/16/24px
// ===========================================================================
object ZemoteRadius {
    val xs: Dp = 2.dp // --radius-xs
    val sm: Dp = 4.dp // --radius-sm，也是裸 .rounded
    val md: Dp = 6.dp // --radius-md
    val lg: Dp = 8.dp // --radius-lg
    val xl: Dp = 12.dp // --radius-xl
    val xxl: Dp = 16.dp // --radius-2xl
    val xxxl: Dp = 24.dp // --radius-3xl

    val xsShape: Shape = RoundedCornerShape(xs)
    val smShape: Shape = RoundedCornerShape(sm)
    val mdShape: Shape = RoundedCornerShape(md)
    val lgShape: Shape = RoundedCornerShape(lg)
    val xlShape: Shape = RoundedCornerShape(xl)
    val xxlShape: Shape = RoundedCornerShape(xxl)
    val xxxlShape: Shape = RoundedCornerShape(xxxl)

    /** .rounded-none{border-radius:0} */
    val noneShape: Shape = RoundedCornerShape(0.dp)

    /**
     * .rounded-full{border-radius:3.40282e38px} —— 那是 calc(infinity * 1px) 的浮点
     * 渲染结果，Compose 对应 CircleShape，不要照抄那个天文数字。
     */
    val fullShape: Shape = CircleShape
}

// ===========================================================================
//  模糊：--blur-xs/sm/md/xl/2xl/3xl = 4/8/12/24/40/64px
//  仅作视觉目标：Compose Modifier.blur() 与 CSS backdrop-filter 的渲染代价不同。
// ===========================================================================
object ZemoteBlur {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 40.dp
    val xxxl: Dp = 64.dp
}

// ===========================================================================
//  动效时长
// ===========================================================================
object ZemoteDuration {
    /** .duration-100 */
    const val d100 = 100

    /** .duration-150，也是 --default-transition-duration（39 条规则用它作默认值） */
    const val d150 = 150

    /** .duration-200 */
    const val d200 = 200

    /** .duration-300 */
    const val d300 = 300

    /** .duration-500 */
    const val d500 = 500

    /** --default-transition-duration = 0.15s */
    const val Default = d150

    /** --wf-t-fast = 0.12s（工作流可视化专用） */
    const val wfFast = 120

    /** --wf-t-base = 0.16s */
    const val wfBase = 160

    /** --wf-t-enter = 0.2s */
    const val wfEnter = 200

    /** --wf-t-ink = 0.32s */
    const val wfInk = 320

    /** --wf-beat = 1.6s */
    const val wfBeat = 1600
}

// ===========================================================================
//  缓动
// ===========================================================================
object ZemoteEasing {
    /** --ease-out = cubic-bezier(0, 0, .2, 1) */
    val out: Easing = CubicBezierEasing(0f, 0f, 0.2f, 1f)

    /** --ease-in-out = cubic-bezier(.4, 0, .2, 1) */
    val inOut: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    /** --ease-linear */
    val linear: Easing = Easing { it }

    /** --default-transition-timing-function = cubic-bezier(.4, 0, .2, 1) */
    val default: Easing = inOut

    /** --wf-ease = cubic-bezier(.22, .61, .36, 1) */
    val wf: Easing = CubicBezierEasing(0.22f, 0.61f, 0.36f, 1f)
}

// ===========================================================================
//  断点：宽 640/768/1024/1280/1536dp + 两个自定义（窄屏 480dp、矮视口高 740dp）
//  CSS 里没有 --breakpoint-* 变量，值直接读自 @media 条件。
// ===========================================================================
object ZemoteBreakpoint {
    val sm: Dp = 640.dp
    val md: Dp = 768.dp
    val lg: Dp = 1024.dp
    val xl: Dp = 1280.dp
    val xxl: Dp = 1536.dp

    /** @media (width<=480px) —— 窄屏手机 */
    val narrowPhone: Dp = 480.dp

    /** @media (height<=740px) —— 横屏 / 小窗口，多个工具类按高度切换 */
    val shortViewport: Dp = 740.dp
}

// ===========================================================================
//  容器宽：--container-xs … --container-6xl（.max-w-*）
// ===========================================================================
object ZemoteContainer {
    val xs: Dp = 320.dp
    val sm: Dp = 384.dp
    val md: Dp = 448.dp
    val lg: Dp = 512.dp
    val xl: Dp = 576.dp
    val xxl: Dp = 672.dp
    val xxxl: Dp = 768.dp
    val x4xl: Dp = 896.dp
    val x5xl: Dp = 1024.dp
    val x6xl: Dp = 1152.dp
}

// ===========================================================================
//  层级：CSS 里没有 --z-* 变量，只有字面量整数（layout.json z_index）。
//  这里按语义归并，而不是照抄 9998/9999/2147483647 这类逃生值。
// ===========================================================================
object ZemoteZIndex {
    const val background = 0
    const val content = 1
    const val sticky = 10
    const val dropdown = 20
    const val overlay = 40
    const val modal = 50
    const val toast = 9999
}

/** --aspect-video = 16 / 9，快照里唯一被定义的比例令牌。 */
const val ZemoteAspectVideo: Float = 16f / 9f
