# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Dual-app Android system for ham radio antenna rotator control. A **RotatorServer** app (runs on a shack phone connected to an Arduino via USB) and a **RotatorRemote** app (mobile client). They communicate over WiFi via TCP/IP with UDP-based LAN discovery.

```
[RotatorRemote]  <--TCP:9999--> [RotatorServer] <--USB Serial--> [Arduino] <--> Rotator
                 <--UDP:8888-->  (LAN discovery)   (9600 baud)
```

## Build Commands

```bash
./gradlew :app:assembleDebug                    # Build Remote client APK
./gradlew :RotatorServer:app:assembleDebug      # Build Server APK
./gradlew :app:testDebugUnitTest                # Run Remote client unit tests
./gradlew :RotatorServer:app:testDebugUnitTest  # Run Server unit tests
./gradlew test                                  # Run all unit tests
./gradlew incrementBuildNumber                  # Bump test_build_number in gradle.properties
./gradlew installDebug                          # Install debug build to connected device
```

Build output goes to `C:/BuildTemp/` (custom, not the standard `build/` directory).

## Module Structure

- **`:app`** — RotatorRemote client app (no USB libraries)
- **`:RotatorServer:app`** — RotatorServer app (includes `usb-serial-for-android:3.7.0` via jitpack)

## Architecture

### RotatorRemote (Client)

- **`RotatorViewModel.kt`** — Core state machine. Manages `ConnectionState` (DISCONNECTED → DISCOVERING → CONNECTING → CONNECTED → ERROR). Runs UDP discovery with multicast lock, TCP connection/auth, heartbeat ping loop (5s), stall detection (3s timeout), and target heading automation (shortest-path rotation logic). All network I/O runs on `Dispatchers.IO` via coroutines with `SupervisorJob`.
- **`RotatorClient.kt`** — Lower-level networking helper. Handles UDP broadcast listening, TCP connect (retries up to 3 times with short pauses), greeting validation (`ROTATOR_SERVER:1.0` or legacy `ROTATOR_SERVER:` prefix), and heartbeat.
- **`MainActivity.kt`** — Compose UI with three screens: splash, connection, and control. Control screen includes a compass dial (360°), slider for CW/CCW/STOP, stall warnings, and connection status bar.

### RotatorServer (Server)

- **`RotatorService.kt`** — Foreground service (the heart of the server). Manages: Arduino USB reconnection loop, UDP broadcast every 1.5s, TCP server on port 9999, multi-client handler, Arduino message processing (`POS:XXX`, `ARDUINO:CONNECTED/DISCONNECTED`), WakeLock + WiFiLock, NSD (mDNS) service registration, and periodic public IP fetch. Pushes state by writing to `MainActivity.serviceState`.
- **`MainActivity.kt`** — Compose UI showing heading, Arduino status, client count, local IP. Exposes `companion object { val serviceState = MutableStateFlow<RotatorService.ServiceState?>(null) }` as the IPC bridge from the service to the UI (no ViewModel on the server side). Buttons for manual CW/CCW/STOP and calibration.
- **`UsbLayer.kt`** / **`RealUsbLayer.kt`** / **`MockUsbLayer.kt`** — Interface + implementations for USB serial I/O (abstracted for testability).

### Protocol

- **Authentication**: All commands sent as `Password|Command` (default password: `"MyRotatorPassword"`)
- **Commands**: `CW`, `CCW`, `STOP`, `PING`
- **Server feedback**: `POS:XXX` (heading), `ARDUINO:CONNECTED`, `ARDUINO:DISCONNECTED`, heartbeat responses
- **Discovery**: Server UDP broadcasts `ROTATOR_SERVER:IP:PORT` to `255.255.255.255:8888`; client also handles legacy format `ROTATOR_SERVER:PORT`

### Data Flow (Control)

```
User tap → RotatorViewModel.sendCommand() → TCP socket write
         → RotatorService.handleClientMessage() → usbLayer.write() → Arduino
```

```
Arduino → USB serial → RotatorService.runReader() reads "POS:XXX"
        → broadcastToClients() → TCP write → RotatorViewModel.processServerMessage() → UI update
```

## Key Constants

- **Ports**: UDP 8888 (discovery), TCP 9999 (commands)
- **Baud rate**: 9600
- **Heartbeat interval**: 5 seconds
- **Stall timeout**: 3 seconds
- **UDP broadcast interval**: 1.5 seconds

## Tech Stack

| Layer | Tech |
|---|---|
| UI | Jetpack Compose + Material 3 |
| Async | Kotlin Coroutines + `SupervisorJob` |
| Networking | `java.net` (`Socket`, `DatagramSocket`, `ServerSocket`) |
| USB Serial | `usb-serial-for-android` 3.7.0 (HoHo, via jitpack) |
| Build | AGP 9.1.0, Kotlin 2.2.10, Compose 2.2.10, compileSdk 34, minSdk 24 |

## Testing

Unit tests live under `src/test/` in each module. Key test files:
- `DiscoveryParserTest.kt` — UDP discovery message parsing (both legacy and new IP-embedded formats)
- `GreetingValidationTest.kt` — TCP greeting validation
- `MockUsbLayerTest.kt` — Mock USB layer behavior

No instrumented (Espresso) tests exist; all tests are local JVM unit tests.
