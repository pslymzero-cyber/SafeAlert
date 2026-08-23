# Technology Stack

**Analysis Date:** 2026-08-23

## Languages

**Primary:**
- Kotlin 1.9.22 - Core application logic, activities, services, utilities
- Java 17 - Compilation target and JVM version

**Secondary:**
- XML - Resource definitions, layouts, manifests

## Runtime

**Environment:**
- Android Runtime (ART)
- Target SDK: 34 (Android 14)
- Min SDK: 26 (Android 8.0 Oreo)
- Compile SDK: 34

**Package Manager:**
- Gradle 8.3.2 (Android Gradle Plugin)
- Dependency management: Maven Central, Google Maven

## Frameworks

**Core:**
- Android Framework (SDK 34)
- AndroidX Core 1.12.0 - Modern Android APIs, backward compatibility
- AndroidX AppCompat 1.6.1 - Compatibility layer for activities and themes

**UI:**
- Android Material Design 1.11.0 - Material Design 3 components
- AndroidX ConstraintLayout 2.1.4 - Flexible layout system
- AndroidX RecyclerView 1.3.2 - List/grid views for beacon manager

**Architecture:**
- AndroidX Lifecycle 2.7.0 - Lifecycle-aware components
- AndroidX Lifecycle Service 2.7.0 - Lifecycle-aware service management
- LifecycleService (`com.wf11.safealert.service.BleService` : `LifecycleService`) - Background BLE scanning with lifecycle awareness

**Async:**
- Kotlin Coroutines 1.7.3 - Asynchronous operations, background tasks
- Lifecycle Runtime KTX 2.7.0 - lifecycleScope for coroutine execution

**Hardware/Sensing:**
- AndroidX UWB (Ultra-Wideband) 1.0.0-alpha09 - Precise distance measurement for proximity alerts
- Android Bluetooth APIs - BLE scanning/advertising, dual-role (broadcaster + scanner)

**Testing:**
- JUnit 4.13.2 - Unit test framework
- AndroidX Test JUnit 1.1.5 - Instrumentation test runner
- Espresso Core 3.5.1 - UI testing (configured but minimal coverage observed)

**Build/Dev:**
- AGP (Android Gradle Plugin) 8.3.2 - Build automation, APK compilation
- Kotlin Gradle Plugin 1.9.22 - Kotlin compilation integration
- Google Services Gradle Plugin 4.4.1 - Firebase integration, google-services.json processing

## Key Dependencies

**Critical:**
- Firebase Realtime Database (from BOM 32.7.2) - Alert history, beacon set sharing, version metadata storage
  - Why: Central data backend for multi-device beacon synchronization and alert persistence
- Firebase Analytics KTX (from BOM 32.7.2) - Event tracking (included but minimal instrumentation observed)
- AndroidX UWB 1.0.0-alpha09 - Ultra-Wideband ranging for sub-meter accuracy
  - Why: Phase 1+ proximity detection uses UWB as primary distance authority (BLE/RSSI as fallback)

**Infrastructure:**
- Kotlin Stdlib - Language runtime
- Kotlin Coroutines Android 1.7.3 - Dispatcher.Main.immediate for UI thread dispatch
- ViewBinding - Type-safe view references (enabled in build.gradle)

## Configuration

**Environment:**
- `google-services.json` (Firebase project configuration) - Path: `app/google-services.json`
  - Contains Firebase project ID, API key, database URL
  - Restored from GitHub Secrets during CI build (base64-encoded GOOGLE_SERVICES_JSON)
- `local.properties` - Android SDK path (`sdk.dir`)
- `gradle.properties` - JVM memory, AndroidX, Jetifier, Kotlin style settings

**Build:**
- `build.gradle` (root) - Plugin versions and dependency BOM pinning
- `app/build.gradle` - App module configuration
  - Namespace: `com.wf11.safealert`
  - Version: 1.1.70 (versionCode 126)
  - Source/target compatibility: Java 17
- `settings.gradle` - Module inclusion (`:app` only)
- `gradle/wrapper/gradle-wrapper.properties` - Gradle distribution version

**Debug Signing:**
- Debug keystore: `$HOME/.android/debug.keystore` (hardcoded path in signingConfigs)
  - Fingerprint verified in CI: `4C:40:F0:35:E4:2C:78:D0:71:34:5F:EB:B6:23:4E:F7:56:F9:11:6B:7C:92:05:00:0A:14:5B:D0:F7:DF:19:21`
  - Allows local and CI builds to use same signing key → in-place APK updates

**Release:**
- ProGuard rules: `app/proguard-rules.pro` - Obfuscation disabled (`minifyEnabled false` in release build)

## Platform Requirements

**Development:**
- JDK 17 (Temurin or equivalent)
- Android SDK 34 (Build Tools 34.x)
- Gradle 8.3.2 (via wrapper)
- Kotlin 1.9.22

**Production:**
- Deployment target: Android 8.0+ (minSdk 26)
- Runtime permissions: BLUETOOTH, BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT, ACCESS_FINE_LOCATION, FOREGROUND_SERVICE, UWB_RANGING, INTERNET, VIBRATE, WAKE_LOCK, MODIFY_AUDIO_SETTINGS, SYSTEM_ALERT_WINDOW, POST_NOTIFICATIONS, REQUEST_INSTALL_PACKAGES, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, RECEIVE_BOOT_COMPLETED
- Hardware requirements: Bluetooth LE (mandatory), UWB (optional, gracefully degrades to BLE/RSSI)

---

*Stack analysis: 2026-08-23*
