package com.zcode.remote.core

/**
 * 3.17.1 的「桌面版本闸门」的另一半（TODO §3.1-②）。
 *
 * 页面自己的 `Pir()` 会比较 URL 里的 `app_version` 与桌面端回的 `desktopAppVersion`：
 * 不相等时，未就绪就 `location.replace()` 整页换 URL（**清空 `history.state`**，把
 * `pinChatRoute` 一起抹掉），已就绪就弹一个阻断式 modal 并停掉一切 relay 发送。
 *
 * 治本的办法是让壳加载的 URL 一开始就带正确的值 —— 但**不猜**：只在真的从页面的
 * bootstrap 帧里学到 `desktopAppVersion` 之后才拼；学不到就什么都不做，交给闸门①
 * （document-start 重钉 `history.state`）与闸门③（探测 modal 不重载）兜。
 *
 * 纪律（用户 2026-11-04 拍板）：
 *   * **不写回 `Prefs`**：这是运行时学到的事实，不是用户配置；进程重启后重新学。
 *   * **不新增 URL 监听**：页面自己 `location.replace()` 出来的 URL 不回写持久化值。
 *   * 只在**加载/重载**时拼参数，不碰页面已打开的文档。
 */
object DesktopVersion {

    /** 形如 `3.17.1` / `3.17.1-beta.2`：只接受 URL 参数里安全的字符，拒绝任何凭据形状。 */
    private val VALID = Regex("^[0-9A-Za-z._+-]{1,40}$")

    @Volatile
    private var learned: String? = null

    /** 页面 bootstrap 帧里的 `desktopAppVersion`；非法值一律丢弃，绝不猜。 */
    fun learn(raw: String?) {
        val value = parse(raw) ?: return
        if (value == learned) return
        learned = value
        Diagnostics.info("版本闸门：学到桌面版本 $value（下次加载/重载时拼进 URL）")
    }

    /** 纯校验（可单测）：合法版本号返回去空白后的值，否则 null。 */
    fun parse(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        return if (VALID.matches(value)) value else null
    }

    fun current(): String? = learned

    /** 给加载用的 URL 补上**已学到**的 `app_version`。 */
    fun withAppVersion(url: String): String = withAppVersion(url, learned)

    /**
     * 给加载用的 URL 补上 `app_version`。已存在就**替换**（扫码链接里的旧值会让闸门
     * 直接触发一次整页导航）；[version] 为 null 时原样返回。
     *
     * 纯字符串实现，不依赖 `android.net.Uri`，因此可以在 JVM 单测里逐条钉住。
     */
    fun withAppVersion(url: String, version: String?): String {
        val value = parse(version) ?: return url
        if (url.isEmpty()) return url
        val fragmentAt = url.indexOf('#')
        val withoutFragment = if (fragmentAt >= 0) url.substring(0, fragmentAt) else url
        val fragment = if (fragmentAt >= 0) url.substring(fragmentAt) else ""
        val queryAt = withoutFragment.indexOf('?')
        val base = if (queryAt >= 0) withoutFragment.substring(0, queryAt) else withoutFragment
        val query = if (queryAt >= 0) withoutFragment.substring(queryAt + 1) else ""
        val params = if (query.isEmpty()) mutableListOf() else query.split('&').toMutableList()
        val replacement = "app_version=$value"
        val index = params.indexOfFirst { it == "app_version" || it.startsWith("app_version=") }
        if (index >= 0) params[index] = replacement else params.add(replacement)
        return "$base?${params.joinToString("&")}$fragment"
    }
}
