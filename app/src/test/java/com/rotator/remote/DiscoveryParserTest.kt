package com.rotator.remote

import org.junit.Assert.*
import org.junit.Test

class DiscoveryParserTest {
    private val defaultPort = 9999

    private fun parse(message: String, sourceIp: String?): Pair<String?, Int> {
        if (!message.startsWith("ROTATOR_SERVER:")) return Pair(null, -1)
        val payload = message.substringAfter(":")
        val parts = payload.split(":")
        val (ipCandidate, portCandidate) = when (parts.size) {
            1 -> Pair(sourceIp, parts[0])
            else -> Pair(parts[0], parts.getOrNull(1) ?: "")
        }
        val port = portCandidate.trim().toIntOrNull() ?: defaultPort
        val ip = ipCandidate?.takeIf { it.isNotBlank() }
        return Pair(ip, port)
    }

    @Test
    fun `legacy format returns source address`() {
        val (ip, port) = parse("ROTATOR_SERVER:9999", "192.168.1.42")
        assertEquals("192.168.1.42", ip)
        assertEquals(9999, port)
    }

    @Test
    fun `new format with ip`() {
        val (ip, port) = parse("ROTATOR_SERVER:10.0.0.5:1234", "192.168.1.42")
        assertEquals("10.0.0.5", ip)
        assertEquals(1234, port)
    }

    @Test
    fun `bad port falls back to default`() {
        val (ip, port) = parse("ROTATOR_SERVER:hello", "1.2.3.4")
        assertEquals("1.2.3.4", ip)
        assertEquals(defaultPort, port)
    }

    @Test
    fun `missing ip returns null`() {
        val (ip, port) = parse("ROTATOR_SERVER::8888", null)
        assertNull(ip)
        assertEquals(8888, port)
    }
}
