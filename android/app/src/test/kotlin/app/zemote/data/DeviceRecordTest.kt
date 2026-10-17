package app.zemote.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [DeviceRecord] / [RelayStatus] / [AppSettings] / [RemoteUrlParser] 的纯逻辑单测。 */
class DeviceRecordTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun relayStatus_wireNamesMatchProtocol() {
        // 与 rust/crates/protocol/src/relay_client.rs 的 RelayState::as_str 一致
        assertEquals(
            listOf(
                "idle", "connecting", "authenticating", "waiting", "paired",
                "reconnecting", "error", "kicked", "closed",
            ),
            RelayStatus.entries.map { it.wireName },
        )
    }

    @Test
    fun relayStatus_fromWire_roundTripsAndFallsBack() {
        for (status in RelayStatus.entries) {
            assertEquals(status, RelayStatus.fromWire(status.wireName))
        }
        assertEquals(RelayStatus.IDLE, RelayStatus.fromWire(null))
        assertEquals(RelayStatus.DEFAULT, RelayStatus.fromWire("some-future-state"))
    }

    @Test
    fun deviceRecord_serializesWithExpectedFieldNames() {
        val record = DeviceRecord(
            id = "d1",
            name = "Desk",
            remoteUrl = "https://zcode.z.ai/remote/v4?sid=s&hash=h&t=1",
            host = "zcode.z.ai",
            sid = "s",
            addedAt = 10L,
            lastConnectedAt = 20L,
            lastRelayStatus = RelayStatus.PAIRED,
            sortKey = 1L,
        )
        val encoded = json.encodeToString(DeviceRecord.serializer(), record)

        for (field in listOf(
            "id", "name", "remoteUrl", "host", "sid",
            "addedAt", "lastConnectedAt", "lastRelayStatus", "sortKey",
        )) {
            assertTrue("序列化字段 $field 应存在：$encoded", encoded.contains("\"$field\""))
        }
        assertTrue(encoded.contains("\"lastRelayStatus\":\"paired\""))
        assertEquals(record, json.decodeFromString(DeviceRecord.serializer(), encoded))
        assertFalse(record.needsRepair)
        assertEquals("zcode.z.ai|s", record.dedupKey)
    }

    @Test
    fun deviceRecord_nullUrlMeansNeedsRepair() {
        val record = DeviceRecord(
            id = "d1", name = "Desk", remoteUrl = null,
            host = "h", sid = "s", addedAt = 0L, sortKey = 0L,
        )
        assertTrue(record.needsRepair)
    }

    @Test
    fun appSettings_normalized_clampsOutOfRange() {
        val dirty = AppSettings(locale = " ", textScale = 3f, codeFontSize = 1f)
        val clean = dirty.normalized()
        assertEquals(AppSettings.DEFAULT_LOCALE, clean.locale)
        assertEquals(AppSettings.TEXT_SCALE_MAX, clean.textScale, 0.0001f)
        assertEquals(AppSettings.CODE_FONT_SIZE_MIN, clean.codeFontSize, 0.0001f)
        assertEquals(AppSettings(), AppSettings().normalized())
    }

    // ------------------------------------------------------------------
    // 远控 URL 解析（去重靠它拿 host + sid）
    // ------------------------------------------------------------------

    @Test
    fun parser_extractsHostSidAndName() {
        val parts = RemoteUrlParser.parse(
            "https://zcode.z.ai/remote/v4?sid=sid-9&hash=abc%3D&t=17&mid=m&name=My%20Mac"
        )!!
        assertEquals("zcode.z.ai", parts.host)
        assertEquals("sid-9", parts.sid)
        assertEquals("My Mac", parts.deviceName)
    }

    @Test
    fun parser_acceptsWssAndIgnoresWhitespace() {
        val parts = RemoteUrlParser.parse("  wss://host.example/remote/v4?sid=s&hash=h&t=1  ")!!
        assertEquals("host.example", parts.host)
        assertNull(parts.deviceName)
    }

    @Test
    fun parser_rejectsBadInput() {
        assertNull(RemoteUrlParser.parse(""))
        assertNull(RemoteUrlParser.parse("not a url"))
        assertNull(RemoteUrlParser.parse("http://zcode.z.ai/remote/v4?sid=s&hash=h&t=1"))
        assertNull(RemoteUrlParser.parse("https://zcode.z.ai/remote/v4?hash=h&t=1"))
        assertNull(RemoteUrlParser.parse("https://zcode.z.ai/remote/v4?sid=%20&hash=h&t=1"))
        assertNull(RemoteUrlParser.parse("file:///tmp/x?sid=s&hash=h&t=1"))
    }
}
