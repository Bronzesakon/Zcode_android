package com.zcode.remote.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * relayProof 与页面实现逐字对齐的回归锚（docs/05 快照 @4696180）：
 * `base64url(HMAC-SHA256(key=passHash, msg=nonce|role|deviceSid))`，无填充。
 * 向量由 node crypto 以同一算法离线生成（2026-09-13）。
 */
class NativeRelayClientTest {

    @Test
    fun proofMatchesThePageAlgorithm() {
        assertEquals(
            "zjwQIGBnBdXIw4FjcrL2CWJ1_wgs_uObTtQcpvWWQOY",
            relayProof("test-pass-hash", "test-nonce-1", "terminal", "device-001"),
        )
        assertEquals(
            "nBirF2y2KBlcYo75gGzHqv6xurhVxksEUU3y7KrBTK0",
            relayProof("abc", "2026-09-13T01:00:00Z", "terminal", "sid-xyz"),
        )
    }

    @Test
    fun differentRolesChangeTheProof() {
        val withTerminal = relayProof("hash", "nonce", "terminal", "sid")
        val withOther = relayProof("hash", "nonce", "controller", "sid")
        assert(withTerminal != withOther)
    }

    // ------------------------------------------------------------- 3.17.1 认证
    //
    // 这两条是"承载在 3.17.1 上到底能不能启动"的全部：形状错一个字段，配对永远
    // 不 matched，而且真机上一次握手要重新扫码——所以形状必须在 JVM 上钉死。

    private fun tokenCreds() = RelayCreds(
        wsUrl = "wss://zcode.z.ai/api/v1/remote/ws2?token=<TOKEN>&mid=<MID>",
        deviceSid = "sid-1",
        passHash = "",
        deviceMid = "<MID>",
        authToken = "<TOKEN>",
    )

    private fun legacyCreds() = RelayCreds(
        wsUrl = "wss://zcode.z.ai/ws?mid=<MID>",
        deviceSid = "sid-1",
        passHash = "pass-hash",
        deviceMid = "<MID>",
    )

    @Test
    fun tokenModeWinsWhenATokenIsPresent() {
        assertTrue(tokenCreds().tokenMode)
        assertFalse(legacyCreds().tokenMode)
        // 空串 token 不算 token 模式（页面没带 token 时不要误判）。
        assertFalse(legacyCreds().copy(authToken = "").tokenMode)
    }

    @Test
    fun tokenModeAuthInitHasNoRoleAndUsesSeconds() {
        val frame = authInitFrame(tokenCreds(), nowMs = 1_700_000_000_123L)
        assertEquals("auth_init", frame.getString("type"))
        assertFalse("token 模式没有 role", frame.has("role"))
        assertEquals("sid-1", frame.getString("device_sid"))
        // Unix 秒，与页面 Math.floor(Date.now()/1e3) 同口径。
        assertEquals(1_700_000_000L, frame.getLong("client_ts"))
        assertEquals("web", frame.getJSONObject("meta").getString("platform"))
        assertFalse("token 绝不进 auth_init 的任何字段", frame.toString().contains("<TOKEN>"))
    }

    @Test
    fun legacyAuthInitKeepsRoleAndMilliseconds() {
        val frame = authInitFrame(legacyCreds(), nowMs = 1_700_000_000_123L)
        assertEquals("terminal", frame.getString("role"))
        assertEquals(1_700_000_000_123L, frame.getLong("client_ts"))
        assertEquals("sid-1", frame.getString("device_sid"))
        // 形状与旧实现逐字一致：五个键，不多不少。
        assertEquals(
            setOf("type", "role", "device_sid", "meta", "client_ts"),
            frame.keys().asSequence().toSet(),
        )
    }

    @Test
    fun theTokenNeverAppearsInTheAuthInitFrame() {
        val frame = authInitFrame(tokenCreds(), nowMs = 1L)
        assertEquals(
            setOf("type", "device_sid", "meta", "client_ts"),
            frame.keys().asSequence().toSet(),
        )
        assertEquals(JSONObject().put("platform", "web").put("version", "web").put("name", "mobile-browser").toString(),
            frame.getJSONObject("meta").toString())
    }
}
