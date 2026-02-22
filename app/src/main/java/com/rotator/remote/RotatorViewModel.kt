package com.rotator.remote

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.*

class RotatorViewModel : ViewModel() {
    
    companion object {
        const val UDP_DISCOVERY_PORT = 8888
        const val PASSWORD = "MyRotatorPassword" // Must match server!
        const val STALL_TIMEOUT_MS = 3000L
    }
    
    // UI State
    var connectionState by mutableStateOf(ConnectionState.DISCONNECTED)
        private set
    
    var currentHeading by mutableStateOf(0)
        private set
    
    var isRotating by mutableStateOf(false)
        private set
    
    var showStallAlert by mutableStateOf(false)
        private set
    
    var errorMessage by mutableStateOf<String?>(null)
        private set
    
    var serverIp by mutableStateOf<String?>(null)
        private set
    
    var manualIp by mutableStateOf("")
    
    // Network
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var lastHeadingChangeTime = 0L
    private var lastHeading = 0
    
    // Jobs
    private var discoveryJob: Job? = null
    private var readerJob: Job? = null
    private var stallCheckJob: Job? = null
    
    enum class ConnectionState {
        DISCONNECTED,
        DISCOVERING,
        CONNECTING,
        CONNECTED,
        ERROR
    }
    
    // ===========================
    // DISCOVERY (UDP)
    // ===========================
    
    fun startDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = viewModelScope.launch(Dispatchers.IO) {
            connectionState = ConnectionState.DISCOVERING
            errorMessage = null
            
            try {
                val socket = DatagramSocket()
                socket.broadcast = true
                socket.soTimeout = 5000
                
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)
                
                withTimeout(10000) {
                    while (isActive) {
                        try {
                            socket.receive(packet)
                            val message = String(packet.data, 0, packet.length)
                            
                            if (message.startsWith("ROTATOR_SERVER:")) {
                                val port = message.substringAfter(":").toIntOrNull() ?: 9999
                                val ip = packet.address.hostAddress
                                
                                socket.close()
                                serverIp = ip
                                connectToServer(ip!!, port)
                                return@withTimeout
                            }
                        } catch (e: SocketTimeoutException) {
                            // Continue listening
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                connectionState = ConnectionState.ERROR
                errorMessage = "No server found on local network"
            } catch (e: Exception) {
                connectionState = ConnectionState.ERROR
                errorMessage = "Discovery error: ${e.message}"
            }
        }
    }
    
    // ===========================
    // MANUAL CONNECTION
    // ===========================
    
    fun connectManual(ip: String, port: Int = 9999) {
        serverIp = ip
        connectToServer(ip, port)
    }
    
    // ===========================
    // TCP CONNECTION
    // ===========================
    
    private fun connectToServer(ip: String, port: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                connectionState = ConnectionState.CONNECTING
                errorMessage = null
                
                socket = Socket()
                socket?.connect(InetSocketAddress(ip, port), 5000)
                reader = BufferedReader(InputStreamReader(socket?.getInputStream()))
                
                // Authenticate
                sendCommand("$PASSWORD|PING")
                
                // Wait for AUTH_OK
                val response = withTimeout(3000) {
                    reader?.readLine()
                }
                
                if (response == "AUTH_OK" || response == "PONG") {
                    connectionState = ConnectionState.CONNECTED
                    startReader()
                    startStallCheck()
                } else {
                    throw IOException("Authentication failed")
                }
                
            } catch (e: Exception) {
                disconnect()
                connectionState = ConnectionState.ERROR
                errorMessage = "Connection failed: ${e.message}"
            }
        }
    }
    
    // ===========================
    // MESSAGE READER
    // ===========================
    
    private fun startReader() {
        readerJob?.cancel()
        readerJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                while (isActive && socket?.isConnected == true) {
                    val line = reader?.readLine() ?: break
                    processServerMessage(line)
                }
            } catch (e: Exception) {
                if (isActive) {
                    disconnect()
                    errorMessage = "Connection lost: ${e.message}"
                }
            }
        }
    }
    
    private fun processServerMessage(message: String) {
        when {
            message.startsWith("POS:") -> {
                val heading = message.substringAfter("POS:").toIntOrNull() ?: 0
                
                // Stall detection
                if (isRotating && heading != lastHeading) {
                    lastHeadingChangeTime = System.currentTimeMillis()
                    showStallAlert = false
                }
                
                lastHeading = heading
                currentHeading = heading
            }
            message.startsWith("ERROR:STALL") -> {
                isRotating = false
                showStallAlert = true
            }
            message.startsWith("ERROR:") -> {
                errorMessage = message.substringAfter("ERROR:")
            }
        }
    }
    
    // ===========================
    // STALL DETECTION
    // ===========================
    
    private fun startStallCheck() {
        stallCheckJob?.cancel()
        stallCheckJob = viewModelScope.launch {
            while (isActive) {
                if (isRotating) {
                    val timeSinceChange = System.currentTimeMillis() - lastHeadingChangeTime
                    if (timeSinceChange > STALL_TIMEOUT_MS) {
                        showStallAlert = true
                    }
                }
                delay(500)
            }
        }
    }
    
    // ===========================
    // COMMANDS
    // ===========================
    
    fun startRotateCW() {
        sendCommand("$PASSWORD|CW")
        isRotating = true
        lastHeadingChangeTime = System.currentTimeMillis()
        showStallAlert = false
    }
    
    fun startRotateCCW() {
        sendCommand("$PASSWORD|CCW")
        isRotating = true
        lastHeadingChangeTime = System.currentTimeMillis()
        showStallAlert = false
    }
    
    fun stopRotation() {
        sendCommand("$PASSWORD|STOP")
        isRotating = false
        showStallAlert = false
    }
    
    private fun sendCommand(command: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                socket?.getOutputStream()?.write("$command\n".toByteArray())
                socket?.getOutputStream()?.flush()
            } catch (e: Exception) {
                disconnect()
                errorMessage = "Send failed: ${e.message}"
            }
        }
    }
    
    // ===========================
    // LIFECYCLE
    // ===========================
    
    fun disconnect() {
        discoveryJob?.cancel()
        readerJob?.cancel()
        stallCheckJob?.cancel()
        
        try {
            socket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        
        socket = null
        reader = null
        connectionState = ConnectionState.DISCONNECTED
        isRotating = false
        showStallAlert = false
    }
    
    fun dismissError() {
        errorMessage = null
    }
    
    fun dismissStallAlert() {
        showStallAlert = false
    }
    
    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}
