package com.rotator.server

import com.hoho.android.usbserial.driver.UsbSerialPort

class RealUsbLayer(private val port: UsbSerialPort) : UsbLayer {
    override fun read(buffer: ByteArray, timeout: Int): Int = port.read(buffer, timeout)
    override fun write(bytes: ByteArray, timeout: Int) = port.write(bytes, timeout)
    override fun close() {
        try {
            port.close()
        } catch (e: Exception) {
            // Ignore
        }
    }
}
