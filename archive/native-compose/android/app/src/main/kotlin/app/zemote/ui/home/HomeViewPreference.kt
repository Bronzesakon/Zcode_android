package app.zemote.ui.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 首页项目树的视图。
 *
 * [Grouped]：按项目分组（项目行 → 展开的任务行）；
 * [Timeline]：按更新时间平铺（置顶仍在最上方独立成区）。
 */
enum class HomeView(val label: String) {
    Grouped("分组"),
    Timeline("列表"),
    ;

    fun toggled(): HomeView = if (this == Grouped) Timeline else Grouped
}

/**
 * 首页视图偏好的**会话内**存储。
 *
 * 放进程级而不是 `rememberSaveable`：切到「设置」Tab 时 HomeScreen 会离开组合，
 * `rememberSaveable` 留不住；这里只存内存，不落盘，重启回到默认「分组」。
 */
object HomeViewPreference {

    private val _view = MutableStateFlow(HomeView.Grouped)

    val view: StateFlow<HomeView> = _view.asStateFlow()

    fun select(view: HomeView) {
        _view.value = view
    }

    fun toggle() {
        _view.value = _view.value.toggled()
    }

    /** 单测用：恢复默认，避免用例互相污染。 */
    internal fun reset() {
        _view.value = HomeView.Grouped
    }
}
