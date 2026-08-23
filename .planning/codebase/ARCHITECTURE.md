<!-- refreshed: 2026-08-23 -->
# Architecture

**Analysis Date:** 2026-08-23

## System Overview

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│                            Application Layer (UI)                            │
├──────────────┬──────────────┬──────────────┬────────────────┬─────────────┤
│  MainActivity │  Beacon      │  BLE         │  DevSettings   │  OpenSource │
│  (Main UI)   │  Manager     │  Settings    │  Activity      │  Licenses   │
│ `05_ui/`     │ `05_ui/`     │ `05_ui/`     │ `05_ui/`       │ `05_ui/`    │
└──────────┬───┴──────┬───────┴──────┬──────┴────────┬────────┴─────────────┘
           │          │              │               │
           ▼          ▼              ▼               ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          Service & Business Logic Layer                      │
│                                                                               │
│  BleService (LifecycleService, Foreground Service)  `03_service/`            │
│  ├─ BleScanner (BLE scan results)      `02_ble/`                            │
│  ├─ BleScanCallback (process results)  `02_ble/`                            │
│  ├─ BleAdvertiser (broadcast state)    `02_ble/`                            │
│  ├─ Alert Logic (state machine: SAFE/WARNING/DANGER)                        │
│  ├─ AlertSoundPlayer                   `03_service/`                        │
│  ├─ VibrationHelper                    `03_service/`                        │
│  ├─ ImuFusion (motion state)           `06_utils/`                          │
│  ├─ UwbRanger (distance measurement)   `06_utils/`                          │
│  ├─ OverlayManager (alert overlays)    `06_utils/`                          │
│  ├─ Echo RSSI Calibration (echo diff stats)                                 │
│  └─ Firebase Sync (device profiles, settings) `04_firebase/`                │
└──────────┬──────────────────┬──────────────────────┬──────────────────────┘
           │                  │                      │
           ▼                  ▼                      ▼
┌─────────────────────────────────┐  ┌──────────────────────┐  ┌─────────────┐
│    Signal Processing Layer       │  │   Utilities Layer    │  │   Firebase  │
│                                  │  │                      │  │             │
│  RssiPreFilter                   │  │  BeaconRegistry      │  │  Firebase   │
│  MedianFilter  (5-sample window) │  │  DevSettings         │  │  Database   │
│  KalmanFilter  (prediction)      │  │  UwbCalibrator       │  │ (Firestore) │
│  BleConstants  (protocol)        │  │  UpdateManager       │  └─────────────┘
│ `02_ble/`                        │  │ `06_utils/`          │
└──────────────────────────────────┘  └──────────────────────┘
```

## Component Responsibilities

| Component | Responsibility | File |
|-----------|----------------|------|
| **SafeAlertApp** | Application initialization | `SafeAlertApp.kt` |
| **MainActivity** | Main UI, device list display, role selection | `05_ui/MainActivity.kt` |
| **BleService** | Core service managing scanning, advertising, alert logic | `03_service/BleService.kt` |
| **BleScanner** | Initiates and manages BLE scan lifecycle | `02_ble/BleScanner.kt` |
| **BleScanCallback** | Processes scan results, RSSI filtering, distance calc | `02_ble/BleScanCallback.kt` |
| **BleAdvertiser** | Broadcasts device state (1-byte bitpacked payload) | `02_ble/BleAdvertiser.kt` |
| **BleConstants** | Protocol constants, bitpacking/unpacking | `02_ble/BleConstants.kt` |
| **BeaconRegistry** | Stores beacon profiles in SharedPreferences | `06_utils/BeaconRegistry.kt` |
| **DevSettings** | App configuration (RSSI thresholds, scan period) | `06_utils/DevSettings.kt` |
| **ImuFusion** | IMU-based motion state detection | `06_utils/ImuFusion.kt` |
| **UwbRanger** | UWB distance measurement (alpha09 API) | `06_utils/UwbRanger.kt` |
| **UwbCalibrator** | UWB calibration data storage | `06_utils/UwbCalibrator.kt` |
| **FirebaseManager** | Firebase database operations | `04_firebase/FirebaseManager.kt` |
| **AlertSoundPlayer** | Audio playback for alerts | `03_service/AlertSoundPlayer.kt` |
| **VibrationHelper** | Vibration feedback | `03_service/VibrationHelper.kt` |
| **OverlayManager** | System overlay for alert display | `06_utils/OverlayManager.kt` |

## Pattern Overview

**Overall:** Layered architecture with reactive event-driven design

**Key Characteristics:**
- **Service-centric**: BleService is the single source of truth for alert state
- **BLE-primary detection**: RSSI-based distance estimation with UWB override when available
- **Static snapshots**: State broadcasted to UI via string snapshots (ASCII separators)
- **Polling fallback**: UI polls service every 800ms if broadcasts fail
- **Stateless filtering**: 3-stage RSSI pipeline (PreFilter → Median → Kalman) per sample
- **Bitpacked protocol**: 1-byte payload (2-2-2-2 bits: CAT/STATE/TURN/RISK)

## Layers

**SafeAlertApp:**
- Purpose: Application initialization entry point
- Location: `SafeAlertApp.kt`
- Contains: Application class
- Depends on: DevSettings, BeaconRegistry, UwbCalibrator, FirebaseConfig
- Used by: Android framework

**05_ui (Presentation):**
- Purpose: Android Activities and UI rendering
- Location: `app/src/main/java/com/wf11/safealert/05_ui/`
- Contains: MainActivity, BeaconManagerActivity, BleSettingsActivity, DevSettingsActivity, OpenSourceLicensesActivity, adapters
- Depends on: BleService (polling + broadcasts), BeaconRegistry, DevSettings, ImuFusion
- Used by: Android framework (displayed to user)

**03_service (Business Logic):**
- Purpose: Core alert logic and lifecycle management
- Location: `app/src/main/java/com/wf11/safealert/03_service/`
- Contains: BleService (LifecycleService), AlertSoundPlayer, VibrationHelper
- Depends on: BleScanner, BleScanCallback, BleAdvertiser, ImuFusion, UwbRanger, FirebaseManager, OverlayManager
- Used by: MainActivity (broadcasting), system (foreground service)

**02_ble (Bluetooth & Signal Processing):**
- Purpose: BLE scanning, advertising, signal filtering, UWB ranging
- Location: `app/src/main/java/com/wf11/safealert/02_ble/`
- Contains: BleScanner, BleScanCallback, BleAdvertiser, BleConstants, KalmanFilter, MedianFilter, RssiPreFilter
- Depends on: Android Bluetooth APIs, DevSettings
- Used by: BleService

**06_utils (Shared Utilities):**
- Purpose: Configuration, registry, IMU fusion, UWB calibration, overlays, updates
- Location: `app/src/main/java/com/wf11/safealert/06_utils/`
- Contains: BeaconRegistry, DevSettings, ImuFusion, UwbRanger, UwbCalibrator, OverlayManager, UpdateManager
- Depends on: SharedPreferences, Android APIs, Firebase
- Used by: BleService, UI, SafeAlertApp

**01_model (Data Models):**
- Purpose: Core data classes
- Location: `app/src/main/java/com/wf11/safealert/01_model/`
- Contains: AppMode, BeaconProfile
- Depends on: none
- Used by: Service, UI, Utils

**04_firebase (Cloud Integration):**
- Purpose: Remote database sync, model priors, auto-update
- Location: `app/src/main/java/com/wf11/safealert/04_firebase/`
- Contains: FirebaseManager, FirebaseConfig
- Depends on: Firebase SDK, BleService
- Used by: BleService (async), DevSettingsActivity

## Data Flow

### Primary Request Path (BLE Scan Detection)

1. **Scan Initiation** (`BleService.startScanning()`) - `03_service/BleService.kt:~2000`
   - Calls `BleScanner.startScan()`
   - Registers `BleScanCallback` with scan filters

2. **Scan Result Processing** (`BleScanCallback.onScanResult()`) - `02_ble/BleScanCallback.kt:~400`
   - Parse manufacturer data (0x1234/0x5678 company ID)
   - Extract displayName, RSSI, state payload (1-byte CAT/STATE/TURN/RISK)
   - Check for UWB extension data (0x9ABC address exchange)
   - Extract echo RSSI (0xE0C0)

3. **RSSI Filtering** (`BleScanCallback.filterRssi()`) - `02_ble/BleScanCallback.kt:~900`
   - `RssiPreFilter` — outlier detection (5dB deviation)
   - `MedianFilter` — 5-sample sliding window
   - `KalmanFilter` — state prediction + measurement update
   - Result: `filteredRssi` with confidence estimate

4. **Distance Calculation** (`BleScanCallback.getCurrentDistance()`) - `02_ble/BleScanCallback.kt:~1100`
   - Prefer UWB if available (freshUwbDistM, ≤1s old) — `06_utils/UwbRanger.kt`
   - Fallback to RSSI → dBm conversion (no log-distance model, direct RSSI use)
   - Return: "Xm" or empty string for dBm display

5. **Alert State Update** (`BleService.processAlert()`) - `03_service/BleService.kt:~1500`
   - Kalman filter output → alertState map `{fullId → AlertState}`
   - Decision tree:
     - Special state (REVERSE/LOADING) at rssiDanger → immediate DANGER
     - UWB distance active (Case A)? Use UWB primary authority
     - RSSI-only (Case B)? Apply Kalman + thresholds
     - Evaluate TTC, velocity direction (closing/departing)
   - Broadcast result: `BROADCAST_DETECTED` (serialized snapshot)
   - Trigger alert sounds/vibrations if level changed

6. **UI Update** (`MainActivity.updateDetectedDisplay()`) - `05_ui/MainActivity.kt:~2300`
   - Receive `BROADCAST_DETECTED` intent
   - Parse snapshot string (record separator 0x001E, field sep 0x001F)
   - Sort by alert level (DANGER > WARNING > SAFE)
   - Render in RecyclerView adapter
   - Fallback: Poll `BleService.detectedSnapshot` every 800ms

### Local State Broadcasting Path

1. **Local State Update** (`BleService.updateLocalState()`) - `03_service/BleService.kt:~1400`
   - Read `ImuFusion.motionState()` → IDLE/FORWARD/REVERSE/LOADING
   - Read `ImuFusion.turnDirection()` → STRAIGHT/LEFT/RIGHT
   - Serialize: `category|state|turnDir` (field sep 0x001F)
   - Broadcast: `BROADCAST_LOCAL_STATE`

2. **Advertiser Update** (`BleAdvertiser.encodePayload()`) - `02_ble/BleAdvertiser.kt:~600`
   - Bitpack 1 byte: bits[7:6]=CAT, bits[5:4]=STATE, bits[3:2]=TURN, bits[1:0]=RISK
   - Risk bits = max alert level from own alertState (downlink from receivers)
   - Transmit via `advertisingData.addServiceData(UUID, byte[])`

3. **UI Display** (`MainActivity.updateLocalDisplay()`) - `05_ui/MainActivity.kt:~2400`
   - Parse localSnapshot (separate from detectedSnapshot)
   - Display own state in top panel (never receives data from others)

### Echo RSSI Calibration (v1.1.54+)

1. **Echo Collection** (`BleScanCallback.processEchoRssi()`) - `02_ble/BleScanCallback.kt:~1200`
   - Parse 0xE0C0 echo table from peer scan response
   - Match short hash against `BleScanner.myEchoHash`
   - Calculate diff = (my avgRssi) - (peer's peerEchoRssi)
   - Accumulate in 5dB buckets (-40 to +40dB, 16 buckets)

2. **Auto-Calibration** (`BleService.echoCalLocalDb()`) - `03_service/BleService.kt:~1800`
   - Compute median of histogram
   - Gate: n (echo ticks) ≥ echoCalMinTicks
   - Gate: IQR/2 ≤ echoCalMaxIqrDb
   - Result: `echoCal = -(median/2)` clamped to ±echoCalClampDb
   - Applied in totalOffset if `echoAutoCalibEnabled`

3. **Firebase Prior** (`FirebaseManager.loadEchoPriors()`) - `04_firebase/FirebaseManager.kt:~500`
   - Fetch device model pairs from Firebase (async)
   - Cache model statistics: `{peer_model → (median_dB, sample_count)}`
   - Fallback if local n < threshold

### State Management

**Static Service State** (`BleService` companion object):
- `lastStatus` — last status string
- `isRunning` — service active
- `isMutedPublic` — alert muted flag
- `detectedSnapshot` — serialized device list (read by MainActivity polling)
- `detectedCount` — current alert device count
- `localSnapshot` — own state (category/state/turnDir)
- `echoDiffLive` — echo calibration map (device → histogram)

**MainActivity State**:
- `detectedDevices` — mutable list of DetectedRow (name, level, rssi, dist)
- `lastSyncedSnapshot` — last seen detectedSnapshot (prevent duplicate renders)
- `lastLocalSnapshot` — last seen localSnapshot

**BleService Internal State**:
- `alertState` — map `{fullId → AlertState(rssi, velocity, level, etc.)}`
- `detectedDevicesMap` — map `{fullId → DeviceCache}`

## Key Abstractions

**AlertState:**
- Purpose: Encapsulates detected device alert info
- Examples: `03_service/BleService.kt` (inline data class)
- Pattern: Immutable snapshot, updated per Kalman cycle (~120ms)

**DeviceCache:**
- Purpose: Tracks device RSSI history, filters, timestamps
- Examples: `02_ble/BleScanCallback.kt` (companion object cache)
- Pattern: Lazy initialization, timeout-based cleanup

**Kalman Filter:**
- Purpose: State estimation from noisy RSSI samples
- Examples: `02_ble/KalmanFilter.kt`
- Pattern: Stateful filter, per-device instance in DeviceCache

**BLE Payload (1-byte bitpacked):**
- Purpose: Encode device category, motion state, turn direction, risk level
- Pattern: Bits 7:6=CAT, 5:4=STATE, 3:2=TURN, 1:0=RISK
- Serialization: `BleConstants.encodePayload()` / `decodePayload()`

## Entry Points

**Application Entry:**
- Location: `SafeAlertApp` (application class)
- Triggers: Android framework (process startup)
- Responsibilities: Initialize DevSettings, BeaconRegistry, UwbCalibrator, Firebase

**UI Entry:**
- Location: `MainActivity` (main activity, exported)
- Triggers: User taps app launcher
- Responsibilities: Display detected devices, role selection, permission requests

**Service Entry:**
- Location: `BleService` (foreground service, declared in manifest)
- Triggers: `Intent(context, BleService::class.java).also { startForegroundService(it) }`
- Responsibilities: BLE scanning/advertising, alert state machine, broadcasting

**Receiver Entry:**
- Location: N/A (statically registered broadcasts use companion object fields)
- Trigger method: MainActivity polls `BleService.detectedSnapshot` + `localSnapshot` (800ms timer)
- Fallback for broadcast failures: `ACTION_REAPPLY_UWB`, `ACTION_OPEN_SWITCH_ROLE` via system intent

## Architectural Constraints

- **Threading:** Main thread for UI + broadcasts, LifecycleService handles worker threads; Kalman filter runs on service thread (~120ms)
- **Global state:** BleService uses `@Volatile` static fields for inter-process communication (no locks needed for reads in polls)
- **Circular imports:** None (layering enforced: UI → Service → BLE/Utils → Model)
- **Memory limits:** AlertState map capped by device timeout (2-6s), no unbounded growth
- **Foreground service:** Must display persistent notification (CHANNEL_ID, NOTIF_ID)

## Anti-Patterns

### Shared Mutable State Without Guards

**What happens:** BleService.alertState (mutable map) is directly modified on service thread and read on UI thread without synchronization

**Why it's wrong:** Race conditions could cause ConcurrentModificationException or missed updates

**Do this instead:** Use `@Volatile` snapshot strings (detectedSnapshot) like v1.0.42 introduced — serialize to immutable string, UI reads atomically. Modify internal alertState with full service thread exclusion if needed.

### Broadcast Receiver Not Exported

**What happens:** MainActivity registers BroadcastReceiver for BROADCAST_DETECTED implicitly (not android:exported)

**Why it's wrong:** Android 12+ requires explicit export declaration; broadcasts silently fail on restricted devices

**Do this instead:** v1.0.42 already fixed this with explicit `registerReceiver(..., IntentFilter(), Context.RECEIVER_NOT_EXPORTED)` + static snapshot fallback poll (800ms timer)

### Kalman Filter State Leak Across Devices

**What happens:** DeviceCache stores per-device KalmanFilter instance, but timeout cleanup can orphan old filters if timestamps diverge

**Why it's wrong:** Memory leaks in long-running service; stale data if same device reconnects

**Do this instead:** Implement timeouts correctly (already done: `DEVICE_TIMEOUT_ACTIVE_MS` / `DEVICE_TIMEOUT_REST_MS`). DeviceCache cleanup in `BleScanCallback.cleanupOldDevices()`.

## Error Handling

**Strategy:** Try-catch with logging, graceful fallback to safe state

**Patterns:**
- **Bluetooth unavailable:** `runCatching { BluetoothAdapter.getDefaultAdapter() }` → fallback to UI message "블루투스 OFF"
- **Permission denied:** Check `ContextCompat.checkSelfPermission()` before operations; request via `ActivityResultContracts.RequestPermission()`
- **Firebase failure:** Async timeout (non-blocking); cache local priors from SharedPreferences
- **UWB not available:** Gracefully fallback to RSSI (no crash); `UwbRanger` returns null if API unavailable
- **Invalid payload:** `runCatching` in `decodePayload()` → assume all-zeros (safe default)

## Cross-Cutting Concerns

**Logging:** 
- `Log.d(TAG, "message")` in each file; TAG = class name
- BleService logs scan count, device count, alert level changes
- Dev settings expose log filters (e.g., echo histogram dumps to console)

**Validation:**
- RSSI range: dBm, typically -100 to 0
- Distance: positive meters or empty (fallback to RSSI)
- Payload decoding: all-zeros safe default if corrupted

**Authentication:**
- Firebase rules check user email (not used in SafeAlert)
- Local beacon profiles stored in SharedPreferences (not encrypted — consider for sensitive deployments)
- No app-to-app authentication; UWB uses random address rotation per session

---

*Architecture analysis: 2026-08-23*
