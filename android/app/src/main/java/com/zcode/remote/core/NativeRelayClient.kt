package com.zcode.remote.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * relay 配对证明：`proof = base64url(HMAC-SHA256(key=passHash, msg=nonce|role|deviceSid))`。
 *
 * 与页面实现逐字对齐（docs/05 快照 @4696180：`calculateProof(passHash, nonce, role, deviceSid)`，
 * HMAC 密钥是 passHash 的 UTF-8 字节，消息是 `nonce|role|deviceSid` 的 UTF-8 字节，
 * 输出 base64url 无填充）。role 固定为 `terminal`。
 */
/**
 * `auth_init` 帧（3.17.1 适配，TODO §2.2）。
 *
 * 与归档 Rust 逐字对齐（`relay_client.rs:556-590`，3.17.1 快照核对）：
 *   * **token 模式**：没有 `role`，`client_ts` 用 **Unix 秒**（页面 `Math.floor(Date.now()/1e3)`）；
 *   * **legacy**：`role:"terminal"` + **毫秒**，随后走 `auth_challenge` / HMAC proof。
 *
 * 抽成顶层纯函数是为了能在 JVM 单测里把两条分支的形状钉住——真机上一次握手要扫码，
 * 而这里错一个字段就是"配对永远不 matched"。
 */
private val JSON_MEDIA_TYPE: okhttp3.MediaType =
    "application/json; charset=utf-8".toMediaType()

internal fun authInitFrame(
    creds: RelayCreds,
    nowMs: Long,
    /** 页面 meta.version 用桌面版本号（3.17.2 页面：`appVersion`）；未知时退回 "web"。 */
    appVersion: String? = null,
): JSONObject = JSONObject().apply {
    put("type", "auth_init")
    if (!creds.tokenMode) put("role", "terminal")
    put("device_sid", creds.deviceSid)
    put(
        "meta",
        JSONObject()
            .put("platform", "web")
            .put("version", appVersion?.takeIf { it.isNotBlank() } ?: "web")
            .put("name", "mobile-browser"),
    )
    put("client_ts", if (creds.tokenMode) nowMs / 1000 else nowMs)
}

/**
 * `pair_status_query` 心跳帧：页面 3.17.1/3.17.2 的形状是**只有 type + client_ts（秒）**，
 * 没有 `device_sid`。这里逐字对齐（多带字段服务端目前容忍，但没有理由冒这个险）。
 */
internal fun pairStatusQueryFrame(nowMs: Long): JSONObject = JSONObject()
    .put("type", "pair_status_query")
    .put("client_ts", nowMs / 1000)

/** 业务帧外层：`{type:"data", payload, client_ts:秒}`（页面 `prepare()` 同口径）。 */
internal fun dataEnvelope(payload: JSONObject, nowMs: Long): JSONObject = JSONObject()
    .put("type", "data")
    .put("payload", payload)
    .put("client_ts", nowMs / 1000)

/**
 * 原生承载的拨号 URL：**只用** host/path，token 与 mid 一律重新拼。
 *
 * 页面移交的 wsUrl 可能带着它自己那枚一次性 token；原样复用会拿到 1006
 * （2026-11-05 真机 CDP 实证）。这里把查询串丢掉，换成原生现铸的 token。
 */
internal fun carrierWsUrl(base: String, relayToken: String?, deviceMid: String?): String {
    val out = StringBuilder(base.substringBefore('?'))
    val params = ArrayList<String>(2)
    if (!relayToken.isNullOrBlank()) {
        params.add("token=" + java.net.URLEncoder.encode(relayToken, "UTF-8"))
    }
    if (!deviceMid.isNullOrBlank()) {
        params.add("mid=" + java.net.URLEncoder.encode(deviceMid, "UTF-8"))
    }
    if (params.isNotEmpty()) {
        out.append('?').append(params.joinToString("&"))
    }
    return out.toString()
}

internal fun relayProof(passHash: String, nonce: String, role: String, deviceSid: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(passHash.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    val digest = mac.doFinal("$nonce|$role|$deviceSid".toByteArray(Charsets.UTF_8))
    return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

/** 原生承载的连接凭证。只驻内存；任何日志路径都不得打印这些字段。 */
data class RelayCreds(
    val wsUrl: String,
    val deviceSid: String,
    /** legacy HMAC 模式的 passHash；3.16.1+ 的 token 模式下为空串。 */
    val passHash: String,
    val deviceMid: String?,
    /**
     * 页面移交的 ZCode JWT（仅内存）。**relay token 是一次性的**（2026-11-05 真机
     * CDP 实证：同一枚第二次连接直接 1006），所以原生不能复用页面那枚 token，而是
     * 每次连接前用它现铸一枚：`POST /api/v1/remote/token {"role":"terminal"}`。
     * 与 `passHash` 一样是凭证，**绝不进日志**。
     */
    val jwt: String? = null,
) {
    /** 有 JWT 即 token 模式；legacy 回退留给带 `hash` 的旧链接。 */
    val tokenMode: Boolean get() = !jwt.isNullOrBlank()
}

/**
 * 原生承载— 原生直连 relay 客户端，完全不经过 WebView/renderer。
 *
 * 两种模式（2026-09-13 拍板的后台独占形态）：
 *   * 实验模式（durationMs>0，诊断指令 carrier_test）：配对跑一轮自动关闭，
 *     用于 KICK/takeover 语义观测——已定案：配对层面单控制端互斥。
 *   * 接管模式（durationMs<=0）：后台页面链路判死时由 ShellRuntime 启动；
 *     配对成功后启动 BridgeManager 覆盖（M3b/c：workspace-list → 每工作区
 *     4-RPC 握手 + sessions-index 订阅），任务事件经 [sessionsSink] 直接进
 *     原生 TaskStore/通知链路。断线自动重连（退避 10s），直到 [stop]（回前台
 *     交还）。接管会踢掉页面连接（KICK 互斥），因此前台绝不进入本模式。
 *
 * 握手链与页面逐字对齐（docs/05 @4699005/@4702045）：
 *   connect → auth_init{role:'terminal', device_sid, meta, client_ts}
 *   ← auth_challenge{nonce}
 *   → auth_response{device_sid, proof, client_ts}
 *   ← auth_ack{pair_status} → paired
 *
 * 日志只打阶段与 pair_status——凭证、URL、sid/hash/mid 一律不落日志。
 */
object NativeRelayClient {

    // Protect publication/cancellation, never blocking bridge cleanup or RPC waits.
    private val lifecycleLock = Any()
    private val lifetime = ProbeLifetime()
    private var foregroundRestricted = false

    /**
     * 握手 FSM 阶段。[Phase.CONNECTING]（[connectNow] 里赋值、`onOpen` 才转
     * [Phase.AUTHENTICATING]）是**真实状态，不是漏删**：今天没有任何分支区分它与
     * AUTHENTICATING，保留是为了与页面侧那台状态机
     * 逐步镜像——少一格，将来要按阶段分支时就得回头补。
     */
    enum class Phase { IDLE, CONNECTING, AUTHENTICATING, PAIRED, CLOSED }

    /**
     * 一次覆盖最多开几座桥。
     *
     * **这是成本取舍，不是平台/协议限制**（2026-09-17 定案并固化）：
     *  - 每座桥 = 桌面端**一条常驻会话**，且每轮轮换要**回收 + 重新握手 + 重订**一次；
     *  - 所以这个数直接乘上"每轮一次的握手成本"；
     *  - **轮换本身已经与 N 和 M 都无关**（[com.zcode.remote.core.BridgeManager.reanchorProgress]
     *    每轮只对"被服务的那座"发 resync、而那座也只 resync 自己**最饿的一条会话**，
     *    只轮换最饿的一座），因此抬高这个数**不会**让轮换互相冲突——它只决定"最多同时
     *    照顾几个工作区"。
     *
     * 3 → **5**（2026-09-17）：为了在鸿蒙侧做 **5 并发**可行性实验（用户拍板，只做理论验证）。
     * **卡片侧不设上限**（2026-09-18 用户否决硬上限）：在跑的任务有几个就提升几个；
     * 只有"留到用户清掉"的完成卡有界限（`PromotionPolicy.MAX_FINISHED_PROMOTED`）。
     * 曾经以为"ColorOS 只并发提升 2 张"，被现场推翻（三张卡同时显示、dump 里三条 `PROMOTED_ONGOING`）。
     * 协议与桌面端侧**没有**明文上限；真机实测过的最大并发是 2 座（158）。
     *
     * ⚠️ **单源**（2026-09-17）：`ShellRuntime.MULTI_WS_COVERAGE_CAP` **直接引用本常量**，
     * 不再各写一个 5——改这里一处就够，两处不同步会直接编译不过。
     */
    internal const val DEFAULT_MAX_COVERAGE = 5

    /** 同一工作区连续开桥失败几次后进入冷却。 */
    private const val BRIDGE_FAIL_COOLDOWN_AFTER = 2

    /** 冷却时长：别每轮重连都去撞同一个必失败的桥。 */
    private const val BRIDGE_FAIL_COOLDOWN_MS = 10 * 60 * 1000L

    /**
     * 覆盖刷新的周期（[startCoverageRefresh]）：60s 够快（新任务最多一分钟就被覆盖），
     * 又远慢于 12s 的轮换拍，不会和轮换抢握手。
     */
    private const val COVERAGE_REFRESH_MS = 60_000L

    @Volatile
    private var phase: Phase = Phase.IDLE

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var creds: RelayCreds? = null

    /** 接管模式的任务事件出口（ShellRuntime 注入，喂 TaskStore/通知）。 */
    @Volatile
    var sessionsSink: ((JSONObject) -> Unit)? = null

    private var heartbeatCount = 0
    private var ackCount = 0

    /** 交还时"等 socket 关闭确认"的登记表：key 是 socket 实例本身（身份相等）。 */
    private val closeWatchers = java.util.concurrent.ConcurrentHashMap<WebSocket, () -> Unit>()

    /**
     * relay 心跳间隔。**5s，不是 10s**：3.17.1 页面与归档 Rust 都是
     * `heartbeatIntervalMs ?? 5_000` / `HEARTBEAT_INTERVAL_MS = 5_000`，ack 超时
     * 15s（`relay_client.rs:138-140`、`validation-B0HfQ5gr.js` off≈152400）。
     * `docs/25` 里那句"心跳 10s / ack 30s"是 3.16.1 时代的旧值，别按它调。
     */
    private const val HEARTBEAT_INTERVAL_MS = 5_000L

    /**
     * 握手超时：3.17.1 页面新增了同一常量（快照 validation @152,196-152,218、@154,596，
     * 见 `docs/android/web-3.17.1-vs-docs-diff-audit.md` 发现 2）——`auth_init` 发出后
     * 15s 内没等到 `auth_ack{matched}` 就断线重连。没有它，"连上了但永远不配对"会占着
     * socket 干等：心跳/ack 看门狗都只看**配对后**的帧，罩不住握手期。
     */
    private const val HANDSHAKE_TIMEOUT_MS = 15_000L

    /**
     * 关闭确认的等待上限（TODO §2.3 的 300ms 交还预算）。**100ms，不是 250ms**：
     * 常态路径是 close 确认回调（本机 `cancel()` 通常在一个事件循环内就回调
     * `onFailure`，远早于超时），这个值只是防"对端不回 FIN"把回前台卡死；
     * 250ms 会让最坏情况（超时 + reload 派发）贴穿 300ms 预算。
     * 真机若量出常态确认 >100ms，再回来调，并以 §2.6 实测为准。
     */
    private const val CLOSE_CONFIRM_TIMEOUT_MS = 100L

    /** 关闭确认的兜底计时器：共享一个 daemon 线程，成功路径会取消任务（审查 Kotlin P2-1）。 */
    private val closeConfirmTimer = java.util.Timer("carrier-close-confirm", true)
    private var startedAtMs = 0L
    private var persistent = false
    private var carrierPaired: (() -> Unit)? = null
    private var carrierKicked: (() -> Unit)? = null
    @Volatile
    private var stopping = false
    private var reconnectAttempt = 0
    private var durationTimer: java.util.Timer? = null
    private var bootstrapTimer: java.util.Timer? = null

    /** 页面正在显示的工作区（view-state 帧用，也是默认的覆盖目标）；空串=未知。 */
    @Volatile
    private var onlyWorkspace: String = ""

    /**
     * **显式覆盖目标**（多工作区）。非空时只用它，空=退回"只开页面当前工作区"。
     *
     * 内容由 [ShellRuntime] 决定（多工作区开关打开时＝page 工作区 ∪ 有在跑任务的工作区，
     * 上限 [DEFAULT_MAX_COVERAGE]），或由诊断指令 `coverage_ws:<k1>,<k2>` 直接指定（E1 用）。
     *
     * **失败冷却**：只有 `coverage_ws:` 诊断清单（[coverageIsDiagnostic]）豁免——实验要能
     * 看到失败本身；生产路径（多工作区开关 + 发现链）**照旧受冷却**，否则每轮重连都会去撞
     * 同一座必失败的桥。2026-09-17 之前这段注释说的是"显式覆盖都不受冷却"，与代码不符。
     */
    @Volatile
    private var coverageWorkspaces: List<String> = emptyList()

    /** 开桥失败计数 / 冷却截止（毫秒，`SystemClock` 不可用，这里用墙钟）。 */
    private val bridgeFailCount = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val bridgeCooldownUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * 发现链落点：`bootstrap-response.result.tasks` 里"有活动任务"的工作区键。
     * 与 [BridgeManager.discoveredActiveWorkspaces]（workspace-list 那条）一起供
     * [discoveredCoverage] 使用——**这两条都是我们在收的响应，零新增订阅**。
     */
    @Volatile
    private var bootstrapActiveWorkspaces: List<String> = emptyList()

    /** `coverage_ws:` 诊断清单＝只用它（E1 语义），不并入发现链。 */
    @Volatile
    private var coverageIsDiagnostic = false

    /**
     * 定向接管的覆盖来源：从**已经在收的响应**里解析出来的"有活动任务的工作区"。
     *
     * 它解决的是老缺口——壳只认识"页面打开过的工作区"（`pageWorkspaceKey` 来自页面上报），
     * 所以多工作区覆盖以前要求用户先把每个工作区在手机上打开一遍。现在承载一动就能自己知道。
     * 顺序：bootstrap（必填源）优先，其次 workspace-list（可选源）。
     */
    fun discoveredCoverage(): List<String> {
        val out = LinkedHashSet<String>()
        out.addAll(bootstrapActiveWorkspaces)
        out.addAll(bridgeManager?.discoveredActiveWorkspaces.orEmpty())
        return out.toList()
    }

    /** 页面正在显示的任务（view-state 帧里要用；空串=未知）。 */
    @Volatile
    private var onlyTaskId: String = ""

    /**
     * 覆盖刷新定时器（见 [startCoverageRefresh]）：承载期间定期补开"新出现的活动工作区"的桥。
     */
    private var coverageTimer: java.util.Timer? = null

    private var heartbeatTimer: java.util.Timer? = null

    /** 握手超时计时器（见 [HANDSHAKE_TIMEOUT_MS]）。 */
    private var handshakeTimer: java.util.Timer? = null
    private var reconnectTimer: java.util.Timer? = null

    @Volatile
    private var bridgeManager: BridgeManager? = null

    private val client by lazy {
        OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** Includes a disconnected persistent session waiting for its retry. */
    /**
     * 并行覆盖实验开关（TODO §2.4）：关掉桥轮换，只开桥 + 同时订阅，并每拍打印
     * 每座桥的收帧年龄。用于验证「桌面端是否真的同一时刻只服务一座桥」——
     * 结论出来之前不写策略。
     */
    @Volatile
    private var parallelCoverage = false

    fun setParallelCoverage(enabled: Boolean) {
        parallelCoverage = enabled
        bridgeManager?.rotateEnabled = !enabled
        Diagnostics.log(
            "warn",
            "承载: 并行覆盖实验" + (if (enabled) {
                "开启——不轮换，每 12s 打一行每桥收帧年龄（观察 ≥10 分钟）"
            } else {
                "关闭——恢复 12s 轮换（只 resync 最饿的一座）"
            }),
        )
    }

    fun isParallelCoverage(): Boolean = parallelCoverage

    /** Includes a disconnected persistent session waiting for its retry. */
    fun isRunning(): Boolean = lifetime.active

    /**
     * M4：对话进展的出口（ShellRuntime 注入：工作区键、会话 id、文本）。
     * 接管开始时挂上，桥把"最新一行"解出来就回调到这里。
     */
    @Volatile
    var progressSink: ((workspaceKey: String, sessionId: String, text: String) -> Unit)? = null

    /** 配对成功回调向原生询问的**前台态**（ShellRuntime 注入；null 视为"不在前台"，走老路）。 */
    @Volatile
    var isForegroundNow: (() -> Boolean)? = null

    /**
     * 配对成功但应用已回前台的**竞态处置**（ShellRuntime 注入：交还 + 触发页面恢复）。
     *
     * 现场（2026-09-27 17:07）：接管判定在后台做出，配对完成时用户已经回来——relay 在
     * matched 一刻把页面连接踢掉，页面进 KICKED 终态；而注入层"前台被顶→不自动干预"
     * 的规则会让它一直挂着，直到用户再进出一次应用（activity 重建）才恢复。
     */
    @Volatile
    var foregroundConflict: (() -> Unit)? = null

    /**
     * 会话流运行态出口：用会话尾窗的 `turnHeader.state` 判"在跑"。
     *
     * **它是两条运行态源之一，不是"兜底"**——与**页面自己订的** controller 流平级，按
     * "**谁后到谁算数**"参与判定（时刻表见 `TaskStore.livePhasesAt`）。
     * 它的盲区：按会话生效，只覆盖承载已经订阅的那些工作区——"任务正在别的工作区里跑"
     * 它看不见，那一格由页面自己的 controller 流补。
     * （原先还有第三源＝**壳自己订**的 `controller/tasks-index`；那条已于 4a 轮随订阅实现
     * 一起删除——打开它会让桌面端 host 崩溃自毁，取证见 `docs/18` §4。）
     * 所以少任何一条源都可能让后台卡片冻在接管那一刻。
     */
    @Volatile
    var turnStateSink: ((workspaceKey: String, sessionId: String, running: Boolean) -> Unit)? = null

    /**
     * M4：某工作区当前在跑的会话（ShellRuntime 注入）。握手时用它把对话订阅
     * **抢在索引订阅之前**发出去——顺序错了桌面端就永远不回包（见 runHandshake）。
     */
    @Volatile
    var runningSessionsProvider: ((workspaceKey: String) -> List<String>)? = null

    /**
     * M4：订阅某任务的对话详情。未接管 / 该工作区没有桥时静默无操作
     * （调用方下一轮再试）。阻塞，调用方自己保证在后台线程。
     */
    fun subscribeProgress(workspaceKey: String, sessionId: String) {
        val manager = bridgeManager ?: return
        try {
            manager.subscribeProgress(workspaceKey, sessionId)
        } catch (e: Exception) {
            Diagnostics.log("debug", "承载: 订阅对话详情失败（$workspaceKey）: ${e.message}")
        }
    }

    /** 周期重挂：逼桌面端再推一份对话快照（推送稀疏时的保险）。 */
    fun reanchorProgress() {
        val manager = bridgeManager ?: return
        try {
            manager.reanchorProgress()
        } catch (e: Exception) {
            Diagnostics.log("debug", "承载: 重挂对话订阅失败: ${e.message}")
        }
    }

    /**
     * @param durationMs 探针存活时长；<=0 表示接管模式（持久，直到 [stop]，
     *   且配对成功后启动桥覆盖 + 断线自动重连）。
     * @param onlyWorkspace 页面**自己**正在显示的工作区键：view-state 帧用它，也是
     *   没有 [coverageWorkspaces] 时的唯一覆盖目标。空串表示注入层还没上报。
     * @param coverageWorkspaces 多工作区覆盖清单（非空时只用它，顺序即开桥顺序）。
     * @param coverageIsDiagnostic `coverage_ws:` 给的清单＝只用它（E1 实验语义），
     *   并且**豁免开桥失败冷却**（实验要能看到失败本身）；false（承载生产路径）时会在
     *   **配对拿到 bootstrap 响应之后**并入"发现链"给出的活动工作区，且照旧受冷却。
     */
    fun start(
        newCreds: RelayCreds,
        durationMs: Long = 60_000L,
        onlyWorkspace: String = "",
        onlyTaskId: String = "",
        coverageWorkspaces: List<String> = emptyList(),
        coverageIsDiagnostic: Boolean = false,
        onPaired: (() -> Unit)? = null,
        onKicked: (() -> Unit)? = null,
    ) {
        startInternal(
            newCreds,
            durationMs,
            takeoverOverride = null,
            onlyWorkspace = onlyWorkspace,
            onlyTaskId = onlyTaskId,
            coverageWorkspaces = coverageWorkspaces,
            coverageIsDiagnostic = coverageIsDiagnostic,
            onPaired = onPaired,
            onKicked = onKicked,
        )
    }

    /**
     * 真机验证用：接管语义（开覆盖 + 重连）跑固定时长后自动交还，
     * 让 `carrier_takeover` 诊断指令能在不依赖"后台判死"的情况下验证 M3b/c。
     */
    fun startTakeoverForTest(newCreds: RelayCreds, autoStopMs: Long) {
        startInternal(
            newCreds,
            durationMs = -1L,
            takeoverOverride = autoStopMs,
            onlyWorkspace = "",
            onlyTaskId = "",
        )
    }

    private fun startInternal(
        newCreds: RelayCreds,
        durationMs: Long,
        takeoverOverride: Long?,
        onlyWorkspace: String = "",
        onlyTaskId: String = "",
        coverageWorkspaces: List<String> = emptyList(),
        coverageIsDiagnostic: Boolean = false,
        onPaired: (() -> Unit)? = null,
        onKicked: (() -> Unit)? = null,
    ) = synchronized(lifecycleLock) {
        if (isRunning()) {
            Diagnostics.log("warn", "承载: 已在运行（phase=$phase），忽略重复启动")
            return@synchronized
        }
        foregroundRestricted = durationMs <= 0 && takeoverOverride == null
        if (foregroundRestricted && isForegroundNow?.invoke() == true) return@synchronized
        val token = lifetime.begin() ?: return@synchronized
        creds = newCreds
        this.onlyWorkspace = onlyWorkspace
        this.onlyTaskId = onlyTaskId
        this.coverageWorkspaces = coverageWorkspaces
        this.coverageIsDiagnostic = coverageIsDiagnostic
        bridgeFailCount.clear()
        bridgeCooldownUntil.clear()
        persistent = durationMs <= 0
        carrierPaired = onPaired
        carrierKicked = onKicked
        stopping = false
        reconnectAttempt = 0
        heartbeatCount = 0
        ackCount = 0
        startedAtMs = System.currentTimeMillis()
        Diagnostics.log(
            "info",
            "承载: 启动原生直连探针（${if (persistent) "接管模式" else "duration=${durationMs / 1000}s"}，凭证仅内存）",
        )
        bootstrapActiveWorkspaces = emptyList()
        connectNow(token)
        if (!lifetime.isCurrent(token)) return@synchronized
        if (takeoverOverride != null) {
            durationTimer = java.util.Timer(true).apply {
                schedule(
                    object : java.util.TimerTask() {
                        override fun run() = stopIfCurrent(token, "接管验证到点")
                    },
                    takeoverOverride,
                )
            }
        } else if (durationMs > 0) {
            durationTimer = java.util.Timer(true).apply {
                schedule(
                    object : java.util.TimerTask() {
                        override fun run() = stopIfCurrent(token, "duration 到点")
                    },
                    durationMs,
                )
            }
        }
        heartbeatTimer = java.util.Timer(true).apply {
            scheduleAtFixedRate(
                object : java.util.TimerTask() {
                    override fun run() = sendHeartbeat(token)
                },
                HEARTBEAT_INTERVAL_MS,
                HEARTBEAT_INTERVAL_MS,
            )
        }
    }

    private fun isCurrent(token: Long, webSocket: WebSocket): Boolean =
        lifetime.isCurrent(token) && !stopping && phase != Phase.CLOSED && socket === webSocket

    /**
     * 握手看门狗：`onOpen`（auth_init 已发）后 [HANDSHAKE_TIMEOUT_MS] 内没转 [Phase.PAIRED]
     * 就断线重连。配对成功、socket 死亡、[stop] 三处都会取消它。
     * 调用方持有 lifecycleLock。
     */
    private fun scheduleHandshakeTimeout(token: Long, webSocket: WebSocket) {
        cancelHandshakeTimeout()
        handshakeTimer = java.util.Timer(true).apply {
            schedule(
                object : java.util.TimerTask() {
                    override fun run() {
                        val reconnect: Boolean
                        synchronized(lifecycleLock) {
                            if (!lifetime.active || !isCurrent(token, webSocket)) return
                            if (phase == Phase.PAIRED) return
                            val wasPhase = phase
                            phase = Phase.CLOSED
                            reconnect = true
                            Diagnostics.log(
                                "warn",
                                "承载: ${HANDSHAKE_TIMEOUT_MS / 1000}s 握手超时（auth_ack 未到，" +
                                    "phase=$wasPhase）——断线重连",
                            )
                            try {
                                webSocket.close(1000, "handshake timeout")
                            } catch (e: Exception) {
                                Diagnostics.log("debug", "承载: 握手超时关闭异常 ${e.message}")
                            }
                        }
                        if (reconnect) scheduleReconnect(token)
                    }
                },
                HANDSHAKE_TIMEOUT_MS,
            )
        }
    }

    private fun cancelHandshakeTimeout() {
        handshakeTimer?.cancel()
        handshakeTimer = null
    }

    /** Caller holds lifecycleLock, so a stop cannot race the connection publication. */
    private fun connectNow(token: Long) {
        if (!lifetime.isCurrent(token)) return
        if (!mayAuthenticate(token)) return
        val c = creds ?: return
        phase = Phase.CONNECTING
        if (!c.tokenMode) {
            openSocket(token, c, null)
            return
        }
        // token 一次性：每次连接/重连都现铸一枚。铸取不能占主线程（start() 由
        // mainHandler 调进来），放到单线程 executor，结果回来再在锁内建 socket。
        mintExecutor.execute {
            val minted = try {
                mintRelayToken(c)
            } catch (e: Exception) {
                null
            }
            synchronized(lifecycleLock) {
                if (!lifetime.isCurrent(token) || phase == Phase.CLOSED) return@execute
                if (minted == null) {
                    Diagnostics.log("warn", "承载: relay token 铸造失败，10s 后重试")
                    scheduleReconnect(token)
                    return@execute
                }
                if (!mayAuthenticate(token)) return@execute
                openSocket(token, c, minted)
            }
        }
    }

    /** Build the ws URL from the host/path only, with the freshly minted token and mid. */
    private fun openSocket(token: Long, c: RelayCreds, relayToken: String?) {
        val url = carrierWsUrl(c.wsUrl, relayToken, c.deviceMid)
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, createListener(token, carrierPaired, carrierKicked))
    }

    /**
     * Mint a one-shot relay token with the page's JWT.
     *
     * 服务端 `POST /api/v1/remote/token`，body `{"role":"terminal"}`，返回
     * `{code:0,data:{token}}`（3.17.2 页面同款调用，见 3.17.2-relay-audit.md）。
     * 失败只记状态码，绝不记 JWT/token 内容。
     */
    private fun mintRelayToken(c: RelayCreds): String? {
        val jwt = c.jwt ?: return null
        val origin = try {
            val uri = java.net.URI(c.wsUrl)
            val scheme = if (uri.scheme == "wss") "https" else "http"
            "$scheme://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
        } catch (e: Exception) {
            Diagnostics.log("warn", "承载: 无法从 wsUrl 推导 origin")
            return null
        }
        val body = "{\"role\":\"terminal\"}".toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("$origin/api/v1/remote/token")
            .header("Authorization", "Bearer $jwt")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Diagnostics.log("warn", "承载: token 接口 HTTP ${response.code}")
                return null
            }
            val text = response.body?.string() ?: return null
            val data = JSONObject(text).optJSONObject("data") ?: return null
            return data.optString("token").takeIf { it.isNotBlank() }
        }
    }

    private val mintExecutor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "zcode-carrier-token").apply { isDaemon = true }
        }

    /** Authentication sends must not proceed after an early foreground signal. */
    private fun mayAuthenticate(token: Long): Boolean {
        if (!lifetime.isCurrent(token)) return false
        if (!foregroundRestricted || isForegroundNow?.invoke() != true) return true
        stop("前台已返回，取消尚未完成的原生配对")
        return false
    }

    private fun stopIfCurrent(token: Long, reason: String) {
        synchronized(lifecycleLock) {
            if (lifetime.isCurrent(token)) stop(reason)
        }
    }

    /**
     * 交还不变式（TODO §2.2 / §2.3）：**先停承载 → 确认 socket close → 才允许页面
     * 重载/重拨**。
     *
     * 单控制端互斥：relay 只服务"最近连上的那一个"。页面在原生 socket 还没真正关掉时
     * 重载，自己的新连接会被旧的原生连接顶掉——真机 2026-09-18 用 CDP 实测过那条路径
     * （页面订阅成功后随即又被 KICKED）。所以重载必须挂在"关闭确认"上，而不是一个固定
     * 延时（固定延时要么不够、要么白等）。
     *
     * `onClosed(true)` = 对端/本端都确认关闭；`false` = 超时放行（**不阻塞用户**：
     * 关不掉的是本机 socket，页面重拨仍然可能成功，总比永远不回前台好）。
     */
    fun stopAndAwaitClose(
        reason: String,
        timeoutMs: Long = CLOSE_CONFIRM_TIMEOUT_MS,
        onClosed: (Boolean) -> Unit,
    ) {
        val delivered = java.util.concurrent.atomic.AtomicBoolean(false)
        val deliver = { confirmed: Boolean ->
            if (delivered.compareAndSet(false, true)) {
                try {
                    onClosed(confirmed)
                } catch (e: Exception) {
                    Diagnostics.log("warn", "承载: 交还回调异常 ${e.message}")
                }
            }
        }
        // 两件事都要等：主 socket 的关闭确认 + 各工作区桥全部 dispose。
        // 只等主 socket 是审查 P1-3 指出的"改了一半"：BridgeManager.disposeEverything
        // 在另一个线程跑，若不等它，重载会在旧桥仍可能活着时发出（页面再被顶一次）。
        var watched: WebSocket? = null
        var hadBridges = false
        synchronized(lifecycleLock) {
            if (socket != null && lifetime.active) watched = socket
            hadBridges = bridgeManager != null
        }
        var remaining = 0
        if (watched != null) remaining += 1
        if (hadBridges) remaining += 1
        if (remaining == 0) {
            stop(reason)
            deliver(false)
            return
        }
        val pending = java.util.concurrent.atomic.AtomicInteger(remaining)
        val watchedSocket = watched
        val timeoutTask = object : java.util.TimerTask() {
            override fun run() {
                if (watchedSocket != null) closeWatchers.remove(watchedSocket)
                deliver(false)
            }
        }
        closeConfirmTimer.schedule(timeoutTask, timeoutMs)
        val oneDone = {
            if (pending.decrementAndGet() == 0) {
                timeoutTask.cancel()
                deliver(true)
            }
        }
        if (watchedSocket != null) {
            synchronized(lifecycleLock) { closeWatchers[watchedSocket] = { oneDone() } }
        }
        stop(reason, onBridgesDisposed = if (hadBridges) ({ oneDone() }) else null)
    }

    /** 关闭确认：listener 在**任何**早退判断之前调它，见 [stopAndAwaitClose]。 */
    private fun signalSocketClosed(webSocket: WebSocket) {
        val watcher = closeWatchers.remove(webSocket) ?: return
        watcher()
    }

    fun stop(reason: String, onBridgesDisposed: (() -> Unit)? = null) {
        synchronized(lifecycleLock) {
            if (!lifetime.active) return
            lifetime.end()
            stopping = true
            persistent = false
            carrierPaired = null
            carrierKicked = null
            phase = Phase.CLOSED
            durationTimer?.cancel()
            durationTimer = null
            bootstrapTimer?.cancel()
            bootstrapTimer = null
            cancelHandshakeTimeout()
            stopCoverageRefresh()
            heartbeatTimer?.cancel()
            heartbeatTimer = null
            reconnectTimer?.cancel()
            reconnectTimer = null
            val manager = bridgeManager
            bridgeManager = null
            val s = socket
            socket = null
            creds = null
            val lasted = (System.currentTimeMillis() - startedAtMs) / 1000
            Diagnostics.log(
                "warn",
                "承载: 关闭（$reason）存活=${lasted}s 心跳=$heartbeatCount ack=$ackCount",
            )
            try {
                // 交还要的是"对端确认释放控制端"：优雅 close 走关闭握手（onClosed 才会
                // 触发关闭确认），cancel() 只是本机放弃、证明不了任何事（审查 P1-3）。
                // 排队中的 auth proof 不再放行由 lifetime.end() + stopping 先封死；
                // 对端不应答时由 stopAndAwaitClose 的超时兜底。
                s?.close(1000, "handback")
            } catch (e: Exception) {
                Diagnostics.log("warn", "承载: close 异常 ${e.message}")
            }
            if (manager != null) {
                Thread {
                    try {
                        manager.disposeEverything(reason)
                    } catch (e: Exception) {
                        Diagnostics.log("debug", "承载: 后台清理桥失败 ${e.message}")
                    } finally {
                        onBridgesDisposed?.invoke()
                    }
                }.start()
            }
        }
    }

    /**
     * 配对之后的**第一帧业务数据**：`bootstrap-request`，然后才开桥。
     *
     * 这是 2026-09-16 01:12 用 CDP 抓页面自己的那一轮接入录下来的逐帧顺序
     * （`tools/cdp-capture.mjs`）：
     * ```
     * auth_init → auth_challenge → auth_response → auth_ack(pair_status=matched)
     * → {zcode_type:'bootstrap-request'}        ← 响应约 24 KB
     * → {zcode_type:'workspace-bridge-open'}    ← 响应约 0.5 KB
     * → rpc-frame / rpc-frame-ack …
     * ```
     * **我们此前整条链路都跳过了 bootstrap**（原生 `BridgeManager` 与注入层
     * `zcode-protocol.js` 都直接从 `workspace-list-request` 开始）。后果不是"少拿一份数据"，
     * 而是桌面端**不认这次接入**：干净 A/B 显示原生裸配对之后 4.3 秒桌面端就
     * `[task-realtime] unregistered host` + `host process (local-1) exited with code 1`
     * （docs/16 §8）——bootstrap 很可能就是"把这次终端登记成移动连接/窗口归属"的那一步。
     *
     * 顺序照页面来：先发 bootstrap，给它 1.2s 收响应（真机实测 300ms 内就回，24KB），
     * 再开桥覆盖。
     */
    private fun sendBootstrapThenCoverage(token: Long, webSocket: WebSocket) {
        if (!isCurrent(token, webSocket) || phase != Phase.PAIRED || bridgeManager != null) return
        // ① 页面配对后**立刻**发的两帧（2026-09-16 01:12 抓包逐帧录下来的顺序）：
        //    mobile-diagnostic(state-transition: paired) 与 mobile-view-state-update。
        //    后者带着 `viewState.activeWorkspaceKey/activeTaskId`——**这很可能就是桌面端
        //    给新终端绑定窗口的依据**：真机 A/B 显示，裸配对（不发这两帧）之后 4.3 秒
        //    桌面端就 `unregistered host`（docs/16 §8/§10），而页面自己的重连从不触发它。
        val now = System.currentTimeMillis()
        sendBusinessPayload(
            JSONObject()
                .put("zcode_type", "mobile-diagnostic")
                .put("event", "state-transition")
                .put("timestamp", now)
                .put("state", "paired")
                .put("previousState", "authenticating")
                .put("visibilityState", "hidden")
                .put("online", true),
            token, webSocket,
        )
        if (onlyWorkspace.isNotEmpty()) {
            val viewState = JSONObject()
                .put("activeWorkspaceKey", onlyWorkspace)
                .put("updatedAt", now)
            if (onlyTaskId.isNotEmpty()) {
                viewState.put("activeTaskId", onlyTaskId)
            }
            sendBusinessPayload(
                JSONObject()
                    .put("zcode_type", "mobile-view-state-update")
                    .put("viewState", viewState)
                    .put(
                        "deviceInfo",
                        JSONObject()
                            .put("platform", "web")
                            .put("version", "web")
                            .put("name", "mobile-browser")
                            .put("language", "zh-CN")
                            .put("timeZone", java.util.TimeZone.getDefault().id),
                    ),
                token, webSocket,
            )
            Diagnostics.log(
                "info",
                "承载: 已发 mobile-view-state-update（工作区 $onlyWorkspace" +
                    (if (onlyTaskId.isEmpty()) "" else " · 任务 $onlyTaskId") + "）",
            )
        }
        // ② 然后是 bootstrap-request（页面顺序：diagnostic → bootstrap → bridge-open）。
        sendBusinessPayload(
            JSONObject()
                .put("zcode_type", "bootstrap-request")
                .put("requestId", "zcshell-bootstrap-" + java.util.UUID.randomUUID()),
            token, webSocket,
        )
        Diagnostics.log("warn", "承载: 已发 bootstrap-request（补上页面配对后的第一步，1.2s 后开桥）")
        bootstrapTimer?.cancel()
        val timer = java.util.Timer(true)
        bootstrapTimer = timer
        timer.schedule(
            object : java.util.TimerTask() {
                override fun run() {
                    startCoverage(token, webSocket)
                }
            },
            1200L,
        )
    }

    private fun startCoverage(token: Long, webSocket: WebSocket) {
        val manager = synchronized(lifecycleLock) {
            if (!isCurrent(token, webSocket) || phase != Phase.PAIRED || bridgeManager != null) return
            if (!mayAuthenticate(token)) return
            bootstrapTimer?.cancel()
            bootstrapTimer = null
            val manager = BridgeManager(
                // Bind every bridge, including its eventual cleanup, to this socket.
                sendPayloadOut = { payload -> sendBusinessPayload(payload, token, webSocket) },
                onSessionsUpdate = { update ->
                    try {
                        synchronized(lifecycleLock) {
                            if (isCurrent(token, webSocket)) sessionsSink?.invoke(update)
                        }
                    } catch (e: Exception) {
                        Diagnostics.log("warn", "承载: sessions 回调失败 ${e.message}")
                    }
                },
                onLogLine = { line -> Diagnostics.log("debug", line) },
                progressSink = { key, sessionId, text ->
                    try {
                        synchronized(lifecycleLock) {
                            if (isCurrent(token, webSocket)) progressSink?.invoke(key, sessionId, text)
                        }
                    } catch (e: Exception) {
                        Diagnostics.log("warn", "承载: 活进展回调失败 ${e.message}")
                    }
                },
                runningSessions = { key ->
                    if (isCurrent(token, webSocket)) runningSessionsProvider?.invoke(key).orEmpty() else emptyList()
                },
                turnStateSink = { key, sessionId, running ->
                    try {
                        synchronized(lifecycleLock) {
                            if (isCurrent(token, webSocket)) turnStateSink?.invoke(key, sessionId, running)
                        }
                    } catch (e: Exception) {
                        Diagnostics.log("warn", "承载: 会话运行态回调失败 ${e.message}")
                    }
                },
                onBridgeResult = { key, ok ->
                    synchronized(lifecycleLock) {
                        if (isCurrent(token, webSocket)) recordBridgeResult(key, ok)
                    }
                },
                maxWorkspaces = DEFAULT_MAX_COVERAGE,
            )
            bridgeManager = manager
            manager.rotateEnabled = !parallelCoverage
            manager
        }
        Thread {
            try {
                if (!isCurrent(token, webSocket) || bridgeManager !== manager) return@Thread
                val workspaces = manager.listWorkspacesBlocking()
                if (!isCurrent(token, webSocket) || bridgeManager !== manager) return@Thread
                if (workspaces.isEmpty()) {
                    Diagnostics.log("warn", "承载: 接管模式拿到空工作区列表")
                    return@Thread
                }
                // **有在跑任务的工作区排最前**：真机 2026-09-15 定案——桌面端对
                // "页面正在看的那个窗口工作区"只在我们刚把页面顶掉后的很短一段时间里
                // 肯开桥（11:52 那次 6 s 内成功；等循环走到它时已过 16 s，就变成
                // `desktop-disconnected: 未找到桌面窗口 host process`）。
                // 所以别按列表顺序慢慢开，先把要跟踪的那个抢下来。
                val ordered = workspaces.sortedByDescending { workspace ->
                    val key = workspaceKeyOf(workspace)
                    if (key == null) 0 else runningSessionsProvider?.invoke(key).orEmpty().size
                }
                // 覆盖目标三档（2026-09-16 第二版：多工作区）：
                //   ① **显式清单**（多工作区开关打开 / `coverage_ws:` 诊断）——只用它，按给定顺序；
                //      `coverage_ws:` 诊断清单**不受失败冷却影响**（实验要能看到失败），
                //      生产路径照旧受冷却（见下面 `live` 的取法）；
                //   ② **页面工作区**（默认）——与页面自己的行为一致，是经过真机验收的生产路径；
                //   ③ 都没有 ——按"在跑任务数"排序取前 [DEFAULT_MAX_COVERAGE] 个。
                //      **不再全量**：扫全部工作区曾被认为是桌面端拆 host 的诱因，虽然后来查明真凶是
                //      controller 流那次 `rpc:listen`（docs/16 §8/§10），但没有理由为此开满 12 座桥。
                // **发现链要在这一刻读**（不是承载启动前）：数据来自配对之后的
                // `bootstrap-response.result.tasks`，承载启动时它还没到（154 实测踩到：
                // 清单在 `maybeStartNativeCarrier` 里算，日志显示"发现链 0 个"）。
                // 只在多工作区开关打开时并入（开关关＝只开页面工作区，语义不变）；
                // `coverage_ws:` 诊断清单不并入（E1 要的是"只用它"）。
                val discovered =
                    if (coverageIsDiagnostic || coverageWorkspaces.isEmpty()) {
                        emptyList()
                    } else {
                        discoveredCoverage()
                    }
                if (discovered.isNotEmpty()) {
                    Diagnostics.log(
                        "info",
                        "承载: 发现链给出 ${discovered.size} 个有活动任务的工作区：${discovered.joinToString()}",
                    )
                }
                // **截断要在进"覆盖清单"这一档之前做**（2026-09-17，鸿蒙侧审计 4 号）：
                // 清单 = 显式清单 + 发现链，条数可以超过 [DEFAULT_MAX_COVERAGE]，而
                // `beginCoverage` 只会开前 maxWorkspaces 座——不截断就会打出"多工作区覆盖 7 座"
                // 而实际只开 5 座的假账（日志与现场对不上，是最贵的一类误导）。
                // 取法：**显式清单/发现链优先**（保持它们给出的顺序），条数不够时才轮到
                // ③ 的"其余按在跑任务数排序"（`ordered` 已经按在跑任务数降序）。
                val wanted = (coverageWorkspaces + discovered).distinct()
                val explicit = wanted.take(DEFAULT_MAX_COVERAGE)
                if (explicit.size < wanted.size) {
                    Diagnostics.log(
                        "info",
                        "承载: 覆盖清单 ${wanted.size} 座超过上限 $DEFAULT_MAX_COVERAGE" +
                            "（显式清单/发现链优先、其余按在跑任务数排序），取前 $DEFAULT_MAX_COVERAGE：" +
                            explicit.joinToString(),
                    )
                }
                val only = onlyWorkspace
                val targets = when {
                    explicit.isNotEmpty() -> {
                        val wantedKeys = explicit.toSet()
                        val hit = ordered.filter {
                            val k = workspaceKeyOf(it)
                            k != null && k in wantedKeys
                        }
                        // 冷却的适用范围**按来源分**（2026-09-17 与注释对齐）：
                        //   * `coverage_ws:` 诊断清单 → **豁免**：E1 实验的目的就是"看到失败"，
                        //     上一轮失败把它冷却掉，实验就再也看不到同一座桥的失败现场了；
                        //   * 生产路径（多工作区开关 + 发现链）→ **照旧受冷却**：同一座桥连续
                        //     失败还每轮去撞，就是用户看到的"正在尝试重连"churn。
                        val cooledCount = hit.count { cooledDown(workspaceKeyOf(it)) }
                        val live = if (coverageIsDiagnostic) {
                            if (cooledCount > 0) {
                                Diagnostics.log(
                                    "info",
                                    "承载: 诊断覆盖清单豁免失败冷却——其中 $cooledCount 座在冷却中，本轮照开",
                                )
                            }
                            hit
                        } else {
                            if (cooledCount > 0) {
                                Diagnostics.log(
                                    "info",
                                    "承载: 覆盖清单里 $cooledCount 座在冷却中，本轮跳过",
                                )
                            }
                            hit.filterNot { cooledDown(workspaceKeyOf(it)) }
                        }
                        if (live.isEmpty()) {
                            Diagnostics.log(
                                "warn",
                                "承载: 覆盖清单（${explicit.joinToString()}）在桌面端列表里没有可用项，本轮不开桥",
                            )
                            emptyList()
                        } else {
                            Diagnostics.log(
                                "info",
                                "承载: 多工作区覆盖 ${live.size} 座（上限 $DEFAULT_MAX_COVERAGE）：" +
                                    live.mapNotNull { workspaceKeyOf(it) }.joinToString(),
                            )
                            live
                        }
                    }
                    only.isNotEmpty() -> {
                        val hit = ordered.filter { workspaceKeyOf(it) == only }
                        if (hit.isNotEmpty()) {
                            Diagnostics.log("info", "承载: 只开页面当前工作区的桥：$only")
                            hit
                        } else {
                            Diagnostics.log(
                                "warn",
                                "承载: 页面工作区 $only 不在桌面端列表里（${ordered.size} 个），" +
                                    "退回按在跑任务排序的前 $DEFAULT_MAX_COVERAGE 个",
                            )
                            ordered.take(DEFAULT_MAX_COVERAGE)
                        }
                    }
                    else -> {
                        Diagnostics.log(
                            "warn",
                            "承载: 注入层还没上报页面工作区，按在跑任务排序开前 $DEFAULT_MAX_COVERAGE 个",
                        )
                        ordered.take(DEFAULT_MAX_COVERAGE)
                    }
                }
                if (targets.isEmpty()) return@Thread
                synchronized(lifecycleLock) {
                    if (!isCurrent(token, webSocket) || bridgeManager !== manager) return@Thread
                    manager.beginCoverage(targets)
                    startCoverageRefresh(manager, token, webSocket)
                }
            } catch (e: Exception) {
                Diagnostics.log("warn", "承载: 覆盖启动失败 ${e.message}")
            }
        }.start()
    }

    /**
     * 覆盖刷新（2026-09-18 加）：承载期间每 [COVERAGE_REFRESH_MS] 重问一次工作区列表，
     * 把**新出现的"有活动任务的工作区"**补进覆盖。
     *
     * 为什么需要它：页面被顶掉之后，第三条源（页面自己的 controller 流）就停了
     * （`docs/18` §3.11 B），而发现链只在**配对那一刻**跑一次（`bootstrap-response` +
     * `workspace-list-response`）。于是承载期间你在**没被覆盖的工作区**里新起任务，
     * 壳是不知道的——卡片不会出现，直到下一次接管重来一遍。真机把这个边界诊断出来之后
     * 用户拍板补上（`docs/18` §3.11 F 第 1 条）。
     *
     * 三条纪律：① 只**补**不拆（覆盖一旦建立就交给轮换去管），② 受**失败冷却**约束
     * （同一座桥连续被拒就别每 60s 再撞一次），③ 受**总上限**约束
     * （[BridgeManager.remainingCoverageSlots]，别让定时刷新把桥数顶过 5）。
     */
    private fun startCoverageRefresh(manager: BridgeManager, token: Long, webSocket: WebSocket) {
        stopCoverageRefresh()
        val timer = java.util.Timer("carrier-coverage-refresh", true)
        coverageTimer = timer
        timer.schedule(
            object : java.util.TimerTask() {
                override fun run() {
                    if (!isCurrent(token, webSocket) || bridgeManager !== manager) return
                    try {
                        val workspaces = manager.listWorkspacesBlocking()
                        if (!isCurrent(token, webSocket) || bridgeManager !== manager) return
                        val covered = manager.coveredKeys()
                        val discovered = manager.discoveredActiveWorkspaces
                        val missing = discovered
                            .filter { it.isNotEmpty() && it !in covered && !cooledDown(it) }
                        // 每拍一行 debug：这条机制在"没有缺口"时是完全静默的，
                        // 而"它到底有没有在跑"必须能从日志里回答（60s 一行，不吵）。
                        Diagnostics.log(
                            "debug",
                            "承载: 覆盖刷新：已覆盖 ${covered.size} 座 · 发现链 ${discovered.size} 座 · " +
                                "待补 ${missing.size} 座",
                        )
                        if (missing.isEmpty()) return
                        val slots = manager.remainingCoverageSlots()
                        if (slots <= 0) {
                            Diagnostics.log(
                                "debug",
                                "承载: 覆盖刷新——${missing.size} 座有活动任务的工作区没被覆盖，" +
                                    "但桥数已到上限 $DEFAULT_MAX_COVERAGE",
                            )
                            return
                        }
                        val byKey = HashMap<String, JSONObject>()
                        for (workspace in workspaces) {
                            val key = workspaceKeyOf(workspace) ?: continue
                            byKey[key] = workspace
                        }
                        val targets = missing.mapNotNull { byKey[it] }.take(slots)
                        if (targets.isEmpty()) {
                            Diagnostics.log(
                                "info",
                                "承载: 覆盖刷新——发现 ${missing.size} 座有活动任务的工作区" +
                                    "（${missing.joinToString()}），但桌面端列表里没有可用项",
                            )
                            return
                        }
                        Diagnostics.log(
                            "info",
                            "承载: 覆盖刷新——新发现 ${targets.size} 座有活动任务的工作区，" +
                                "补开桥（已覆盖 ${covered.size} 座 · 上限 $DEFAULT_MAX_COVERAGE）：" +
                                targets.mapNotNull { workspaceKeyOf(it) }.joinToString(),
                        )
                        synchronized(lifecycleLock) {
                            if (!isCurrent(token, webSocket) || bridgeManager !== manager) return
                            manager.beginCoverage(targets)
                        }
                    } catch (e: Exception) {
                        Diagnostics.log("debug", "承载: 覆盖刷新失败 ${e.message}")
                    }
                }
            },
            COVERAGE_REFRESH_MS,
            COVERAGE_REFRESH_MS,
        )
    }

    private fun stopCoverageRefresh() {
        coverageTimer?.cancel()
        coverageTimer = null
    }

    /** 该工作区是否在"连续开桥失败"的冷却里（冷却到期自动解除）。 */
    private fun cooledDown(key: String?): Boolean {
        if (key == null) return false
        val until = bridgeCooldownUntil[key] ?: return false
        if (System.currentTimeMillis() >= until) {
            bridgeCooldownUntil.remove(key)
            bridgeFailCount.remove(key)
            return false
        }
        return true
    }

    /** 桥开启结果：成功清账；连续失败到阈值则冷却，避免每轮重连都撞同一座必失败的桥。 */
    private fun recordBridgeResult(key: String, ok: Boolean) {
        if (ok) {
            bridgeFailCount.remove(key)
            bridgeCooldownUntil.remove(key)
            return
        }
        val n = (bridgeFailCount[key] ?: 0) + 1
        bridgeFailCount[key] = n
        if (n >= BRIDGE_FAIL_COOLDOWN_AFTER) {
            bridgeCooldownUntil[key] = System.currentTimeMillis() + BRIDGE_FAIL_COOLDOWN_MS
            Diagnostics.log(
                "warn",
                "承载: 工作区 $key 连续 $n 次开桥失败，冷却 ${BRIDGE_FAIL_COOLDOWN_MS / 60_000} 分钟",
            )
        }
    }

    private fun scheduleReconnect(token: Long) {
        if (!lifetime.isCurrent(token) || !persistent || stopping) return
        bootstrapTimer?.cancel()
        bootstrapTimer = null
        stopCoverageRefresh()
        reconnectTimer?.cancel()
        reconnectAttempt += 1
        val delayMs = 10_000L
        if (reconnectAttempt % 6 == 1) {
            Diagnostics.log("warn", "承载: 断线，${delayMs / 1000}s 后重连（第 $reconnectAttempt 次）")
        }
        val timer = java.util.Timer(true)
        reconnectTimer = timer
        timer.schedule(
            object : java.util.TimerTask() {
                override fun run() {
                    val manager = synchronized(lifecycleLock) {
                        if (!lifetime.isCurrent(token) || !persistent || reconnectTimer !== timer) return
                        reconnectTimer = null
                        timer.cancel()
                        if (!mayAuthenticate(token)) return
                        val old = bridgeManager
                        bridgeManager = null
                        old
                    }
                    try {
                        manager?.disposeEverything("重连重建")
                    } catch (e: Exception) {
                        Diagnostics.log("debug", "承载: 重连清理桥失败 ${e.message}")
                    }
                    // Cleanup can block for seconds; stop/new start may have happened meanwhile.
                    synchronized(lifecycleLock) {
                        if (lifetime.isCurrent(token) && persistent) connectNow(token)
                    }
                }
            },
            delayMs,
        )
    }

    private fun createListener(token: Long, onPaired: (() -> Unit)?, onKicked: (() -> Unit)?) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            synchronized(lifecycleLock) {
                if (!isCurrent(token, webSocket)) {
                    webSocket.cancel()
                    return
                }
                if (!mayAuthenticate(token)) return
                phase = Phase.AUTHENTICATING
                val c = creds ?: return
                webSocket.send(
                    authInitFrame(c, System.currentTimeMillis(), DesktopVersion.current()).toString(),
                )
                scheduleHandshakeTimeout(token, webSocket)
                Diagnostics.log(
                    "warn",
                    "承载: 已连接，auth_init 已发（${if (c.tokenMode) "token 模式" else "legacy HMAC"}，等待配对）",
                )
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent(token, webSocket)) return
            val frame = try {
                JSONObject(text)
            } catch (e: Exception) {
                return
            }
            when (frame.optString("type")) {
                "auth_challenge" -> {
                    synchronized(lifecycleLock) {
                        if (!isCurrent(token, webSocket) || !mayAuthenticate(token)) return
                        val c = creds ?: return
                        // token 模式（3.16.1+）不需要 HMAC proof，服务端也不该再发挑战；
                        // 若仍收到只能忽略——拿同一个 token 重试也不会成功（照 Rust 口径）。
                        if (c.tokenMode || c.passHash.isEmpty()) {
                            Diagnostics.log(
                                "warn",
                                "承载: token 模式下收到 auth_challenge，忽略（无 passHash 可算 proof）",
                            )
                            return
                        }
                        val nonce = frame.optString("nonce")
                        val proof = relayProof(c.passHash, nonce, "terminal", c.deviceSid)
                        val response = JSONObject().apply {
                            put("type", "auth_response")
                            put("device_sid", c.deviceSid)
                            put("proof", proof)
                            put("client_ts", System.currentTimeMillis())
                        }
                        webSocket.send(response.toString())
                        Diagnostics.log("warn", "承载: 质询已应答（HMAC proof），等待配对判定")
                    }
                }
                "auth_ack", "pair_status_ack" -> {
                    synchronized(lifecycleLock) {
                        if (!isCurrent(token, webSocket)) return
                        // 3.16.1 起 ack 带 device_sid，必须与本端一致才认（照 Rust
                        // `relay_client.rs:700-716` 的门控）。缺字段的旧服务端放行。
                        val ackSid = frame.optString("device_sid")
                        val expectedSid = creds?.deviceSid
                        if (ackSid.isNotEmpty() && expectedSid != null && ackSid != expectedSid) {
                            Diagnostics.log("warn", "承载: 忽略 device_sid 不匹配的配对应答")
                            return
                        }
                        val status = frame.optString("pair_status", "unknown")
                        ackCount += 1
                        if (phase != Phase.PAIRED && status == "matched") {
                            // A proof already in flight can be accepted just as the app returns.
                            if (foregroundRestricted && isForegroundNow?.invoke() == true) {
                                Diagnostics.log(
                                    "warn",
                                    "承载: 配对成功但应用已回前台——立即交还并重载页面" +
                                        "（接管判定做出后用户才回来的竞态）",
                                )
                                stop("配对成功但已回前台——立即交还")
                                foregroundConflict?.invoke()
                                return
                            }
                            phase = Phase.PAIRED
                            cancelHandshakeTimeout()
                            onPaired?.invoke()
                            if (persistent) {
                                Diagnostics.log(
                                    "warn",
                                    "承载: ★接管配对成功（matched）——页面连接已被顶掉，开始桥覆盖",
                                )
                            } else {
                                Diagnostics.log(
                                    "warn",
                                    "承载: ★配对成功（matched）——实验模式，同时开桥覆盖（验证 M3b/c 解码）",
                                )
                            }
                            sendBootstrapThenCoverage(token, webSocket)
                        }
                    }
                }
                "data" -> {
                    // Keep potentially large bridge decoding outside the lifecycle lock.
                    val payload = frame.optJSONObject("payload") ?: return
                    val kind = payload.optString("zcode_type")
                    val manager = synchronized(lifecycleLock) {
                        if (!isCurrent(token, webSocket)) return
                        if (kind.startsWith("bootstrap")) {
                            // Bootstrap can contain credentials; never log the raw response.
                            Diagnostics.log("info", "承载: 收到 $kind（${text.length} 字符）")
                            if (kind == "bootstrap-response") {
                                val result = payload.optJSONObject("result")
                                Diagnostics.log("info", RelayTaskDigest.describe(result, "bootstrap"))
                                Diagnostics.log("info", RelayTaskDigest.shapeOf(result))
                                bootstrapActiveWorkspaces = RelayTaskDigest.activeWorkspaces(result)
                            }
                        }
                        bridgeManager
                    }
                    manager?.acceptRelayPayload(payload)
                }
                "error" -> {
                    synchronized(lifecycleLock) {
                        if (!isCurrent(token, webSocket)) return
                        if (frame.optString("code") == "KICKED") onKicked?.invoke()
                        Diagnostics.log(
                            "warn",
                            "承载: 服务端 error code=${frame.optString("code")} " +
                                "msg=${frame.optString("message").take(80)}",
                        )
                    }
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
            // 交还路径先拿"关闭确认"：下面的 isCurrent 检查在 stop() 之后必然为假。
            signalSocketClosed(webSocket)
            synchronized(lifecycleLock) {
                if (!isCurrent(token, webSocket)) return
                phase = Phase.CLOSED
                socket = null
                cancelHandshakeTimeout()
                Diagnostics.log("warn", "承载: 连接失败 ${t.javaClass.simpleName}: ${t.message?.take(120)}")
                scheduleReconnect(token)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            signalSocketClosed(webSocket)
            synchronized(lifecycleLock) {
                if (!isCurrent(token, webSocket)) return
                phase = Phase.CLOSED
                socket = null
                cancelHandshakeTimeout()
                Diagnostics.log("warn", "承载: 对端关闭 code=$code reason=${reason.take(60)}")
                scheduleReconnect(token)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (!isCurrent(token, webSocket)) return
            // relay 终端协议是 JSON 文本帧；二进制帧出现即记录不处理。
            Diagnostics.log("debug", "承载: 收到二进制帧 ${bytes.size}B（忽略）")
        }
    }

    private fun sendBusinessPayload(payload: JSONObject, token: Long, webSocket: WebSocket) {
        synchronized(lifecycleLock) {
            if (!isCurrent(token, webSocket) || phase != Phase.PAIRED) return
            val envelope = dataEnvelope(payload, System.currentTimeMillis())
            try {
                webSocket.send(envelope.toString())
            } catch (e: Exception) {
                Diagnostics.log("warn", "承载: 业务帧发送失败 ${e.message}")
            }
        }
    }

    private fun sendHeartbeat(token: Long) {
        synchronized(lifecycleLock) {
            if (!lifetime.isCurrent(token)) return
            val s = socket ?: return
            creds ?: return
            if (phase != Phase.PAIRED && phase != Phase.AUTHENTICATING) return
            heartbeatCount += 1
            val q = pairStatusQueryFrame(System.currentTimeMillis())
            try {
                s.send(q.toString())
            } catch (e: Exception) {
                Diagnostics.log("warn", "承载: 心跳发送失败 ${e.message}")
            }
        }
    }
}
