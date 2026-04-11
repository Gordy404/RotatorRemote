# Debugging WiFi Connection Issues

## For Users

### If the Remote app still cannot discover the Server:

1. **Check Server is Running**
   - Open the Server app on the Arduino phone
   - Verify it shows "Connected" with a green indicator
   - Check that the IP address is visible in the connection bar

2. **Verify WiFi Network**
   - Ensure both devices are on the **same WiFi network** (not guest networks)
   - Check WiFi is not in sleep mode on the server device
   - Try turning WiFi off and back on on both devices

3. **Enable UDP Broadcasts**
   - Go to Settings > Apps > Rotator Server > Permissions
   - Ensure "Change WiFi Multicast State" is granted
   - Do the same for Rotator Remote

4. **Check Firewall/Router**
   - Some routers block broadcast packets—try disabling the firewall
   - If discovery fails, use "CONNECT MANUALLY" and enter the IP manually

5. **Manual Connection**
   - Note the server's IP from the Server app
   - In Remote app, tap "CONNECT MANUALLY"
   - Enter the IP address (e.g., 192.168.1.42)

### Logs to Check
If problems persist, enable developer mode on both devices and capture logs:
```bash
adb logcat | grep -i "rotator\|udp\|socket\|discovery"
```

**Look for:**
- `UDP broadcast started` - server is broadcasting
- `Discovery packet received` - client is receiving
- `Server found at` - parsing succeeded
- Any error messages with host/port details

---

## For Developers

### How WiFi Discovery Works (Post-Fix)

#### Server Side (RotatorService)
1. Every 1.5 seconds, get local IP via `getLocalIpAddress()`
2. Build message: `ROTATOR_SERVER:<IP>:<PORT>`
3. Send UDP `DatagramPacket` to broadcast address on port 8888

```kotlin
val localIp = cachedIp.takeIf { it != "0.0.0.0" } ?: getLocalIpAddress() ?: ""
val message = if (localIp.isNotBlank()) {
    "ROTATOR_SERVER:$localIp:$TCP_SERVER_PORT"
} else {
    "ROTATOR_SERVER:$TCP_SERVER_PORT"  // fallback
}
```

#### Client Side (RotatorViewModel)
1. Acquire multicast lock
2. Bind UDP socket to port 8888 (not ephemeral port)
3. Set 15-second discovery timeout
4. Receive first broadcast packet
5. Parse message and extract IP:
   - If only one colon: `ROTATOR_SERVER:PORT` → use packet source IP
   - If two colons: `ROTATOR_SERVER:IP:PORT` → use embedded IP
6. Connect to extracted IP on port 9999

### Common Pitfalls

❌ **Using packet source address**: Unreliable on WiFi  
✅ **Including IP in message**: Ensures correct address  

❌ **Using ephemeral UDP port**: May not receive broadcasts  
✅ **Binding to port 8888**: Known port, broadcasts reach it  

❌ **Forgetting multicast lock**: Broadcasts blocked on some devices  
✅ **Acquiring lock**: Ensures UDP reception  

### Message Format Migration

Both formats are now supported for backwards compatibility:

| Format | Example | When |
|--------|---------|------|
| Legacy | `ROTATOR_SERVER:9999` | Old server code |
| New    | `ROTATOR_SERVER:192.168.1.42:9999` | Updated server code |

The client auto-detects and handles both.

### Initial TCP Handshake

Recent releases changed the text that the server sends immediately after a TCP
connection is accepted.  Older clients expected the greeting to start with a
colon (`ROTATOR_SERVER:`) whereas the service now sends a version string
(`ROTATOR_SERVER v1.0`).  This mismatch could cause the client to abort the
connection and appear to fail randomly; retrying repeatedly would eventually
succeed once a compatible app version was running.  The code has been updated to
accept either prefix, and the problem should no longer occur, but if you see
``Connection error: Invalid server response`` in the logs consider installing
matching versions of the apps on both devices.

### Automatic Retry Logic

The remote app now attempts the TCP connection up to three times (with short
pauses) before giving up.  This helps when the server is just starting and the
broadcast arrives slightly ahead of the listening socket.

The status bar will still show "Connecting…" during retries; if the
connection ultimately fails an error message will be displayed.

### Testing the Fix

```bash
# On server device, capture broadcasts
adb shell tcpdump -i wlan0 -n 'udp port 8888' | grep ROTATOR

# On client device, watch discovery
adb logcat | grep -E "(UDP|Discovery|Server found)"

# Manually test with nc (requires rooted device)
adb shell nc -l -u 8888 &
adb shell echo "ROTATOR_SERVER:192.168.1.42:9999" | nc -u -w1 255.255.255.255 8888
```
