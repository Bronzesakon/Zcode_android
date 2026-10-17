package app.zemote.update

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 模型层的纯函数：MD5 解析/计算、清单反序列化、进度换算。 */
class UpdateModelsTest {

    @Test
    fun `parseMd5Hex 能读懂 md5sum 的输出`() {
        assertEquals(
            "0123456789abcdef0123456789abcdef",
            parseMd5Hex("0123456789ABCDEF0123456789ABCDEF  zemote-arm64-v8a.apk\n"),
        )
    }

    @Test
    fun `parseMd5Hex 能读懂裸 hex`() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", parseMd5Hex("d41d8cd98f00b204e9800998ecf8427e"))
    }

    @Test
    fun `parseMd5Hex 遇到非校验值返回 null`() {
        assertNull(parseMd5Hex("not a checksum"))
        assertNull(parseMd5Hex(""))
        // 31 位、33 位都不是 MD5。
        assertNull(parseMd5Hex("0123456789abcdef0123456789abcde"))
        assertNull(parseMd5Hex("0123456789abcdef0123456789abcdef0"))
    }

    @Test
    fun `md5Hex 与标准值一致`() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", ByteArray(0).md5Hex())
        assertEquals("900150983cd24fb0d6963f7d28e17f72", "abc".toByteArray().md5Hex())
        // 负数字节要按无符号补零，不能输出成 "-1" 之类。
        assertEquals("8d79cbc9a4ecdde112fc91ba625b13c2", ByteArray(16) { -1 }.md5Hex())
    }

    @Test
    fun `清单能反序列化`() {
        val decoded = UpdateConfig.json.decodeFromString<VersionManifest>(manifestJson())

        assertEquals(26101623, decoded.versionCode)
        assertEquals("0.1.0-dev.20261016.2314", decoded.versionName)
        assertEquals(UpdateConfig.ABI, decoded.abi)
        assertEquals(APK_ASSET, decoded.apkAsset)
        assertEquals(MD5_ASSET, decoded.md5Asset)
        assertEquals(1_106_350L, decoded.sizeBytes)
    }

    @Test
    fun `清单缺必填字段时抛序列化异常`() {
        assertThrows(SerializationException::class.java) {
            UpdateConfig.json.decodeFromString<VersionManifest>("""{"versionCode": 1}""")
        }
    }

    @Test
    fun `清单字段类型不对时抛序列化异常`() {
        // versionCode 变成非数字字符串：CI 模板改坏了就会这样。
        // 注意 kotlinx-serialization 1.11 会把**带引号的数字**（"26101623"）照常读成 Int，
        // 所以这里用真正转不了的值来验证"类型不对要报错"。
        val broken = manifestJson().replace("\"versionCode\": 26101623", "\"versionCode\": \"abc\"")
        assertThrows(SerializationException::class.java) {
            UpdateConfig.json.decodeFromString<VersionManifest>(broken)
        }
    }

    @Test
    fun `清单字段被写成带引号的数字时仍能解析`() {
        // 记录实际行为：严格模式下也接受 quoted numeric literal。不是我们期望的输入，
        // 但既然能读对就不必让它失败。
        val quoted = manifestJson().replace("\"versionCode\": 26101623", "\"versionCode\": \"26101623\"")
        assertEquals(26101623, UpdateConfig.json.decodeFromString<VersionManifest>(quoted).versionCode)
    }

    @Test
    fun `清单里的未知字段被忽略`() {
        val withExtra = manifestJson().trimEnd('}') + """, "futureField": {"a": 1}}"""
        val decoded = UpdateConfig.json.decodeFromString<VersionManifest>(withExtra)

        assertEquals(26101623, decoded.versionCode)
    }

    @Test
    fun `进度换算：有总长时给出比例`() {
        assertEquals(0.5f, DownloadProgress(50, 100, 0).fraction!!, 1e-6f)
        assertEquals(1f, DownloadProgress(100, 100, 0).fraction!!, 1e-6f)
    }

    @Test
    fun `进度换算：总长未知或非正时不给比例`() {
        assertNull(DownloadProgress(50, null, 0).fraction)
        assertNull(DownloadProgress(50, 0, 0).fraction)
    }

    @Test
    fun `进度换算：超出总长时截断到 1`() {
        // 服务端多给了字节也不能让进度条越过 100%。
        assertTrue(DownloadProgress(120, 100, 0).fraction!! <= 1f)
    }
}
