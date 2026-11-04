package com.zcode.remote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the fixed status-bar colour table.
 *
 * These matter more than they look: the strip behind the status bar is the only
 * thing on screen that has to match the remote page pixel for pixel, and the
 * values it is matched against live in the page's stylesheet, not here. A wrong
 * literal shows up as a seam the user sees immediately, and the device is the
 * only place it can be caught — so the mapping itself is pinned here.
 */
class PageBarColorTest {

    // Not `const`: 0xFFFAFAFA is a Long literal, so `.toInt()` is a call and the
    // value is not a compile-time constant.
    // 2026-09-28 像素实测 + 2026-10-08 快照解析：boot 阶段有两段，切点是 <html> 上的
    // theme-zai-* 类（主包顶层 IIFE 挂上，早于 React 挂载）——
    //   boot-shell（未着主题）：--color-background 落回 :root 的 neutral-50/900，
    //                            亮 #FAFAFA（真机实测 250/255，缝恰在状态栏高度）、
    //                            暗 #171717（oklch(20.5% 0 0)）；
    //   boot（已着主题）：与 KICKED/配对等待等状态页同色，亮 #F8F8F8、暗 #161616。
    private val BOOT_SHELL_LIGHT = 0xFFFAFAFA.toInt()
    private val BOOT_SHELL_DARK = 0xFF171717.toInt()
    private val BOOT_LIGHT = 0xFFF8F8F8.toInt()
    private val BOOT_DARK = 0xFF161616.toInt()
    private val HEADER_LIGHT = 0xFFFFFFFF.toInt()
    private val HEADER_DARK = 0xFF202020.toInt()
    private val SURFACE_LIGHT = 0xFFECECEE.toInt()
    private val SURFACE_DARK = 0xFF2B2B2B.toInt()
    // D4：登录/授权面的专用表（照鸿蒙现值，用户已确认观感无问题）。
    private val AUTH_BIGMODEL = 0xFFF7F8FA.toInt()
    private val AUTH_CONSENT_LIGHT = 0xFFF8F8F8.toInt()
    private val AUTH_CONSENT_DARK = 0xFF161616.toInt()
    private val AUTH_LOGIN_LIGHT = 0xFFF5F6F8.toInt()
    private val AUTH_LOGIN_LIGHT_NARROW = 0xFFFFFFFF.toInt()
    private val AUTH_LOGIN_DARK = 0xFF141618.toInt()

    @Test
    fun `every state has a light and a dark surface`() {
        assertEquals(BOOT_SHELL_LIGHT, PageBarColor.resolve(PageBarState.BOOT_SHELL, dark = false))
        assertEquals(BOOT_SHELL_DARK, PageBarColor.resolve(PageBarState.BOOT_SHELL, dark = true))
        assertEquals(BOOT_LIGHT, PageBarColor.resolve(PageBarState.BOOT, dark = false))
        assertEquals(BOOT_DARK, PageBarColor.resolve(PageBarState.BOOT, dark = true))
        assertEquals(HEADER_LIGHT, PageBarColor.resolve(PageBarState.MAIN_HEADER, dark = false))
        assertEquals(HEADER_DARK, PageBarColor.resolve(PageBarState.MAIN_HEADER, dark = true))
        assertEquals(SURFACE_LIGHT, PageBarColor.resolve(PageBarState.MAIN_SURFACE, dark = false))
        assertEquals(SURFACE_DARK, PageBarColor.resolve(PageBarState.MAIN_SURFACE, dark = true))
    }

    @Test
    fun `the tokens are the ones the injected layer reports`() {
        // These strings are the contract with inject.js section 6; a rename on one
        // side and not the other silently pins the bar to the boot colour.
        assertEquals("boot-shell", PageBarState.BOOT_SHELL.token)
        assertEquals("boot", PageBarState.BOOT.token)
        assertEquals("main-header", PageBarState.MAIN_HEADER.token)
        assertEquals("main-surface", PageBarState.MAIN_SURFACE.token)
        assertEquals("light", PageTheme.LIGHT.token)
        assertEquals("dark", PageTheme.DARK.token)
    }

    @Test
    fun `an unknown or missing state falls back to boot, never to a wrong colour`() {
        assertEquals(PageBarState.BOOT, PageBarColor.stateOf(null))
        assertEquals(PageBarState.BOOT, PageBarColor.stateOf(""))
        assertEquals(PageBarState.BOOT, PageBarColor.stateOf("main"))
        assertEquals(PageBarState.BOOT, PageBarColor.stateOf("MAIN-HEADER"))
        assertTrue(PageBarState.entries.all { PageBarColor.stateOf(it.token) == it })
    }

    @Test
    fun `an unknown theme means the system night mode decides`() {
        assertEquals(null, PageBarColor.themeOf(null))
        assertEquals(null, PageBarColor.themeOf(""))
        assertEquals(null, PageBarColor.themeOf("zai-dark"))
        assertEquals(PageTheme.LIGHT, PageBarColor.themeOf("light"))
        assertEquals(PageTheme.DARK, PageBarColor.themeOf("dark"))
    }

    @Test
    fun `the status bar icons invert against the surface`() {
        // Light surface -> dark icons (isAppearanceLightStatusBars = true).
        assertTrue(PageBarColor.appearanceLightStatusBars(dark = false))
        assertFalse(PageBarColor.appearanceLightStatusBars(dark = true))
    }

    @Test
    fun `D8 the icons follow the resolved surface, not the page theme`() {
        // The bug: system dark + pageTheme == null (every login page) painted #171717
        // and then asked for dark icons. The resolved `dark` is the single input now.
        val systemDark = true
        val state = PageBarState.AUTH_BIGMODEL
        val dark = PageBarColor.darkFor(state, theme = null, systemDark = systemDark)
        // bigmodel is a fixed light surface even in system dark -> dark icons, light strip.
        assertFalse(dark)
        assertTrue(PageBarColor.appearanceLightStatusBars(dark))
        assertEquals(AUTH_BIGMODEL, PageBarColor.resolve(state, dark))
        // A normal page with no theme falls back to the system, and the icons agree.
        val bootDark = PageBarColor.darkFor(PageBarState.BOOT_SHELL, null, systemDark)
        assertTrue(bootDark)
        assertFalse(PageBarColor.appearanceLightStatusBars(bootDark))
        // A page that reports a theme wins over the system.
        assertFalse(PageBarColor.darkFor(PageBarState.BOOT, PageTheme.LIGHT, systemDark))
    }

    @Test
    fun `D4 the eight login surfaces carry their own light and dark bit`() {
        val auth = PageBarState.entries.filter { it.token.startsWith("auth-") }
        assertEquals("八个 auth-* 状态名，与鸿蒙口径一致", 8, auth.size)
        assertEquals(
            listOf(
                "auth-bigmodel", "auth-zai-consent-light", "auth-zai-consent-dark",
                "auth-zai-login-light", "auth-zai-login-light-narrow", "auth-zai-login-dark",
                "auth-zcode-light", "auth-zcode-dark",
            ),
            auth.map { it.token },
        )
        // The auth names ignore the `dark` flag: a system night switch must not repaint
        // a page that follows the *web* theme.
        assertEquals(AUTH_CONSENT_LIGHT, PageBarColor.resolve(PageBarState.AUTH_ZAI_CONSENT_LIGHT, dark = true))
        assertEquals(AUTH_CONSENT_DARK, PageBarColor.resolve(PageBarState.AUTH_ZAI_CONSENT_DARK, dark = false))
        assertEquals(AUTH_LOGIN_LIGHT, PageBarColor.resolve(PageBarState.AUTH_ZAI_LOGIN_LIGHT, dark = true))
        assertEquals(AUTH_LOGIN_LIGHT_NARROW, PageBarColor.resolve(PageBarState.AUTH_ZAI_LOGIN_LIGHT_NARROW, dark = true))
        assertEquals(AUTH_LOGIN_DARK, PageBarColor.resolve(PageBarState.AUTH_ZAI_LOGIN_DARK, dark = false))
        assertEquals(AUTH_CONSENT_LIGHT, PageBarColor.resolve(PageBarState.AUTH_ZCODE_LIGHT, dark = true))
        assertEquals(AUTH_CONSENT_DARK, PageBarColor.resolve(PageBarState.AUTH_ZCODE_DARK, dark = false))
    }

    @Test
    fun `D5 the URL gives the login page its initial surface`() {
        assertEquals(
            PageBarState.AUTH_BIGMODEL,
            PageBarColor.authStateFor(RemoteUrl.AuthSurface.BIGMODEL, dark = true, narrow = true),
        )
        assertEquals(
            PageBarState.AUTH_ZAI_CONSENT_DARK,
            PageBarColor.authStateFor(RemoteUrl.AuthSurface.ZAI_CONSENT, dark = true, narrow = false),
        )
        assertEquals(
            PageBarState.AUTH_ZAI_LOGIN_LIGHT,
            PageBarColor.authStateFor(RemoteUrl.AuthSurface.ZAI_LOGIN, dark = false, narrow = false),
        )
        // ≤512px: the card turns pure white (the narrow variant of the same page).
        assertEquals(
            PageBarState.AUTH_ZAI_LOGIN_LIGHT_NARROW,
            PageBarColor.authStateFor(RemoteUrl.AuthSurface.ZAI_LOGIN, dark = false, narrow = true),
        )
        // Dark wins over narrow: there is no narrow dark variant.
        assertEquals(
            PageBarState.AUTH_ZAI_LOGIN_DARK,
            PageBarColor.authStateFor(RemoteUrl.AuthSurface.ZAI_LOGIN, dark = true, narrow = true),
        )
        assertEquals(
            PageBarState.AUTH_ZCODE_DARK,
            PageBarColor.authStateFor(RemoteUrl.AuthSurface.ZCODE, dark = true, narrow = false),
        )
    }

    @Test
    fun `the log line names the state, the theme and the applied colour`() {
        val line = PageBarColor.describe(PageBarState.MAIN_HEADER, PageTheme.DARK, HEADER_DARK)
        assertTrue(line.contains("main-header/dark"))
        assertTrue(line.contains("#202020"))
        val shell = PageBarColor.describe(PageBarState.BOOT_SHELL, null, BOOT_SHELL_LIGHT)
        assertTrue(shell.contains("boot-shell/system"))
        assertTrue(shell.contains("#FAFAFA"))
        val themed = PageBarColor.describe(PageBarState.BOOT, null, BOOT_LIGHT)
        assertTrue(themed.contains("boot/system"))
        assertTrue(themed.contains("#F8F8F8"))
    }
}
