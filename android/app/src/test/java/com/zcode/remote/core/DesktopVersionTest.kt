package com.zcode.remote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 闸门②（TODO §3.1）：装载时把学到的 `desktopAppVersion` 拼进 URL。
 *
 * 纪律：**不写回 Prefs、不新增 URL 监听、拿不到值就不猜**。所以这里钉两件事——
 * 值怎么来（只接受 URL 参数安全的形状），以及拼进去时怎么改（已存在就替换，
 * 不能让扫码链接里的旧值把闸门触发出来）。
 */
class DesktopVersionTest {

    @Test
    fun `unknown version leaves the url untouched`() {
        assertEquals(
            "https://zcode.z.ai/remote/v4",
            DesktopVersion.withAppVersion("https://zcode.z.ai/remote/v4", null),
        )
        assertEquals("https://zcode.z.ai/remote/v4", DesktopVersion.withAppVersion("https://zcode.z.ai/remote/v4", ""))
        assertEquals("", DesktopVersion.withAppVersion("", "3.17.1"))
    }

    @Test
    fun `a version is appended, replaced, and kept before the fragment`() {
        assertEquals(
            "https://zcode.z.ai/remote/v4?app_version=3.17.1",
            DesktopVersion.withAppVersion("https://zcode.z.ai/remote/v4", "3.17.1"),
        )
        // 已有其它参数：追加，不覆盖别人的。
        assertEquals(
            "https://zcode.z.ai/remote/v4?sid=<SID>&app_version=3.17.1",
            DesktopVersion.withAppVersion("https://zcode.z.ai/remote/v4?sid=<SID>", "3.17.1"),
        )
        // 已有 app_version：替换（旧值会让闸门当场触发一次整页导航）。
        assertEquals(
            "https://zcode.z.ai/remote/v4?sid=<SID>&app_version=3.17.1",
            DesktopVersion.withAppVersion("https://zcode.z.ai/remote/v4?sid=<SID>&app_version=3.16.1", "3.17.1"),
        )
        // 参数正好是第一个：也不重复。
        assertEquals(
            "https://zcode.z.ai/remote/v4?app_version=3.17.1&mid=<MID>",
            DesktopVersion.withAppVersion("https://zcode.z.ai/remote/v4?app_version=3.16.1&mid=<MID>", "3.17.1"),
        )
        // fragment 留在最后。
        assertEquals(
            "https://zcode.z.ai/remote/v4?app_version=3.17.1#/task/1",
            DesktopVersion.withAppVersion("https://zcode.z.ai/remote/v4#/task/1", "3.17.1"),
        )
    }

    @Test
    fun `only plausible version strings are accepted`() {
        listOf("", " ", "3.17.1 beta", "3.17.1?x=1", "a".repeat(41), "sid=secret", "3.17.1/\n").forEach {
            assertNull("拒绝不可信的版本值: '$it'", DesktopVersion.parse(it))
        }
        assertEquals("3.17.1", DesktopVersion.parse("  3.17.1  "))
        assertEquals("3.17.1-rc.2", DesktopVersion.parse("3.17.1-rc.2"))
        // 学到的值才写内存；非法值不覆盖已学到的。
        DesktopVersion.learn("3.17.1")
        assertEquals("3.17.1", DesktopVersion.current())
        DesktopVersion.learn("not a version")
        assertEquals("3.17.1", DesktopVersion.current())
    }
}
