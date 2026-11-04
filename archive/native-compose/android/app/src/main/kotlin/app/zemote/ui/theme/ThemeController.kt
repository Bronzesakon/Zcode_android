// ---------------------------------------------------------------------------
//  ThemeController —— 主题模式（跟随系统 / 深色 / 浅色）的持久化控制器
//
//  口径（docs/UI-CONFIRM-3.17.1.md §1.2）：
//    - 默认跟随系统：系统深色 -> zai-dark，系统浅色 -> zai-light；
//    - 手动切换只在「设置 → 外观」，首页不放主题菜单；
//    - 不做动态取色（Material You 壁纸取色），见 Theme.kt。
//
//  API 契约（设置页代理直接调用，签名不可改）：
//    enum class ThemeMode { SYSTEM, DARK, LIGHT }
//    class ThemeController(context: Context) {
//        val mode: StateFlow<ThemeMode>
//        fun setMode(mode: ThemeMode)
//    }
//    val LocalThemeController = staticCompositionLocalOf<ThemeController?> { null }
// ---------------------------------------------------------------------------

package app.zemote.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 主题模式：跟随系统 / 强制深色 / 强制浅色。 */
enum class ThemeMode {
    SYSTEM,
    DARK,
    LIGHT,
}

/**
 * 把 [ThemeMode] 与系统当前明暗合成「最终是否用暗色」。
 *
 * 纯函数，单测直接覆盖；[ThemeMode.SYSTEM] 时返回 [systemInDark]。
 */
fun ThemeMode.resolveDark(systemInDark: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemInDark
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
}

/**
 * 主题控制器：读写 [ThemeMode]，用 SharedPreferences 持久化。
 *
 * 构造只保留 applicationContext，避免持有 Activity 造成泄漏。读取用同步
 * `getString`（单次、量小），写入用 `apply()`。外部通过 [mode] 订阅变化；
 * 设置页调用 [setMode] 后，[ZemoteTheme]（经 [LocalThemeController]）会立即重组。
 */
class ThemeController(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(readPersistedMode())
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    /** 持久化并广播新模式；写入失败不影响内存态（下次启动回退到上次成功值）。 */
    fun setMode(mode: ThemeMode) {
        if (_mode.value == mode) return
        _mode.value = mode
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    private fun readPersistedMode(): ThemeMode {
        val raw = prefs.getString(KEY_MODE, null) ?: return ThemeMode.SYSTEM
        return runCatching { ThemeMode.valueOf(raw) }.getOrDefault(ThemeMode.SYSTEM)
    }

    private companion object {
        const val PREFS_NAME = "zemote_theme"
        const val KEY_MODE = "theme_mode"
    }
}

/**
 * 当前可用的 [ThemeController]；未安装（如预览/测试）时为 null。
 *
 * [ZemoteTheme] 读到非 null 时以它的 [ThemeController.mode] 为准，否则回退到
 * 调用方传入的 darkTheme（系统明暗或设置页的显式值）。
 */
val LocalThemeController = staticCompositionLocalOf<ThemeController?> { null }
