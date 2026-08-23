# Codebase Structure

**Analysis Date:** 2026-08-23

## Directory Layout

```
SafeAlert/
├── app/                                  # Main Android application module
│   ├── build/                            # Build outputs (generated, not committed)
│   ├── build.gradle                      # Gradle build config (version, dependencies)
│   └── src/main/
│       ├── java/com/wf11/safealert/
│       │   ├── 01_model/                 # Data models
│       │   │   ├── AppMode.kt
│       │   │   └── BeaconProfile.kt
│       │   ├── 02_ble/                   # Bluetooth LE layer
│       │   │   ├── BleAdvertiser.kt      # Broadcasts device state
│       │   │   ├── BleConstants.kt       # Protocol constants (UUIDs, bitpacking)
│       │   │   ├── BleScanCallback.kt    # Scan result handler, RSSI filtering
│       │   │   ├── BleScanner.kt         # Scan lifecycle manager
│       │   │   ├── KalmanFilter.kt       # State estimation
│       │   │   ├── MedianFilter.kt       # Median window filter (5 samples)
│       │   │   └── RssiPreFilter.kt      # Outlier removal
│       │   ├── 03_service/               # Business logic / Service layer
│       │   │   ├── AlertSoundPlayer.kt   # Audio playback for alerts
│       │   │   ├── BleService.kt         # Core foreground service & alert logic
│       │   │   └── VibrationHelper.kt    # Vibration feedback
│       │   ├── 04_firebase/              # Cloud integration
│       │   │   ├── FirebaseConfig.kt     # Firebase initialization
│       │   │   └── FirebaseManager.kt    # Database operations, model priors
│       │   ├── 05_ui/                    # User interface layer
│       │   │   ├── MainActivity.kt       # Main activity (entry point)
│       │   │   ├── BeaconManagerActivity.kt  # Manage beacon profiles
│       │   │   ├── BleSettingsActivity.kt    # BLE threshold settings
│       │   │   ├── DevSettingsActivity.kt    # Developer settings (UWB, echo diags)
│       │   │   ├── OpenSourceLicensesActivity.kt  # OSS license display
│       │   │   └── adapter/
│       │   │       ├── BeaconListAdapter.kt
│       │   │       └── (other adapters)
│       │   ├── 06_utils/                 # Shared utilities
│       │   │   ├── BeaconRegistry.kt     # SharedPreferences registry
│       │   │   ├── DevSettings.kt        # Configuration (RSSI thresholds, etc.)
│       │   │   ├── ImuFusion.kt          # IMU motion state
│       │   │   ├── OverlayManager.kt     # System overlay alerts
│       │   │   ├── UpdateManager.kt      # Auto-update via Firebase
│       │   │   ├── UwbCalibrator.kt      # UWB calibration storage
│       │   │   └── UwbRanger.kt          # UWB distance measurement (alpha09)
│       │   └── SafeAlertApp.kt           # Application class
│       ├── res/                          # Android resources
│       │   ├── drawable/                 # Vector drawables, icons
│       │   ├── layout/                   # Activity/dialog XML layouts
│       │   ├── values/                   # Strings, colors, dimens, themes
│       │   ├── xml/                      # file_provider_paths.xml
│       │   └── raw/                      # Alert sound files
│       └── AndroidManifest.xml           # App manifest (permissions, activities, service)
├── dashboard/                            # Web dashboard (separate module)
├── .github/workflows/                    # CI/CD workflows (GitHub Actions)
│   └── (build, test, release configs)
├── .planning/                            # This directory (GSD planning docs)
│   └── codebase/                         # Architecture/structure documents
├── build.gradle                          # Root project gradle config
├── gradle/                               # Gradle wrapper
└── README.md, LICENSE, THIRD_PARTY_NOTICES.txt
```

## Directory Purposes

**app/src/main/java/com/wf11/safealert/:**
- Purpose: All Kotlin source code organized by architectural layers
- Contains: 6 numbered packages (01_model through 06_utils) + root package (SafeAlertApp)

**01_model/:**
- Purpose: Immutable data classes
- Contains: AppMode (operation mode enum), BeaconProfile (stored beacon config)
- Key files: `BeaconProfile.kt`

**02_ble/:**
- Purpose: Bluetooth LE scanning, advertising, signal processing
- Contains: BleScanner (scan lifecycle), BleScanCallback (result handler), BleAdvertiser (TX), signal filters
- Key files: `BleScanner.kt`, `BleScanCallback.kt`, `BleAdvertiser.kt`, `BleConstants.kt`
- Dependencies: Android Bluetooth APIs, DevSettings

**03_service/:**
- Purpose: Core business logic (alert state machine, sound/vibration)
- Contains: BleService (LifecycleService), AlertSoundPlayer, VibrationHelper
- Key files: `BleService.kt` (most complex, ~3000 lines)
- Entry point: BleService (started via intent from MainActivity)

**04_firebase/:**
- Purpose: Remote database integration
- Contains: FirebaseConfig (init), FirebaseManager (DB operations)
- Key files: `FirebaseManager.kt`
- Used by: BleService (async echo priors), UpdateManager (APK downloads)

**05_ui/:**
- Purpose: Android Activities and UI rendering
- Contains: MainActivity (main), settings/manager activities, adapters
- Key files: `MainActivity.kt` (UI polling loop, ~2500 lines)
- Adapters location: `05_ui/adapter/`

**06_utils/:**
- Purpose: Shared utilities (config, registry, IMU, overlays)
- Contains: BeaconRegistry (SharedPreferences), DevSettings, ImuFusion, UwbRanger, OverlayManager
- Key files: `DevSettings.kt` (all config), `UwbRanger.kt` (UWB integration)
- Used by: All layers

**app/src/main/res/:**
- Purpose: Android resources (layouts, drawables, strings)
- layout/: Activity/dialog XML (data binding, ViewBinding)
- values/: strings.xml, colors.xml, dimens.xml, styles.xml
- drawable/: SVG/bitmap icons, vector drawables
- raw/: Alert sound MP3 files

## Key File Locations

**Entry Points:**
- `SafeAlertApp.kt`: Application class (Android framework entry)
- `05_ui/MainActivity.kt`: Main activity, user-facing UI
- `03_service/BleService.kt`: Foreground service (background logic)
- `AndroidManifest.xml`: App manifest (permissions, exported components)

**Configuration:**
- `build.gradle`: Version (versionName, versionCode), dependencies, SDK levels
- `06_utils/DevSettings.kt`: RSSI thresholds, scan period, echo calibration settings
- `AndroidManifest.xml`: Permissions, activities, service declaration

**Core Logic:**
- `03_service/BleService.kt`: Alert state machine, broadcast logic
- `02_ble/BleScanCallback.kt`: RSSI filtering pipeline, device detection
- `02_ble/BleAdvertiser.kt`: Outbound payload encoding/transmission
- `06_utils/ImuFusion.kt`: IMU-based motion state (IDLE/FORWARD/REVERSE/LOADING)

**Testing:**
- `app/src/test/`: Unit tests (if any)
- `app/src/androidTest/`: Instrumentation tests
- Test files follow standard naming: `*Test.kt`, `*Spec.kt`

## Naming Conventions

**Files:**
- PascalCase: `MainActivity.kt`, `BleService.kt`, `BeaconRegistry.kt`
- Layers prefixed: `02_ble/`, `03_service/`, etc.
- Adapters subfolder: `05_ui/adapter/`

**Directories:**
- PascalCase for logical groups (adapter, model, service, ui)
- Prefixed by layer number (01_model, 02_ble, etc.)

**Classes:**
- PascalCase: `MainActivity`, `BleService`, `KalmanFilter`
- Activity suffix: `MainActivity`, `BeaconManagerActivity`
- Adapter suffix: `BeaconListAdapter`

**Functions & Variables:**
- camelCase: `startScanning()`, `updateAlertState()`, `detectedDevices`
- Constants UPPER_SNAKE_CASE: `DEFAULT_RSSI_DANGER`, `CHANNEL_ID`, `TAG`

**Data Classes:**
- PascalCase: `AlertState`, `DeviceCache`, `BeaconProfile`
- Inline in companion objects if small scope

## Where to Add New Code

**New Feature (e.g., proximity-based geofencing):**
- Primary code: `03_service/BleService.kt` (add logic to processAlert)
- Model: `01_model/` (add GeofenceZone.kt if needed)
- Utils: `06_utils/` (if shared across services)
- UI: `05_ui/MainActivity.kt` or new activity
- Tests: `app/src/test/` (unit test) or `app/src/androidTest/` (integration)

**New BLE-related feature (e.g., UWB override logic):**
- Core: `02_ble/` (new class or extend existing)
- Integration: `02_ble/BleScanCallback.kt` (hook into scan callback)
- Service: `03_service/BleService.kt` (call from alert logic)
- Utils: `06_utils/UwbRanger.kt` (if distance-related)

**New Activity/UI Screen:**
- Layout: `res/layout/activity_*.xml` (data binding enabled)
- Activity: `05_ui/*Activity.kt` (inherit AppCompatActivity)
- Strings: `res/values/strings.xml` (user-visible text)
- Adapter (if list): `05_ui/adapter/*Adapter.kt`

**New Settings/Configuration:**
- Storage: `06_utils/DevSettings.kt` (SharedPreferences accessors)
- UI: `05_ui/BleSettingsActivity.kt` or `05_ui/DevSettingsActivity.kt`
- Layout: `res/layout/activity_ble_settings.xml` or similar

**Shared Utilities:**
- Location: `06_utils/` (Utils.kt or HelperClass.kt)
- Example: `UwbRanger.kt` (distance measurement), `ImuFusion.kt` (motion)
- Reusable: All layers can import from 06_utils

**Database/Firebase:**
- Manager: `04_firebase/FirebaseManager.kt` (add methods)
- Models: `01_model/` (data classes for Firestore)
- Async callbacks: Registered in `BleService` or activity (lifecycle-aware)

## Special Directories

**app/build/:**
- Purpose: Gradle build outputs (generated)
- Generated: Yes
- Committed: No (.gitignore)
- Contains: compiled classes, APK, manifest merger logs, databinding generated classes

**.gradle/:**
- Purpose: Gradle wrapper cache
- Generated: Yes
- Committed: No (.gitignore)
- Contains: downloaded Gradle distributions

**.planning/:**
- Purpose: GSD planning documents (this directory)
- Generated: No
- Committed: Yes
- Contains: ARCHITECTURE.md, STRUCTURE.md, CONVENTIONS.md, TESTING.md, CONCERNS.md

**.github/workflows/:**
- Purpose: CI/CD pipeline definitions (GitHub Actions)
- Generated: No
- Committed: Yes
- Contains: build.yml, test.yml, release.yml (if any)

**app/src/main/res/:**
- Purpose: Android resource files
- Generated: Partially (during build)
- Committed: Yes (source XML, images)
- Contains: layouts, strings, drawables, colors, themes

**app/src/main/AndroidManifest.xml:**
- Purpose: App manifest
- Generated: No (manually edited)
- Committed: Yes
- Contains: permissions, activities, service, receivers, provider declarations

---

*Structure analysis: 2026-08-23*
