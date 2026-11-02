// ---------------------------------------------------------------------------
//  zai 主题令牌与 ThemeMode 映射的最小 JVM 单测
//
//  覆盖：
//    1. ZaiDark / ZaiLight 的关键令牌已解析（非空、非 Color.Unspecified），且与网页
//       实测值一致（docs/UI-CONFIRM-3.17.1.md §0.4、3.17.1 CSS）。
//    2. 皮肤确实换了一套值：两套各自至少 4/6 个关键令牌与 3.15.3 默认 Light/Dark
//       不同（zai-light 的 card 在网页上本来就是 #FFFFFF，不算失败）。
//    3. ThemeMode.resolveDark 的映射（SYSTEM 跟随系统、DARK/LIGHT 强制）。
// ---------------------------------------------------------------------------

package app.zemote.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZaiThemeTest {

    private val keyTokens: List<Pair<String, Color>> = listOf(
        "background" to Color(0xFF161616),
        "foreground" to Color(0xFFD4D4D4),
        "card" to Color(0xFF2B2B2B),
        "border" to Color(0x1AFFFFFF),
        "primary" to Color(0xFFFFFFFF),
        "accent" to Color(0xFF001D3D),
    )

    @Test
    fun zaiDarkMatchesWebSnapshot() {
        val c = ZemoteColors.ZaiDark
        val actual = mapOf(
            "background" to c.background,
            "foreground" to c.foreground,
            "card" to c.card,
            "border" to c.border,
            "primary" to c.primary,
            "accent" to c.accent,
        )
        keyTokens.forEach { (name, expected) ->
            assertEquals("zai-dark $name", expected, actual.getValue(name))
        }
    }

    @Test
    fun zaiLightMatchesWebSnapshot() {
        val c = ZemoteColors.ZaiLight
        // 3.17.1 `:root,:host` + `.theme-zai-light` 解析值
        assertEquals("zai-light background", Color(0xFFF8F8F8), c.background)
        assertEquals("zai-light foreground", Color(0xFF262626), c.foreground)
        assertEquals("zai-light card", Color(0xFFFFFFFF), c.card)
        assertEquals("zai-light border", Color(0x1A0D0D0D), c.border)
        assertEquals("zai-light primary", Color(0xFF000000), c.primary)
        assertEquals("zai-light accent", Color(0xFFEBF4FF), c.accent)
    }

    @Test
    fun keyTokensAreSpecifiedAndNotJustTheDefaultPalette() {
        val darkPairs = listOf(
            ZemoteColors.ZaiDark.background to ZemoteColors.Dark.background,
            ZemoteColors.ZaiDark.foreground to ZemoteColors.Dark.foreground,
            ZemoteColors.ZaiDark.card to ZemoteColors.Dark.card,
            ZemoteColors.ZaiDark.border to ZemoteColors.Dark.border,
            ZemoteColors.ZaiDark.primary to ZemoteColors.Dark.primary,
            ZemoteColors.ZaiDark.accent to ZemoteColors.Dark.accent,
        )
        val lightPairs = listOf(
            ZemoteColors.ZaiLight.background to ZemoteColors.Light.background,
            ZemoteColors.ZaiLight.foreground to ZemoteColors.Light.foreground,
            ZemoteColors.ZaiLight.card to ZemoteColors.Light.card,
            ZemoteColors.ZaiLight.border to ZemoteColors.Light.border,
            ZemoteColors.ZaiLight.primary to ZemoteColors.Light.primary,
            ZemoteColors.ZaiLight.accent to ZemoteColors.Light.accent,
        )

        // 非空：alpha 不为 0、也不是 Color.Unspecified
        (darkPairs + lightPairs).forEach { (zai, _) ->
            assertNotEquals(Color.Unspecified, zai)
            assertTrue("zai token 透明: $zai", zai.alpha > 0f)
        }

        // 两套皮肤各自与默认口径不同（zai-light 的 card 网页上同值 #FFFFFF，属正常）：
        // zai-dark 至少 5/6 不同，zai-light 至少 4/6 不同。
        assertTrue("zai-dark 与默认 Dark 区分度不足", darkPairs.count { it.first != it.second } >= 5)
        assertTrue("zai-light 与默认 Light 区分度不足", lightPairs.count { it.first != it.second } >= 4)
        assertNotEquals(ZemoteColors.ZaiDark.background, ZemoteColors.Dark.background)
        assertNotEquals(ZemoteColors.ZaiLight.background, ZemoteColors.Light.background)
    }

    @Test
    fun themeModeResolution() {
        assertTrue(ThemeMode.SYSTEM.resolveDark(systemInDark = true))
        assertFalse(ThemeMode.SYSTEM.resolveDark(systemInDark = false))
        assertTrue(ThemeMode.DARK.resolveDark(systemInDark = false))
        assertTrue(ThemeMode.DARK.resolveDark(systemInDark = true))
        assertFalse(ThemeMode.LIGHT.resolveDark(systemInDark = true))
        assertFalse(ThemeMode.LIGHT.resolveDark(systemInDark = false))
    }
}
