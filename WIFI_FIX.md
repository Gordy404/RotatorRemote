# WiFi Connection Fix: UDP Broadcast Discovery

## Problem
The Remote app could not connect to the Server app over WiFi. The discovery mechanism relied on extracting the server's IP address from the UDP broadcast packet's source address field, which can be problematic due to:
- Network bridging and address translation
- System routing behavior on multi-interface devices  
- Android firewall/multicast handling

## Root Cause
The server's UDP broadcast discovery messages were sent without including the server's IP address:
```
ROTATOR_SERVER:9999  (contains only port, not IP)
```

The client had to extract the IP from the UDP packet header, which is unreliable in WiFi environments.

## Solution

### Server Changes (RotatorService.kt)
Updated the UDP broadcast message to include the server's local IP address:
```kotlin
// Old format: ROTATOR_SERVER:9999
// New format: ROTATOR_SERVER:192.168.1.42:9999
```

The server now:
1. Retrieves its local IP address (updated every 2 seconds via `getLocalIpAddress()`)
2. Includes it in every UDP broadcast packet
3. Falls back to the legacy format if IP cannot be determined

### Client Changes (RotatorViewModel.kt, RotatorClient.kt)
Updated discovery parsing to handle both formats:
```kotlin
// Parse payload "PORT" or "IP:PORT"
val parts = payload.split(":")
val (ip, port) = when (parts.size) {
    1 -> Pair(packet.address?.hostAddress, parts[0])  // Legacy: use packet source IP
    else -> Pair(parts[0], parts.getOrNull(1) ?: "")  // New: use included IP
}
```

### Additional Improvements
- **RotatorClient.kt**: Bound discovery socket to port 8888 to receive broadcasts reliably
- **Unit Tests**: Added `DiscoveryParserTest.kt` to verify parsing of both message formats
- **Documentation**: Updated README.md to reflect the new broadcast format

## Backwards Compatibility
The solution maintains full backwards compatibility:
- Clients can work with servers running old or new code
- Servers gracefully handle clients using the old discovery protocol
- Discovery works with both `ROTATOR_SERVER:PORT` (legacy) and `ROTATOR_SERVER:IP:PORT` (new)

## Testing Checklist
- [ ] Server broadcasts with IP included in message
- [ ] Client discovery receives and parses broadcast correctly
- [ ] Connection succeeds using discovered IP
- [ ] Manual IP entry still works
- [ ] Multicast lock is properly acquired/released
- [ ] Both old and new broadcast formats are handled
