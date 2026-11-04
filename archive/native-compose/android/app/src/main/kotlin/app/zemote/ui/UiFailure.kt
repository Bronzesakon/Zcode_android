package app.zemote.ui

import app.zemote.auth.RemoteTokenException
import app.zemote.engine.EngineException
import app.zemote.ui.chat.ChatOpsUnavailable

/**
 * 把失败收敛成**一句可以直接展示的话**（表现层的职责：失败只变成状态，不往上抛）。
 *
 * 两条约定：
 *
 * - `EngineException` 的 `panic` 分支**不透传原文**：契约 §12 明说 panic 的 message 可能含
 *   Rust 内部路径，只配进日志/调试页。这里给用户一句收敛文案。
 * - 其余分支尽量带上 `kind` 与 message——`badInput` 的原文往往正是用户需要看到的
 *   （「URL 解析不了」这类），把它翻译一遍只会丢掉信息。
 *
 * TODO(i18n): 文案尚未走 `AppSettings.locale`，接入本地化时改这一个函数即可。
 */
internal fun Throwable.toUserMessage(): String = when (this) {
    is EngineException -> when (error.kind) {
        "panic" -> "引擎内部错误（panic），本次操作已中止。详情见日志。"
        "timeout" -> "操作超时：${error.message.orEmpty()}"
        "invalidState" -> "当前状态不允许这个操作：${error.message.orEmpty()}"
        "badInput" -> "参数被引擎拒绝：${error.message.orEmpty()}"
        else -> "引擎错误（${error.kind}）：${error.message.orEmpty()}"
    }

    is RemoteTokenException -> message ?: "远控登录失败"

    is ChatOpsUnavailable -> message ?: "对话层尚未接线"

    is IllegalArgumentException -> message ?: "参数不合法"

    else -> "${this::class.java.simpleName}: ${message ?: "无详情"}"
}
