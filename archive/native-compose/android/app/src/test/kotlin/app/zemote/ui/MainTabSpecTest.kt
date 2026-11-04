package app.zemote.ui

import app.zemote.ui.nav.MainTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 底部双 Tab 的呈现数据：顺序、文案与选中态是纯值，逐条钉住。
 */
class MainTabSpecTest {

    @Test
    fun `默认选中对话时对话为选中态`() {
        val specs = mainTabSpecs(MainTab.HOME)

        assertEquals(listOf(MainTab.HOME, MainTab.SETTINGS), specs.map { it.tab })
        assertEquals(listOf("对话", "设置"), specs.map { it.label })
        assertEquals(
            listOf(TabEmphasis.SELECTED, TabEmphasis.UNSELECTED),
            specs.map { it.emphasis },
        )
        assertTrue(specs[0].selected)
        assertFalse(specs[1].selected)
    }

    @Test
    fun `选中设置时设置唯一高亮`() {
        val specs = mainTabSpecs(MainTab.SETTINGS)

        assertFalse(specs[0].selected)
        assertTrue(specs[1].selected)
    }

    @Test
    fun `任何 Tab 下都恰好一个选中项`() {
        for (tab in MainTab.entries) {
            assertEquals("当前 Tab：$tab", 1, mainTabSpecs(tab).count { it.selected })
        }
    }

    @Test
    fun `Tab 顺序固定为对话在前设置在后`() {
        assertEquals(
            listOf(MainTab.HOME, MainTab.SETTINGS),
            mainTabSpecs(MainTab.SETTINGS).map { it.tab },
        )
    }
}
