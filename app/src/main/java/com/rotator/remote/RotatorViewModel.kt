package com.rotator.remote

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.*

class RotatorViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private const val TAG = "RotatorViewModel"
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

    var isArduinoConnected by mutableStateOf(false)
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
    
    // Multicast Lock
    private var multicastLock: WifiManager.MulticastLock? = null
    
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
        if (connectionState == ConnectionState.DISCOVERING || connectionState == ConnectionState.CONNECTING) return
        Log.d(TAG, "Starting discovery...")
        discoveryJob?.cancel()
        discoveryJob = viewModelScope.launch(Dispatchers.IO) {
            connectionState = ConnectionState.DISCOVERING
            errorMessage = null
            serverIp = null
            
            var udpSocket: DatagramSocket? = null
            try {
                // Acquire Multicast Lock to receive UDP broadcasts
                val wifiManager = getApplication<Application>().getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicastLock = wifiManager.createMulticastLock("RotatorDiscoveryLock").apply {
                    setReferenceCounted(true)
                    acquire()
                }
                Log.d(TAG, "Multicast lock acquired.")

                udpSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(UDP_DISCOVERY_PORT))
                    broadcast = true
                    soTimeout = 2000 // Short read timeout for the loop
                }
                Log.d(TAG, "UDP socket bound to port $UDP_DISCOVERY_PORT")
                
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)
                
                withTimeout(15000) { // Overall discovery timeout
                    Log.d(TAG, "Waiting for discovery packets...")
                    while (isActive) {
                        try {
                            udpSocket.receive(packet)
                            val message = String(packet.data, 0, packet.length)
                            Log.i(TAG, "Discovery packet received from ${packet.address.hostAddress}: $message")
                            
                            if (message.startsWith("ROTATOR_SERVER:")) {
                                val port = message.substringAfter(":").trim().toIntOrNull() ?: 9999
                                val ip = packet.address?.hostAddress
                                
                                if (ip != null) {
                                    Log.i(TAG, "Server found at $ip:$port")
                                    serverIp = ip
                                    connectToServer(ip, port)
                                    return@withTimeout
                                }
                            }
                        } catch (e: SocketTimeoutException) {
                            Log.d(TAG, "Socket receive timeout, still waiting...")
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "Discovery timed out.")
                connectionState = ConnectionState.ERROR
                errorMessage = "No server found on local network"
            } catch (e: Exception) {
                Log.e(TAG, "Discovery error", e)
                connectionState = ConnectionState.ERROR
                errorMessage = "Discovery error: ${e.message}"
            } finally {
                Log.d(TAG, "Discovery job cleanup.")
                udpSocket?.close()
                releaseMulticastLock()
            }
        }
    }
    
    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
                Log.d(TAG, "Multicast lock released.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing multicast lock", e)
        }
        multicastLock = null
    }

    // ===========================
    // MANUAL CONNECTION
    // ===========================
    
    fun connectManual(ip: String, port: Int = 9999) {
        if (connectionState == ConnectionState.CONNECTING) return
        Log.d(TAG, "Attempting manual connection to $ip:$port")
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
                Log.i(TAG, "Connecting to server at $ip:$port")
                
                val newSocket = Socket()
                try {
                    newSocket.connect(InetSocketAddress(ip, port), 5000) // 5 sec timeout
                } catch (e: Exception) {
                    throw IOException("Socket connect failed: ${e.message}")
                }
                socket = newSocket
                Log.i(TAG, "Socket connected successfully.")
                
                val newReader = BufferedReader(InputStreamReader(newSocket.getInputStream()))
                reader = newReader
                
                // 1. Read greeting
                val greeting = try {
                    withTimeout(3000) {
                        newReader.readLine() 
                    }
                } catch (e: Exception) {
                    throw IOException("Failed to read greeting: ${e.message}")
                }
                Log.d(TAG, "Received greeting: $greeting")

                // 2. Authenticate
                val sendResult = sendCommandSync("$PASSWORD|PING")
                if (!sendResult) {
                    throw IOException("Failed to send auth command")
                }
                Log.d(TAG, "Sent auth command.")
                
                // 3. Wait for AUTH_OK
                val response = try {
                    withTimeout(3000) {
                        newReader.readLine()
                    }
                } catch (e: Exception) {
                    throw IOException("Failed to read auth response: ${e.message}")
                }
                Log.d(TAG, "Received auth response: $response")
                
                if (response == "AUTH_OK") {
                    Log.i(TAG, "Authentication successful.")
                    connectionState = ConnectionState.CONNECTED
                    startReader()
                    startStallCheck()
                } else {
                    throw IOException("Auth failed: server said '$response'")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                disconnect()
                connectionState = ConnectionState.ERROR
                errorMessage = e.message ?: "Unknown connection error"
            }
        }
    }
    
    // ===========================
    // MESSAGE READER
    // ===========================
    
    private fun startReader() {
        readerJob?.cancel()
        readerJob = viewModelScope.launch(Dispatchers.IO) {
            Log.d(TAG, "Starting message reader.")
            try {
                while (isActive && socket?.isConnected == true) {
                    val line = reader?.readLine() ?: break
                    Log.d(TAG, "From server: $line")
                    processServerMessage(line)
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Connection lost", e)
                    disconnect()
                    errorMessage = "Connection lost: ${e.message}"
                }
            }
            Log.d(TAG, "Message reader stopped.")
        }
    }
    
    private fun processServerMessage(message: String) {
        when {
            message.startsWith("POS:") -> {
                val heading = message.substringAfter("POS:").toIntOrNull() ?: currentHeading
                
                // Stall detection
                if (isRotating && heading != lastHeading) {
                    lastHeadingChangeTime = System.currentTimeMillis()
                    showStallAlert = false
                }
                
                lastHeading = heading
                currentHeading = heading
            }
            message.startsWith("ARDUINO:CONNECTED") -> {
                isArduinoConnected = true
            }
            message.startsWith("ARDUINO:DISCONNECTED") -> {
                isArduinoConnected = false
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
                        isRotating = false // Stop trying to rotate
                    }
                }
                delay(500)
            }
        }
    }
    
    // ===========================
    // COMMANDS
    // ===========================
    
    fun requestStatus() {
        sendCommand("$PASSWORD|STATUS")
    }

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
            sendCommandSync(command)
        }
    }

    private suspend fun sendCommandSync(command: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "To server: $command")
                socket?.getOutputStream()?.write("$command\n".toByteArray())
                socket?.getOutputStream()?.flush()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Send failed", e)
                disconnect()
                errorMessage = "Send failed: ${e.message}"
                false
            }
        }
    }
    
    // ===========================
    // LIFECYCLE
    // ===========================
    
    fun disconnect() {
        Log.d(TAG, "Disconnecting...")
        discoveryJob?.cancel()
        readerJob?.cancel()
        stallCheckJob?.cancel()
        releaseMulticastLock()
        
        try {
            socket?.close()
        } catch (_: Exception) {
            // Ignore
        }
        
        socket = null
        reader = null
        connectionState = ConnectionState.DISCONNECTED
        isRotating = false
        showStallAlert = false
        isArduinoConnected = false
    }
    
    fun dismissError() {
        errorMessage = null
        if (connectionState == ConnectionState.ERROR) {
            connectionState = ConnectionState.DISCONNECTED
        }
    }
    
    fun dismissStallAlert() {
        showStallAlert = false
    }
    
    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "ViewModel cleared, disconnecting.")
        disconnect()
    }
}
