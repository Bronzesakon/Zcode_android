package app.zemote.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.zemote.data.AppSettings
import app.zemote.engine.RelayState
import app.zemote.notify.LiveUpdate
import app.zemote.ui.common.NoticeBar
import app.zemote.ui.common.OutlineActionButton
import app.zemote.ui.common.PageHeader
import app.zemote.ui.common.SettingsDivider
import app.zemote.ui.common.SettingsGroupHeader
import app.zemote.ui.common.SettingsRow
import app.zemote.ui.common.SettingsSwitchRow
import app.zemote.ui.common.SettingsValue
import app.zemote.ui.common.StatusDot
import app.zemote.ui.common.ZemoteCard
import app.zemote.ui.common.zemoteSwitchColors
import app.zemote.ui.theme.LocalThemeController
import app.zemote.ui.theme.LocalZemoteColors
import app.zemote.ui.theme.ZemoteType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 设置二级页。
 *
 * 每个一级分类一个页面；`CONNECTION` / `NOTIFICATIONS` / `APPEARANCE` 是本机设置，
 * 其余分类读桌面端 `setting.get()`（UI-SPEC §3）。
 *
 * 视觉按 `docs/audit/06-non-chat.md` §3.2 / §5.2：44dp 页头、12dp 圆角卡片 + 1px 边框、
 * 行式设置（label 左 / control 右）、行间 1px 分隔线；主题走 [LocalThemeController]。
 */
@Composable
fun SettingsSectionScreen(
    category: SettingsCategory,
    state: SettingsSectionUiState,
    onBack: () -> Unit,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
    onUpdateAppSettings: ((AppSettings) -> AppSettings) -> Unit,
    onUpdateDesktop: (String, kotlinx.serialization.json.JsonElement) -> Unit,
    onDismissError: () -> Unit,
    onDismissNotice: () -> Unit,
    providerActions: ProviderActions = ProviderActions.None,
    modifier: Modifier = Modifier,
) {
    val colors = LocalZemoteColors.current

    Column(modifier = modifier.fillMaxSize().background(colors.background)) {
        PageHeader(title = category.title, onBack = onBack)

        if (state.error != null) {
            NoticeBar(text = state.error, destructive = true, onClose = onDismissError)
        }
        if (state.notice != null) {
            NoticeBar(text = state.notice, destructive = false, onClose = onDismissNotice)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (category) {
                SettingsCategory.CONNECTION -> ConnectionSection(
                    state = state,
                    onReconnect = onReconnect,
                    onDisconnect = onDisconnect,
                )

                SettingsCategory.NOTIFICATIONS -> NotificationsSection(
                    settings = state.appSettings,
                    onUpdate = onUpdateAppSettings,
                )

                SettingsCategory.APPEARANCE -> AppearanceSection(
                    settings = state.appSettings,
                    desktop = state.desktop,
                    loading = state.desktopLoading,
                    onUpdateAppSettings = onUpdateAppSettings,
                    onUpdateDesktop = onUpdateDesktop,
                )

                SettingsCategory.MODEL_PROVIDER -> ModelProviderSection(
                    state = state,
                    actions = providerActions,
                )

                else -> RemoteSection(
                    category = category,
                    desktop = state.desktop,
                    loading = state.desktopLoading,
                    onUpdate = onUpdateDesktop,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// 本机：连接
// ---------------------------------------------------------------------------

@Composable
private fun ConnectionSection(
    state: SettingsSectionUiState,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val colors = LocalZemoteColors.current
    val device = state.device

    SectionCard(title = "当前电脑") {
        if (device == null) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("还没有连接电脑", style = ZemoteType.uiBase, fontWeight = FontWeight.Medium, color = colors.foreground)
                Text("去「对话」Tab 扫码配对一台电脑。", style = ZemoteType.uiSm, color = colors.foregroundSubtle)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        device.name,
                        style = ZemoteType.uiBase,
                        fontWeight = FontWeight.Medium,
                        color = colors.foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        StatusDot(color = relayDotColor(state.relayState))
                        Text(relayText(state.relayState), style = ZemoteType.uiSm, color = colors.foregroundSubtle)
                    }
                }
                OutlineActionButton("重新连接", onClick = onReconnect)
            }
            SettingsDivider()
            Text(
                text = "断开后配对信息仍保留，下次打开应用会自动重连。",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = ZemoteType.uiSm,
                color = colors.foregroundSubtle,
            )
            SettingsDivider()
            SettingsRow(
                title = "断开连接",
                titleColor = colors.destructive,
                onClick = onDisconnect,
            )
        }
    }
}

@Composable
private fun relayDotColor(state: RelayState): Color {
    val colors = LocalZemoteColors.current
    return when (state) {
        RelayState.Paired -> colors.success
        RelayState.Connecting,
        RelayState.Authenticating,
        RelayState.Waiting,
        RelayState.Reconnecting,
        -> colors.warning
        RelayState.Error,
        RelayState.Kicked,
        -> colors.destructive
        RelayState.Closed,
        RelayState.Idle,
        -> colors.foregroundSubtlest
    }
}

// ---------------------------------------------------------------------------
// 本机：通知
// ---------------------------------------------------------------------------

@Composable
private fun NotificationsSection(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val colors = LocalZemoteColors.current
    val fluidCloud = remember { LiveUpdate.describeEligibility(context) }

    SectionCard(title = "通知") {
        SettingsSwitchRow(
            title = "通知总开关",
            description = "关闭后不发任何系统通知，连接与后台服务继续",
            checked = settings.notificationEnabled,
            onCheckedChange = { v -> onUpdate { it.copy(notificationEnabled = v) } },
        )
        SettingsDivider()
        SettingsSwitchRow(
            title = "声音与震动",
            description = "完成 / 等待授权通知是否响铃",
            checked = settings.notificationSound,
            onCheckedChange = { v -> onUpdate { it.copy(notificationSound = v) } },
        )
    }

    SectionCard(title = "提醒事件") {
        SettingsSwitchRow(
            title = "任务完成",
            checked = settings.notifyOnComplete,
            onCheckedChange = { v -> onUpdate { it.copy(notifyOnComplete = v) } },
        )
        SettingsDivider()
        SettingsSwitchRow(
            title = "任务出错",
            checked = settings.notifyOnError,
            onCheckedChange = { v -> onUpdate { it.copy(notifyOnError = v) } },
        )
        SettingsDivider()
        SettingsSwitchRow(
            title = "等待授权 / 输入",
            checked = settings.notifyOnAttention,
            onCheckedChange = { v -> onUpdate { it.copy(notifyOnAttention = v) } },
        )
    }

    SectionCard(title = "后台与流体云") {
        SettingsSwitchRow(
            title = "后台常驻",
            description = "保持连接；关闭后系统可能回收连接",
            checked = settings.foregroundServiceEnabled,
            onCheckedChange = { v -> onUpdate { it.copy(foregroundServiceEnabled = v) } },
        )
        SettingsDivider()
        Text(
            text = fluidCloud,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = ZemoteType.uiSm,
            color = colors.foregroundSubtle,
        )
    }
}

// ---------------------------------------------------------------------------
// 本机 + 桌面：外观
// ---------------------------------------------------------------------------

@Composable
private fun AppearanceSection(
    settings: AppSettings,
    desktop: kotlinx.serialization.json.JsonObject?,
    loading: Boolean,
    onUpdateAppSettings: ((AppSettings) -> AppSettings) -> Unit,
    onUpdateDesktop: (String, kotlinx.serialization.json.JsonElement) -> Unit,
) {
    val colors = LocalZemoteColors.current
    val controller = LocalThemeController.current
    val mode = currentThemeMode()

    SectionCard(title = "主题") {
        Column(Modifier.padding(16.dp)) {
            ThemeModeSegmented(
                selected = mode,
                onSelect = { controller?.setMode(it) },
                enabled = controller != null,
            )
        }
        SettingsDivider()
        Text(
            text = "跟随系统时，系统深色用 zai-dark、浅色用 zai-light。",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = ZemoteType.uiSm,
            color = colors.foregroundSubtle,
        )
    }

    SectionCard(title = "字号") {
        SliderRow(
            title = "界面字号",
            value = settings.textScale,
            range = 0.8f..1.4f,
            steps = 5,
            label = "${(settings.textScale * 100).toInt()}%",
            onChange = { v -> onUpdateAppSettings { it.copy(textScale = v) } },
        )
        SettingsDivider()
        SliderRow(
            title = "代码字号",
            value = settings.codeFontSize,
            range = 10f..20f,
            steps = 9,
            label = "${settings.codeFontSize.toInt()} px",
            onChange = { v -> onUpdateAppSettings { it.copy(codeFontSize = v) } },
        )
    }

    if (desktop != null) {
        val code = desktop["codePreviewSettings"] as? kotlinx.serialization.json.JsonObject
        SectionCard(title = "代码预览（桌面端）") {
            SettingsSwitchRow(
                title = "显示行号",
                checked = code?.bool("showLineNumbers") ?: true,
                onCheckedChange = { v -> onUpdateDesktop("codePreviewSettings", patchCode(code, "showLineNumbers", v)) },
            )
            SettingsDivider()
            SettingsSwitchRow(
                title = "自动换行",
                checked = code?.bool("wrapLongLines") ?: false,
                onCheckedChange = { v -> onUpdateDesktop("codePreviewSettings", patchCode(code, "wrapLongLines", v)) },
            )
        }
    } else if (loading) {
        SectionCard(title = "桌面端外观") {
            Text(
                "正在读取…",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = ZemoteType.uiSm,
                color = colors.foregroundSubtle,
            )
        }
    }
}

private fun patchCode(
    base: kotlinx.serialization.json.JsonObject?,
    key: String,
    value: Boolean,
): kotlinx.serialization.json.JsonObject =
    kotlinx.serialization.json.JsonObject((base ?: kotlinx.serialization.json.JsonObject(emptyMap())) + (key to JsonPrimitive(value)))

// ---------------------------------------------------------------------------
// 桌面设置：通用与其余分类
// ---------------------------------------------------------------------------

@Composable
private fun RemoteSection(
    category: SettingsCategory,
    desktop: kotlinx.serialization.json.JsonObject?,
    loading: Boolean,
    onUpdate: (String, kotlinx.serialization.json.JsonElement) -> Unit,
) {
    val colors = LocalZemoteColors.current

    if (category.readOnly) {
        SectionCard(title = "桌面端专属") {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "这一类设置只对电脑端有意义，手机端无法修改。",
                    style = ZemoteType.uiSm,
                    color = colors.foregroundSubtle,
                )
                Text(
                    "请到电脑端 ZCode 的「设置」里调整。",
                    style = ZemoteType.uiSm,
                    color = colors.foregroundSubtle,
                )
            }
        }
        return
    }

    if (desktop == null) {
        SectionCard(title = category.title) {
            Text(
                text = if (loading) "正在读取桌面设置…" else "还没有读到桌面设置。",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = ZemoteType.uiSm,
                color = colors.foregroundSubtle,
            )
        }
        return
    }

    when (category) {
        SettingsCategory.GENERAL -> GeneralRemoteSection(desktop, onUpdate)
        SettingsCategory.MEMORY -> SectionCard(title = "记忆") {
            SettingsSwitchRow(
                title = "启用跨会话记忆",
                checked = desktop.bool("memoryEnabled", true),
                onCheckedChange = { v -> onUpdate("memoryEnabled", JsonPrimitive(v)) },
            )
            SettingsDivider()
            SettingsSwitchRow(
                title = "主动建议",
                checked = desktop.bool("proactiveSuggestionsEnabled", false),
                onCheckedChange = { v -> onUpdate("proactiveSuggestionsEnabled", JsonPrimitive(v)) },
            )
        }
        else -> SectionCard(title = category.title) {
            Text(
                "这一类设置的界面正在接线。已读取到 ${desktop.size} 个桌面设置键。",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = ZemoteType.uiSm,
                color = colors.foregroundSubtle,
            )
        }
    }
}

@Composable
private fun GeneralRemoteSection(
    desktop: kotlinx.serialization.json.JsonObject,
    onUpdate: (String, kotlinx.serialization.json.JsonElement) -> Unit,
) {
    SectionCard(title = "语言与界面") {
        SelectRow(
            title = "语言",
            value = desktop.str("localePreference", "system"),
            options = listOf("system" to "跟随系统", "zh-CN" to "中文", "en-US" to "English"),
            onSelect = { v -> onUpdate("localePreference", JsonPrimitive(v)) },
        )
        SettingsDivider()
        SelectRow(
            title = "对话交互行为",
            value = desktop.str("zcodeInteractionBehavior", "queue"),
            options = listOf("queue" to "排队", "guide" to "引导"),
            onSelect = { v -> onUpdate("zcodeInteractionBehavior", JsonPrimitive(v)) },
        )
        SettingsDivider()
        SelectRow(
            title = "动态工作流",
            value = desktop.str("dynamicWorkflowMode", "disabled"),
            options = listOf(
                "disabled" to "关闭",
                "onDemand" to "按需",
                "alwaysOn" to "始终开启",
            ),
            onSelect = { v -> onUpdate("dynamicWorkflowMode", JsonPrimitive(v)) },
        )
        SettingsDivider()
        SettingsSwitchRow(
            title = "增强原生搜索",
            checked = desktop.bool("nativeSearchEnhancementsEnabled", true),
            onCheckedChange = { v -> onUpdate("nativeSearchEnhancementsEnabled", JsonPrimitive(v)) },
        )
    }

    SectionCard(title = "对话显示") {
        SettingsSwitchRow(
            title = "显示思考过程",
            checked = desktop.bool("messageStreamShowReasoning", true),
            onCheckedChange = { v -> onUpdate("messageStreamShowReasoning", JsonPrimitive(v)) },
        )
        SettingsDivider()
        SettingsSwitchRow(
            title = "显示待办",
            checked = desktop.bool("messageStreamShowTodos", false),
            onCheckedChange = { v -> onUpdate("messageStreamShowTodos", JsonPrimitive(v)) },
        )
        SettingsDivider()
        SettingsSwitchRow(
            title = "自动确认用户提问",
            checked = desktop.bool("askUserQuestionAutoResolutionEnabled", true),
            onCheckedChange = { v -> onUpdate("askUserQuestionAutoResolutionEnabled", JsonPrimitive(v)) },
        )
        SettingsDivider()
        SettingsSwitchRow(
            title = "完整保留模型输入输出",
            description = "可能包含敏感内容",
            checked = desktop.bool("modelIoFullRetentionEnabled", false),
            onCheckedChange = { v -> onUpdate("modelIoFullRetentionEnabled", JsonPrimitive(v)) },
        )
    }

    SectionCard(title = "工具分组") {
        SettingsSwitchRow(
            title = "折叠探索类工具",
            checked = desktop.bool("toolGroupingExploreEnabled", true),
            onCheckedChange = { v -> onUpdate("toolGroupingExploreEnabled", JsonPrimitive(v)) },
        )
        SettingsDivider()
        SettingsSwitchRow(
            title = "折叠终端类工具",
            checked = desktop.bool("toolGroupingTerminalEnabled", true),
            onCheckedChange = { v -> onUpdate("toolGroupingTerminalEnabled", JsonPrimitive(v)) },
        )
        SettingsDivider()
        SettingsSwitchRow(
            title = "折叠变更类工具",
            checked = desktop.bool("toolGroupingChangesEnabled", false),
            onCheckedChange = { v -> onUpdate("toolGroupingChangesEnabled", JsonPrimitive(v)) },
        )
    }

    SectionCard(title = "任务") {
        SettingsSwitchRow(
            title = "自动归档旧任务",
            checked = desktop.bool("taskAutoArchiveEnabled", false),
            onCheckedChange = { v -> onUpdate("taskAutoArchiveEnabled", JsonPrimitive(v)) },
        )
        SettingsDivider()
        TextSettingRow(
            title = "归档天数",
            value = desktop.number("taskAutoArchiveOlderThanDays", 7.0).toInt().toString(),
            onSave = { v -> v.toIntOrNull()?.let { onUpdate("taskAutoArchiveOlderThanDays", JsonPrimitive(it)) } },
        )
    }

    SectionCard(title = "网络") {
        TextSettingRow(
            title = "HTTP 代理",
            value = desktop.str("httpProxy"),
            placeholder = "http://127.0.0.1:7890",
            onSave = { v -> onUpdate("httpProxy", JsonPrimitive(v)) },
        )
        SettingsDivider()
        TextSettingRow(
            title = "不走代理的地址",
            value = desktop.str("httpProxyNoProxy"),
            placeholder = "localhost,127.0.0.1",
            onSave = { v -> onUpdate("httpProxyNoProxy", JsonPrimitive(v)) },
        )
        SettingsDivider()
        TextSettingRow(
            title = "代理 CA 证书路径",
            value = desktop.str("httpProxyCaCertPath"),
            onSave = { v -> onUpdate("httpProxyCaCertPath", JsonPrimitive(v)) },
        )
    }

    SectionCard(title = "存储与终端") {
        InfoRow("数据目录", desktop.str("dataBaseDir", "默认"))
        SettingsDivider()
        TextSettingRow(
            title = "终端字体",
            value = desktop.str("terminalFontFamily"),
            placeholder = "留空 = 跟随系统",
            onSave = { v -> onUpdate("terminalFontFamily", JsonPrimitive(v)) },
        )
        SettingsDivider()
        SettingsSwitchRow(
            title = "终端继承系统配置",
            checked = desktop.bool("terminalInheritSystemProfile", true),
            onCheckedChange = { v -> onUpdate("terminalInheritSystemProfile", JsonPrimitive(v)) },
        )
    }

    SectionCard(title = "桌面端更新") {
        SettingsSwitchRow(
            title = "接收预览版更新",
            checked = desktop.bool("receivePreviewUpdates", false),
            onCheckedChange = { v -> onUpdate("receivePreviewUpdates", JsonPrimitive(v)) },
        )
        SettingsDivider()
        SettingsSwitchRow(
            title = "自动下载并安装更新",
            checked = desktop.bool("autoDownloadAndInstallUpdates", false),
            onCheckedChange = { v -> onUpdate("autoDownloadAndInstallUpdates", JsonPrimitive(v)) },
        )
    }
}

// ---------------------------------------------------------------------------
// 模型与服务商（`provider-settings`）
// ---------------------------------------------------------------------------

/** 服务商页的动作集合（避免把 ViewModel 传进无状态界面）。 */
data class ProviderActions(
    val refresh: () -> Unit = {},
    val setProviderEnabled: (providerId: String, enabled: Boolean) -> Unit = { _, _ -> },
    val renameProvider: (providerId: String, name: String) -> Unit = { _, _ -> },
    val deleteProvider: (providerId: String) -> Unit = {},
    val createProvider: (templateId: String, name: String) -> Unit = { _, _ -> },
    val setModelEnabled: (providerId: String, modelId: String, enabled: Boolean) -> Unit =
        { _, _, _ -> },
    val deleteModel: (providerId: String, modelId: String) -> Unit = { _, _ -> },
    val addModel: (providerId: String, modelId: String) -> Unit = { _, _ -> },
) {
    companion object {
        val None = ProviderActions()
    }
}

@Composable
private fun ModelProviderSection(state: SettingsSectionUiState, actions: ProviderActions) {
    val colors = LocalZemoteColors.current
    val view = state.providers
    var addProviderOpen by remember { mutableStateOf(false) }
    var addModelFor by remember { mutableStateOf<String?>(null) }
    var renameFor by remember { mutableStateOf<String?>(null) }
    var deleteFor by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(setOf<String>()) }

    SectionCard(title = "服务商") {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = when {
                    state.providersLoading -> "正在读取…"
                    view == null -> "还没有读到服务商"
                    else -> "共 ${(view["providers"] as? JsonArray)?.size ?: 0} 个"
                },
                modifier = Modifier.weight(1f),
                style = ZemoteType.uiSm,
                color = colors.foregroundSubtle,
            )
            TextButton(onClick = actions.refresh) { Text("刷新", style = ZemoteType.uiSm) }
            TextButton(onClick = { addProviderOpen = true }) { Text("新增", style = ZemoteType.uiSm) }
        }
    }

    val providers = (view?.get("providers") as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        .orEmpty()

    providers.forEach { provider ->
        val providerId = provider.str("providerId")
        val name = provider.str("providerName").ifBlank { providerId }
        val enabled = provider.bool("enabled", true)
        val isPersonal = provider["personalConfig"] is JsonObject
        val account = provider["accountState"] is JsonObject
        val models = (provider["models"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
        val busy = state.providerBusy == providerId
        val isOpen = providerId in expanded

        SectionCard(title = name) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        expanded = if (isOpen) expanded - providerId else expanded + providerId
                    }
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = provider.str("templateId").ifBlank { "个人服务商" },
                        style = ZemoteType.uiSm,
                        color = colors.foreground,
                    )
                    Text(
                        text = "${models.size} 个模型" + if (account) " · 账户服务商" else "",
                        style = ZemoteType.uiXs,
                        color = colors.foregroundSubtle,
                    )
                }
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else if (isPersonal) {
                    Switch(
                        checked = enabled,
                        onCheckedChange = { v -> actions.setProviderEnabled(providerId, v) },
                        colors = zemoteSwitchColors(),
                    )
                } else {
                    Text(
                        text = if (enabled) "已启用" else "已停用",
                        style = ZemoteType.uiXs,
                        color = colors.foregroundSubtle,
                    )
                }
            }

            if (isOpen) {
                models.forEach { model ->
                    val modelId = model.str("modelId")
                    val modelBusy = state.providerBusy == "$providerId/$modelId"
                    SettingsDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = modelId,
                                style = ZemoteType.uiSm.copy(fontFamily = FontFamily.Monospace),
                                color = colors.foreground,
                            )
                            val flags = buildList {
                                if (model.bool("builtin", false)) add("内置")
                                if (model.bool("selectable", true).not()) add("不可选")
                            }
                            if (flags.isNotEmpty()) {
                                Text(
                                    text = flags.joinToString(" · "),
                                    style = ZemoteType.ui2xs,
                                    color = colors.foregroundSubtle,
                                )
                            }
                        }
                        if (modelBusy) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        } else {
                            Switch(
                                checked = model.bool("enabled", true),
                                onCheckedChange = { v ->
                                    actions.setModelEnabled(providerId, modelId, v)
                                },
                                colors = zemoteSwitchColors(),
                            )
                            if (!model.bool("builtin", false)) {
                                IconButton(onClick = { actions.deleteModel(providerId, modelId) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "删除模型",
                                        tint = colors.foregroundSubtle,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                SettingsDivider()
                Row(
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { addModelFor = providerId }) { Text("添加模型", style = ZemoteType.uiSm) }
                    if (isPersonal) {
                        TextButton(onClick = { renameFor = providerId }) { Text("重命名", style = ZemoteType.uiSm) }
                        TextButton(onClick = { deleteFor = providerId }) {
                            Text("删除", style = ZemoteType.uiSm, color = colors.destructive)
                        }
                    }
                }
            }
        }
    }

    if (addProviderOpen) {
        val templates = (view?.get("providerTemplates") as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
        var templateId by remember {
            mutableStateOf(templates.firstOrNull()?.str("templateId").orEmpty())
        }
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addProviderOpen = false },
            title = { Text("新增服务商") },
            text = {
                Column {
                    if (templates.isEmpty()) {
                        Text(
                            "电脑端没有可用的模板。",
                            style = ZemoteType.uiSm,
                            color = colors.foregroundSubtle,
                        )
                    } else {
                        Text("模板", style = ZemoteType.uiXs, color = colors.foregroundSubtle)
                        templates.forEach { template ->
                            val id = template.str("templateId")
                            SelectableCheckRow(id, id == templateId) { templateId = id }
                        }
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("显示名称（可选）") },
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = templateId.isNotBlank(),
                    onClick = {
                        addProviderOpen = false
                        actions.createProvider(templateId, name.trim())
                    },
                ) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { addProviderOpen = false }) { Text("取消") } },
        )
    }

    addModelFor?.let { providerId ->
        var modelId by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addModelFor = null },
            title = { Text("添加模型") },
            text = {
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text("模型 ID") },
                    placeholder = { Text("例如 glm-5.2") },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = modelId.isNotBlank(),
                    onClick = {
                        val id = modelId.trim()
                        addModelFor = null
                        actions.addModel(providerId, id)
                    },
                ) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { addModelFor = null }) { Text("取消") } },
        )
    }

    renameFor?.let { providerId ->
        val current = providers.firstOrNull { it.str("providerId") == providerId }
            ?.str("providerName").orEmpty()
        var draft by remember { mutableStateOf(current) }
        AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text("重命名服务商") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = draft.isNotBlank(),
                    onClick = {
                        renameFor = null
                        actions.renameProvider(providerId, draft.trim())
                    },
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text("取消") } },
        )
    }

    deleteFor?.let { providerId ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("删除这个服务商？") },
            text = { Text("它会从电脑端的服务商列表里移除，已选中的模型将不可用。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteFor = null
                    actions.deleteProvider(providerId)
                }) { Text("删除", color = colors.destructive) }
            },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("取消") } },
        )
    }
}

// ---------------------------------------------------------------------------
// 通用控件
// ---------------------------------------------------------------------------

/** 分组：分组标题 + 12dp 圆角卡片；行间分隔线由调用方在行之间插入。 */
@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        SettingsGroupHeader(title)
        ZemoteCard(content = content)
    }
}

@Composable
private fun SelectRow(
    title: String,
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == value }?.second ?: value
    Box {
        SettingsRow(
            title = title,
            onClick = { open = true },
            trailing = {
                SettingsValue(label)
            },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (v, l) ->
                DropdownMenuItem(
                    text = { Text(l, style = ZemoteType.uiBase) },
                    onClick = { open = false; onSelect(v) },
                    trailingIcon = if (v == value) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun TextSettingRow(
    title: String,
    value: String,
    placeholder: String = "",
    onSave: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var draft by remember(value) { mutableStateOf(value) }

    SettingsRow(
        title = title,
        onClick = { draft = value; open = true },
        trailing = {
            SettingsValue(value.ifBlank { "未设置" }, monospace = true)
        },
    )

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text(placeholder) },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { open = false; onSave(draft.trim()) }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { open = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    val colors = LocalZemoteColors.current
    SettingsRow(
        title = title,
        trailing = {
            Text(
                text = value,
                style = ZemoteType.uiSm.copy(fontFamily = FontFamily.Monospace),
                color = colors.foregroundSubtle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(160.dp),
            )
        },
    )
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    label: String,
    onChange: (Float) -> Unit,
) {
    val colors = LocalZemoteColors.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = ZemoteType.uiBase, fontWeight = FontWeight.Medium, color = colors.foreground)
            Text(label, style = ZemoteType.uiSm, color = colors.foregroundSubtle)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
        )
    }
}

/** 单选行（对话框里的模板选择 / 列表内互斥项）。 */
@Composable
private fun SelectableCheckRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalZemoteColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = ZemoteType.uiBase, color = colors.foreground)
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = colors.foreground,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun relayText(state: RelayState): String = when (state) {
    RelayState.Paired -> "已连接"
    RelayState.Connecting -> "连接中"
    RelayState.Authenticating -> "认证中"
    RelayState.Waiting -> "等待配对"
    RelayState.Reconnecting -> "重连中"
    RelayState.Error -> "连接错误"
    RelayState.Kicked -> "已被接管"
    RelayState.Closed -> "已断开"
    RelayState.Idle -> "未连接"
}
