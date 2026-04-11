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
        const val TCP_PORT = 9999
        const val PASSWORD = "MyRotatorPassword" // Must match server!
        const val STALL_TIMEOUT_MS = 3000L
        const val PING_INTERVAL_MS = 5000L
        const val ARRIVAL_DEGREES = 5  // Stop within this many degrees of target to allow for motor coasting
    }
    
    // UI State
    var connectionState by mutableStateOf(ConnectionState.DISCONNECTED)
        private set
    
    var currentHeading by mutableStateOf(0)
        private set
    
    var targetHeading by mutableStateOf<Int?>(null)
        private set

    var isRotatingCW by mutableStateOf(false)
        private set

    var isRotatingCCW by mutableStateOf(false)
        private set

    val isRotating: Boolean get() = isRotatingCW || isRotatingCCW
    
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
    private var rotationControlJob: Job? = null
    private var pingJob: Job? = null
    
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
                                                        // message may be one of two formats now:
                                // 1. ROTATOR_SERVER:PORT              (legacy)
                                // 2. ROTATOR_SERVER:<IP>:PORT         (new, preferred)
                                // parsing gracefully handles both cases to work on existing
                                // installations and with the updated server code.
                                val sourceIp = packet.address?.hostAddress
                                val payload = message.substringAfter(":")
                                val parts = payload.split(":")
                                val (ipCandidate, portCandidate) = when (parts.size) {
                                    1 -> Pair(sourceIp, parts[0])
                                    else -> Pair(parts[0], parts.getOrNull(1) ?: "")
                                }
                                val port = portCandidate.trim().toIntOrNull() ?: TCP_PORT
                                val ip = ipCandidate?.takeIf { it.isNotBlank() }
                                // If the server embedded a different IP than the packet source,
                                // keep the source as a fallback in case the embedded IP is wrong
                                val fallbackIp = sourceIp?.takeIf { it != ip }

                                if (ip != null) {
                                    Log.i(TAG, "Server found at $ip:$port (source: $sourceIp)")
                                    serverIp = ip
                                    connectToServer(ip, port, fallbackIp)
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
    
    private fun connectToServer(ip: String, port: Int, fallbackIp: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            connectionState = ConnectionState.CONNECTING
            errorMessage = null
            Log.i(TAG, "Starting connection sequence to $ip:$port")

            val success = try {
                attemptConnection(ip, port)
            } catch (e: Exception) {
                if (fallbackIp != null) {
                    Log.w(TAG, "Primary IP $ip failed, trying fallback $fallbackIp")
                    serverIp = fallbackIp
                    try {
                        attemptConnection(fallbackIp, port)
                    } catch (e2: Exception) {
                        Log.e(TAG, "Fallback IP $fallbackIp also failed", e2)
                        errorMessage = e2.message ?: "Unknown connection error"
                        false
                    }
                } else {
                    Log.e(TAG, "Connection attempts failed", e)
                    errorMessage = e.message ?: "Unknown connection error"
                    false
                }
            }

            if (success) {
                connectionState = ConnectionState.CONNECTED
                startReader()
                startStallCheck()
                startPingLoop()
            } else {
                connectionState = ConnectionState.ERROR
            }
        }
    }
    
    // ===========================
    // CONNECTION HELPER

    /**
     * Try to open a socket, read the greeting, perform authentication.  If the
     * initial attempt fails we will retry a few times with short delays; the
     * server may be just starting up or the network stack may not be ready.
     *
     * Throws an exception if all attempts fail.
     */
    private suspend fun attemptConnection(ip: String, port: Int): Boolean {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                Log.i(TAG, "connection attempt ${attempt + 1} to $ip:$port")
                val newSocket = Socket()
                newSocket.connect(InetSocketAddress(ip, port), 5000)
                socket = newSocket
                Log.i(TAG, "Socket connected successfully.")

                val newReader = BufferedReader(InputStreamReader(newSocket.getInputStream()))
                reader = newReader

                // read greeting and validate format
                val greeting = withTimeout(3000) { newReader.readLine() }
                Log.d(TAG, "Received greeting: $greeting")
                if (greeting == null || !greeting.startsWith("ROTATOR_SERVER")) {
                    throw IOException("Invalid greeting from server: '$greeting'")
                }

                // authenticate (PING is a cheap command)
                if (!sendCommandSync("$PASSWORD|PING")) {
                    throw IOException("Failed to send auth command")
                }

                val response = withTimeout(3000) { newReader.readLine() }
                Log.d(TAG, "Received auth response: $response")
                if (response != "AUTH_OK") {
                    throw IOException("Auth failed: server said '$response'")
                }

                return true
            } catch (e: Exception) {
                lastError = e
                Log.i(TAG, "connection attempt ${attempt + 1} failed: ${e.message}")
                try {
                    socket?.close()
                } catch (_: Exception) {}
                socket = null
                if (attempt < 2) delay(1000)
            }
        }
        throw lastError ?: IOException("unknown error")
    }

    // ===========================
    // MESSAGE READER
    // ===========================
    
    private fun startReader() {
        readerJob?.cancel()
        readerJob = viewModelScope.launch(Dispatchers.IO) {
            Log.d(TAG, "Starting message reader.")
            try {
                while (isActive) {
                    val line = reader?.readLine() ?: break
                    Log.d(TAG, "From server: $line")
                    processServerMessage(line)
                }
                if (isActive && connectionState == ConnectionState.CONNECTED) {
                    Log.i(TAG, "Reader loop ended (EOF)")
                    handleDisconnect("Connection lost")
                }
            } catch (e: Exception) {
                if (isActive && connectionState == ConnectionState.CONNECTED) {
                    Log.e(TAG, "Connection lost during read", e)
                    handleDisconnect("Connection lost")
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
                
                // Target arrival check
                targetHeading?.let { target ->
                    val arrived = Math.abs(heading - target) <= ARRIVAL_DEGREES
                    val overshoot = (isRotatingCW && heading > target) || (isRotatingCCW && heading < target)
                    if (arrived || overshoot) {
                        stopRotation()
                        targetHeading = null
                    }
                }
            }
            message.startsWith("ARDUINO:CONNECTED") -> {
                isArduinoConnected = true
            }
            message.startsWith("ARDUINO:DISCONNECTED") -> {
                isArduinoConnected = false
            }
            message.startsWith("ERROR:STALL") -> {
                isRotatingCW = false
                isRotatingCCW = false
                showStallAlert = true
                targetHeading = null
            }
            message.uppercase().startsWith("ERROR:HEARTBEAT_TIMEOUT") -> {
                // Suppress heartbeat timeout — Arduino fires this during rotation when
                // it hasn't heard from the server recently; not actionable for the user.
                Log.d(TAG, "Suppressing HEARTBEAT_TIMEOUT error.")
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
                        isRotatingCW = false // Stop trying to rotate
                        isRotatingCCW = false
                        targetHeading = null
                    }
                }
                delay(500)
            }
        }
    }

    // ===========================
    // PING LOOP (To detect server offline)
    // ===========================
    
    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive && connectionState == ConnectionState.CONNECTED) {
                delay(PING_INTERVAL_MS)
                if (!sendCommandSync("$PASSWORD|PING")) {
                    // Send failed, handle as disconnect
                    break
                }
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
        targetHeading = null // Override automatic target
        rotationControlJob?.cancel()
        
        if (isRotatingCCW) return 
        sendCommand("$PASSWORD|CW")
        isRotatingCW = true
        lastHeadingChangeTime = System.currentTimeMillis()
        showStallAlert = false
    }
    
    fun startRotateCCW() {
        targetHeading = null // Override automatic target
        rotationControlJob?.cancel()
        
        if (isRotatingCW) return 
        sendCommand("$PASSWORD|CCW")
        isRotatingCCW = true
        lastHeadingChangeTime = System.currentTimeMillis()
        showStallAlert = false
    }
    
    fun setTargetHeading(heading: Int) {
        targetHeading = heading
        rotationControlJob?.cancel()
        rotationControlJob = viewModelScope.launch {
            while (isActive && targetHeading != null) {
                val current = currentHeading
                val target = targetHeading!!

                // Within arrival window — stop and let motor coast to target
                if (Math.abs(current - target) <= ARRIVAL_DEGREES) {
                    stopRotation()
                    targetHeading = null
                    break
                }

                // Overshoot: motor passed through the target in the direction of travel
                if ((isRotatingCW && current > target) || (isRotatingCCW && current < target)) {
                    stopRotation()
                    targetHeading = null
                    break
                }

                // Direction: never cross the 0/360 hard stop —
                // go CW if target is numerically higher, CCW if lower
                if (target > current) {
                    if (!isRotatingCW) {
                        sendCommandSync("$PASSWORD|CW")
                        isRotatingCW = true
                        isRotatingCCW = false
                        lastHeadingChangeTime = System.currentTimeMillis()
                    }
                } else {
                    if (!isRotatingCCW) {
                        sendCommandSync("$PASSWORD|CCW")
                        isRotatingCCW = true
                        isRotatingCW = false
                        lastHeadingChangeTime = System.currentTimeMillis()
                    }
                }
                delay(200)
            }
        }
    }
    
    fun stopRotation() {
        rotationControlJob?.cancel()
        sendCommand("$PASSWORD|STOP")
        isRotatingCW = false
        isRotatingCCW = false
        showStallAlert = false
        // Note: we don't clear targetHeading here to allow the UI to keep showing it 
        // until processServerMessage stops it or a new target is set
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
                val out = socket?.getOutputStream()
                if (out == null) {
                    if (connectionState == ConnectionState.CONNECTED) {
                        handleDisconnect("Connection lost")
                    }
                    return@withContext false
                }
                out.write("$command\n".toByteArray())
                out.flush()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Send failed", e)
                if (connectionState == ConnectionState.CONNECTED) {
                    handleDisconnect("Connection lost")
                }
                false
            }
        }
    }
    
    // ===========================
    // LIFECYCLE
    // ===========================
    
    private fun handleDisconnect(error: String? = null) {
        viewModelScope.launch(Dispatchers.Main) {
            if (connectionState != ConnectionState.DISCONNECTED) {
                disconnect()
                errorMessage = error
                if (error != null) {
                    connectionState = ConnectionState.ERROR
                }
            }
        }
    }

    fun disconnect() {
        Log.d(TAG, "Disconnecting...")
        discoveryJob?.cancel()
        readerJob?.cancel()
        stallCheckJob?.cancel()
        rotationControlJob?.cancel()
        pingJob?.cancel()
        releaseMulticastLock()
        
        try {
            socket?.close()
        } catch (_: Exception) {
            // Ignore
        }
        
        socket = null
        reader = null
        connectionState = ConnectionState.DISCONNECTED
        isRotatingCW = false
        isRotatingCCW = false
        showStallAlert = false
        isArduinoConnected = false
        targetHeading = null
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
