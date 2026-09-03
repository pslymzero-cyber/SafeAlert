# SafeAlert Architecture

> 전체를 읽지 말고 필요한 섹션만 grep -n → sed -n 'A,Bp' 로 본다.

<!-- GSD:stack-start source:codebase/STACK.md -->

## Technology Stack

## Languages

- Kotlin 1.9.22 - Core application logic, activities, services, utilities
- Java 17 - Compilation target and JVM version
- XML - Resource definitions, layouts, manifests

## Runtime

- Android Runtime (ART)
- Target SDK: 34 (Android 14)
- Min SDK: 26 (Android 8.0 Oreo)
- Compile SDK: 34
- Gradle 8.3.2 (Android Gradle Plugin)
- Dependency management: Maven Central, Google Maven

## Frameworks

- Android Framework (SDK 34)
- AndroidX Core 1.12.0 - Modern Android APIs, backward compatibility
- AndroidX AppCompat 1.6.1 - Compatibility layer for activities and themes
- Android Material Design 1.11.0 - Material Design 3 components
- AndroidX ConstraintLayout 2.1.4 - Flexible layout system
- AndroidX RecyclerView 1.3.2 - List/grid views for beacon manager
- AndroidX Lifecycle 2.7.0 - Lifecycle-aware components
- AndroidX Lifecycle Service 2.7.0 - Lifecycle-aware service management
- LifecycleService (`com.wf11.safealert.service.BleService` : `LifecycleService`) - Background BLE scanning with lifecycle awareness
- Kotlin Coroutines 1.7.3 - Asynchronous operations, background tasks
- Lifecycle Runtime KTX 2.7.0 - lifecycleScope for coroutine execution
- AndroidX UWB (Ultra-Wideband) 1.0.0-alpha09 - Precise distance measurement for proximity alerts
- Android Bluetooth APIs - BLE scanning/advertising, dual-role (broadcaster + scanner)
- JUnit 4.13.2 - Unit test framework
- AndroidX Test JUnit 1.1.5 - Instrumentation test runner
- Espresso Core 3.5.1 - UI testing (configured but minimal coverage observed)
- AGP (Android Gradle Plugin) 8.3.2 - Build automation, APK compilation
- Kotlin Gradle Plugin 1.9.22 - Kotlin compilation integration
- Google Services Gradle Plugin 4.4.1 - Firebase integration, google-services.json processing

## Key Dependencies

- Firebase Realtime Database (from BOM 32.7.2) - Alert history, beacon set sharing, version metadata storage
- Firebase Analytics KTX (from BOM 32.7.2) - Event tracking (included but minimal instrumentation observed)
- AndroidX UWB 1.0.0-alpha09 - Ultra-Wideband ranging for sub-meter accuracy
- Kotlin Stdlib - Language runtime
- Kotlin Coroutines Android 1.7.3 - Dispatcher.Main.immediate for UI thread dispatch
- ViewBinding - Type-safe view references (enabled in build.gradle)

## Configuration

- `google-services.json` (Firebase project configuration) - Path: `app/google-services.json`
- `local.properties` - Android SDK path (`sdk.dir`)
- `gradle.properties` - JVM memory, AndroidX, Jetifier, Kotlin style settings
- `build.gradle` (root) - Plugin versions and dependency BOM pinning
- `app/build.gradle` - App module configuration
- `settings.gradle` - Module inclusion (`:app` only)
- `gradle/wrapper/gradle-wrapper.properties` - Gradle distribution version
- Debug keystore: `$HOME/.android/debug.keystore` (hardcoded path in signingConfigs)
- ProGuard rules: `app/proguard-rules.pro` - Obfuscation disabled (`minifyEnabled false` in release build)

## Platform Requirements

- JDK 17 (Temurin or equivalent)
- Android SDK 34 (Build Tools 34.x)
- Gradle 8.3.2 (via wrapper)
- Kotlin 1.9.22
- Deployment target: Android 8.0+ (minSdk 26)
- Runtime permissions: BLUETOOTH, BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT, ACCESS_FINE_LOCATION, FOREGROUND_SERVICE, UWB_RANGING, INTERNET, VIBRATE, WAKE_LOCK, MODIFY_AUDIO_SETTINGS, SYSTEM_ALERT_WINDOW, POST_NOTIFICATIONS, REQUEST_INSTALL_PACKAGES, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, RECEIVE_BOOT_COMPLETED
- Hardware requirements: Bluetooth LE (mandatory), UWB (optional, gracefully degrades to BLE/RSSI)

<!-- GSD:stack-end -->

<!-- GSD:conventions-start source:CONVENTIONS.md -->

## Conventions

## Naming Patterns

- PascalCase for class/interface files: `BleScanner.kt`, `FirebaseManager.kt`
- camelCase for utility singleton objects: `beaconRegistry.kt`, `devSettings.kt`
- Descriptive names reflecting responsibility: `MedianFilter.kt`, `KalmanFilter.kt`
- camelCase for all functions: `push()`, `getAll()`, `containsUuid()`
- Action verbs for mutating functions: `add()`, `remove()`, `save()`
- Predicate functions prefixed with `is`/`contains`/`find`: `isFull()`, `containsUuid()`, `findZoneProfileByUuid()`
- Private functions in companion objects (static helpers) use clear intent names: `mapScanMode()`, `sanitizeKey()`
- camelCase for all variables: `isScanning`, `detectedDevices`, `hazardNear`
- Boolean flags prefixed with `is`/`has`/`can`/`should`: `isRunning`, `hazardNear`, `isMutedPublic`
- Mutable collections typed explicitly: `mutableMapOf()`, `mutableListOf()`, `ArrayDeque()`
- Short-lived loop variables acceptable: `i`, `n`, `c`, `f`, `s` (with context clarity)
- PascalCase for data classes: `BeaconProfile`, `DetectedRow`, `EchoDiffStats`
- PascalCase for enums: `AppMode`, following Android conventions
- Sealed classes for exhaustive pattern matching where needed

## Code Style

- Kotlin standard: 4-space indentation (implicit, not explicitly configured)
- Line length: Pragmatic, no strict enforcement observed (ranges 80–120+ chars based on readability)
- Spacing: Single blank line between methods; double blank line between logical sections
- No explicit linting config file detected (no detekt.yml, .ktlint, or klint config)
- Build should enforce Android Lint defaults via AGP 8.3.2
- Manual code review expected given developer-driven quality gates in the codebase
- Extensive inline comments (Korean) explaining design trade-offs and algorithm choices
- KDoc-style JavaDoc comments (`/** ... */`) for public API functions with clear intent
- Example from `MedianFilter.kt`: Full explanation of filter design, window buffering strategy, and trade-offs in class-level KDoc

## Import Organization

- None detected; full package paths always used
- Avoided; explicit imports only (follows Kotlin best practices)

## Error Handling

- **For blocking operations:** Try-catch with `Log.e()` for errors (e.g., Firebase operations)
- **For parsing:** `runCatching { ... }.getOrDefault(defaultValue)` (defensive, non-throwing)
- **For optional deserialization:** `optString()`, `optInt()`, `optLong()`, `optBoolean()` from JSONObject (null-safe)
- **For async callbacks:** `addOnFailureListener { Log.e(TAG, "message: ${it.message}") }` pattern for Firebase tasks
- **For null safety:** Elvis operator `?:` and `getOrNull()` / `getOrDefault()` for safe extraction

## Logging

- Every class defines a companion object with `const val TAG = "ClassName"` at the top
- `Log.d(TAG, "message")` for debug info
- `Log.e(TAG, "error reason: ${it.message}")` for error tracking
- Logging includes: operation name, data IDs, RSSI values, timestamps where relevant
- Example (FirebaseManager.kt): `Log.e(TAG, "경보 저장 실패: ${it.message}")` with context
- Localized log messages in Korean (design intent comments and error messages)
- English used for technical terms (UWB, RSSI, BLE, dBm)

## Comments

- Algorithm design decisions and trade-offs (priority: explain WHY, not WHAT)
- Non-obvious performance choices (e.g., "throttle UI renders to 500ms to reduce GPU load")
- Workarounds and compatibility notes (e.g., "[v1.0.48 #5] Android OS scan API limitation...")
- Cross-layer dependencies and invariants (e.g., "v1.1.55 needs echoDiffLive synced with SharedPreferences")
- Complex state machines or conditional logic requiring context
- Single-line: `// ` for short clarifications
- Multi-line: `// [version tag] detailed explanation` for design notes
- Block separators: `// ── section name ───────` for visual grouping in large files
- Public functions: Always include KDoc block with `@param` and `@return`
- Example (MedianFilter.kt):

## Function Design

- Range: 5–60 lines typical; larger functions broken into helper methods
- Large functions indicate complex state machines (e.g., `MainActivity.statusRunnable.run()` ~100 lines for comprehensive polling logic)
- 1–4 parameters typical; avoid long parameter lists
- Use data classes (`BeaconProfile`, `DetectedRow`) for bundled data instead of spreading across function signature
- Nullable parameters marked `Type?` with defaults via `?: defaultValue` pattern
- Prefer explicit return types (no implicit Unit suppression)
- Use `Boolean` for predicates (`isFull()`, `containsUuid()`)
- Use typed returns for data extraction (`List<BeaconProfile>`, `String?`, `Int`)
- Callback pattern common for async: `onResult: (Boolean) -> Unit` for Firebase operations
- Variables declared as close to use as possible
- Mutable state marked `@Volatile` when accessed from multiple threads (e.g., `@Volatile var isScreenOn: Boolean`)
- Use of `val` and `var` follows immutability preference (val default, var only when mutation needed)

## Module Design

- Singleton objects (`object BeaconRegistry`) for stateful utilities (preferred over static factories)
- Public methods named clearly for discovery (no getter/setter boilerplate; direct property access)
- Private helper functions marked `private` within companions
- Not used; imports are explicit and module-specific
- `01_model/` — Data classes and enums (`AppMode.kt`, `BeaconProfile.kt`)
- `02_ble/` — Bluetooth Low Energy scanning and filtering (`BleScanner.kt`, `MedianFilter.kt`, `KalmanFilter.kt`)
- `03_service/` — Android Services and background processing (`BleService.kt`, `AlertSoundPlayer.kt`)
- `04_firebase/` — Cloud storage and synchronization (`FirebaseManager.kt`, `FirebaseConfig.kt`)
- `05_ui/` — Activities and UI presentation (`MainActivity.kt`, `BleSettingsActivity.kt`)
- `06_utils/` — Utilities and helpers (`BeaconRegistry.kt`, `DevSettings.kt`, `UwbRanger.kt`)
- `06_utils` (utilities) depends on nothing
- `03_service`, `02_ble` depend on `06_utils` and `01_model`
- `05_ui` depends on `03_service`, `02_ble`, `06_utils`
- `04_firebase` (isolated) depends on nothing; consumed by `03_service`
- `SafeAlertApp.kt` (Application) initializes singletons in order:

<!-- GSD:conventions-end -->

<!-- GSD:architecture-start source:ARCHITECTURE.md -->

## Architecture

## System Overview

```text

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

- **Service-centric**: BleService is the single source of truth for alert state
- **BLE-primary detection**: RSSI-based distance estimation with UWB override when available
- **Static snapshots**: State broadcasted to UI via string snapshots (ASCII separators)
- **Polling fallback**: UI polls service every 800ms if broadcasts fail
- **Stateless filtering**: 3-stage RSSI pipeline (PreFilter → Median → Kalman) per sample
- **Bitpacked protocol**: 1-byte payload (2-2-2-2 bits: CAT/STATE/TURN/RISK)

## Layers

- Purpose: Application initialization entry point
- Location: `SafeAlertApp.kt`
- Contains: Application class
- Depends on: DevSettings, BeaconRegistry, UwbCalibrator, FirebaseConfig
- Used by: Android framework
- Purpose: Android Activities and UI rendering
- Location: `app/src/main/java/com/wf11/safealert/05_ui/`
- Contains: MainActivity, BeaconManagerActivity, BleSettingsActivity, DevSettingsActivity, OpenSourceLicensesActivity, adapters
- Depends on: BleService (polling + broadcasts), BeaconRegistry, DevSettings, ImuFusion
- Used by: Android framework (displayed to user)
- Purpose: Core alert logic and lifecycle management
- Location: `app/src/main/java/com/wf11/safealert/03_service/`
- Contains: BleService (LifecycleService), AlertSoundPlayer, VibrationHelper
- Depends on: BleScanner, BleScanCallback, BleAdvertiser, ImuFusion, UwbRanger, FirebaseManager, OverlayManager
- Used by: MainActivity (broadcasting), system (foreground service)
- Purpose: BLE scanning, advertising, signal filtering, UWB ranging
- Location: `app/src/main/java/com/wf11/safealert/02_ble/`
- Contains: BleScanner, BleScanCallback, BleAdvertiser, BleConstants, KalmanFilter, MedianFilter, RssiPreFilter
- Depends on: Android Bluetooth APIs, DevSettings
- Used by: BleService
- Purpose: Configuration, registry, IMU fusion, UWB calibration, overlays, updates
- Location: `app/src/main/java/com/wf11/safealert/06_utils/`
- Contains: BeaconRegistry, DevSettings, ImuFusion, UwbRanger, UwbCalibrator, OverlayManager, UpdateManager
- Depends on: SharedPreferences, Android APIs, Firebase
- Used by: BleService, UI, SafeAlertApp
- Purpose: Core data classes
- Location: `app/src/main/java/com/wf11/safealert/01_model/`
- Contains: AppMode, BeaconProfile
- Depends on: none
- Used by: Service, UI, Utils
- Purpose: Remote database sync, model priors, auto-update
- Location: `app/src/main/java/com/wf11/safealert/04_firebase/`
- Contains: FirebaseManager, FirebaseConfig
- Depends on: Firebase SDK, BleService
- Used by: BleService (async), DevSettingsActivity

## Data Flow

### Primary Request Path (BLE Scan Detection)

### Local State Broadcasting Path

### Echo RSSI Calibration (v1.1.54+)

### State Management

- `lastStatus` — last status string
- `isRunning` — service active
- `isMutedPublic` — alert muted flag
- `detectedSnapshot` — serialized device list (read by MainActivity polling)
- `detectedCount` — current alert device count
- `localSnapshot` — own state (category/state/turnDir)
- `echoDiffLive` — echo calibration map (device → histogram)
- `detectedDevices` — mutable list of DetectedRow (name, level, rssi, dist)
- `lastSyncedSnapshot` — last seen detectedSnapshot (prevent duplicate renders)
- `lastLocalSnapshot` — last seen localSnapshot
- `alertState` — map `{fullId → AlertState(rssi, velocity, level, etc.)}`
- `detectedDevicesMap` — map `{fullId → DeviceCache}`

## Key Abstractions

- Purpose: Encapsulates detected device alert info
- Examples: `03_service/BleService.kt` (inline data class)
- Pattern: Immutable snapshot, updated per Kalman cycle (~120ms)
- Purpose: Tracks device RSSI history, filters, timestamps
- Examples: `02_ble/BleScanCallback.kt` (companion object cache)
- Pattern: Lazy initialization, timeout-based cleanup
- Purpose: State estimation from noisy RSSI samples
- Examples: `02_ble/KalmanFilter.kt`
- Pattern: Stateful filter, per-device instance in DeviceCache
- Purpose: Encode device category, motion state, turn direction, risk level
- Pattern: Bits 7:6=CAT, 5:4=STATE, 3:2=TURN, 1:0=RISK
- Serialization: `BleConstants.encodePayload()` / `decodePayload()`

## Entry Points

- Location: `SafeAlertApp` (application class)
- Triggers: Android framework (process startup)
- Responsibilities: Initialize DevSettings, BeaconRegistry, UwbCalibrator, Firebase
- Location: `MainActivity` (main activity, exported)
- Triggers: User taps app launcher
- Responsibilities: Display detected devices, role selection, permission requests
- Location: `BleService` (foreground service, declared in manifest)
- Triggers: `Intent(context, BleService::class.java).also { startForegroundService(it) }`
- Responsibilities: BLE scanning/advertising, alert state machine, broadcasting
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

### Broadcast Receiver Not Exported

### Kalman Filter State Leak Across Devices

## Error Handling

- **Bluetooth unavailable:** `runCatching { BluetoothAdapter.getDefaultAdapter() }` → fallback to UI message "블루투스 OFF"
- **Permission denied:** Check `ContextCompat.checkSelfPermission()` before operations; request via `ActivityResultContracts.RequestPermission()`
- **Firebase failure:** Async timeout (non-blocking); cache local priors from SharedPreferences
- **UWB not available:** Gracefully fallback to RSSI (no crash); `UwbRanger` returns null if API unavailable
- **Invalid payload:** `runCatching` in `decodePayload()` → assume all-zeros (safe default)

## Cross-Cutting Concerns

- `Log.d(TAG, "message")` in each file; TAG = class name
- BleService logs scan count, device count, alert level changes
- Dev settings expose log filters (e.g., echo histogram dumps to console)
- RSSI range: dBm, typically -100 to 0
- Distance: positive meters or empty (fallback to RSSI)
- Payload decoding: all-zeros safe default if corrupted
- Firebase rules check user email (not used in SafeAlert)
- Local beacon profiles stored in SharedPreferences (not encrypted — consider for sensitive deployments)
- No app-to-app authentication; UWB uses random address rotation per session

<!-- GSD:architecture-end -->
