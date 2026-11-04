package com.zcode.remote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载目标的纯逻辑：只认 http(s)、文件名清洗、Content-Disposition 两种写法。
 *
 * 这些边界一旦错，表现是"下载下来的文件名是 `.._` 或空文件"，而不是崩溃——
 * 所以必须在这里钉死，别指望真机上看出来。
 */
class DownloadPlanTest {

    @Test
    fun `only http and https can go to the system downloader`() {
        assertTrue(DownloadPlan.of("https://example.com/a.zip", null, null) != null)
        assertTrue(DownloadPlan.of("http://example.com/a.zip", null, null) != null)
        for (rejected in listOf(
            "blob:https://zcode.z.ai/1234-5678",
            "data:text/plain;base64,SGk=",
            "javascript:alert(1)",
            "ftp://example.com/a.zip",
            "",
            "   ",
        )) {
            assertNull("必须拒绝：$rejected", DownloadPlan.of(rejected, null, null))
        }
        assertNull("null 也必须拒绝", DownloadPlan.of(null, null, null))
    }

    @Test
    fun `host is the authority without userinfo or port`() {
        assertEquals("example.com", DownloadPlan.hostOf("https://example.com/a.zip"))
        assertEquals("example.com", DownloadPlan.hostOf("https://example.com:8443/a.zip"))
        assertEquals("example.com", DownloadPlan.hostOf("https://user:pass@example.com/a.zip"))
        assertEquals("EXAMPLE.com".lowercase(), DownloadPlan.hostOf("https://EXAMPLE.com/a.zip"))
        assertEquals("::1", DownloadPlan.hostOf("http://[::1]:8080/a.zip"))
        assertNull(DownloadPlan.hostOf("https:///no-host"))
    }

    @Test
    fun `rfc5987 filename wins over the plain one and is percent-decoded`() {
        val plan = DownloadPlan.of(
            "https://zcode.z.ai/api/file/abc",
            "attachment; filename=fallback.zip; filename*=UTF-8''%E4%B8%AD%E6%96%87.zip",
            "application/zip",
        )!!
        assertEquals("中文.zip", plan.fileName)
    }

    @Test
    fun `plain quoted and unquoted filenames are honoured`() {
        assertEquals(
            "report 2026.pdf",
            DownloadPlan.fileNameOf("https://h/x", "attachment; filename=\"report 2026.pdf\"", null),
        )
        assertEquals(
            "a.zip",
            DownloadPlan.fileNameOf("https://h/x", "attachment; filename=a.zip; size=12", null),
        )
    }

    @Test
    fun `without a disposition the url tail is used and decoded`() {
        assertEquals(
            "中文.pdf",
            DownloadPlan.fileNameOf("https://h/files/%E4%B8%AD%E6%96%87.pdf?token=redacted", null, null),
        )
        // 路径语义里 '+' 是字面加号，不能被当成空格
        assertEquals("a+b.txt", DownloadPlan.fileNameOf("https://h/a+b.txt", null, null))
    }

    @Test
    fun `path separators control chars and leading dots cannot escape the filename`() {
        assertEquals("_evil.zip", DownloadPlan.fileNameOf("https://h/x", "attachment; filename=../evil.zip", null))
        assertEquals("a_b.zip", DownloadPlan.fileNameOf("https://h/x", "attachment; filename=a\\b.zip", null))
        assertEquals("a_b.zip", DownloadPlan.fileNameOf("https://h/x", "attachment; filename=a:b.zip", null))
        assertEquals(
            "x_y.zip",
            DownloadPlan.fileNameOf("https://h/x", "attachment; filename=x\u0007y.zip", null),
        )
        // 名字全被清空时不能留空名
        assertTrue(DownloadPlan.fileNameOf("https://h/", "attachment; filename=...", null).isNotEmpty())
    }

    @Test
    fun `an extension is appended from the mime type when the name has none`() {
        assertEquals("file.pdf", DownloadPlan.fileNameOf("https://h/file", null, "application/pdf"))
        assertEquals("file.bin", DownloadPlan.fileNameOf("https://h/file", null, "application/octet-stream"))
        assertEquals("file", DownloadPlan.fileNameOf("https://h/file", null, "text/html"))
        // mime 参数要剥掉
        assertEquals(
            "file.pdf",
            DownloadPlan.fileNameOf("https://h/file", null, "application/pdf; charset=binary"),
        )
        // 没有路径段时退回固定的 download 名
        assertEquals("download.pdf", DownloadPlan.fileNameOf("https://h/", null, "application/pdf"))
    }

    @Test
    fun `a very long name is truncated but keeps its extension`() {
        val longName = "x".repeat(150) + ".zip"
        val name = DownloadPlan.fileNameOf("https://h/x", "attachment; filename=$longName", "application/zip")
        assertTrue("长度应受限：${name.length}", name.length <= 120)
        assertTrue("扩展名要保留：$name", name.endsWith(".zip"))
    }

    @Test
    fun `plan carries a normalised mime type and never logs the url`() {
        val plan = DownloadPlan.of("https://h/a.BIN", null, "Application/Octet-Stream")!!
        assertEquals("application/octet-stream", plan.mimeType)
        assertEquals("h", plan.host)
        assertEquals("a.BIN", plan.fileName)
        // 计划里保留原始 URL 供入队（可能带凭证）——调用方只许记录 host/fileName。
        assertTrue(plan.url.startsWith("https://h/"))
    }
}
