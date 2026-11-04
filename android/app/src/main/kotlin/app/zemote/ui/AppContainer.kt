package app.zemote.ui

import android.content.Context
import android.content.res.Configuration
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.zemote.BuildConfig
import app.zemote.auth.OAuthTokenClient
import app.zemote.auth.RemoteTokenClient
import app.zemote.data.AppSettings
import app.zemote.data.AppTheme
import app.zemote.data.AuthStore
import app.zemote.data.DataStoreAuthStore
import app.zemote.data.DeviceStore
import app.zemote.notify.NotificationCoordinator
import app.zemote.pairing.UnavailableQrScanner
import app.zemote.ui.chat.ChatOps
import app.zemote.ui.chat.ChatViewModel
import app.zemote.ui.auth.LoginViewModel
import app.zemote.ui.chat.ConversationRegistry
import app.zemote.ui.chat.EngineChatOps
import app.zemote.ui.devices.DeviceRepository
import app.zemote.ui.devices.DevicesViewModel
import app.zemote.ui.devices.RelayStatusWriter
import app.zemote.ui.devices.StoreDeviceRepository
import app.zemote.ui.home.HomeViewModel
import app.zemote.ui.nav.NavViewModel
import app.zemote.ui.pairing.PairingViewModel
import app.zemote.ui.settings.SettingsSectionViewModel
import app.zemote.ui.settings.SettingsViewModel
import app.zemote.ui.update.ApkInstaller
import app.zemote.ui.update.DownloadNotificationSink
import app.zemote.ui.update.UpdateViewModel
import app.zemote.update.HttpFetcher
import app.zemote.update.InstallResult
import app.zemote.update.OkHttpHttpFetcher
import app.zemote.update.UpdateChecker
import app.zemote.update.UpdateDownloader
import app.zemote.update.UpdateInstaller
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

/**
 * 手工依赖装配（**不引 DI 框架**）。
 *
 * ## 为什么不用 Hilt / Koin
 *
 * 全应用的依赖就这些：一个 [DeviceStore]、一个 [EnginePort]（内含引擎会话）、一个
 * [DeviceRepository]、一个 [ChatOps]、一个更新器三件套。用注解处理器引入的间接层
 * （编译期代码生成、组件作用域、测试替换规则）换来的收益接近零，而代价是构建时间、
 * 版本兼容面和一个必须在 CI 里额外验证的插件。手工装配在这个规模上是**更少**的代码，
 * 而且依赖关系一眼可见——这个类就是那张图。
 *
 * ## 唯一的装配点（重要）
 *
 * [DeviceStore] 要求「同一路径只有一个实例」（见它的 KDoc）：两个实例会各自缓存
 * DataStore 状态、互相覆盖写。所以：
 *
 * - 本类是**进程级单例**（[get]），不是每个 Activity / 每个界面各建一份；
 * - [deviceStore] 只在这里 `create` 一次，别的地方只拿 [DeviceRepository]。
 *
 * 之所以要自己做单例：本项目的 `AndroidManifest.xml` 不在本次改动范围内，
 * 没有地方挂 `Application` 子类。将来若加上 `ZemoteApp : Application`，
 * 把单例挪进去即可（本类的对外接口不用变）。
 *
 * ## 生命周期
 *
 * [appScope] 与进程同寿；[engine] 的引擎实例**每次换设备都会被替换**（一个引擎 = 一条连接），
 * 转接逻辑在 [ZemoteEnginePort] 里。
 */
class AppContainer private constructor(context: Context) {

    private val appContext: Context = context.applicationContext

    /** 应用级作用域：引擎会话、设置快照、relay 状态回写都挂在这里。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** **唯一**的 [DeviceStore] 实例。 */
    val deviceStore: DeviceStore by lazy { DeviceStore.create(appContext) }

    /** 3.16.1 OAuth 登录态（JWT 加密落盘，与设备凭据分开）。 */
    val authStore: AuthStore by lazy { DataStoreAuthStore.create(appContext) }

    private val oauthTokenClient: OAuthTokenClient by lazy { OAuthTokenClient() }

    /** OAuth JWT → ws2 的 terminal token（3.16.1 必需）。 */
    private val remoteTokenClient: RemoteTokenClient by lazy { RemoteTokenClient() }

    private val repository: DeviceRepository by lazy { StoreDeviceRepository(deviceStore) }

    /** 设置页要直接改主题/字号；表现层通过它走 `updateSettings`。 */
    val settingsRepository: DeviceRepository get() = repository

    /** 应用设置的当前值（`AppRoot` 用它决定主题；这里也让「设置一变」不必每次读盘）。 */
    val appSettings: StateFlow<AppSettings> = deviceStore.settings
        .stateIn(appScope, SharingStarted.Eagerly, AppSettings())

    /** 当前电脑 id（通知点击定位时用）。 */
    val currentDeviceId: StateFlow<String?> = repository.currentDevice
        .map { it?.id }
        .stateIn(appScope, SharingStarted.Eagerly, null)

    /** 通知点击带来的定位目标；导航层消费后清空。 */
    private val _pendingTarget = kotlinx.coroutines.flow.MutableStateFlow<NotifyTarget?>(null)
    val pendingTarget: StateFlow<NotifyTarget?> = _pendingTarget

    /**
     * 解析通知点击的 intent（`ACTION_LOCATE_TASK`）。
     *
     * 只认任务 id；工作区键可能为空（通知里没带），导航层会退回该电脑的默认落点。
     */
    fun onNotifyIntent(intent: android.content.Intent?) {
        if (intent?.action != app.zemote.notify.Notifier.ACTION_LOCATE_TASK) return
        val taskId = intent.getStringExtra(app.zemote.notify.Notifier.EXTRA_SESSION_ID)
        if (taskId.isNullOrBlank()) return
        _pendingTarget.value = NotifyTarget(
            workspaceKey = intent.getStringExtra(app.zemote.notify.Notifier.EXTRA_WORKSPACE_KEY),
            taskId = taskId,
        )
        // 同一个 Intent 可能被重复投递（onNewIntent 的 setIntent），清掉 action 防重。
        intent.action = null
    }

    fun consumePendingTarget() {
        _pendingTarget.value = null
    }

    /** 建会话时读一次当前主题（`AppTheme` → 引擎的 `"dark"` / `"light"`）。 */
    private val themeSnapshot: StateFlow<String?> = appSettings
        .map { it.theme.toEngineTheme() }
        .stateIn(appScope, SharingStarted.Eagerly, null)

    /** 引擎会话面。 */
    val engine: EnginePort by lazy {
        ZemoteEnginePort(
            scope = appScope,
            appVersion = BuildConfig.VERSION_NAME,
            deviceName = android.os.Build.MODEL,
            theme = { themeSnapshot.value },
            terminalTokenProvider = { host, jwt -> remoteTokenClient.exchange(host, jwt).token },
        )
    }

    /** 对话层能力（`op` 透传）。会话/key 由 [EngineChatOps] 自己惰性开、按桥会话缓存。 */
    val chatOps: ChatOps by lazy {
        EngineChatOps(
            engine = engine,
            session = { engine.bridgeSession.value },
            // scope 由调用方（`ChatViewModel`）显式给：它才知道真实 workspacePath 与 identity。
            // 这里的兜底是空对象，只为「忘传」时不崩，不是可用值。
            defaultScope = { JsonObject(emptyMap()) },
            registry = conversationRegistry,
        )
    }

    /**
     * 把引擎的 relay 状态回写进当前设备记录（列表页显示的是**持久化的最后已知状态**）。
     *
     * 挂在这里而不是 ViewModel：relay 状态在**离开设备页之后**照样会变（重连 / 掉线），
     * 挂在界面作用域上就会漏掉那些变化。写盘失败只记日志，不影响连接。
     */
    private val relayStatusWriter: RelayStatusWriter by lazy {
        RelayStatusWriter(repository = repository, engine = engine)
    }

    private val httpFetcher: HttpFetcher by lazy { OkHttpHttpFetcher() }

    private val updateChecker: UpdateChecker by lazy {
        UpdateChecker(fetcher = httpFetcher, currentVersionCode = BuildConfig.VERSION_CODE)
    }

    private val updateDownloader: UpdateDownloader by lazy {
        // 与 res/xml/file_paths.xml 的 `files-path updates/` 白名单对应。
        UpdateDownloader(fetcher = httpFetcher, directory = File(appContext.filesDir, "updates"))
    }

    /** 对话行的本地缓存（打开过的对话先出内容，再等快照覆盖）。 */
    private val chatCache: app.zemote.data.ChatCache by lazy {
        app.zemote.data.ChatCache(appContext)
    }

    /** 首页项目树的本地快照：冷启动/Activity 重建先出内容，再后台刷新。 */
    private val homeCache: app.zemote.data.HomeCache by lazy {
        app.zemote.data.HomeCache(appContext)
    }

    /**
     * 进程级对话保活：按工作区复用桥与 conversation，离开页面不关闭。
     *
     * 只有任务删除（[ChatViewModel] 调 `close`）或进程结束才释放。
     */
    private val conversationRegistry: ConversationRegistry by lazy {
        ConversationRegistry(engine, appScope)
    }

    private val apkInstaller: ApkInstaller by lazy { AndroidApkInstaller(appContext) }

    /** 通知总控：任务卡、完成提醒、前台服务。进程级，跟着容器一起活。 */
    val notifications: NotificationCoordinator by lazy {
        NotificationCoordinator(
            context = appContext,
            engine = engine,
            settings = appSettings,
            scope = appScope,
        )
    }

    init {
        // relay 状态回写设备记录（只写变化，见 RelayStatusWriter）。
        relayStatusWriter.start(appScope)
        notifications.start()
    }

    // ------------------------------------------------------------------
    // ViewModel 工厂
    // ------------------------------------------------------------------

    fun navFactory(): ViewModelProvider.Factory =
        viewModelFactory { initializer { NavViewModel() } }

    fun devicesFactory(): ViewModelProvider.Factory =
        viewModelFactory {
            initializer {
                DevicesViewModel(repository = repository, engine = engine, authStore = authStore)
            }
        }

    fun pairingFactory(): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            PairingViewModel(
                repository = repository,
                // 扫码能力尚未接入（CameraX / ML Kit / zxing 属界面层）：占位实现永远「用户取消」。
                scanner = UnavailableQrScanner,
                engine = engine,
                authStore = authStore,
            )
        }
    }

    /** 3.16.1 OAuth 登录页。 */
    fun loginFactory(): ViewModelProvider.Factory = viewModelFactory {
        initializer { LoginViewModel(authStore = authStore, tokenClient = oauthTokenClient) }
    }

    /**
     * 对话页工厂。
     *
     * @param deviceId 连到哪台设备（由路由携带；不同的设备/落点用不同的
     *   `NavBackStackEntry`，见 `AppNavHost`，因此不会互相污染）。
     */
    fun chatFactory(
        deviceId: String,
        workspaceKey: String?,
        taskId: String?,
        taskTitle: String? = null,
        workspacePath: String? = null,
        workspaceIdentity: String? = null,
        hostId: String? = null,
        projectId: String? = null,
    ): ViewModelProvider.Factory =
        viewModelFactory {
            initializer {
                ChatViewModel(
                    repository = repository,
                    engine = engine,
                    authStore = authStore,
                    chatOps = chatOps,
                    deviceId = deviceId,
                    initialWorkspaceKey = workspaceKey,
                    initialTaskId = taskId,
                    initialTaskTitle = taskTitle,
                    initialWorkspacePath = workspacePath,
                    initialWorkspaceIdentity = workspaceIdentity,
                    initialHostId = hostId,
                    initialProjectId = projectId,
                    onAttention = { taskId, title, workspaceKey, active ->
                        notifications.onAttention(taskId, title, workspaceKey, active)
                    },
                    cache = chatCache,
                    registry = conversationRegistry,
                )
            }
        }

    fun settingsFactory(): ViewModelProvider.Factory =
        viewModelFactory { initializer { SettingsViewModel(repository) } }

    /** 「对话」Tab。 */
    fun homeFactory(): ViewModelProvider.Factory =
        viewModelFactory {
            initializer {
                HomeViewModel(
                    repository = repository,
                    engine = engine,
                    authStore = authStore,
                    registry = conversationRegistry,
                    cache = chatCache,
                    homeCache = homeCache,
                )
            }
        }

    /** 设置二级页（本机设置 + 桌面 `setting` 通道）。 */
    fun settingsSectionFactory(): ViewModelProvider.Factory =
        viewModelFactory {
            initializer {
                SettingsSectionViewModel(
                    repository = repository,
                    engine = engine,
                    authStore = authStore,
                )
            }
        }

    fun updateFactory(): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            UpdateViewModel(
                checker = updateChecker,
                downloader = updateDownloader,
                installer = apkInstaller,
                currentVersionCode = BuildConfig.VERSION_CODE,
                downloadNotifications = object : DownloadNotificationSink {
                    override fun onProgress(percent: Int, indeterminate: Boolean) {
                        if (!appSettings.value.notificationEnabled) return
                        app.zemote.notify.Notifier.get(appContext).updateDownload(
                            app.zemote.notify.DownloadProgress(
                                percent = percent,
                                indeterminate = indeterminate,
                            ),
                        )
                    }

                    override fun onFinished() {
                        app.zemote.notify.Notifier.get(appContext).clearDownload()
                    }
                },
            )
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private fun AppTheme.toEngineTheme(): String = when (this) {
        AppTheme.LIGHT -> "light"
        AppTheme.DARK -> "dark"
        // 引擎要的是具体主题；「跟随系统」在宿主侧解析（核心不读平台配置）。
        AppTheme.SYSTEM -> if (isSystemDark()) "dark" else "light"
    }

    private fun isSystemDark(): Boolean {
        val mode = appContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    companion object {

        @Volatile
        private var instance: AppContainer? = null

        /**
         * 取进程级唯一的容器。
         *
         * 用 `context.applicationContext` 建，所以传 Activity 进来也不会泄漏。
         */
        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context.applicationContext).also { instance = it }
            }
    }
}

/**
 * 通知点击的定位目标：哪个工作区的哪个任务。
 */
data class NotifyTarget(
    val workspaceKey: String?,
    val taskId: String,
)

/**
 * [ApkInstaller] 的生产实现：转发到 [UpdateInstaller]。
 *
 * 这一层只是为了把 `Context` 挡在表现层外面（真实安装器要 `FileProvider` 与
 * `PackageManager` 的签名比对，JVM 单测里构造不出来）。
 */
private class AndroidApkInstaller(private val context: Context) : ApkInstaller {

    private val installer = UpdateInstaller(context)

    override fun install(apk: File): InstallResult = installer.install(apk)

    override fun openUnknownSourcesSettings(): Boolean = installer.openUnknownSourcesSettings()
}
