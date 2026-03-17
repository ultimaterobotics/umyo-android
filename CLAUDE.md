# umyo-android

Android BLE bridge app for uMyo.
Two main functions: live data streaming and OTA firmware update.
Current release: v0.1.0 (single-device). Multi-device refactor done, not yet released.

## Architecture

### Key classes

| Class | Location | Purpose |
|---|---|---|
| `UmyoApp` | `UmyoApp.kt` | Application subclass. Owns scanner, device map, shared UDP queue. |
| `DeviceSession` | `DeviceSession.kt` | All per-device state — GATT, name, seq counter, stats. |
| `Uf1Codec` | `Uf1Codec.kt` | Pure UF1 encoding functions, no state. |
| `DeviceListActivity` | `DeviceListActivity.kt` | Launcher screen. Device list, scan control, navigate to streaming. |
| `StreamingActivity` | `StreamingActivity.kt` | IP/port config, start/stop UDP worker, per-device stats. |
| `MainActivity` | `MainActivity.kt` | Legacy single-device code — kept for reference, not the active path. |

### Multi-device design

`UmyoApp` holds:
- `ConcurrentHashMap<String, DeviceSession>` keyed by MAC — authoritative device map
- `SnapshotStateList<DeviceSession>` — Compose-observable mirror of the map
- Single `ArrayBlockingQueue` + UDP worker thread — shared across all devices
- Scanner runs continuously, stops only when 6 slots filled or manually stopped

`DeviceSession` holds all state that was formerly singleton fields on MainActivity:
- `@Volatile gatt`, `gattChar` — GATT connection refs
- `AtomicInteger seqCounter` — race-safe across concurrent GATT callbacks
- `@Volatile` lifecycle booleans (`servicesStarted`, `gotFirstData`)
- `mutableIntStateOf rssi` — main-thread observable
- `deviceName: String` — read from firmware on connect
- Per-device stats (notifyCount, windowStartMs, notifyRateFps, lastPayloadLen)

Max 6 simultaneous devices. Scanner stops automatically when limit reached.

### Threading model

- GATT callbacks run on a dedicated BLE thread per connection
- `seqCounter` is AtomicInteger — safe for concurrent callback access
- RSSI updates posted to main thread via mainHandler
- UDP send queue is thread-safe BlockingQueue — all sessions enqueue, one worker drains

## Live data path

uMyo → BLE GATT → DeviceSession → UF1 frames → shared sendQueue → UDP → Python workbench

User flow:
1. Open app — scanning starts automatically
2. uMyo devices appear in DeviceListActivity as they connect
3. Tap "Streaming →" → StreamingActivity
4. Enter PC IP + port 26750
5. Tap "Start Streaming"
6. Run `uf1_workbench.py` on PC

## Device naming

On connect, app READs the name characteristic (UUID `FBD02`) directly — does not rely on notify.
Both API < 33 and API 33+ read paths are handled.
If characteristic absent (old firmware), falls through gracefully — shows MAC address instead.
Device name is stored on the firmware, not in the app. See `uMyo/CLAUDE.md` for details.

## OTA update path

uMyo bootloader (BLE mode) → this app → BLE OTA upload

User flow:
1. Put device in bootloader BLE mode (power off → hold button → short press)
2. Open OTA mode in app
3. Scan for bootloader (advertises as `uECG boot`)
4. Start OTA — takes ~4–5 minutes
5. Device reboots automatically

OTA currently uses bundled `.bin` asset — no file picker yet.

## BLE characteristics (firmware side)

| Characteristic | UUID | Purpose |
|---|---|---|
| Telemetry | `FC7A850D-C1A5-F61F-0DA7-9995621FBD01` | EMG/IMU/QUAT stream, NOTIFY |
| Device name | `FC7A850D-C1A5-F61F-0DA7-9995621FBD02` | READ + WRITE_NO_RESP, 16 bytes max |

## UF1 fanout — payload dispatch

Dispatch is on payload size in `DeviceSession.handleGattNotify()`:

| Size | Profile | Fanout |
|---|---|---|
| 20 / 36 / 52 | Raw EMG only | (size-4)/16 × EMG_RAW frames |
| 60 | S1 | 3× EMG_RAW + 1× QUAT |
| 26 | S2 aux | 1× IMU + 1× MAG + 1× QUAT (via handleAux26) |
| other | unknown | 1× debug block 0xF1 |

If firmware payload sizes or profiles change, `handleGattNotify` and `handleAux26` in
`DeviceSession.kt` must be updated in sync.

## Android permissions (Android 12+)

Manifest declares and app requests at runtime:
- `BLUETOOTH_SCAN`
- `BLUETOOTH_CONNECT`

Both are required. Missing either causes silent failure at scanner registration.

## Known-tested devices

- Samsung A5 2017 ✓
- Pixel 4a 5G ✓

## Known limitations / next steps

- Device name editing UI not yet implemented (name read works, write not wired to UI yet)
- OTA has no file picker — uses bundled asset only
- Deprecation warnings in DeviceSession.kt:284-285 (CCCD descriptor write, API 33+) — cosmetic, not functional
- "notifications enabled, waiting for data..." status message is a leftover from old single-device design
- Python workbench needs update to read device names from UF1 stream and label lanes
- Battery % not yet displayed
- Package name `com.example.uf1bridgedemo` is a placeholder — rename eventually
