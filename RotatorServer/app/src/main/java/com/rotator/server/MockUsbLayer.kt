package com.rotator.server

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay

class MockUsbLayer : UsbLayer {
    private val ch = Channel<ByteArray>(Channel.UNLIMITED)
    @Volatile
    private var closed = false

    init {
        GlobalScope.launch(Dispatchers.IO) {
            // Announce ready then stream POS updates
            ch.send("READY\n".toByteArray())
            var heading = 0
            while (!closed) {
                heading = (heading + 5) % 360
                ch.send("POS:$heading\n".toByteArray())
                delay(1000)
            }
        }
    }

    override fun read(buffer: ByteArray, timeout: Int): Int {
        val data = runBlocking {
            withTimeoutOrNull(timeout.toLong()) { ch.receive() }
        } ?: return 0

        val len = minOf(data.size, buffer.size)
        System.arraycopy(data, 0, buffer, 0, len)
        return len
    }

    override fun write(bytes: ByteArray, timeout: Int) {
        GlobalScope.launch(Dispatchers.IO) {
            val cmd = String(bytes).trim()
            // Echo an ACK so app logic can observe responses
            ch.send("ACK:$cmd\n".toByteArray())
        }
    }

    override fun close() {
        closed = true
        ch.close()
    }
}
