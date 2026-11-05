package com.zcode.remote.core

/**
 * The strip behind the status bar, and what it must be painted with.
 *
 * The main screen has no app bar any more: the page starts directly under the
 * status bar, so that strip *is* the top of the remote page's own chrome. It
 * therefore has to carry the page's surface colour, and the value has to be
 * exact — any mismatch reads as a seam between the system bar and the page.
 *
 * Runtime colour reading is deliberately absent (the same rule the HarmonyOS
 * build follows): the injected layer reports two *names* — which visual state the
 * DOM is in, and which theme the page resolved for itself — and this table maps
 * them to the literals. The literals come from the page's own stylesheet
 * (`docs/05-…`, 2026-09-11 snapshot):
 *
 * | token          | light     | dark      | where it is the top surface |
 * | -------------- | --------- | --------- | --------------------------- |
 * | `boot-shell`   | `#fafafa` | `#171717` | pre-rendered shell before the page stamps its theme class |
 * | `boot`         | `#f8f8f8` | `#161616` | themed shell, status pages, **and the narrow home/task-list/settings pages**（它们的顶部是整页 `--color-background`，不是标题栏） |
 * | `main-surface` | `#ececee` | `#2b2b2b` | 状态栏正下方是窗口外框/左列（`--color-background-win-alt`）：宽屏侧栏、**设置页左 68px 图标栏**（用户 2026-11-05 口径：对齐左列较暗背景） |
 * | `main-header`  | `#ffffff` | `#202020` | 有「可见且贴顶」标题栏（`--color-header`）时的窄屏对话页 |
 *
 * Light/dark is not taken from the system: the page has its own theme setting,
 * so the reported theme wins and the system's night mode is only the fallback.
 * See inject.js section 6 for how the state is derived.
 */
enum class PageBarState(
    val token: String,
    /**
     * Login/authorization surfaces carry their own light/dark name from the page
     * (D4): they follow the *web page's* theme, never the system's, and the
     * native side has no DOM to ask on those pages. `null` means "the page
     * reports a theme and the table follows it" — every non-auth state.
     */
    val fixedDark: Boolean? = null,
) {
    /**
     * Pre-rendered boot shell while `<html>` carries no `theme-zai-*` class yet,
     * i.e. before the page's own top-level IIFE stamps it: the page background
     * still falls back to the `:root` neutral-50/900 token.
     */
    BOOT_SHELL("boot-shell"),

    /**
     * Themed boot shell (the page's IIFE stamps the theme class before React
     * mounts), plus status pages and every screen that is ours rather than the
     * page's — all of them show the themed page background.
     */
    BOOT("boot"),

    /** Control view, narrow: the page's own header sits directly under the bar. */
    MAIN_HEADER("main-header"),

    /** Control view: the page's own navigation surface (left rail / window frame). */
    MAIN_SURFACE("main-surface"),

    // ---------------------------------------------------------- login (D4)
    // 8 names, mirroring the Harmony shell. The light/dark bit is baked into
    // the name because these pages run without the injected layer: nobody
    // reports a theme there, so the native side can only pick by URL + system
    // night mode when the page starts loading (D5).

    /** `*.bigmodel.cn` — the BigModel login page is always the light surface. */
    AUTH_BIGMODEL("auth-bigmodel", fixedDark = false),

    /** `chat.z.ai` + `/auth/oauth/authorize`. */
    AUTH_ZAI_CONSENT_LIGHT("auth-zai-consent-light", fixedDark = false),
    AUTH_ZAI_CONSENT_DARK("auth-zai-consent-dark", fixedDark = true),

    /** `chat.z.ai` + `/auth…` (the phone login page). */
    AUTH_ZAI_LOGIN_LIGHT("auth-zai-login-light", fixedDark = false),

    /** Same page at ≤512 CSS px: the card turns pure white. */
    AUTH_ZAI_LOGIN_LIGHT_NARROW("auth-zai-login-light-narrow", fixedDark = false),
    AUTH_ZAI_LOGIN_DARK("auth-zai-login-dark", fixedDark = true),

    /** `zcode.z.ai` + `/web-remote`, `/share`, `/cn/share`. */
    AUTH_ZCODE_LIGHT("auth-zcode-light", fixedDark = false),
    AUTH_ZCODE_DARK("auth-zcode-dark", fixedDark = true);

    companion object {
        val DEFAULT = BOOT
    }
}

/** Light or dark, as resolved by the page itself. */
enum class PageTheme(val token: String) {
    LIGHT("light"),
    DARK("dark"),
}

object PageBarColor {

    /**
     * Token -> state / theme. Both live on the table's owner rather than on the
     * enums so there is exactly one place that knows the wire names — a rename on
     * the inject.js side then has one counterpart to change, not two.
     */
    fun stateOf(token: String?): PageBarState =
        PageBarState.entries.firstOrNull { it.token == token } ?: PageBarState.DEFAULT

    /** Unknown or missing means "the page has not said"; the caller falls back. */
    fun themeOf(token: String?): PageTheme? =
        PageTheme.entries.firstOrNull { it.token == token }


    // 网页 boot 阶段有两段底色，切点是 <html> 上的 `theme-zai-*` 类（由主包**顶层
    // IIFE** 挂上，早于 React 挂载），**不是**预渲染壳是否存在（两段壳都在）：
    //   boot-shell：壳在、主题类未挂 → `--color-background` 落回 `:root` 的
    //               neutral-50/900 = #FAFAFA/#171717（2026-09-28 真机像素实测
    //               250/255，缝恰在状态栏高度）；
    //   boot：      壳在、主题类已挂（IIFE → 首次 render，中间夹着 relay 握手 await），
    //               与 KICKED/接管、配对等待等 React 状态页同色 = #F8F8F8/#161616。
    // 旧实现两段共用一个 boot 态，只能二选一（取 #FAFAFA 则状态页有色缝，取 #F8F8F8
    // 则开屏第一段有色缝）；2026-10-08 拆分后三段全覆盖，配对 waiting 这类可长时间
    // 停留的页面不再被当成「罕见错误页」牺牲掉。
    private const val BOOT_SHELL_LIGHT = 0xFFFAFAFA.toInt()
    private const val BOOT_SHELL_DARK = 0xFF171717.toInt()
    private const val BOOT_LIGHT = 0xFFF8F8F8.toInt()
    private const val BOOT_DARK = 0xFF161616.toInt()
    private const val MAIN_HEADER_LIGHT = 0xFFFFFFFF.toInt()
    private const val MAIN_HEADER_DARK = 0xFF202020.toInt()
    // 2026-11-05 校正（鸿蒙 3.17.2 快照复核）：宽屏侧栏/外壳实际露出的是
    // --color-background-win-alt = #ECECEE/#2B2B2B（旧值 #F0F0F0 是 --color-sidebar
    // 的亮色、暗色还错了 21 级）。窄屏横向导航条用 max-md:bg-sidebar，亮色同值。
    private const val MAIN_SURFACE_LIGHT = 0xFFECECEE.toInt()
    private const val MAIN_SURFACE_DARK = 0xFF2B2B2B.toInt()

    // 登录/授权面（D4，2026-11-04 照抄鸿蒙现值——用户已确认观感无问题，勿改）。
    // 这是一张**独立**的表：色值随网页主题，不随系统深浅；`dark` 位由状态名自带。
    private const val AUTH_BIGMODEL_SURFACE = 0xFFF7F8FA.toInt()
    private const val AUTH_CONSENT_LIGHT = 0xFFF8F8F8.toInt()
    private const val AUTH_CONSENT_DARK = 0xFF161616.toInt()
    private const val AUTH_LOGIN_LIGHT = 0xFFF5F6F8.toInt()
    private const val AUTH_LOGIN_LIGHT_NARROW = 0xFFFFFFFF.toInt()
    private const val AUTH_LOGIN_DARK = 0xFF141618.toInt()
    private const val AUTH_ZCODE_LIGHT = 0xFFF8F8F8.toInt()
    private const val AUTH_ZCODE_DARK = 0xFF161616.toInt()

    /**
     * @param state which page state the injected layer last reported.
     * @param theme the theme the page resolved for itself; null means "the page
     *   has not said", in which case the caller passes the system's night mode.
     */
    fun resolve(state: PageBarState, dark: Boolean): Int = when (state) {
        PageBarState.BOOT_SHELL -> if (dark) BOOT_SHELL_DARK else BOOT_SHELL_LIGHT
        PageBarState.BOOT -> if (dark) BOOT_DARK else BOOT_LIGHT
        PageBarState.MAIN_HEADER -> if (dark) MAIN_HEADER_DARK else MAIN_HEADER_LIGHT
        PageBarState.MAIN_SURFACE -> if (dark) MAIN_SURFACE_DARK else MAIN_SURFACE_LIGHT
        // The auth names already carry their light/dark bit; `dark` is ignored on
        // purpose — otherwise a system theme switch would paint the wrong surface
        // on a page that follows the web theme.
        PageBarState.AUTH_BIGMODEL -> AUTH_BIGMODEL_SURFACE
        PageBarState.AUTH_ZAI_CONSENT_LIGHT -> AUTH_CONSENT_LIGHT
        PageBarState.AUTH_ZAI_CONSENT_DARK -> AUTH_CONSENT_DARK
        PageBarState.AUTH_ZAI_LOGIN_LIGHT -> AUTH_LOGIN_LIGHT
        PageBarState.AUTH_ZAI_LOGIN_LIGHT_NARROW -> AUTH_LOGIN_LIGHT_NARROW
        PageBarState.AUTH_ZAI_LOGIN_DARK -> AUTH_LOGIN_DARK
        PageBarState.AUTH_ZCODE_LIGHT -> AUTH_ZCODE_LIGHT
        PageBarState.AUTH_ZCODE_DARK -> AUTH_ZCODE_DARK
    }

    /**
     * D8：图标明暗必须跟着**真的画上去的那一面**，不能跟着页面原始主题。
     *
     * 登录页没有注入层 ⇒ `theme` 恒 null；旧实现里颜色按系统夜景取（深色下
     * `#171717` 的条带），图标却按 `pageTheme != DARK` 取「浅色表面 ⇒ 深图标」，
     * 于是深图标压在近黑条带上。这里把两者收进同一个函数，调用方只有一个 `dark`。
     */
    fun darkFor(state: PageBarState, theme: PageTheme?, systemDark: Boolean): Boolean =
        state.fixedDark ?: (theme?.let { it == PageTheme.DARK } ?: systemDark)

    /**
     * D5：登录页没有注入层 ⇒ 状态名只能由 URL 先给初值，不等回推。
     *
     * `dark` 是**网页**主题的替代信号：登录页上唯一能拿到的就是系统夜景。鸿蒙的默认
     * 口径照抄在这里——bigmodel 恒亮、chat.z.ai 默认亮、zcode `/web-remote|/share`
     * 默认暗；`narrow` 只影响 chat.z.ai 登录页（≤512 CSS px 时卡片转纯白）。
     */
    fun authStateFor(surface: RemoteUrl.AuthSurface, dark: Boolean, narrow: Boolean): PageBarState =
        when (surface) {
            RemoteUrl.AuthSurface.BIGMODEL -> PageBarState.AUTH_BIGMODEL
            RemoteUrl.AuthSurface.ZAI_CONSENT ->
                if (dark) PageBarState.AUTH_ZAI_CONSENT_DARK else PageBarState.AUTH_ZAI_CONSENT_LIGHT
            RemoteUrl.AuthSurface.ZAI_LOGIN -> when {
                dark -> PageBarState.AUTH_ZAI_LOGIN_DARK
                narrow -> PageBarState.AUTH_ZAI_LOGIN_LIGHT_NARROW
                else -> PageBarState.AUTH_ZAI_LOGIN_LIGHT
            }
            RemoteUrl.AuthSurface.ZCODE ->
                if (dark) PageBarState.AUTH_ZCODE_DARK else PageBarState.AUTH_ZCODE_LIGHT
        }

    /**
     * One line for the log, so a field round can tell what was applied.
     *
     * [appliedArgb] is passed in rather than recomputed: the caller may have had
     * to fall back to the system's night mode when the page has not reported a
     * theme, and the log has to state the colour that is actually on screen.
     */
    fun describe(state: PageBarState, theme: PageTheme?, appliedArgb: Int): String {
        val name = theme?.token
            ?: if (state.fixedDark != null) "fixed" else "system"
        return "状态栏底色: ${state.token}/$name → #${"%06X".format(appliedArgb and 0xFFFFFF)}"
    }

    /**
     * What `isAppearanceLightStatusBars` has to be set to, i.e. "the surface
     * behind the bar is light, so draw dark icons". The caller passes the
     * **resolved** `dark` from [darkFor] — not the page's raw theme (D8).
     */
    fun appearanceLightStatusBars(dark: Boolean): Boolean = !dark

    /**
     * 注入层**采样**到的顶部实际颜色（`#RRGGBB`）→ ARGB。
     *
     * 状态名是推理，采样色是事实：页面改版/同名不同色的表面都靠它兜住。非法值返回
     * null，调用方回退到状态表——采样失败不能让条带没颜色。
     */
    fun parseHex(token: String?): Int? {
        val value = token?.trim().orEmpty()
        if (value.isEmpty()) return null
        val hex = if (value.startsWith("#")) value.substring(1) else value
        if (hex.length != 6) return null
        val rgb = hex.toLongOrNull(16) ?: return null
        return 0xFF000000.toInt() or rgb.toInt()
    }

    /**
     * 采样色是不是深色面（状态栏图标要用浅色）。
     *
     * WCAG 相对亮度，阈值 0.5：`#161616/#202020/#2B2B2B` → 深；`#F8F8F8/#ECECEE/#FFF` → 浅。
     * 只在有采样色时用；没有采样色仍走 [darkFor] 的页面主题口径。
     */
    fun isDarkSurface(argb: Int): Boolean {
        fun channel(value: Int): Double {
            val s = value / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        val luminance = 0.2126 * channel((argb shr 16) and 0xFF) +
            0.7152 * channel((argb shr 8) and 0xFF) +
            0.0722 * channel(argb and 0xFF)
        return luminance < 0.5
    }
}
