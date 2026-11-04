package app.zemote.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Web
import androidx.compose.ui.graphics.vector.ImageVector

/** 设置分组（与网页版 `settings.sidebar.group.*` 对齐）。 */
enum class SettingsGroup(val title: String) {
    LOCAL("本机"),
    BASICS("基础"),
    AGENT("智能体能力"),
    DATA("数据与统计"),
}

/**
 * 设置页的一个一级分类。
 *
 * `remoteKey` 非空表示它对应网页版 `i$n` 数组里的 section id；`null` 表示本机新增。
 * `readOnly` 表示桌面专属、手机上只能看不能改（UI-SPEC §3）。
 */
enum class SettingsCategory(
    val id: String,
    val title: String,
    val subtitle: String,
    val group: SettingsGroup,
    val icon: ImageVector,
    val readOnly: Boolean = false,
) {
    CONNECTION(
        id = "connection",
        title = "连接",
        subtitle = "当前电脑、重连与断开",
        group = SettingsGroup.LOCAL,
        icon = Icons.Default.Computer,
    ),
    NOTIFICATIONS(
        id = "notifications",
        title = "通知",
        subtitle = "完成、出错、等待授权；流体云状态",
        group = SettingsGroup.LOCAL,
        icon = Icons.Default.Bolt,
    ),
    GENERAL(
        id = "general",
        title = "通用",
        subtitle = "语言、界面模式、终端、网络、存储、任务",
        group = SettingsGroup.BASICS,
        icon = Icons.Default.Settings,
    ),
    APPEARANCE(
        id = "appearance",
        title = "外观",
        subtitle = "主题、界面字号、代码字号与代码预览",
        group = SettingsGroup.BASICS,
        icon = Icons.Default.Brush,
    ),
    MODEL_PROVIDER(
        id = "modelProvider",
        title = "模型与服务商",
        subtitle = "服务商、模型与连接方式",
        group = SettingsGroup.BASICS,
        icon = Icons.Default.Cloud,
    ),
    BROWSER(
        id = "browser",
        title = "浏览器",
        subtitle = "内置浏览器控制、缓存与导入",
        group = SettingsGroup.BASICS,
        icon = Icons.Default.Web,
        readOnly = true,
    ),
    COMPUTER_USE(
        id = "computerUse",
        title = "计算机使用",
        subtitle = "桌面自动化权限",
        group = SettingsGroup.BASICS,
        icon = Icons.Default.Computer,
        readOnly = true,
    ),
    SHORTCUTS(
        id = "shortcuts",
        title = "快捷键",
        subtitle = "桌面端键盘快捷键",
        group = SettingsGroup.BASICS,
        icon = Icons.Default.Keyboard,
        readOnly = true,
    ),
    WORKSPACE_SEARCH(
        id = "workspaceFileSearch",
        title = "工作区文件搜索",
        subtitle = "搜索范围与忽略规则",
        group = SettingsGroup.BASICS,
        icon = Icons.Default.Search,
        readOnly = true,
    ),
    MEMORY(
        id = "memory",
        title = "记忆",
        subtitle = "跨会话记忆开关",
        group = SettingsGroup.AGENT,
        icon = Icons.Default.Memory,
    ),
    SUBAGENTS(
        id = "subagents",
        title = "子代理",
        subtitle = "内置与自定义子代理",
        group = SettingsGroup.AGENT,
        icon = Icons.Default.SmartToy,
    ),
    PLUGINS(
        id = "plugin",
        title = "插件",
        subtitle = "插件市场、安装与启用",
        group = SettingsGroup.AGENT,
        icon = Icons.Default.Extension,
    ),
    MCP(
        id = "mcp",
        title = "MCP",
        subtitle = "MCP 服务器状态与配置",
        group = SettingsGroup.AGENT,
        icon = Icons.Default.Hub,
    ),
    SKILLS(
        id = "skill",
        title = "技能",
        subtitle = "技能列表与启用",
        group = SettingsGroup.AGENT,
        icon = Icons.Default.AutoAwesome,
    ),
    COMMANDS(
        id = "commands",
        title = "命令",
        subtitle = "斜杠命令目录",
        group = SettingsGroup.AGENT,
        icon = Icons.Default.Code,
    ),
    AUTOMATIONS(
        id = "automations",
        title = "定时任务",
        subtitle = "创建、启停与立即运行",
        group = SettingsGroup.AGENT,
        icon = Icons.Default.PlayCircle,
    ),
    HOOKS(
        id = "hooks",
        title = "钩子",
        subtitle = "会话与工具钩子",
        group = SettingsGroup.AGENT,
        icon = Icons.Default.Link,
    ),
    USAGE(
        id = "usage",
        title = "用量",
        subtitle = "应用与编码计划用量",
        group = SettingsGroup.DATA,
        icon = Icons.Default.QueryStats,
    ),
    ;

    companion object {
        fun fromId(id: String): SettingsCategory? = entries.firstOrNull { it.id == id }
    }
}
