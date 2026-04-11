package com.rotator.remote

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.*
import android.util.Log

class RotatorClient {
    
    companion object {
        const val UDP_PORT = 8888
        const val TCP_PORT = 9999
        const val DISCOVERY_TIMEOUT = 5000L
        const val CONNECTION_TIMEOUT = 10000
        const val HEARTBEAT_INTERVAL = 1500L

        // helper to validate the initial greeting sent by the server.  the format
        // changed at one point from `ROTATOR_SERVER:` to `ROTATOR_SERVER v1.0`, and
        // older clients would interpret the latter as a failure. tests verify both
        // variants are accepted.
        fun isValidGreeting(greeting: String?): Boolean {
            return greeting?.startsWith("ROTATOR_SERVER") == true
        }
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var tcpSocket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    
    private var heartbeatJob: Job? = null
    
    private val _currentHeading = MutableStateFlow(0)
    val currentHeading = _currentHeading.asStateFlow()
    
    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()
    
    private val _connectionStatus = MutableStateFlow("Disconnected")
    val connectionStatus = _connectionStatus.asStateFlow()
    
    private val _lastHeadingChangeTime = MutableStateFlow(0L)
    val lastHeadingChangeTime = _lastHeadingChangeTime.asStateFlow()
    
    private var lastHeading = 0
    
    // ===========================
    // UDP DISCOVERY (LAN)
    // ===========================
    
    suspend fun discoverServer(): String? = withContext(Dispatchers.IO) {
        try {
            // open a socket bound to the known discovery port so that we will
            // receive the periodic broadcasts regardless of the ephemeral
            // source port assigned by the OS.
            val socket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(UDP_PORT))
                broadcast = true
                soTimeout = DISCOVERY_TIMEOUT.toInt()
            }

            // a legacy probe is harmless; the server simply ignores it but
            // we keep this on the off‑chance someone is still running an old
            // firmware that listens for DISCOVER_ROTATOR.
            val probe = "DISCOVER_ROTATOR".toByteArray()
            val broadcastAddress = InetAddress.getByName("255.255.255.255")
            socket.send(DatagramPacket(probe, probe.size, broadcastAddress, UDP_PORT))

            _connectionStatus.value = "Discovering..."

            // wait for the first broadcast packet from the server
            val buffer = ByteArray(1024)
            val receivePacket = DatagramPacket(buffer, buffer.size)
            socket.receive(receivePacket)

            val response = String(receivePacket.data, 0, receivePacket.length)
            socket.close()

            // message may be ROTATOR_SERVER:PORT or ROTATOR_SERVER:IP:PORT
            if (response.startsWith("ROTATOR_SERVER:")) {
                val payload = response.substringAfter(":")
                val parts = payload.split(":")
                return@withContext when (parts.size) {
                    1 -> receivePacket.address.hostAddress
                    else -> parts[0]
                }
            }

            null
        } catch (e: SocketTimeoutException) {
            _connectionStatus.value = "No server found on LAN"
            null
        } catch (e: Exception) {
            _connectionStatus.value = "Discovery error: ${e.message}"
            null
        }
    }
    
    // ===========================
    // TCP CONNECTION
    // ===========================
    
    suspend fun connectToServer(host: String, password: String): Boolean = withContext(Dispatchers.IO) {
        try {
            disconnect()
            
            _connectionStatus.value = "Connecting to $host..."
            
            tcpSocket = Socket()
            tcpSocket?.connect(InetSocketAddress(host, TCP_PORT), CONNECTION_TIMEOUT)
            
            reader = BufferedReader(InputStreamReader(tcpSocket?.getInputStream()))
            writer = BufferedWriter(OutputStreamWriter(tcpSocket?.getOutputStream()))
            
            // Read greeting
            val greeting = reader?.readLine()
            Log.d("RotatorClient", "Server greeting='$greeting'")
            if (!isValidGreeting(greeting)) {
                // previous clients looked for a colon after the name; newer server
                // versions use a space with a version number.  Accept either so that
                // connections don't randomly fail depending on which APK is running.
                _connectionStatus.value = "Invalid server response"
                disconnect()
                return@withContext false
            }
            
            // Authenticate (send password with first command)
            writer?.write("$password|STATUS\n")
            writer?.flush()
            
            val authResponse = reader?.readLine()
            if (authResponse == "AUTH:OK" || authResponse?.startsWith("POS:") == true) {
                _isConnected.value = true
                _connectionStatus.value = "Connected to $host"
                
                // Start reading messages
                startMessageReader()
                
                // Start heartbeat
                startHeartbeat()
                
                return@withContext true
            } else {
                _connectionStatus.value = "Authentication failed"
                disconnect()
                return@withContext false
            }
        } catch (e: Exception) {
            _connectionStatus.value = "Connection error: ${e.message}"
            disconnect()
            return@withContext false
        }
    }
    
    fun disconnect() {
        heartbeatJob?.cancel()
        
        try {
            writer?.close()
            reader?.close()
            tcpSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        
        _isConnected.value = false
        _connectionStatus.value = "Disconnected"
    }
    
    // ===========================
    // MESSAGE HANDLING
    // ===========================
    
    private fun startMessageReader() {
        scope.launch {
            try {
                while (isActive && tcpSocket?.isConnected == true) {
                    val line = withContext(Dispatchers.IO) {
                        reader?.readLine()
                    } ?: break
                    
                    processMessage(line)
                }
            } catch (e: Exception) {
                if (_isConnected.value) {
                    _connectionStatus.value = "Connection lost"
                    disconnect()
                }
            }
        }
    }
    
    private fun processMessage(message: String) {
        when {
            message.startsWith("POS:") -> {
                val heading = message.substring(4).toIntOrNull() ?: 0
                if (heading != lastHeading) {
                    _lastHeadingChangeTime.value = System.currentTimeMillis()
                    lastHeading = heading
                }
                _currentHeading.value = heading
            }
            message.startsWith("ERROR:") -> {
                _connectionStatus.value = message
            }
            message.startsWith("ACK:") -> {
                // Command acknowledged
            }
        }
    }
    
    // ===========================
    // COMMAND SENDING
    // ===========================
    
    fun sendCommand(command: String) {
        if (!_isConnected.value) return
        
        scope.launch {
            try {
                writer?.write("$command\n")
                writer?.flush()
            } catch (e: Exception) {
                _connectionStatus.value = "Send error: ${e.message}"
                disconnect()
            }
        }
    }
    
    // ===========================
    // HEARTBEAT
    // ===========================
    
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && _isConnected.value) {
                delay(HEARTBEAT_INTERVAL)
                try {
                    // Send STATUS as heartbeat
                    writer?.write("STATUS\n")
                    writer?.flush()
                } catch (e: Exception) {
                    _connectionStatus.value = "Heartbeat failed"
                    disconnect()
                    break
                }
            }
        }
    }
    
    fun cleanup() {
        scope.cancel()
        disconnect()
    }
}
