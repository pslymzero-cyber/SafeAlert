# External Integrations

**Analysis Date:** 2026-08-23

## APIs & External Services

**Firebase Realtime Database:**
- Purpose: Multi-device beacon state synchronization, alert logging, version metadata, beacon set sharing
  - Alerts: Stored under `/wf11/alerts/<YYYYMMDD>/<alertId>` with timestamp, device IDs, RSSI, alert level
  - Beacon Sets: Shared beacons uploaded to `/wf11/beacon_share/<sanitized_key>` for download by other devices (v1.1.17+)
  - Version Metadata: Stored at `/wf11/version` with latest version, APK download URL, changelog, force_update flag
  - Device Profiles: Beacon registry stored in SharedPreferences, synced via Firebase
  - SDK/Client: `firebase-database-ktx` (Kotlin extensions for Realtime Database)
  - Auth: REST API secret (FIREBASE_DB_SECRET) used in CI/CD for version updates; client reads via default Firebase auth rules

**Firebase Analytics:**
- Purpose: Event tracking and crash reporting (built-in, minimal instrumentation observed)
  - SDK/Client: `firebase-analytics-ktx`
  - Auth: Automatic (via google-services.json)

**GitHub API:**
- Purpose: Automated release publishing during CI/CD
  - Integration: GitHub Actions workflow creates release with APK artifact
  - Workflow: `.github/workflows/release.yml` - triggered on version tag push (v*.x.x)

## Data Storage

**Databases:**
- **Firebase Realtime Database (Remote):**
  - Connection: WebSocket via SDK (authenticated by google-services.json)
  - Client: `com.google.firebase:firebase-database-ktx`
  - Persistence: Offline cache enabled (`setPersistenceEnabled(true)` in `FirebaseConfig.init()` at `app/src/main/java/com/wf11/safealert/04_firebase/FirebaseConfig.kt:12`)
  - Root path: Configurable via `DevSettings.firebaseRoot` (default: "wf11")

**Local Storage:**
- **SharedPreferences (App Private)**
  - Persistence: Device-local settings storage (no synchronization)
  - Location: Private app data directory
  - Purpose: Device role (DEVICE/WALKER), BLE settings (RSSI thresholds, scan period, time-gate), Kalman filter presets, vibration/sound settings, beacon registry, UWB calibration offsets
  - Preference file: `dev_settings` (primary settings)
  - Access: `DevSettings` singleton at `app/src/main/java/com/wf11/safealert/06_utils/DevSettings.kt` - **no encryption**

**File Storage:**
- **APK Updates (External Files Cache):**
  - Downloaded updates stored in `context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)`
  - Lifecycle: Temporary (deleted after installation or if newer version already exists)
  - Purpose: In-app update mechanism via DownloadManager

## Authentication & Identity

**Auth Provider:**
- **Custom (Device-local):**
  - No backend authentication required
  - Device identification: Device MAC address + device UUID stored locally
  - Firebase access: Unauthenticated (public Realtime Database with rule-based access)
  - Implementation: Device role and identity managed via SharedPreferences and local BeaconRegistry

**Beacon Identity:**
- Beacons identified by MAC address (BLE advertising address) + 0x9ABC UWB advertisement opcode
- Profile metadata (display name, category: FORKLIFT/WALKER) stored locally and synced via Firebase beacon_share

## Monitoring & Observability

**Error Tracking:**
- Firebase Crashlytics (included via BOM, not explicitly instrumented)
- Log output: Logcat (`android.util.Log`) - development/debugging only
  - Key tags: "FirebaseManager", "UpdateManager", "BleService", "BleScanner", "FirebaseConfig"

**Logs:**
- Approach: No persistent logging in production
- Output: Real-time console via Logcat during app execution
- Location: Tag-based filtering in logcat (e.g., `adb logcat -s BleService`)

**Analytics:**
- Firebase Analytics events (client-side, minimal instrumentation)
- No custom event tracking observed beyond default Firebase events

## CI/CD & Deployment

**Hosting:**
- **GitHub Releases:** APK artifacts published to GitHub releases page
- **Firebase Realtime Database:** Version metadata served to installed apps for update checks

**CI Pipeline:**
- **GitHub Actions (ubuntu-latest runner)**
  - Triggered: On version tag push (pattern: `v*`)
  - Steps:
    1. Checkout code (actions/checkout@v7)
    2. Setup JDK 17 (actions/setup-java@v5)
    3. Restore `google-services.json` from GOOGLE_SERVICES_JSON GitHub Secret (base64-decoded)
    4. Restore debug keystore from DEBUG_KEYSTORE secret (base64-decoded to `$HOME/.android/debug.keystore`)
    5. Verify keystore fingerprint (4C:40:F0:35:E4:2C:78:D0:71:34:5F:EB:B6:23:4E:F7:56:F9:11:6B:7C:92:05:00:0A:14:5B:D0:F7:DF:19:21)
    6. Build debug APK via `./gradlew assembleDebug`
    7. Rename APK to `safealert-<version>.apk`
    8. Create GitHub Release and upload APK (softprops/action-gh-release@v3)
    9. Update Firebase Realtime DB `/wf11/version` with latest version, APK URL, force_update flag

**Build Secrets (GitHub):**
- `GOOGLE_SERVICES_JSON` - Firebase configuration (base64-encoded)
- `DEBUG_KEYSTORE` - Debug signing keystore (base64-encoded)
- `FIREBASE_DB_URL` - Firebase Realtime Database URL (for REST API calls)
- `FIREBASE_DB_SECRET` - Firebase REST secret (for authentication-free API access during CI)

## Environment Configuration

**Required env vars:**
- `GOOGLE_SERVICES_JSON` - GitHub Actions secret, base64-encoded Firebase project config
- `DEBUG_KEYSTORE` - GitHub Actions secret, base64-encoded Android debug keystore
- `FIREBASE_DB_URL` - GitHub Actions secret, Firebase Realtime Database URL (e.g., https://project-name.firebaseio.com)
- `FIREBASE_DB_SECRET` - GitHub Actions secret, Firebase Realtime Database secret token

**Runtime Configuration:**
- Firebase root path: `DevSettings.firebaseRoot` (default: "wf11", configurable via SharedPreferences KEY_FIREBASE_ROOT)
- No .env file support (Android native app pattern uses google-services.json + gradle.properties)

**Secrets location:**
- GitHub Actions secrets: Repository settings → Secrets and variables → Actions
- google-services.json: `app/src/main/google-services.json` (**.gitignored**, restored from CI secret)
- Debug keystore: `$HOME/.android/debug.keystore` (machine-local, restored in CI from secret)

## Webhooks & Callbacks

**Incoming:**
- None detected

**Outgoing:**
- Firebase Realtime Database: Direct REST PATCH to update version metadata during release (`UpdateManager.kt` line 32-52)
  - Endpoint: `{FIREBASE_DB_URL}/wf11/version.json?auth={FIREBASE_DB_SECRET}`
  - Method: PATCH
  - Payload: Latest version, APK URL, changelog, force_update flag
  - Caller: GitHub Actions CI workflow (`.github/workflows/release.yml` line 66-69)

**App-Internal:**
- BroadcastReceiver callbacks (local Intent broadcasts):
  - `BROADCAST_ALERT` - Alert state changes (broadcast to MainActivity)
  - `BROADCAST_DETECTED` - Beacon detection events (broadcast to UI)
  - `BROADCAST_BLE_STATUS` - BLE service status changes (broadcast to MainActivity)
  - `BROADCAST_LOCAL_STATE` - Local device state changes (broadcast to interested activities)

## Device & Hardware Integration

**Bluetooth/BLE:**
- Permissions: BLUETOOTH, BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT, ACCESS_FINE_LOCATION
- Scanner: BluetoothLeScanner (dual-role: scan + advertise simultaneously)
- Beacon broadcast: Custom 0x9ABC manufacturer data format (v1.1.30+)
- RSSI filtering: RssiPreFilter, Kalman Filter, Median Filter chains for distance estimation

**UWB (Ultra-Wideband):**
- Permission: UWB_RANGING
- Hardware requirement: Optional (gracefully degrades to BLE if unavailable)
- SDK: androidx.core.uwb 1.0.0-alpha09
- Purpose: Sub-meter precision distance measurement (primary authority if available)
- Calibration: Per-site and per-pair offsets stored in UwbCalibrator (SharedPreferences)

**Location Services:**
- Purpose: Required for BLE scanning (system requirement, not used directly)
- Permissions: ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION

**Sensors:**
- IMU (Accelerometer/Gyroscope): Used for velocity estimation via ImuFusion (app/src/main/java/com/wf11/safealert/06_utils/ImuFusion.kt)
- Audio: MODIFY_AUDIO_SETTINGS for alert sound playback (AudioManager)
- Vibration: VIBRATE permission for haptic feedback patterns

**Power Management:**
- WAKE_LOCK permission for background BLE scanning (PowerManager.WakeLock)
- Battery optimization exemption: REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
- Foreground service: FOREGROUND_SERVICE, FOREGROUND_SERVICE_CONNECTED_DEVICE (persistent notification required)

---

*Integration audit: 2026-08-23*
