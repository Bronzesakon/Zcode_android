package app.zemote.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zemote.data.AppSettings
import app.zemote.data.DeviceRecord
import app.zemote.engine.RelayState
import app.zemote.ui.EnginePort
import app.zemote.ui.chat.WorkspaceProjection
import app.zemote.ui.devices.DeviceRepository
import app.zemote.ui.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 设置二级页的状态。
 *
 * @property desktop 桌面端 `setting.get()` 的原始对象；`null` = 还没取到。
 *   键名以网页版设置面板的 props 为准（`notificationEnabled` / `messageStreamShowReasoning` …）。
 */
data class SettingsSectionUiState(
    val device: DeviceRecord? = null,
    val relayState: RelayState = RelayState.Idle,
    val appSettings: AppSettings = AppSettings(),
    val desktop: JsonObject? = null,
    val desktopLoading: Boolean = false,
    /** `provider-settings.getView()` 的原始返回；`null` = 还没取到。 */
    val providers: JsonObject? = null,
    val providersLoading: Boolean = false,
    /** 正在写哪个 provider（按钮转圈用）。 */
    val providerBusy: String? = null,
    val error: String? = null,
    val notice: String? = null,
)

/**
 * 设置二级页：本机设置（主题/字号/通知）+ 桌面设置（`setting` 通道）。
 *
 * 桌面设置是**整份读写**：`setting.get()` 拿全量对象，`setting.update(patch)` 只传改动键。
 * 通道需要一条桥；这里挑第一个非伪工作区开一条，和首页的桥互不影响。
 */
class SettingsSectionViewModel(
    private val repository: DeviceRepository,
    private val engine: EnginePort,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsSectionUiState())
    val state: StateFlow<SettingsSectionUiState> = _state.asStateFlow()

    private var bridge: Long? = null
    private var desktopLoaded = false

    init {
        viewModelScope.launch {
            repository.devices.collect { devices ->
                _state.update { it.copy(device = devices.firstOrNull()) }
            }
        }
        viewModelScope.launch {
            repository.settings.collect { settings ->
                _state.update { it.copy(appSettings = settings) }
            }
        }
        viewModelScope.launch {
            engine.relayState.collect { relay -> _state.update { it.copy(relayState = relay) } }
        }
    }

    /** 进入二级页时调；只有需要桌面设置的分类才真的拉。 */
    fun onEnter(category: SettingsCategory) {
        if (category == SettingsCategory.CONNECTION) return
        if (category == SettingsCategory.MODEL_PROVIDER) {
            if (_state.value.providers == null) viewModelScope.launch { loadProviders() }
            return
        }
        if (desktopLoaded) return
        desktopLoaded = true
        viewModelScope.launch { loadDesktop() }
    }

    fun reconnect() {
        val device = _state.value.device ?: return
        val url = device.remoteUrl ?: run {
            _state.update { it.copy(error = "凭据不可用，需要重新配对") }
            return
        }
        engine.ensureSession(url)
        _state.update { it.copy(notice = "正在重新连接…") }
    }

    /** 只断开连接，保留配对信息（UI-SPEC §3.1）。 */
    fun disconnect() {
        engine.disconnect()
        bridge = null
        desktopLoaded = false
        _state.update { it.copy(notice = "已断开连接，配对信息仍保留") }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    /** 本机设置（主题/字号/通知）统一走「读-改-写」。 */
    fun updateAppSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            try {
                repository.updateSettings(transform)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toUserMessage()) }
            }
        }
    }

    // ------------------------------------------------------------------
    // 桌面设置
    // ------------------------------------------------------------------

    private suspend fun loadDesktop() {
        _state.update { it.copy(desktopLoading = true, error = null) }
        try {
            val session = ensureBridge()
            val value = engine.channelCall(session, "setting", "get", JsonArray(emptyList()))
            _state.update {
                it.copy(
                    desktop = (value as? JsonObject) ?: JsonObject(emptyMap()),
                    desktopLoading = false,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(desktopLoading = false, error = e.toUserMessage()) }
        }
    }

    /**
     * 写一个桌面设置键。
     *
     * 先本地乐观更新（开关手感），失败再回滚并报错。
     */
    fun updateDesktop(key: String, value: JsonElement) {
        val previous = _state.value.desktop ?: return
        val patch = buildJsonObject { put(key, value) }
        _state.update { it.copy(desktop = merge(previous, patch), error = null) }
        viewModelScope.launch {
            try {
                val session = ensureBridge()
                engine.channelCall(
                    session = session,
                    channel = "setting",
                    method = "update",
                    args = JsonArray(listOf(patch)),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(desktop = previous, error = e.toUserMessage())
                }
            }
        }
    }

    private suspend fun ensureBridge(): Long {
        bridge?.let { return it }
        // 不能只读 `_state.value.device`：`devices` 是异步流，进页立刻拉桌面设置时
        // 首个快照还没到，读状态会得到 null 并误报「还没有连接电脑」。
        val device = resolveDevice()
            ?: throw IllegalStateException("还没有连接电脑")
        val url = device.remoteUrl
            ?: throw IllegalStateException("凭据不可用，需要重新配对")
        engine.ensureSession(url)
        engine.waitPaired(30_000)
        val snapshot = engine.bootstrap()
        val workspaces = WorkspaceProjection.from(snapshot)
            .filter { it.purpose != "conversation" }
        val workspace = workspaces.firstOrNull()
            ?: throw IllegalStateException("这台电脑还没有工作区，无法读取桌面设置")
        val session = engine.openBridge(workspace.key)
        engine.waitHealthy(session)
        bridge = session
        return session
    }

    /** 当前设备：优先用状态里的快照，没有就现读仓库（避免进页竞态）。 */
    private suspend fun resolveDevice(): DeviceRecord? {
        _state.value.device?.let { return it }
        val devices = repository.devices.first()
        return repository.currentDevice.first() ?: devices.firstOrNull()
    }

    private fun merge(base: JsonObject, patch: JsonObject): JsonObject =
        JsonObject(base + patch)

    // ------------------------------------------------------------------
    // 模型与服务商（`provider-settings` 通道）
    // ------------------------------------------------------------------

    /** 重新读取服务商视图。 */
    fun refreshProviders() {
        viewModelScope.launch { loadProviders() }
    }

    private suspend fun loadProviders() {
        _state.update { it.copy(providersLoading = true, error = null) }
        try {
            val view = providerCall("getView", emptyList())
            _state.update {
                it.copy(providers = view ?: it.providers, providersLoading = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(providersLoading = false, error = e.toUserMessage()) }
        }
    }

    /** 启停服务商（只对个人服务商开放；内置服务商在界面上是只读的）。 */
    fun setProviderEnabled(providerId: String, enabled: Boolean) {
        val overlay = providerOverlay(providerId) ?: run {
            _state.update { it.copy(error = "这个服务商没有可写的配置") }
            return
        }
        providerMutation(
            busy = providerId,
            method = "savePersonalProviderOverlay",
            args = listOf(
                JsonPrimitive(providerId),
                overlay,
                buildJsonObject { put("enabled", enabled) },
            ),
        )
    }

    /** 改名（同样只对个人服务商）。 */
    fun renameProvider(providerId: String, name: String) {
        val overlay = providerOverlay(providerId) ?: return
        providerMutation(
            busy = providerId,
            method = "savePersonalProviderOverlay",
            args = listOf(
                JsonPrimitive(providerId),
                overlay,
                buildJsonObject { put("providerName", name) },
            ),
        )
    }

    fun deleteProvider(providerId: String) {
        providerMutation(
            busy = providerId,
            method = "deletePersonalProvider",
            args = listOf(JsonPrimitive(providerId)),
        )
    }

    fun createProvider(templateId: String, name: String) {
        providerMutation(
            busy = "new-provider",
            method = "createPersonalProvider",
            args = listOf(
                buildJsonObject {
                    put("templateId", templateId)
                    if (name.isNotBlank()) put("providerName", name)
                },
            ),
            notice = "已创建服务商",
        )
    }

    fun setModelEnabled(providerId: String, modelId: String, enabled: Boolean) {
        providerMutation(
            busy = "$providerId/$modelId",
            method = "setPersonalModelEnabled",
            args = listOf(
                JsonPrimitive(providerId),
                JsonPrimitive(modelId),
                JsonPrimitive(enabled),
            ),
        )
    }

    fun deleteModel(providerId: String, modelId: String) {
        providerMutation(
            busy = "$providerId/$modelId",
            method = "deletePersonalModel",
            args = listOf(JsonPrimitive(providerId), JsonPrimitive(modelId)),
        )
    }

    /**
     * 新增模型。
     *
     * 配置从该服务商已有的模型复制（`effectiveConfig`）；一个模型都没有时用一份
     * 最小可用配置兜底。手机端不做完整的模型参数编辑器——那是桌面端的活。
     */
    fun addModel(providerId: String, modelId: String) {
        val provider = providerById(providerId)
        val base = (provider?.get("models") as? JsonArray)
            ?.firstOrNull { (it as? JsonObject)?.get("effectiveConfig") is JsonObject }
            ?.let { (it as JsonObject)["effectiveConfig"] as JsonObject }
            ?: defaultModelConfig()
        providerMutation(
            busy = providerId,
            method = "addPersonalModel",
            args = listOf(JsonPrimitive(providerId), JsonPrimitive(modelId), base),
        )
    }

    private fun providerById(providerId: String): JsonObject? =
        (_state.value.providers?.get("providers") as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { (it["providerId"] as? JsonPrimitive)?.contentOrNull == providerId }

    /** 个人覆盖配置（剥掉三个由仓库维护的成员键）。 */
    private fun providerOverlay(providerId: String): JsonObject? {
        val provider = providerById(providerId) ?: return null
        val source = (provider["personalConfig"] as? JsonObject)
            ?: (provider["effectiveConfig"] as? JsonObject)
            ?: return null
        return JsonObject(
            source.filterKeys {
                it != "builtinModelIds" && it != "personalModelIds" && it != "modelOrder"
            },
        )
    }

    private fun defaultModelConfig(): JsonObject = buildJsonObject {
        put("enabled", true)
        put(
            "properties",
            buildJsonObject {
                put("requiresMfjsToolSchema", false)
                put("contextWindow", 200_000)
                put(
                    "inputFormat",
                    buildJsonObject {
                        put("supportsText", true)
                        put("supportsImage", false)
                        put("supportsVideo", false)
                        put("supportsAudio", false)
                        put("supportsPdf", false)
                    },
                )
                put("outputFormat", buildJsonObject { put("supportsText", true) })
                put("supportsToolCall", true)
                put("supportsJsonSchemaOutput", false)
                put("supportsNativeWebSearch", false)
                put("supportsMidConversationSystem", false)
            },
        )
        put("optionSpecs", JsonObject(emptyMap()))
    }

    private fun providerMutation(
        busy: String,
        method: String,
        args: List<JsonElement>,
        notice: String? = null,
    ) {
        viewModelScope.launch {
            _state.update { it.copy(providerBusy = busy, error = null) }
            try {
                val result = providerCall(method, args)
                // 大多数方法直接返回视图；createPersonalProvider 返回 {providerId, view}。
                val view = when {
                    result?.get("view") is JsonObject -> result["view"] as JsonObject
                    result?.get("providers") is JsonArray -> result
                    else -> null
                }
                if (view != null) {
                    _state.update { it.copy(providers = view, notice = notice) }
                } else if (notice != null) {
                    _state.update { it.copy(notice = notice) }
                    loadProviders()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toUserMessage()) }
            } finally {
                _state.update { it.copy(providerBusy = null) }
            }
        }
    }

    private suspend fun providerCall(method: String, args: List<JsonElement>): JsonObject? {
        val session = ensureBridge()
        val value = engine.channelCall(
            session = session,
            channel = "provider-settings",
            method = method,
            args = JsonArray(args),
        )
        return value as? JsonObject
    }
}

/** 从桌面设置对象里读布尔；缺省用 [fallback]。 */
internal fun JsonObject.bool(key: String, fallback: Boolean = false): Boolean =
    (this[key] as? JsonPrimitive)?.booleanOrNull ?: fallback

/** 从桌面设置对象里读字符串；缺省用 [fallback]。 */
internal fun JsonObject.str(key: String, fallback: String = ""): String =
    (this[key] as? JsonPrimitive)?.contentOrNull ?: fallback

/** 从桌面设置对象里读数字；缺省用 [fallback]。 */
internal fun JsonObject.number(key: String, fallback: Double = 0.0): Double =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: fallback
