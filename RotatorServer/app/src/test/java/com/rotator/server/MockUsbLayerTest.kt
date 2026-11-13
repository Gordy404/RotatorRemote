package com.rotator.server

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class MockUsbLayerTest {

    @Test
    fun testMockUsbLayerReadAndWrite() = runBlocking {
        val mock = MockUsbLayer()
        val buffer = ByteArray(512)

        // First message should be READY or a POS (depending on scheduling)
        val len1 = mock.read(buffer, 2000)
        val s1 = if (len1 > 0) String(buffer, 0, len1) else ""
        assertTrue("Expected READY or POS, got: $s1", s1.startsWith("READY") || s1.startsWith("POS:"))

        // Next message should be a POS update
        val len2 = mock.read(buffer, 3000)
        val s2 = if (len2 > 0) String(buffer, 0, len2) else ""
        assertTrue("Expected POS message, got: $s2", s2.startsWith("POS:"))

        // Send a command and expect an ACK (allow a few reads in case POS interleaves)
        mock.write("CW\n".toByteArray(), 1000)
        var ackFound = false
        for (i in 0 until 5) {
            val len = mock.read(buffer, 1000)
            if (len > 0) {
                val s = String(buffer, 0, len)
                if (s.startsWith("ACK:CW")) {
                    ackFound = true
                    break
                }
            }
        }
        assertTrue("Expected ACK:CW response from mock", ackFound)

        mock.close()
    }
}
