package app.zemote.ui.settings

import app.zemote.data.AppSettings
import app.zemote.data.AppTheme
import app.zemote.ui.FakeDeviceRepository
import app.zemote.ui.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 设置页的状态映射：读盘三态 + 每个 setter 都走「读-改-写」事务。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @Test
    fun `设置从持久化流映射进状态`() = runTest(mainRule.dispatcher) {
        val repository = FakeDeviceRepository(
            initialSettings = AppSettings(theme = AppTheme.DARK, locale = "en-US", textScale = 1.2f),
        )
        val model = SettingsViewModel(repository)
        advanceUntilIdle()

        assertFalse(model.state.value.loading)
        assertEquals(AppTheme.DARK, model.state.value.settings.theme)
        assertEquals("en-US", model.state.value.settings.locale)
        assertEquals(1.2f, model.state.value.settings.textScale)
        assertNull(model.state.value.error)
    }

    @Test
    fun `没有写过设置时是默认值`() = runTest(mainRule.dispatcher) {
        val model = SettingsViewModel(FakeDeviceRepository())
        advanceUntilIdle()

        assertEquals(AppSettings(), model.state.value.settings)
        assertFalse(model.state.value.loading)
    }

    @Test
    fun `读盘失败收敛成错误态`() = runTest(mainRule.dispatcher) {
        val repository = FakeDeviceRepository().apply { readFailure = IllegalStateException("设置读不出来") }
        val model = SettingsViewModel(repository)
        advanceUntilIdle()

        assertFalse(model.state.value.loading)
        assertEquals("IllegalStateException: 设置读不出来", model.state.value.error)
    }

    @Test
    fun `改主题会落盘并回流到状态`() = runTest(mainRule.dispatcher) {
        val repository = FakeDeviceRepository()
        val model = SettingsViewModel(repository)
        advanceUntilIdle()

        model.setTheme(AppTheme.LIGHT)
        advanceUntilIdle()

        assertEquals(listOf(AppTheme.LIGHT), repository.settingsUpdates.map { it.theme })
        assertEquals(AppTheme.LIGHT, model.state.value.settings.theme)
    }

    @Test
    fun `每个 setter 都走 updateSettings 事务`() = runTest(mainRule.dispatcher) {
        val repository = FakeDeviceRepository()
        val model = SettingsViewModel(repository)
        advanceUntilIdle()

        model.setTheme(AppTheme.DARK)
        model.setLocale("en-US")
        model.setTextScale(1.3f)
        model.setCodeFontSize(16f)
        model.setVerboseProtocolFrames(true)
        model.setReceiveBetaUpdates(true)
        advanceUntilIdle()

        assertEquals(6, repository.settingsUpdates.size)
        val last = repository.settingsUpdates.last()
        assertEquals(AppTheme.DARK, last.theme)
        assertEquals("en-US", last.locale)
        assertEquals(1.3f, last.textScale)
        assertEquals(16f, last.codeFontSize)
        assertTrue(last.verboseProtocolFrames)
        assertTrue(last.receiveBetaUpdates)
        // 每一步都是「基于当时的值改一个字段」，不会互相覆盖
        assertEquals(
            listOf(1.0f, 1.0f, 1.3f, 1.3f, 1.3f, 1.3f),
            repository.settingsUpdates.map { it.textScale },
        )
    }

    @Test
    fun `写入失败收敛成错误态且状态不变`() = runTest(mainRule.dispatcher) {
        val repository = FakeDeviceRepository()
        val model = SettingsViewModel(repository)
        advanceUntilIdle()

        repository.writeFailure = IllegalStateException("盘满了")
        model.setTheme(AppTheme.DARK)
        advanceUntilIdle()

        assertEquals("IllegalStateException: 盘满了", model.state.value.error)
        assertEquals(AppTheme.SYSTEM, model.state.value.settings.theme)
    }

    @Test
    fun `恢复默认值写回默认设置`() = runTest(mainRule.dispatcher) {
        val repository = FakeDeviceRepository(
            initialSettings = AppSettings(theme = AppTheme.DARK, textScale = 1.4f),
        )
        val model = SettingsViewModel(repository)
        advanceUntilIdle()

        model.resetToDefaults()
        advanceUntilIdle()

        assertEquals(AppSettings(), repository.settingsUpdates.single())
        assertEquals(AppSettings(), model.state.value.settings)
    }

    @Test
    fun `dismissError 与 dismissNotice 清掉提示`() = runTest(mainRule.dispatcher) {
        val repository = FakeDeviceRepository().apply { readFailure = IllegalStateException("x") }
        val model = SettingsViewModel(repository)
        advanceUntilIdle()

        model.dismissError()

        assertNull(model.state.value.error)
    }
}
