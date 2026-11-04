package app.zemote.ui.settings

import app.zemote.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 主题选项映射：固定三态、顺序与文案，且与 [ThemeMode] 一一对应。
 */
class ThemeOptionsTest {

    @Test
    fun `选项顺序与文案固定`() {
        assertEquals(
            listOf(ThemeMode.SYSTEM, ThemeMode.DARK, ThemeMode.LIGHT),
            ThemeOption.All.map { it.mode },
        )
        assertEquals(
            listOf("跟随系统", "深色", "浅色"),
            ThemeOption.All.map { it.label },
        )
    }

    @Test
    fun `每个 ThemeMode 都能映射到唯一选项`() {
        for (mode in ThemeMode.entries) {
            val matches = ThemeOption.All.filter { it.mode == mode }
            assertEquals("模式：$mode", 1, matches.size)
            assertEquals(mode, ThemeOption.of(mode).mode)
        }
    }

    @Test
    fun `选项覆盖全部 ThemeMode`() {
        assertEquals(ThemeMode.entries.toSet(), ThemeOption.All.map { it.mode }.toSet())
    }

    @Test
    fun `of 往返不变`() {
        for (option in ThemeOption.All) {
            assertEquals(option, ThemeOption.of(option.mode))
        }
    }
}
