package app.zemote.log

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 进程内统一日志：内存环形缓冲 + 文件轮转 + logcat 镜像 + 崩溃兜底。
 *
 * ## 为什么需要它
 *
 * [app.zemote.engine.ZemoteEngine] 自带的 `logs` StateFlow 只活在内存里，进程一死全丢，
 * 而且 UI / ViewModel / FFI 调用点没有统一出口，「对话页一滑动就闪退/重载」只能靠现场复现。
 * 这里把宿主侧与协议层的关键行为收进同一条时间线，闪退后还能从上次进程退出原因里取证。
 *
 * ## 落盘
 *
 * `filesDir/logs/zemote.log`，超过 2 MiB 轮转为 `zemote.1.log` … `zemote.3.log`。
 * 崩溃另存 `crash.log`，上次进程退出原因另存 `crash-history.log`。写入在单独 daemon 线程
 * 批量 flush，主线程只做一次 `offer`；队列满时丢弃一条并计数（随后写一行 dropped 汇总），
 * 绝不让日志把主线程拖死。文件不可写时自动降级为「只写 logcat」，不影响业务流程。
 *
 * ## 脱敏
 *
 * 所有入队文本都过 [redact]：auth token、配对 URL 的 `pass`/`hash`、Cookie、
 * `Authorization`、`apiKey` 一类的值替换成 `***`。协议帧可记录但单行最多 16 KiB。
 * 用户对话内容（工作区路径、消息摘要）属于**本机调试资产**，不上传、不对外发送。
 *
 * ## 崩溃
 *
 * - Kotlin/Java 未捕获异常：同步把栈 + 最近 200 行写入 `crash.log` 与当前日志，
 *   然后交回原 handler（进程行为不变）；
 * - 上次进程退出原因（API 30+）：启动时读 [ApplicationExitInfo] 的 CRASH / ANR /
 *   NATIVE_CRASH / **LOW_MEMORY** / INITIALIZATION_FAILURE，写进 `crash-history.log`
 *   与当前日志（reason/description/timestamp/importance 全写，trace 截断 64 KiB）；
 *   处理过的最大时间戳记在 `SharedPreferences("zlog")`，避免每次启动重复写。
 *   滑动闪退常常是被 LMK 杀掉，没有 Java 栈，只有这里能拿到证据。
 */
object ZLog {

    private const val LOGCAT_TAG = "ZemoteTrace"
    private const val MAX_LINE_CHARS = 16_384
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    private const val MAX_ARCHIVES = 3
    private const val RING_LIMIT = 1_000
    private const val QUEUE_LIMIT = 20_000
    private const val SYNC_RECENT_LINES = 200
    private const val TAIL_WINDOW_BYTES = 256 * 1024
    private const val BUNDLE_LIMIT_BYTES = 8L * 1024 * 1024
    private const val BUNDLE_PER_FILE_BYTES = 3L * 1024 * 1024
    private const val PREF_NAME = "zlog"
    private const val PREF_LAST_EXIT = "lastExitTimestamp"

    /** 敏感键（JSON `"key": value`、`key=value`、HTTP 头）的值替换为 `***`。 */
    private val SECRET_PATTERN = Regex(
        "(?i)(\"?(?:access_token|refresh_token|auth_token|terminal_?token|token|jwt|" +
            "authorization|cookie|passhash|pass_hash|pass|password|secret|api[_-]?key|hash)\"?" +
            "\\s*[:=]\\s*\"?)([^\"\\s,}&]+)",
    )

    private val queue = LinkedBlockingQueue<String>(QUEUE_LIMIT)
    private val ring = ArrayDeque<String>(RING_LIMIT)
    private val ringLock = Any()
    private val fileLock = Any()
    private val timeLock = Any()
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val stampFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val seq = AtomicLong(0)

    @Volatile
    private var logDir: File? = null

    @Volatile
    private var writer: BufferedWriter? = null

    @Volatile
    private var currentBytes = 0L

    @Volatile
    private var fileLoggingEnabled = true

    @Volatile
    private var dropped = 0

    @Volatile
    private var installed = false

    // ------------------------------------------------------------------
    // 初始化
    // ------------------------------------------------------------------

    /**
     * 安装日志底座。幂等；应在 Activity / 前台服务 / Application 最早处调用。
     *
     * 不阻塞调用线程：崩溃历史读取与崩溃栈落盘都在后台线程完成。
     */
    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            installed = true
            val app = context.applicationContext
            val dir = File(app.filesDir, "logs").apply { runCatching { mkdirs() } }
            logDir = dir

            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                writeFatal(thread, error)
                previous?.uncaughtException(thread, error)
            }

            Thread({ writerLoop() }, "ZLogWriter").apply {
                isDaemon = true
                start()
            }
            Thread({ readPreviousExitReasons(app) }, "ZLogExitHistory").apply {
                isDaemon = true
                start()
            }

            i(
                "LIFECYCLE",
                "install",
                "app=${app.packageName} version=${runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()} " +
                    "sdk=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER}/${Build.MODEL} dir=${dir.absolutePath}",
            )
            memory("install")
        }
    }

    // ------------------------------------------------------------------
    // 对外 API
    // ------------------------------------------------------------------

    fun v(category: String, tag: String, message: String) =
        log('V', Log.VERBOSE, category, tag, message, null)

    fun d(category: String, tag: String, message: String) =
        log('D', Log.DEBUG, category, tag, message, null)

    fun i(category: String, tag: String, message: String, error: Throwable? = null) =
        log('I', Log.INFO, category, tag, message, error)

    fun w(category: String, tag: String, message: String, error: Throwable? = null) =
        log('W', Log.WARN, category, tag, message, error)

    fun e(category: String, tag: String, message: String, error: Throwable? = null) =
        log('E', Log.ERROR, category, tag, message, error)

    /** UI / Compose 行为（滚动、点击、生命周期、空态切换）。 */
    fun ui(tag: String, message: String, error: Throwable? = null) =
        if (error == null) i("UI", tag, message) else w("UI", tag, message, error)

    /** ViewModel / 状态机行为（订阅、快照、分页、状态迁移）。 */
    fun vm(tag: String, message: String, error: Throwable? = null) =
        if (error == null) i("VM", tag, message) else w("VM", tag, message, error)

    /** 引擎门面 / relay / FFI 信封。 */
    fun engine(message: String) = i("ENGINE", "engine", message)

    /** 对话层 RPC（op 名、参数摘要、耗时、错误）。 */
    fun ffi(tag: String, message: String, error: Throwable? = null) =
        if (error == null) i("FFI", tag, message) else e("FFI", tag, message, error)

    /** 协议帧 / 对话事件。[mirrorLogcat] 置 false 时只落文件，避免大帧在主线程刷 logcat。 */
    fun proto(tag: String, message: String, mirrorLogcat: Boolean = true) =
        log('I', Log.INFO, "PROTO", tag, message, null, mirrorLogcat)

    /** 网络与 relay。 */
    fun net(tag: String, message: String, error: Throwable? = null) =
        if (error == null) i("NET", tag, message) else w("NET", tag, message, error)

    /** 崩溃路径。 */
    fun crash(message: String) = e("CRASH", "fatal", message)

    /**
     * 轻量内存快照：堆上限 / 已用 / 空闲（MB）+ 调用方给的行数。
     *
     * 用来抓「一滑动就闪退」的 OOM 前兆：闪退往往没有 Java 栈（被 lmkd 杀），
     * 只有退出原因 `REASON_LOW_MEMORY`；滑动前后的这几行是判断行窗口是否失控的第一手证据。
     * 调用方传当前 `rows` 数即可（Timeline/ViewModel 本轮不改，先在 snapshot / loadOlder 前后手工调）。
     */
    fun memory(tag: String, rows: Int? = null) {
        i("MEM", tag, heapSummary() + (rows?.let { " rows=$it" } ?: ""))
    }

    private fun heapSummary(): String {
        val runtime = Runtime.getRuntime()
        val mb = 1024L * 1024L
        val maxMb = runtime.maxMemory() / mb
        val totalMb = runtime.totalMemory() / mb
        val freeMb = runtime.freeMemory() / mb
        val usedMb = totalMb - freeMb
        val percent = if (maxMb > 0) usedMb * 100 / maxMb else 0
        return "heap used=${usedMb}MB/${maxMb}MB (${percent}%) total=${totalMb}MB free=${freeMb}MB"
    }

    /** 单调递增的调用序号，用于把「谁先谁后」写进同一条时间线。 */
    fun nextSeq(): Long = seq.incrementAndGet()

    /**
     * 脱敏：token / pass / hash / Cookie / Authorization 的值替换为 `***`。
     *
     * 只做「键值对」这种能确定的形态，不猜自由文本，避免把正常内容误删。
     */
    fun redact(text: String): String =
        if (text.isEmpty()) text else SECRET_PATTERN.replace(text) { m -> m.groupValues[1] + "***" }

    /** 日志目录；未 [install] 时为 `null`。 */
    fun logDirectory(): File? = logDir

    /** 日志目录（最新在前）。 */
    fun files(): List<File> {
        val dir = logDir ?: return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    /** 内存环形缓冲快照；[maxLines] <= 0 表示全部。 */
    fun snapshot(maxLines: Int = 0): String = synchronized(ringLock) {
        val lines = if (maxLines in 1 until ring.size) ring.toList().takeLast(maxLines) else ring.toList()
        lines.joinToString("\n")
    }

    /** 读某个日志文件末尾 [maxLines] 行（从文件尾部回读，不整文件加载）。 */
    fun readTail(file: File, maxLines: Int = 400): String {
        if (maxLines <= 0 || !file.isFile || file.length() == 0L) return ""
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                val window = minOf(length, TAIL_WINDOW_BYTES.toLong()).toInt()
                val bytes = ByteArray(window)
                raf.seek(length - window)
                raf.readFully(bytes)
                // 窗口从字节中间切进来时，第一行可能是半个 UTF-8 字符，takeLast 前丢掉。
                String(bytes, Charsets.UTF_8)
                    .lineSequence()
                    .drop(if (length > window) 1 else 0)
                    .filter { it.isNotBlank() }
                    .toList()
                    .takeLast(maxLines)
                    .joinToString("\n")
            }
        }.getOrDefault("")
    }

    /** 当前正在写的文件末尾 [maxLines] 行；文件为空时回退到内存环形缓冲。 */
    fun tail(maxLines: Int = 400): String {
        val fromFile = logDir?.let { readTail(File(it, "zemote.log"), maxLines) }.orEmpty()
        return fromFile.ifBlank { snapshot(maxLines) }
    }

    /**
     * 把全部取证材料拼成一份可直接分享的纯文本。
     *
     * 顺序：上次退出原因 → 崩溃栈 → 归档 → 当前日志 → 尚未落盘的环形缓冲。
     * 总大小有上限，超长文件从尾部截取（最近的更值钱）。
     */
    fun bundle(): String {
        val dir = logDir ?: return snapshot()
        val out = StringBuilder(64 * 1024)
        out.append("===== Zemote log bundle =====\n")
        out.append("created=").append(now()).append('\n')
        out.append("dir=").append(dir.absolutePath).append('\n')
        out.append("files:\n")
        files().forEach { f -> out.append("  ").append(f.name).append(' ').append(f.length()).append("B\n") }
        out.append('\n')

        val order = listOf(
            "crash-history.log",
            "crash.log",
            "zemote.$MAX_ARCHIVES.log",
            "zemote.2.log",
            "zemote.1.log",
            "zemote.log",
        )
        for (name in order) {
            val file = File(dir, name)
            if (!file.isFile || file.length() == 0L) continue
            if (out.length >= BUNDLE_LIMIT_BYTES) break
            out.append("===== ").append(name).append(" (").append(file.length()).append("B) =====\n")
            out.append(readHeadOrTail(file))
            out.append('\n')
        }
        val recent = snapshot()
        if (recent.isNotBlank()) {
            out.append("===== memory ring (may be duplicated) =====\n")
            out.append(recent).append('\n')
        }
        return out.toString()
    }

    /** 把 bundle 写到调用方给的 Uri（SAF 导出）。 */
    fun exportTo(context: Context, uri: Uri): Boolean = runCatching {
        val resolver: ContentResolver = context.contentResolver
        val text = bundle()
        resolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
    }.getOrDefault(false).also { ok ->
        if (ok) i("LOG", "export", "exported to uri") else w("LOG", "export", "export failed")
    }

    /**
     * 生成一份用于 ACTION_SEND 的日志文件（放在 `logs/exports/`，由 FileProvider 暴露）。
     *
     * 只保留最近两份导出，避免缓存无限增长。失败返回 `null`（调用方提示用户即可）。
     */
    fun prepareShareFile(): File? = runCatching {
        val dir = logDir ?: return null
        val exports = File(dir, "exports").apply { mkdirs() }
        exports.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(1)
            ?.forEach { it.delete() }
        val stamp = synchronized(timeLock) { stampFormat.format(Date()) }
        val target = File(exports, "zemote-log-$stamp.txt")
        target.writeText(bundle(), Charsets.UTF_8)
        target
    }.onFailure { w("LOG", "share", "prepare share file failed: ${it.message}") }.getOrNull()

    // ------------------------------------------------------------------
    // 内部：格式化 / 入队 / logcat
    // ------------------------------------------------------------------

    private fun log(
        levelChar: Char,
        priority: Int,
        category: String,
        tag: String,
        message: String,
        error: Throwable?,
        mirrorLogcat: Boolean = true,
    ) {
        val text = buildString {
            append(message)
            if (error != null) {
                append(" : ")
                append(error.javaClass.simpleName)
                append(": ")
                append(error.message.orEmpty())
            }
        }.replace('\n', ' ').let { redact(it) }
            .let { if (it.length > MAX_LINE_CHARS) it.take(MAX_LINE_CHARS) + "…" else it }

        val line = "${now()} $levelChar [$category/$tag] ${Thread.currentThread().name}: $text"
        synchronized(ringLock) {
            ring.addLast(line)
            while (ring.size > RING_LIMIT) ring.removeFirst()
        }
        // logcat 统一用 INFO：多数 OEM 会吞 DEBUG/VERBOSE，出问题时至少要能看到一条时间线。
        // 大协议帧在主线程写 logcat 会走 binder，滚动/流式时是额外卡顿源；这类只落文件。
        if (mirrorLogcat) {
            runCatching { Log.println(priority.coerceAtLeast(Log.INFO), LOGCAT_TAG, line.take(4_000)) }
        }
        if (!queue.offer(line)) {
            dropped++
        }
    }

    private fun now(): String = synchronized(timeLock) { timeFormat.format(Date()) }

    // ------------------------------------------------------------------
    // 内部：文件写入
    // ------------------------------------------------------------------

    private fun writerLoop() {
        while (true) {
            try {
                val first = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                val batch = StringBuilder(8_192)
                batch.append(first).append('\n')
                var count = 1
                while (count < 200) {
                    val next = queue.poll() ?: break
                    batch.append(next).append('\n')
                    count++
                }
                if (dropped > 0) {
                    val lost = dropped
                    dropped = 0
                    batch.append("${now()} W [LOG/dropped] writer: 队列满，丢弃 $lost 行\n")
                }
                writeBatch(batch.toString())
            } catch (_: InterruptedException) {
                return
            } catch (_: Throwable) {
                // 文件系统异常不应影响主流程；下一批继续尝试。
            }
        }
    }

    private fun writeBatch(text: String) {
        if (!fileLoggingEnabled) return
        val dir = logDir ?: return
        synchronized(fileLock) {
            try {
                ensureWriter(dir)
                val bytes = text.toByteArray(Charsets.UTF_8)
                if (currentBytes > 0 && currentBytes + bytes.size > MAX_FILE_BYTES) {
                    rotate(dir)
                }
                val out = writer ?: return
                out.write(text)
                out.flush()
                currentBytes += bytes.size
            } catch (e: Throwable) {
                // 落盘失败（存储满/权限）后关闭文件日志，避免每行都抛一次。
                fileLoggingEnabled = false
                runCatching { writer?.close() }
                writer = null
                runCatching { Log.w(LOGCAT_TAG, "文件日志已停用: ${e.message}") }
            }
        }
    }

    private fun ensureWriter(dir: File) {
        if (writer != null) return
        val file = File(dir, "zemote.log")
        currentBytes = file.length()
        if (currentBytes > MAX_FILE_BYTES) {
            // rotate() 会自己打开新 writer；这里直接返回，避免同一把锁里再开第二个。
            rotate(dir)
            return
        }
        writer = BufferedWriter(FileWriter(file, true), 16 * 1024)
        currentBytes = file.length()
    }

    private fun rotate(dir: File) {
        runCatching { writer?.close() }
        writer = null
        for (i in MAX_ARCHIVES downTo 1) {
            val target = File(dir, "zemote.$i.log")
            val source = if (i == 1) File(dir, "zemote.log") else File(dir, "zemote.${i - 1}.log")
            if (source.exists()) {
                runCatching {
                    if (target.exists()) target.delete()
                    source.renameTo(target)
                }
            }
        }
        currentBytes = 0
        writer = BufferedWriter(FileWriter(File(dir, "zemote.log"), true), 16 * 1024)
    }

    /** 导出用：大文件只取末尾 [BUNDLE_PER_FILE_BYTES]，小文件整读。 */
    private fun readHeadOrTail(file: File): String = runCatching {
        val length = file.length()
        if (length <= BUNDLE_PER_FILE_BYTES) {
            file.readText(Charsets.UTF_8)
        } else {
            RandomAccessFile(file, "r").use { raf ->
                val start = length - BUNDLE_PER_FILE_BYTES
                val bytes = ByteArray(BUNDLE_PER_FILE_BYTES.toInt())
                raf.seek(start)
                raf.readFully(bytes)
                val text = String(bytes, Charsets.UTF_8)
                "…(省略前 $start 字节)…\n" + text.substringAfter('\n')
            }
        }
    }.getOrDefault("(read failed)")

    // ------------------------------------------------------------------
    // 内部：崩溃
    // ------------------------------------------------------------------

    private fun writeFatal(thread: Thread, error: Throwable) {
        try {
            val stack = StringWriter().also { sw ->
                PrintWriter(sw).use { error.printStackTrace(it) }
            }.toString()
            val body = buildString {
                append(now()).append(" E [CRASH/Uncaught] ").append(thread.name)
                append(": ").append(error.javaClass.name).append(": ").append(error.message.orEmpty())
                append('\n').append(heapSummary()).append('\n')
                append(stack)
                append("--- recent ---\n").append(snapshot(SYNC_RECENT_LINES)).append('\n')
            }
            val clean = redact(body)
            val dir = logDir
            if (dir != null) {
                synchronized(fileLock) {
                    runCatching { writer?.flush() }
                    runCatching { File(dir, "crash.log").appendText(clean, Charsets.UTF_8) }
                    runCatching { File(dir, "zemote.log").appendText(clean, Charsets.UTF_8) }
                }
            }
            runCatching { Log.e(LOGCAT_TAG, "FATAL ${thread.name}", error) }
        } catch (_: Throwable) {
            // 崩溃路径本身不能再抛。
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readPreviousExitReasons(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val dir = logDir ?: return
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong(PREF_LAST_EXIT, 0L)
        var maxSeen = lastSeen
        try {
            val manager = context.getSystemService(ActivityManager::class.java) ?: return
            val reasons = manager.getHistoricalProcessExitReasons(null, 0, 10)
            // `REASON_INITIALIZATION_FAILURE` 是 API 31 才有的常量：单独取一次，避免在
            // API 30 设备上引用到不存在的字段（常量虽被内联，Lint 仍按引入版本检查）。
            val initFailureReason = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE
            } else {
                null
            }
            val interesting = reasons.filter {
                it.reason == ApplicationExitInfo.REASON_CRASH ||
                    it.reason == ApplicationExitInfo.REASON_ANR ||
                    it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                    // 「一滑动就闪退」最常见的是被系统杀掉，没有 Java 栈：
                    // LMK / 低内存退出只会出现在 exit info 里。
                    it.reason == ApplicationExitInfo.REASON_LOW_MEMORY ||
                    (initFailureReason != null && it.reason == initFailureReason)
            }
            for (info in interesting.asReversed()) {
                if (info.timestamp <= lastSeen) continue
                maxSeen = maxOf(maxSeen, info.timestamp)
                val exitLabel = when {
                    info.reason == ApplicationExitInfo.REASON_CRASH -> "CRASH"
                    info.reason == ApplicationExitInfo.REASON_ANR -> "ANR"
                    info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
                    info.reason == ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
                    initFailureReason != null && info.reason == initFailureReason -> "INIT_FAILURE"
                    else -> "EXIT_${info.reason}"
                }
                val trace = runCatching {
                    info.traceInputStream?.use { stream ->
                        val bytes = stream.readBytes()
                        // 崩溃转储可能很大，只保留前 64 KiB；真正的栈通常在最前面。
                        String(bytes, 0, minOf(bytes.size, 64 * 1024), Charsets.UTF_8)
                    }
                }.getOrNull().orEmpty()
                val body = buildString {
                    append("\n===== ${now()} [$exitLabel] ts=${info.timestamp} ")
                    append("desc=${info.description.orEmpty()} importance=${info.importance} ")
                    append("reason=${info.reason}\n")
                    if (trace.isNotBlank()) append(trace).append('\n')
                    else append("(trace unavailable)\n")
                }
                synchronized(fileLock) {
                    runCatching { writer?.flush() }
                    runCatching { File(dir, "crash-history.log").appendText(redact(body), Charsets.UTF_8) }
                    runCatching { File(dir, "zemote.log").appendText(redact(body), Charsets.UTF_8) }
                }
                runCatching { Log.e(LOGCAT_TAG, "previous exit: $exitLabel ${info.description.orEmpty()}") }
            }
            if (maxSeen > lastSeen) {
                prefs.edit().putLong(PREF_LAST_EXIT, maxSeen).apply()
            }
        } catch (_: Throwable) {
            // API/权限异常不影响主流程。
        }
    }
}
