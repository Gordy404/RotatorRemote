package com.rotator.server

interface UsbLayer {
    @Throws(Exception::class)
    fun read(buffer: ByteArray, timeout: Int): Int

    @Throws(Exception::class)
    fun write(bytes: ByteArray, timeout: Int)

    fun close()
}
