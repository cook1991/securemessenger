package net.spritzlive.securemessenger.net.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class HermesSetupCodeTest {
    private val pin = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
    private val topic = "a".repeat(32)

    private fun code(
        host: String = "relay.example.net",
        port: Int = 443,
        pins: List<String> = listOf(pin),
        wsPath: String = "/ws",
        pairPath: String = "/pair",
        pairingCode: String = "ABCD-1234",
    ) = HermesSetupCode.encode(host, port, wsPath, pairPath, pins, pairingCode)

    private fun rejects(text: String) {
        val result = runCatching { HermesSetupCode.parse(text) }
        assertTrue("expected rejection", result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun roundTrips() {
        val parsed = HermesSetupCode.parse("  " + code() + "\n")
        assertEquals("relay.example.net", parsed.config.host)
        assertEquals("wss://relay.example.net:443/ws", parsed.config.wsUrl())
        assertEquals("https://relay.example.net:443/pair", parsed.config.pairUrl())
        assertEquals(listOf(pin), parsed.config.pins)
        assertEquals("ABCD-1234", parsed.pairingCode)
        assertFalse(parsed.config.hasWake)
    }

    @Test
    fun defaultsPortAndPathsWhenOmitted() {
        val json = """{"host":"relay.example.net","pins":["$pin"],"code":"x1"}"""
        val text = HermesSetupCode.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
        val parsed = HermesSetupCode.parse(text)
        assertEquals(443, parsed.config.port)
        assertEquals("/ws", parsed.config.wsPath)
        assertEquals("/pair", parsed.config.pairPath)
    }

    @Test fun rejectsWrongPrefix() = rejects(code().replace("hermes-setup:1:", "hermes-setup:2:"))
    @Test fun rejectsGarbage() = rejects(HermesSetupCode.PREFIX + "!!!not base64!!!")
    @Test fun rejectsNoPins() = rejects(code(pins = emptyList()))
    @Test fun rejectsShortPin() = rejects(code(pins = listOf(Base64.getEncoder().encodeToString(ByteArray(16)))))
    @Test fun rejectsIpOrSchemeHost() { rejects(code(host = "40.160.87.45")); rejects(code(host = "https://relay.example.net")) }
    @Test fun rejectsBadPort() = rejects(code(port = 70000))
    @Test fun rejectsTraversalPath() = rejects(code(pairPath = "/../admin"))
    @Test fun rejectsWhitespaceInCode() = rejects(code(pairingCode = "two words"))

    @Test
    fun wakeSettingsValidated() {
        val base = HermesSetupCode.parse(code()).config
        assertTrue(base.copy(ntfyHost = "ntfy.example.net", ntfyTopic = topic).validate().hasWake)
        assertTrue(runCatching { base.copy(ntfyHost = "ntfy.example.net", ntfyTopic = "short").validate() }.isFailure)
        assertTrue(runCatching { base.copy(ntfyHost = "ntfy.example.net").validate() }.isFailure)
        assertTrue(runCatching { base.copy(ntfyHost = "ntfy.example.net", ntfyTopic = "$topic/x").validate() }.isFailure)
    }
}
