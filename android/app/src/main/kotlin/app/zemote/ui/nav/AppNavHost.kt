package app.zemote.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import app.zemote.BuildConfig
import app.zemote.ui.AppContainer
import app.zemote.ui.MainScreen
import app.zemote.ui.chat.ChatScreen
import app.zemote.ui.chat.ChatViewModel
import app.zemote.ui.home.HomeViewModel
import app.zemote.ui.pairing.PairingUiState
import app.zemote.ui.pairing.PairingViewModel
import app.zemote.ui.pairing.QrScanScreen
import app.zemote.ui.settings.SettingsCategory
import app.zemote.ui.settings.SettingsSectionScreen
import app.zemote.ui.settings.SettingsSectionViewModel
import app.zemote.ui.theme.ZemoteType
import app.zemote.ui.update.UpdatePhase
import app.zemote.ui.update.UpdateScreen
import app.zemote.ui.update.UpdateViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 导航状态的持有者。
 *
 * 是一个 [ViewModel]（而不是 `remember` 一个 [NavState]）的唯一理由：**跨配置变更**保住返回栈。
 */
class NavViewModel(initial: NavState = NavState.of()) : ViewModel() {

    private val _state = MutableStateFlow(initial)

    val state: StateFlow<NavState> = _state.asStateFlow()

    fun push(route: Route) {
        _state.value = _state.value.push(route)
    }

    fun pop() {
        _state.value = _state.value.pop()
    }

    fun replaceRoot(route: Route) {
        _state.value = _state.value.replaceRoot(route)
    }
}

/**
 * 按当前路由把界面分发出去，并完成**唯一的接线点**。
 *
 * 每个栈条目一份 [ViewModelStore]（[NavEntryStores]），弹出即 `clear()`。
 * 栈里还有上一屏时由 [BackHandler] 吃掉并弹栈；在根上时不拦，交给系统退出应用。
 */
@Composable
fun AppNavHost(container: AppContainer) {
    val nav: NavViewModel = viewModel(factory = container.navFactory())
    val navState by nav.state.collectAsStateWithLifecycle()

    val entries = remember { NavEntryStores() }
    LaunchedEffect(navState.stack) { entries.retain(navState.stack) }

    BackHandler(enabled = navState.canGoBack) { nav.pop() }

    // 通知点击定位：有工作区键就直达对话页，否则回「对话」Tab（列表里能自己找）。
    val pendingTarget by container.pendingTarget.collectAsStateWithLifecycle()
    val currentDeviceId by container.currentDeviceId.collectAsStateWithLifecycle()
    LaunchedEffect(pendingTarget, currentDeviceId) {
        val target = pendingTarget ?: return@LaunchedEffect
        val deviceId = currentDeviceId ?: return@LaunchedEffect
        if (target.workspaceKey != null) {
            nav.push(
                Route.Chat(
                    deviceId = deviceId,
                    workspaceKey = target.workspaceKey,
                    taskId = target.taskId,
                ),
            )
        } else {
            nav.replaceRoot(Route.Main(MainTab.HOME))
        }
        container.consumePendingTarget()
    }

    val route = navState.current
    CompositionLocalProvider(LocalViewModelStoreOwner provides entries.ownerFor(route)) {
        when (route) {
            is Route.Main -> MainRoute(container, nav, route)

            Route.Pairing -> PairingRoute(container, nav)

            is Route.Chat -> ChatRoute(container, nav, route)

            is Route.SettingsSection -> SettingsSectionRoute(container, nav, route)

            Route.AboutUpdate -> UpdateRoute(container, nav)
        }
    }
}

@Composable
private fun MainRoute(container: AppContainer, nav: NavViewModel, route: Route.Main) {
    val home: HomeViewModel = viewModel(factory = container.homeFactory())
    val state by home.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { home.onEnter() }

    // 启动静默检查一次；结果只用来在设置 Tab 上打红点。
    val update: UpdateViewModel = viewModel(factory = container.updateFactory())
    val updateState by update.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { update.check() }

    MainScreen(
        tab = route.tab,
        homeState = state,
        version = BuildConfig.VERSION_NAME,
        updateAvailable = updateState.phase == UpdatePhase.UpdateAvailable,
        onTabChange = { tab -> nav.replaceRoot(Route.Main(tab)) },
        onRefresh = home::refresh,
        onToggleWorkspace = home::toggleWorkspace,
        onOpenTask = { workspace, task ->
            state.deviceId?.let { id ->
                nav.push(Route.Chat(deviceId = id, workspaceKey = workspace.key, taskId = task.id))
            }
        },
        onNewConversation = { workspace ->
            state.deviceId?.let { id ->
                nav.push(Route.Chat(deviceId = id, workspaceKey = workspace.key, taskId = null))
            }
        },
        onDismissError = home::dismissError,
        onPin = { workspace, task, pinned -> home.setPinned(task, workspace, pinned) },
        onArchive = { workspace, task -> home.setArchived(task, workspace, true) },
        onDelete = { workspace, task -> home.delete(task, workspace) },
        onOpenPairing = { nav.push(Route.Pairing) },
        onOpenAbout = { nav.push(Route.AboutUpdate) },
        onOpenCategory = { category -> nav.push(Route.SettingsSection(category.id)) },
    )
}

@Composable
private fun PairingRoute(container: AppContainer, nav: NavViewModel) {
    val viewModel: PairingViewModel = viewModel(factory = container.pairingFactory())
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 配对成功 ⇒ 回主界面「对话」Tab（丢掉配对页这一层）。
    LaunchedEffect(state.paired?.id) {
        if (state.paired != null) nav.replaceRoot(Route.Main(MainTab.HOME))
    }

    PairingRouteContent(
        state = state,
        onSubmit = viewModel::submit,
        onBack = nav::pop,
        onDismissNotice = viewModel::dismissNotice,
    )
}

/**
 * 全屏相机 + 状态覆盖层。
 *
 * 扫码 / 相册 / 粘贴三条路径都由 [QrScanScreen] 收口，拿到的原文直接交给
 * `PairingViewModel.submit`；本层只负责把「正在配对 / 失败 / 提示」压在取景画面上。
 */
@Composable
private fun PairingRouteContent(
    state: PairingUiState,
    onSubmit: (String) -> Unit,
    onBack: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        QrScanScreen(onClose = onBack, onResult = onSubmit)

        val message = state.error
            ?: state.rejection?.toPairingMessage()
            ?: state.notice
        if (state.busy || message != null) {
            Surface(
                color = Color.Black.copy(alpha = 0.78f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp)
                    .fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color.White,
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            text = if (state.busy) "正在连接电脑…" else message.orEmpty(),
                            style = ZemoteType.uiSm,
                            color = Color.White,
                        )
                    }
                    if (!state.busy && state.notice != null) {
                        TextButton(onClick = onDismissNotice) {
                            Text("知道了", style = ZemoteType.uiXs, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

private fun app.zemote.pairing.RejectReason.toPairingMessage(): String = when (this) {
    app.zemote.pairing.RejectReason.EMPTY -> "还没有内容：请扫码，或把远控链接粘贴进来。"
    app.zemote.pairing.RejectReason.NOT_A_URL -> "这不像一个链接：需要以 http(s):// 或 wss:// 开头。"
    app.zemote.pairing.RejectReason.SCHEME_NOT_HTTP -> "只支持 http / https / wss 的链接。"
    app.zemote.pairing.RejectReason.MISSING_HOST -> "链接里没有主机名（host）。"
    app.zemote.pairing.RejectReason.MISSING_SID -> "链接里没有 sid 参数。"
    app.zemote.pairing.RejectReason.MISSING_HASH -> "链接里没有 hash 参数。"
    app.zemote.pairing.RejectReason.NOT_ZEMOTE_LINK -> "这看起来不是 Zemote 的远控链接。"
}

@Composable
private fun ChatRoute(container: AppContainer, nav: NavViewModel, route: Route.Chat) {
    val viewModel: ChatViewModel = viewModel(
        factory = container.chatFactory(
            deviceId = route.deviceId,
            workspaceKey = route.workspaceKey,
            taskId = route.taskId,
        ),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.onEnter() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onForeground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ChatScreen(
        state = state,
        onBack = nav::pop,
        onRefresh = viewModel::refresh,
        onSend = viewModel::sendText,
        onStop = viewModel::stop,
        onLoadOlder = viewModel::loadOlder,
        onPin = viewModel::setPinned,
        onArchive = viewModel::archiveTask,
        onDelete = viewModel::deleteTask,
        onRename = viewModel::renameTask,
        onSwitchMode = viewModel::switchMode,
        onSwitchThought = viewModel::switchThought,
        onResolveInteraction = viewModel::resolveInteraction,
        onDismissError = viewModel::dismissError,
        onAttach = viewModel::attach,
        onRemoveAttachment = viewModel::removeAttachment,
        onLoadAttachment = viewModel::readAttachmentBytes,
    )
}

@Composable
private fun SettingsSectionRoute(
    container: AppContainer,
    nav: NavViewModel,
    route: Route.SettingsSection,
) {
    val category = SettingsCategory.fromId(route.id) ?: return
    val viewModel: SettingsSectionViewModel = viewModel(factory = container.settingsSectionFactory())
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(category) { viewModel.onEnter(category) }

    SettingsSectionScreen(
        category = category,
        state = state,
        onBack = nav::pop,
        onReconnect = viewModel::reconnect,
        onDisconnect = viewModel::disconnect,
        onUpdateAppSettings = viewModel::updateAppSettings,
        onUpdateDesktop = viewModel::updateDesktop,
        onDismissError = viewModel::dismissError,
        onDismissNotice = viewModel::dismissNotice,
        providerActions = app.zemote.ui.settings.ProviderActions(
            refresh = viewModel::refreshProviders,
            setProviderEnabled = viewModel::setProviderEnabled,
            renameProvider = viewModel::renameProvider,
            deleteProvider = viewModel::deleteProvider,
            createProvider = viewModel::createProvider,
            setModelEnabled = viewModel::setModelEnabled,
            deleteModel = viewModel::deleteModel,
            addModel = viewModel::addModel,
        ),
    )
}

@Composable
private fun UpdateRoute(container: AppContainer, nav: NavViewModel) {
    val viewModel: UpdateViewModel = viewModel(factory = container.updateFactory())
    val state by viewModel.state.collectAsStateWithLifecycle()
    UpdateScreen(
        state = state,
        onBack = nav::pop,
        onCheck = viewModel::check,
        onDownload = viewModel::download,
        onCancelDownload = viewModel::cancelDownload,
        onInstall = viewModel::install,
        onOpenInstallSettings = viewModel::openUnknownSourcesSettings,
        onDismissError = viewModel::dismissError,
        onDismissInstallResult = viewModel::dismissInstallResult,
    )
}

/**
 * 每个返回栈条目一份 [ViewModelStore]（`NavBackStackEntry` 的最小等价物）。
 *
 * **主界面的两个 Tab 共用同一个 key**：切 Tab 不销毁 `HomeViewModel`，列表状态与
 * 预览订阅因此保留；连接本来就活在容器里，这里只是避免无谓的重建。
 */
private class NavEntryStores {

    private val owners = mutableMapOf<String, ViewModelStoreOwner>()

    fun ownerFor(route: Route): ViewModelStoreOwner =
        owners.getOrPut(route.storageKey()) { EntryOwner(ViewModelStore()) }

    /** 清掉不在 [stack] 里的条目（弹栈 / 换根时调用）；会走 `ViewModel.onCleared()`。 */
    fun retain(stack: List<Route>) {
        val keep = stack.mapTo(HashSet()) { it.storageKey() }
        val iterator = owners.iterator()
        while (iterator.hasNext()) {
            val (key, owner) = iterator.next()
            if (key !in keep) {
                owner.viewModelStore.clear()
                iterator.remove()
            }
        }
    }
}

private fun Route.storageKey(): String = if (this is Route.Main) "main" else toString()

/** 最小的 [ViewModelStoreOwner]：只提供一个 store。 */
private class EntryOwner(override val viewModelStore: ViewModelStore) : ViewModelStoreOwner
