package app.zemote.ui.nav

/**
 * 应用里的一个界面（路由）。
 *
 * 全部路由都是**值**：`data object` / `data class`，没有 Android 依赖、不需要 `Parcelable`
 * ——返回栈是进程内的纯数据（见 [NavState] 的「已知缺口」）。
 *
 * 参数只带**界面自己需要的最小上下文**：当前电脑在 `DeviceStore` 里、主题在设置里，
 * 都不重复塞进路由；只有「进对话时定位到哪个工作区/任务」是路由本身的语义。
 */
sealed interface Route {

    /**
     * 主界面：底部双 Tab（对话 / 设置）。
     *
     * 应用根。切换 Tab 用 [NavState.replaceRoot]，不产生返回层级。
     */
    data class Main(val tab: MainTab = MainTab.HOME) : Route

    /** 扫码 / 粘贴配对链接。 */
    data object Pairing : Route

    /**
     * 对话页。
     *
     * @param deviceId 连到哪台电脑。**必须由路由携带**，不能只靠「当前电脑」：
     *   点任务行时会异步写 `setCurrentDevice`，而对话页紧接着就组合出来了，
     *   去读「当前电脑」有真实竞态。
     * @param workspaceKey 进入时要打开的工作区；`null` = 进页后由用户选。
     * @param workspacePath 真实磁盘路径（`conversation_open` 的 cwd）。项目树工作区
     *   不在 bootstrap 里，必须由路由带上；否则对话层只能拿 key 当路径。
     * @param workspaceIdentity 远端工作区标识；本地工作区缺席。
     * @param taskId 进入时要定位的任务；`null` = 新建对话的空页。
     * @param hostId 任务/项目所属 host（`local` 或远端 id）。项目树任务靠它触发
     *   桌面端的 `prepareProjectWorkspace`，否则非当前 tab 的项目会拒绝开桥。
     * @param projectId `project.readTree` 的项目 id；在项目里新建对话时用。
     */
    data class Chat(
        val deviceId: String,
        val workspaceKey: String? = null,
        val taskId: String? = null,
        /** 首页已知的任务标题：对话页在 sessions-index 到达前先用它，避免显示「未命名」。 */
        val taskTitle: String? = null,
        val workspacePath: String? = null,
        val workspaceIdentity: String? = null,
        val hostId: String? = null,
        val projectId: String? = null,
    ) : Route

    /** 设置二级页。 */
    data class SettingsSection(val id: String) : Route

    /** 关于与更新。 */
    data object AboutUpdate : Route
}

/** 主界面的两个 Tab。 */
enum class MainTab { HOME, SETTINGS }

/**
 * 导航状态：一条**永不为空**的返回栈。
 *
 * ## 为什么手写，而不是引 `androidx.navigation`
 *
 * 需求面窄：没有深链、没有嵌套导航图、没有多返回栈、没有类型安全的参数序列化需求。
 * 手写状态机零依赖、可 JVM 单测，代价是没有预测式返回手势（当前每个界面都是整屏切换，
 * 可以接受）与 `SavedStateHandle`（跨进程死亡回到根）。
 *
 * ## 不变式
 *
 * 栈**永不为空**（至少一个根），所以 [current] 不需要可空类型。构造请用 [NavState.of]。
 */
data class NavState(val stack: List<Route>) {

    init {
        require(stack.isNotEmpty()) { "导航栈不能为空：至少要有根路由（用 NavState.of(...)）" }
    }

    /** 当前显示的路由。 */
    val current: Route
        get() = stack.last()

    /** 栈深（根为 1）。 */
    val depth: Int
        get() = stack.size

    /** 是否还能返回。`false` 表示在根上——此时系统返回应交给系统（退出应用）。 */
    val canGoBack: Boolean
        get() = stack.size > 1

    /** 入栈。与 [current] 完全相同的路由**不入栈**（防连点）。 */
    fun push(route: Route): NavState =
        if (route == current) this else NavState(stack + route)

    /** 出栈。已在根上时原样返回（系统返回键会走到这里，根上按返回应该退出应用）。 */
    fun pop(): NavState =
        if (stack.size <= 1) this else NavState(stack.dropLast(1))

    /** 换根：整条栈替换成单条 [route]。用于切 Tab、配对完成回主界面。 */
    fun replaceRoot(route: Route): NavState = NavState(listOf(route))

    companion object {
        /** 应用的根界面。 */
        val ROOT: Route = Route.Main()

        /** 建一条只有根的栈。 */
        fun of(root: Route = ROOT): NavState = NavState(listOf(root))
    }
}
