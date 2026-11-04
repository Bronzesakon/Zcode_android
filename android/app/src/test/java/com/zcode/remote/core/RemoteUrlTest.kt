package com.zcode.remote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D1/D2/D3（TODO §4）：域名口径、导航闸门与登录面判定。
 *
 * 这三条决定"登录能不能在壳内走完"：只要 host 不在可信域里，主框架导航会被交给系统
 * 浏览器，而 OAuth nonce 存在 WebView 的 sessionStorage 里 ⇒ 回调必然 CSRF 失败。
 * 判定全部收在纯字符串函数上，`Uri` 只做取值（JVM 单测里 android.net.Uri 是空桩）。
 */
class RemoteUrlTest {

    @Test
    fun `D1 trusted hosts are the two root domains and their subdomains`() {
        listOf("z.ai", "chat.z.ai", "zcode.z.ai", "bigmodel.cn", "www.bigmodel.cn").forEach {
            assertTrue("$it 必须留在壳内", RemoteUrl.isTrustedHost(it))
        }
        listOf(
            "evilz.ai", "z.ai.evil.com", "notbigmodel.cn", "bigmodel.com.cn", "z-ai.com",
        ).forEach {
            assertFalse("$it 是外部域", RemoteUrl.isTrustedHost(it))
        }
        // 大小写不敏感：真实导航给的是 URI host，可能带大写。
        assertTrue(RemoteUrl.isTrustedHost("CHAT.Z.AI"))
    }

    @Test
    fun `D2 a download link is never handed to the system browser`() {
        listOf(
            "https://github.com/x/y/releases/download/v1/zcode-remote.apk",
            "https://cdn.example.com/a/b.zip",
            "https://zcode.z.ai/files/报告.pdf?token=redacted",
            "https://example.com/README.MD",
        ).forEach {
            assertTrue("下载必须留在壳内: $it", RemoteUrl.isDownloadUrl(it))
        }
        listOf(
            "https://example.com/",
            "https://example.com/path",
            "https://example.com/a.b/c",
            "https://example.com/download?file=1.zip",
            "https://example.com/archive.tar.gz/",
        ).forEach {
            assertFalse("不是下载链接: $it", RemoteUrl.isDownloadUrl(it))
        }
    }

    @Test
    fun `D3 the auth surface is decided by host plus path prefix only`() {
        assertEquals(RemoteUrl.AuthSurface.BIGMODEL, RemoteUrl.authSurfaceOf("bigmodel.cn", "/login"))
        assertEquals(
            RemoteUrl.AuthSurface.BIGMODEL,
            RemoteUrl.authSurfaceOf("open.bigmodel.cn", "/anything/at/all"),
        )
        assertEquals(
            RemoteUrl.AuthSurface.ZAI_CONSENT,
            RemoteUrl.authSurfaceOf("chat.z.ai", "/auth/oauth/authorize"),
        )
        assertEquals(RemoteUrl.AuthSurface.ZAI_LOGIN, RemoteUrl.authSurfaceOf("chat.z.ai", "/auth"))
        assertEquals(RemoteUrl.AuthSurface.ZAI_LOGIN, RemoteUrl.authSurfaceOf("chat.z.ai", "/auth/login"))
        assertEquals(RemoteUrl.AuthSurface.ZCODE, RemoteUrl.authSurfaceOf("zcode.z.ai", "/web-remote/callback"))
        assertEquals(RemoteUrl.AuthSurface.ZCODE, RemoteUrl.authSurfaceOf("zcode.z.ai", "/share/abc"))
        assertEquals(RemoteUrl.AuthSurface.ZCODE, RemoteUrl.authSurfaceOf("zcode.z.ai", "/cn/share/abc"))
        // 远程页本身、以及任何别的路径都不是登录面。
        assertNull(RemoteUrl.authSurfaceOf("zcode.z.ai", "/remote/v4"))
        assertNull(RemoteUrl.authSurfaceOf("chat.z.ai", "/"))
        assertNull(RemoteUrl.authSurfaceOf("example.com", "/login"))
    }

    @Test
    fun `D9 a login page under the remote prefix is still not an auth surface`() {
        // 未验证前提（待真机）：登录页若真落在 `/remote/v4/login` 这类路径下，当前判定
        // 既不认它是登录面、`/remote` 前缀闸又会放它进注入层。这一条把现状钉住——
        // 真机确认后若要收窄，改的是这里和 isAllowed，不是猜。
        assertNull(RemoteUrl.authSurfaceOf("zcode.z.ai", "/remote/v4/login"))
        assertNull(RemoteUrl.authSurfaceOf("zcode.z.ai", "/remote/v4"))
    }
}
