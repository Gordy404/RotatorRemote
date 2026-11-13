package com.rotator.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.Charset
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.currentCoroutineContext

class RotatorService : Service() {

	data class ServiceState(
		val currentHeading: Int = 0,
		val isRotating: Boolean = false,
		val isRotatingCCW: Boolean = false,
		val isArduinoConnected: Boolean = false,
		val clientCount: Int = 0,
		val localIp: String = "0.0.0.0",
		val lastMessage: String = "",
		val heartbeatToggle: Boolean = false,
		val clientVersion: String = "",
		val targetHeading: Int? = null,
		// The heading a connected Remote client has requested, purely for
		// display (the client drives its own rotation) — mirrors the green
		// target pointer shown on the Remote app's own compass.
		val remoteTargetHeading: Int? = null,
		val isCalibrating: Boolean = false,
		// True once the Arduino confirms a calibration is actually saved in
		// EEPROM (vs. still running on hardcoded defaults).
		val isCalibrated: Boolean = false,
		val calRawPosition: Int? = null,
		val calMinRaw: Int? = null,
		val calMaxRaw: Int? = null,
		val calMessage: String = "",
		// Set only for genuine error conditions (Arduino ERROR:, USB/TCP/UDP
		// failures, stall) — the Main screen shows this as a dismissible popup.
		val errorMessage: String? = null
	)

	companion object {
		private const val TAG = "RotatorService"
		const val CHANNEL_ID = "RotatorServiceChannel"
		const val NOTIFICATION_ID = 1
		const val UDP_BROADCAST_PORT = 8888
		const val TCP_SERVER_PORT = 9999
		const val HEARTBEAT_INTERVAL = 1000L
		const val PASSWORD = "MyRotatorPassword"

		const val ACTION_CW = "com.rotator.server.ACTION_CW"
		const val ACTION_CCW = "com.rotator.server.ACTION_CCW"
		const val ACTION_STOP = "com.rotator.server.ACTION_STOP"
		const val ACTION_SET_TARGET = "com.rotator.server.ACTION_SET_TARGET"
		const val ACTION_CANCEL_TARGET = "com.rotator.server.ACTION_CANCEL_TARGET"
		const val ACTION_BROADCAST_SETUP = "com.rotator.server.ACTION_BROADCAST_SETUP"
		const val ACTION_CAL_START = "com.rotator.server.ACTION_CAL_START"
		const val ACTION_CAL_SET_CW_LIMIT = "com.rotator.server.ACTION_CAL_SET_CW_LIMIT"
		const val ACTION_CAL_SET_CCW_LIMIT = "com.rotator.server.ACTION_CAL_SET_CCW_LIMIT"
		const val ACTION_CAL_SAVE = "com.rotator.server.ACTION_CAL_SAVE"
		const val ACTION_CAL_CANCEL = "com.rotator.server.ACTION_CAL_CANCEL"
		const val ACTION_CAL_RESET = "com.rotator.server.ACTION_CAL_RESET"
		const val ACTION_DISMISS_ERROR = "com.rotator.server.ACTION_DISMISS_ERROR"
		const val ACTION_USB_PERMISSION = "com.rotator.server.USB_PERMISSION"
		const val EXTRA_TARGET_HEADING = "target_heading"
	}

	private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

	private var usbLayer: UsbLayer? = null

	@Volatile private var currentHeading = 0
	@Volatile private var isRotating = false
	@Volatile private var isRotatingCCW = false
	@Volatile private var isArduinoConnected = false
	@Volatile private var cachedIp = "0.0.0.0"
	@Volatile private var heartbeatToggle = false
	@Volatile private var lastMessage = ""
	@Volatile private var errorMessage: String? = null
	@Volatile private var activeRotationCommand = ""  // "CW", "CCW", or ""
	@Volatile private var connectedClientVersion = ""
	@Volatile private var targetHeadingValue: Int? = null
	private var targetHeadingJob: kotlinx.coroutines.Job? = null
	@Volatile private var remoteTargetHeadingValue: Int? = null

	@Volatile private var isCalibrating = false
	@Volatile private var isCalibrated = false
	@Volatile private var calRawPosition: Int? = null
	@Volatile private var calMinRaw: Int? = null
	@Volatile private var calMaxRaw: Int? = null
	@Volatile private var calMessage: String = ""

	// All commands to the Arduino go through this channel so writes to the
	// serial port are strictly ordered and never overlap. Two independent
	// writers racing on usbLayer.write() was causing corrupted/interleaved
	// commands on the wire (e.g. a manual STOP colliding with the heartbeat's
	// resend of the active rotation command), which the Arduino saw as
	// garbage or out-of-order commands — producing the pulsing/stutter.
	private val arduinoCommandChannel = Channel<String>(Channel.UNLIMITED)

	private var udpSocket: DatagramSocket? = null
	private var tcpServerSocket: ServerSocket? = null
	private val connectedClients = CopyOnWriteArrayList<ClientHandler>()

	private var wakeLock: PowerManager.WakeLock? = null
	private var wifiLock: WifiManager.WifiLock? = null

	override fun onCreate() {
		super.onCreate()
		Log.i(TAG, "Service onCreate")
		acquireLocks()
		createNotificationChannel()

		val notification = createNotification("Starting...")
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
			startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
		} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			startForeground(NOTIFICATION_ID, notification)
		}

		serviceScope.launch {
			try {
				connectionLoop()
			} catch (e: Exception) {
				Log.e(TAG, "connectionLoop crashed", e)
			}
		}
		serviceScope.launch {
			try {
				startUdpBroadcast()
			} catch (e: Exception) {
				Log.e(TAG, "startUdpBroadcast crashed", e)
			}
		}
		serviceScope.launch {
			try {
				startTcpServer()
			} catch (e: Exception) {
				Log.e(TAG, "startTcpServer crashed", e)
			}
		}
		serviceScope.launch {
			try {
				heartbeatLoop()
			} catch (e: Exception) {
				Log.e(TAG, "heartbeatLoop crashed", e)
			}
		}
		serviceScope.launch {
			try {
				updateStateLoop()
			} catch (e: Exception) {
				Log.e(TAG, "updateStateLoop crashed", e)
			}
		}
		serviceScope.launch {
			try {
				arduinoWriterLoop()
			} catch (e: Exception) {
				Log.e(TAG, "arduinoWriterLoop crashed", e)
			}
		}
	}

	private fun acquireLocks() {
		val powerManager = getSystemService(POWER_SERVICE) as PowerManager
		wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Rotator:ServerWakeLock").apply {
			acquire()
		}

		val wifiManager = getSystemService(WIFI_SERVICE) as WifiManager
		wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Rotator:WifiLock").apply {
			acquire()
		}
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		when (intent?.action) {
			ACTION_CW -> { cancelTargetHeading(); startRotationCW("manualButton") }
			ACTION_CCW -> { cancelTargetHeading(); startRotationCCW("manualButton") }
			ACTION_STOP -> { cancelTargetHeading(); stopRotation("manualButton") }
			ACTION_SET_TARGET -> {
				val heading = intent.getIntExtra(EXTRA_TARGET_HEADING, -1)
				if (heading in 0..359) setTargetHeading(heading)
			}
			ACTION_CANCEL_TARGET -> cancelTargetHeading()
			ACTION_BROADCAST_SETUP -> broadcastSetup()
			ACTION_CAL_START -> startCalibration()
			ACTION_CAL_SET_CW_LIMIT -> sendToArduino("CAL_CW")
			ACTION_CAL_SET_CCW_LIMIT -> sendToArduino("CAL_CCW")
			ACTION_CAL_SAVE -> sendToArduino("CAL_SAVE")
			ACTION_CAL_CANCEL -> sendToArduino("CAL_CANCEL")
			ACTION_CAL_RESET -> sendToArduino("CAL_RESET")
			ACTION_DISMISS_ERROR -> { errorMessage = null; updateState() }
		}
		return START_STICKY
	}

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onDestroy() {
		super.onDestroy()
		Log.i(TAG, "Service onDestroy")
		serviceScope.cancel()
		arduinoCommandChannel.close()
		usbLayer?.close()
		udpSocket?.close()
		tcpServerSocket?.close()
		connectedClients.forEach { it.close() }

		wifiLock?.let { if (it.isHeld) it.release() }
		wakeLock?.let { if (it.isHeld) it.release() }

		MainActivity.serviceState.value = null
	}

	private suspend fun updateStateLoop() {
		while (currentCoroutineContext().isActive) {
			cachedIp = getLocalIpAddress() ?: "0.0.0.0"
			updateState()
			delay(2000)
		}
	}

	private fun updateState(message: String = "") {
		if (message.isNotEmpty()) lastMessage = message
		MainActivity.serviceState.value = ServiceState(
			currentHeading = currentHeading,
			isRotating = isRotating,
			isRotatingCCW = isRotatingCCW,
			isArduinoConnected = isArduinoConnected,
			clientCount = connectedClients.size,
			localIp = cachedIp,
			lastMessage = lastMessage,
			heartbeatToggle = heartbeatToggle,
			clientVersion = connectedClientVersion,
			targetHeading = targetHeadingValue,
			remoteTargetHeading = remoteTargetHeadingValue,
			isCalibrating = isCalibrating,
			isCalibrated = isCalibrated,
			calRawPosition = calRawPosition,
			calMinRaw = calMinRaw,
			calMaxRaw = calMaxRaw,
			calMessage = calMessage,
			errorMessage = errorMessage
		)
	}

	private fun getLocalIpAddress(): String? {
		return try {
			val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
			for (intf in interfaces) {
				if (intf.isLoopback || !intf.isUp) continue
				val addrs = Collections.list(intf.inetAddresses)
				for (addr in addrs) {
					if (!addr.isLoopbackAddress && addr is Inet4Address) {
						return addr.hostAddress
					}
				}
			}
			null
		} catch (_: Exception) {
			null
		}
	}

	private suspend fun connectionLoop() {
		withContext(Dispatchers.IO) {
			while (coroutineContext.isActive) {
				if (isArduinoConnected) {
					delay(2000)
					continue
				}

				try {
					val manager = getSystemService(USB_SERVICE) as UsbManager
					val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)

					if (availableDrivers.isEmpty()) {
						isArduinoConnected = false
						broadcastToClients("ARDUINO:DISCONNECTED\n")
						updateNotification("No USB devices found")
						delay(5000)
						continue
					}

					val driver = availableDrivers[0]
					val device = driver.device

					if (!manager.hasPermission(device)) {
						updateNotification("Requesting USB Permission...")
						val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
							PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
						} else {
							PendingIntent.FLAG_UPDATE_CURRENT
						}
						val usbPermissionIntent = PendingIntent.getBroadcast(
							this@RotatorService, 0, Intent(ACTION_USB_PERMISSION), flags
						)
						manager.requestPermission(device, usbPermissionIntent)
						delay(5000)
						continue
					}

					val connection = manager.openDevice(device)
						?: throw IOException("Failed to open device")

					val port = driver.ports[0].apply {
						open(connection)
						setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
						dtr = true
						rts = true
					}

					usbLayer = RealUsbLayer(port)
					isArduinoConnected = true
					broadcastToClients("ARDUINO:CONNECTED\n")
					updateState()
					updateNotification("Connected to Arduino")

					runReader()

				} catch (e: Exception) {
					isArduinoConnected = false
					broadcastToClients("ARDUINO:DISCONNECTED\n")
					errorMessage = "USB Error: ${e.message}"
					updateState()
					updateNotification(errorMessage!!)
					usbLayer?.close()
					usbLayer = null
					delay(5000)
				}
			}
		}
	}

	private suspend fun runReader() {
		val buffer = ByteArray(1024)
		var accumulated = ""
		while (currentCoroutineContext().isActive && isArduinoConnected) {
			val len = try {
				usbLayer?.read(buffer, 1000) ?: 0
			} catch (e: Exception) {
				isArduinoConnected = false
				broadcastToClients("ARDUINO:DISCONNECTED\n")
				updateState()
				throw e
			}

			if (len > 0) {
				accumulated += String(buffer, 0, len, Charset.forName("UTF-8"))
				while ("\n" in accumulated) {
					val line = accumulated.substringBefore("\n").trim()
					accumulated = accumulated.substringAfter("\n")
					processArduinoMessage(line)
				}
			}
		}
	}

	private fun processArduinoMessage(message: String) {
		heartbeatToggle = !heartbeatToggle
		when {
			message.startsWith("POS:") -> {
				currentHeading = message.substringAfter("POS:").toIntOrNull() ?: 0
				broadcastToClients("POS:$currentHeading\n")
				updateState()
			}
			message.startsWith("ACK:") -> {
				broadcastToClients("$message\n")
				updateState()
			}
			message.startsWith("ERROR:") -> {
				Log.w(TAG, "Arduino: $message")
				errorMessage = "Arduino: $message"
				updateNotification(errorMessage!!)
				broadcastToClients("$message\n")
				updateState(message)
			}
			message == "READY" -> {
				Log.w(TAG, "Arduino sent READY (this means it just booted/reset)")
				updateNotification("Arduino Ready - Heading: $currentHeading°")
				isArduinoConnected = true
				broadcastToClients("ARDUINO:CONNECTED\n")
				updateState("Arduino Ready")
			}
			message.startsWith("CAL_POS:") -> {
				calRawPosition = message.substringAfter("CAL_POS:").toIntOrNull()
				updateState()
			}
			message == "CAL_MODE:ACTIVE" -> {
				isCalibrating = true
				calMessage = "Jog CW/CCW to each end stop, then capture it"
				updateState()
			}
			message.startsWith("CAL_CW:") -> {
				calMaxRaw = message.substringAfter("CAL_CW:").toIntOrNull()
				calMessage = "CW limit captured"
				updateState()
			}
			message.startsWith("CAL_CCW:") -> {
				calMinRaw = message.substringAfter("CAL_CCW:").toIntOrNull()
				calMessage = "CCW limit captured"
				updateState()
			}
			message == "CAL_SAVED" -> {
				isCalibrating = false
				isCalibrated = true
				calMessage = "Calibration saved"
				updateState()
			}
			message == "CAL_CANCELLED" -> {
				isCalibrating = false
				calMinRaw = null
				calMaxRaw = null
				calMessage = "Calibration cancelled"
				updateState()
			}
			// Sent by the Arduino on boot (and again after CAL_CANCEL, which
			// reloads from EEPROM) — tells us whether a calibration has ever
			// actually been saved, or it's still running on defaults.
			message == "CAL_LOADED" -> {
				isCalibrated = true
				updateState()
			}
			message == "CAL_DEFAULT" -> {
				isCalibrated = false
				updateState()
			}
			message.startsWith("CAL_MODE:") || message.startsWith("CAL_") -> {
				// Instructional text — no state change needed, just avoid
				// falling into the unknown branch.
				updateState()
			}
			else -> {
				updateState()
			}
		}
	}

	// True only while the *currently active* rotation was started by a remote
	// TCP client. The heartbeat watchdog uses this to decide whether "no
	// clients connected" means anything — rotation driven by the server's own
	// local UI (manual buttons, tap-to-set-heading) never had a remote client
	// in the first place, so it must not be auto-stopped for that reason.
	@Volatile private var activeRotationNeedsClientWatchdog = false

	fun startRotationCW(source: String = "unknown", requiresClientWatchdog: Boolean = false) {
		Log.i(TAG, "startRotationCW from=$source")
		activeRotationCommand = "CW"
		activeRotationNeedsClientWatchdog = requiresClientWatchdog
		sendToArduino("CW")
		isRotating = true
		isRotatingCCW = false
		updateState()
	}

	fun startRotationCCW(source: String = "unknown", requiresClientWatchdog: Boolean = false) {
		Log.i(TAG, "startRotationCCW from=$source")
		activeRotationCommand = "CCW"
		activeRotationNeedsClientWatchdog = requiresClientWatchdog
		sendToArduino("CCW")
		isRotating = true
		isRotatingCCW = true
		updateState()
	}

	fun stopRotation(source: String = "unknown") {
		Log.i(TAG, "stopRotation from=$source")
		activeRotationCommand = ""
		sendToArduino("STOP")
		isRotating = false
		updateState()
	}

	fun setTargetHeading(heading: Int) {
		targetHeadingValue = heading
		updateState()
		targetHeadingJob?.cancel()
		targetHeadingJob = serviceScope.launch { runTargetHeadingAutomation() }
	}

	fun cancelTargetHeading() {
		targetHeadingJob?.cancel()
		targetHeadingValue = null
		stopRotation("cancelTargetHeading")
	}

	// Display-only: a connected Remote client informs the server of the
	// heading it's driving toward so the server's own compass can show the
	// same green target pointer. Unlike setTargetHeading(), this never starts
	// server-side rotation automation — the client is already driving via its
	// own CW/CCW commands, so running our own automation on top would fight it.
	fun setRemoteTargetHeading(heading: Int?) {
		remoteTargetHeadingValue = heading
		updateState()
	}

	private suspend fun runTargetHeadingAutomation() {
		while (currentCoroutineContext().isActive) {
			val target = targetHeadingValue ?: break
			val current = currentHeading
			val diff = ((target - current + 540) % 360) - 180
			if (kotlin.math.abs(diff) <= 5) {
				stopRotation("targetHeadingAutomation-arrived")
				targetHeadingValue = null
				updateState()
				break
			}
			if (diff > 0) {
				if (activeRotationCommand != "CW") startRotationCW("targetHeadingAutomation")
			} else {
				if (activeRotationCommand != "CCW") startRotationCCW("targetHeadingAutomation")
			}
			delay(200)
		}
	}

	fun sendToArduino(command: String) {
		arduinoCommandChannel.trySend(command)
	}

	fun startCalibration() {
		cancelTargetHeading()
		stopRotation("calibrationStart")
		isCalibrating = true
		calMinRaw = null
		calMaxRaw = null
		calMessage = "Calibration started — jog to each end stop and capture it"
		sendToArduino("CAL_START")
		updateState()
	}

	private suspend fun arduinoWriterLoop() {
		for (command in arduinoCommandChannel) {
			try {
				usbLayer?.write("$command\n".toByteArray(), 1000)
			} catch (e: Exception) {
				Log.e(TAG, "write '$command' failed", e)
				errorMessage = "Send error: ${e.message}"
				updateNotification(errorMessage!!)
				updateState()
			}
		}
	}

	@Suppress("DEPRECATION") // dhcpInfo deprecated at API 31; fallback to 255.255.255.255 handles modern Android
	private fun getBroadcastAddress(): InetAddress? {
		return try {
			val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
			val dhcpInfo = wifiManager.dhcpInfo ?: return null

			// Handle Little Endian vs Big Endian in DhcpInfo (common Android bug/behavior)
			val ip = dhcpInfo.ipAddress
			val mask = dhcpInfo.netmask

			if (ip == 0 || mask == 0) return null

			val broadcast = (ip and mask) or mask.inv()
			val quads = ByteArray(4)
			for (k in 0..3) {
				quads[k] = (broadcast shr (k * 8)).toByte()
			}
			InetAddress.getByAddress(quads)
		} catch (e: Exception) {
			Log.e(TAG, "Error calculating broadcast address", e)
			null
		}
	}

	private suspend fun startUdpBroadcast() {
		withContext(Dispatchers.IO) {
			try {
				// Ensure the TCP server has finished binding before broadcasting;
				// otherwise a client might attempt to connect to a port not yet listening.
				while (tcpServerSocket == null || tcpServerSocket?.isBound == false) {
					delay(100)
				}
				udpSocket = DatagramSocket().apply {
					broadcast = true
				}
				Log.i(TAG, "UDP broadcast started on port $UDP_BROADCAST_PORT")
				while (coroutineContext.isActive) {
					try {
						val broadcastAddress = getBroadcastAddress() ?: InetAddress.getByName("255.255.255.255")
						// Include the current IP in the packet so discovery doesn't have to
						// guess based on the packet source address.
						val localIp = cachedIp.takeIf { it != "0.0.0.0" } ?: getLocalIpAddress() ?: ""
						val message = if (localIp.isNotBlank()) {
							"ROTATOR_SERVER:$localIp:$TCP_SERVER_PORT"
						} else {
							"ROTATOR_SERVER:$TCP_SERVER_PORT" // fallback
						}
						val buffer = message.toByteArray()
						Log.d(TAG, "UDP broadcast message='$message' to $broadcastAddress")

						// Send to calculated subnet broadcast
						udpSocket?.send(DatagramPacket(buffer, buffer.size, broadcastAddress, UDP_BROADCAST_PORT))

						// Also send to global broadcast for maximum compatibility
						if (broadcastAddress.hostAddress != "255.255.255.255") {
							udpSocket?.send(DatagramPacket(buffer, buffer.size, InetAddress.getByName("255.255.255.255"), UDP_BROADCAST_PORT))
						}
					} catch (e: Exception) {
						Log.e(TAG, "UDP Send failed", e)
					}
					delay(1500)
				}
			} catch (e: Exception) {
				Log.e(TAG, "UDP broadcast error", e)
				errorMessage = "UDP error: ${e.message}"
				updateNotification(errorMessage!!)
				updateState()
			} finally {
				udpSocket?.close()
			}
		}
	}

	private suspend fun startTcpServer() {
		withContext(Dispatchers.IO) {
			Log.i(TAG, "TCP Server: Attempting to start on port $TCP_SERVER_PORT")
			try {
				// Explicitly bind to 0.0.0.0 (IPv4 all interfaces)
				tcpServerSocket = ServerSocket()
				tcpServerSocket?.reuseAddress = true
				tcpServerSocket?.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), TCP_SERVER_PORT))

				updateNotification("TCP Server listening on :$TCP_SERVER_PORT")
				Log.i(TAG, "TCP Server: Listening on ${tcpServerSocket?.localSocketAddress}")
				while (coroutineContext.isActive) {
					try {
						val socket = tcpServerSocket?.accept()
						socket?.let {
							Log.i(TAG, "TCP Server: Accepted connection from ${it.inetAddress}")
							val handler = ClientHandler(it, this@RotatorService)
							connectedClients.add(handler)
							serviceScope.launch {
								updateState()
								handler.handle()
							}
						}
					} catch (e: Exception) {
						if (coroutineContext.isActive) {
							Log.e(TAG, "TCP Server: Accept error", e)
							errorMessage = "Accept error: ${e.message}"
							updateNotification(errorMessage!!)
							updateState()
						}
					}
				}
			} catch (e: Exception) {
				Log.e(TAG, "TCP Server: Fatal error", e)
				errorMessage = "TCP error: ${e.message}"
				updateNotification(errorMessage!!)
				updateState()
			} finally {
				tcpServerSocket?.close()
			}
		}
	}

	inner class ClientHandler(
		private val socket: Socket,
		private val service: RotatorService
	) {
		@Volatile private var authenticated = false

		suspend fun handle() {
			withContext(Dispatchers.IO) {
				try {
					val input = socket.getInputStream().bufferedReader()
					val output = socket.getOutputStream()
					// Greeting includes a colon to satisfy older clients; version
					// string follows after the colon.
					output.write("ROTATOR_SERVER:1.0\n".toByteArray())
					output.flush()

					while (coroutineContext.isActive && !socket.isClosed) {
						val line = try {
							input.readLine()
						} catch (e: IOException) {
							Log.w(TAG, "Client connection read error", e)
							break
						}

						if (line == null) {
							Log.i(TAG, "Client disconnected gracefully.")
							break
						}

						processClientCommand(line.trim(), output)
					}
				} catch (e: Exception) {
					Log.e(TAG, "Client handler error", e)
				} finally {
					close()
				}
			}
		}

		private fun processClientCommand(command: String, output: java.io.OutputStream) {
			val strippedCommand = if (command.startsWith("$PASSWORD|")) {
				command.substringAfter("|")
			} else {
				command
			}

			if (!authenticated) {
				if (command.startsWith("$PASSWORD|")) {
					authenticated = true
					output.write("AUTH_OK\n".toByteArray())

					// Send full state immediately after successful auth
					output.write("ARDUINO:${if (isArduinoConnected) "CONNECTED" else "DISCONNECTED"}\n".toByteArray())
					output.write("POS:$currentHeading\n".toByteArray())

					// Always send operator info (empty strings are valid — tells Remote to clear)
					val prefs = applicationContext.getSharedPreferences("rotator_setup", android.content.Context.MODE_PRIVATE)
					val callsign    = prefs.getString("callsign",     "") ?: ""
					val firstName   = prefs.getString("first_name",   "") ?: ""
					val antennaName = prefs.getString("antenna_name", "") ?: ""
					output.write("SETUP:$callsign|$firstName|$antennaName\n".toByteArray())

					output.flush()

					if (strippedCommand.isNotEmpty() && strippedCommand != "PING") {
						processAuthenticatedCommand(strippedCommand, output)
					}
				} else {
					output.write("AUTH_REQUIRED\n".toByteArray())
					output.flush()
				}
			} else {
				processAuthenticatedCommand(strippedCommand, output)
			}
		}

		private fun processAuthenticatedCommand(command: String, output: java.io.OutputStream) {
			when {
				command.uppercase() == "CW" -> service.startRotationCW("clientCommand", requiresClientWatchdog = true)
				command.uppercase() == "CCW" -> service.startRotationCCW("clientCommand", requiresClientWatchdog = true)
				command.uppercase() == "STOP" -> service.stopRotation("clientCommand")
				command.uppercase() == "STATUS" -> {
					service.sendToArduino("STATUS")
					output.write("ARDUINO:${if (isArduinoConnected) "CONNECTED" else "DISCONNECTED"}\n".toByteArray())
					output.write("POS:$currentHeading\n".toByteArray())
					output.flush()
				}
				command.uppercase() == "PING" -> {
					output.write("PONG\n".toByteArray())
					output.flush()
				}
				command.uppercase().startsWith("CLIENT_VERSION:") -> {
					connectedClientVersion = command.substringAfter(":")
					updateState()
				}
				command.uppercase() == "STALL_DETECTED" -> {
					service.stopRotation("clientStallDetected")
					errorMessage = "Stall detected"
					updateState("Stall detected")
				}
				command.uppercase().startsWith("TARGET:") -> {
					val value = command.substringAfter(":")
					if (value.uppercase() == "CLEAR") {
						service.setRemoteTargetHeading(null)
					} else {
						value.toIntOrNull()?.takeIf { it in 0..359 }?.let {
							service.setRemoteTargetHeading(it)
						}
					}
				}
				else -> {
					output.write("UNKNOWN_CMD\n".toByteArray())
					output.flush()
				}
			}
		}

		fun sendMessage(message: String) {
			if (!authenticated && !message.startsWith("ARDUINO:")) return
			try {
				socket.getOutputStream().write(message.toByteArray())
				socket.getOutputStream().flush()
			} catch (_: Exception) {
				close()
			}
		}

		fun close() {
			try {
				socket.close()
			} catch (_: Exception) {}
			connectedClients.remove(this)
			if (connectedClients.isEmpty()) {
				connectedClientVersion = ""
				remoteTargetHeadingValue = null
			}
			updateState()
		}
	}

	private suspend fun heartbeatLoop() {
		withContext(Dispatchers.IO) {
			while (coroutineContext.isActive) {
				try {
					heartbeatToggle = !heartbeatToggle
					// Safety watchdog: if the rotator is moving but all clients have
					// disconnected (e.g. remote app crashed), send STOP so the antenna
					// doesn't spin indefinitely.
					if (isRotating && activeRotationNeedsClientWatchdog && connectedClients.isEmpty()) {
						stopRotation("heartbeatWatchdog-noClients")
					}
					// Keep the Arduino's heartbeat alive during rotation by re-sending
					// the active direction command every second. Without this the Arduino
					// fires ERROR:heartbeat_timeout after ~3 s of serial silence.
					val cmd = activeRotationCommand
					if (cmd.isNotEmpty() && isArduinoConnected) {
						sendToArduino(cmd)
					}
					updateState()
				} catch (_: Exception) {}
				delay(HEARTBEAT_INTERVAL)
			}
		}
	}

	private fun broadcastToClients(message: String) {
		connectedClients.forEach { it.sendMessage(message) }
	}

	private fun broadcastSetup() {
		val prefs = applicationContext.getSharedPreferences("rotator_setup", android.content.Context.MODE_PRIVATE)
		val callsign    = prefs.getString("callsign",     "") ?: ""
		val firstName   = prefs.getString("first_name",   "") ?: ""
		val antennaName = prefs.getString("antenna_name", "") ?: ""
		broadcastToClients("SETUP:$callsign|$firstName|$antennaName\n")
	}

	private fun updateNotification(message: String) {
		val notification = createNotification(message)
		val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
		notificationManager.notify(NOTIFICATION_ID, notification)
	}

	private fun createNotification(content: String): Notification {
		return NotificationCompat.Builder(this, CHANNEL_ID)
			.setContentTitle("Server v.1a.${BuildConfig.TEST_BUILD_NUMBER}")
			.setContentText(content)
			.setSmallIcon(android.R.drawable.ic_dialog_info)
			.setOngoing(true)
			.setPriority(NotificationCompat.PRIORITY_LOW)
			.build()
	}

	private fun createNotificationChannel() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val channel = NotificationChannel(
				CHANNEL_ID,
				"Rotator Service",
				NotificationManager.IMPORTANCE_LOW
			).apply {
				description = "Rotator control server"
			}
			val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
			notificationManager.createNotificationChannel(channel)
		}
	}
}
