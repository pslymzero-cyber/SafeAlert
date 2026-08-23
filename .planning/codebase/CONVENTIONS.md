# Coding Conventions

**Analysis Date:** 2026-08-23

## Naming Patterns

**Files:**
- PascalCase for class/interface files: `BleScanner.kt`, `FirebaseManager.kt`
- camelCase for utility singleton objects: `beaconRegistry.kt`, `devSettings.kt`
- Descriptive names reflecting responsibility: `MedianFilter.kt`, `KalmanFilter.kt`

**Functions:**
- camelCase for all functions: `push()`, `getAll()`, `containsUuid()`
- Action verbs for mutating functions: `add()`, `remove()`, `save()`
- Predicate functions prefixed with `is`/`contains`/`find`: `isFull()`, `containsUuid()`, `findZoneProfileByUuid()`
- Private functions in companion objects (static helpers) use clear intent names: `mapScanMode()`, `sanitizeKey()`

**Variables:**
- camelCase for all variables: `isScanning`, `detectedDevices`, `hazardNear`
- Boolean flags prefixed with `is`/`has`/`can`/`should`: `isRunning`, `hazardNear`, `isMutedPublic`
- Mutable collections typed explicitly: `mutableMapOf()`, `mutableListOf()`, `ArrayDeque()`
- Short-lived loop variables acceptable: `i`, `n`, `c`, `f`, `s` (with context clarity)

**Types:**
- PascalCase for data classes: `BeaconProfile`, `DetectedRow`, `EchoDiffStats`
- PascalCase for enums: `AppMode`, following Android conventions
- Sealed classes for exhaustive pattern matching where needed

## Code Style

**Formatting:**
- Kotlin standard: 4-space indentation (implicit, not explicitly configured)
- Line length: Pragmatic, no strict enforcement observed (ranges 80–120+ chars based on readability)
- Spacing: Single blank line between methods; double blank line between logical sections

**Linting:**
- No explicit linting config file detected (no detekt.yml, .ktlint, or klint config)
- Build should enforce Android Lint defaults via AGP 8.3.2
- Manual code review expected given developer-driven quality gates in the codebase

**Documentation:**
- Extensive inline comments (Korean) explaining design trade-offs and algorithm choices
- KDoc-style JavaDoc comments (`/** ... */`) for public API functions with clear intent
- Example from `MedianFilter.kt`: Full explanation of filter design, window buffering strategy, and trade-offs in class-level KDoc

## Import Organization

**Order:**
1. Android framework imports (`android.*`)
2. AndroidX imports (`androidx.*`)
3. Google Play Services / Firebase (`com.google.*`)
4. Project internal imports (`com.wf11.safealert.*`)
5. Kotlin stdlib and standard library imports (organized alphabetically)

**Path Aliases:**
- None detected; full package paths always used

**Wildcard imports:**
- Avoided; explicit imports only (follows Kotlin best practices)

## Error Handling

**Patterns:**
- **For blocking operations:** Try-catch with `Log.e()` for errors (e.g., Firebase operations)
- **For parsing:** `runCatching { ... }.getOrDefault(defaultValue)` (defensive, non-throwing)
- **For optional deserialization:** `optString()`, `optInt()`, `optLong()`, `optBoolean()` from JSONObject (null-safe)
- **For async callbacks:** `addOnFailureListener { Log.e(TAG, "message: ${it.message}") }` pattern for Firebase tasks
- **For null safety:** Elvis operator `?:` and `getOrNull()` / `getOrDefault()` for safe extraction

**Example (BeaconRegistry.kt):**
```kotlin
fun getAll(): List<BeaconProfile> {
    val json = prefs.getString(KEY_LIST, "[]") ?: "[]"
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            BeaconProfile(
                uuid = obj.getString("uuid").uppercase(),
                label = obj.getString("label"),
                type = obj.optString("type", "IBEACON"),
                addedAt = obj.optLong("addedAt", 0L)
            )
        }
    }.getOrDefault(emptyList())
}
```

## Logging

**Framework:** `android.util.Log` (standard Android logging)

**Patterns:**
- Every class defines a companion object with `const val TAG = "ClassName"` at the top
- `Log.d(TAG, "message")` for debug info
- `Log.e(TAG, "error reason: ${it.message}")` for error tracking
- Logging includes: operation name, data IDs, RSSI values, timestamps where relevant
- Example (FirebaseManager.kt): `Log.e(TAG, "경보 저장 실패: ${it.message}")` with context

**Accessibility:**
- Localized log messages in Korean (design intent comments and error messages)
- English used for technical terms (UWB, RSSI, BLE, dBm)

## Comments

**When to Comment:**
- Algorithm design decisions and trade-offs (priority: explain WHY, not WHAT)
- Non-obvious performance choices (e.g., "throttle UI renders to 500ms to reduce GPU load")
- Workarounds and compatibility notes (e.g., "[v1.0.48 #5] Android OS scan API limitation...")
- Cross-layer dependencies and invariants (e.g., "v1.1.55 needs echoDiffLive synced with SharedPreferences")
- Complex state machines or conditional logic requiring context

**Comment Style:**
- Single-line: `// ` for short clarifications
- Multi-line: `// [version tag] detailed explanation` for design notes
- Block separators: `// ── section name ───────` for visual grouping in large files

**JSDoc/KDoc:**
- Public functions: Always include KDoc block with `@param` and `@return`
- Example (MedianFilter.kt):
```kotlin
/**
 * 신규 RSSI 표본을 윈도우에 넣고 현재 윈도우의 중앙값을 반환.
 *
 * @param deviceId 기기 식별자 (기기별 독립 윈도우)
 * @param rssi     원시 RSSI (dBm, 음수)
 * @return 윈도우 중앙값(임펄스 제거된 RSSI). 짝수 표본은 중앙 2개의 정수평균.
 */
fun push(deviceId: String, rssi: Int): Int { ... }
```

## Function Design

**Size:** 
- Range: 5–60 lines typical; larger functions broken into helper methods
- Large functions indicate complex state machines (e.g., `MainActivity.statusRunnable.run()` ~100 lines for comprehensive polling logic)

**Parameters:**
- 1–4 parameters typical; avoid long parameter lists
- Use data classes (`BeaconProfile`, `DetectedRow`) for bundled data instead of spreading across function signature
- Nullable parameters marked `Type?` with defaults via `?: defaultValue` pattern

**Return Values:**
- Prefer explicit return types (no implicit Unit suppression)
- Use `Boolean` for predicates (`isFull()`, `containsUuid()`)
- Use typed returns for data extraction (`List<BeaconProfile>`, `String?`, `Int`)
- Callback pattern common for async: `onResult: (Boolean) -> Unit` for Firebase operations

**Scope of Variables:**
- Variables declared as close to use as possible
- Mutable state marked `@Volatile` when accessed from multiple threads (e.g., `@Volatile var isScreenOn: Boolean`)
- Use of `val` and `var` follows immutability preference (val default, var only when mutation needed)

## Module Design

**Exports:**
- Singleton objects (`object BeaconRegistry`) for stateful utilities (preferred over static factories)
- Public methods named clearly for discovery (no getter/setter boilerplate; direct property access)
- Private helper functions marked `private` within companions

**Barrel Files:**
- Not used; imports are explicit and module-specific

**Architecture Layers (Numeric Directory Naming):**
- `01_model/` — Data classes and enums (`AppMode.kt`, `BeaconProfile.kt`)
- `02_ble/` — Bluetooth Low Energy scanning and filtering (`BleScanner.kt`, `MedianFilter.kt`, `KalmanFilter.kt`)
- `03_service/` — Android Services and background processing (`BleService.kt`, `AlertSoundPlayer.kt`)
- `04_firebase/` — Cloud storage and synchronization (`FirebaseManager.kt`, `FirebaseConfig.kt`)
- `05_ui/` — Activities and UI presentation (`MainActivity.kt`, `BleSettingsActivity.kt`)
- `06_utils/` — Utilities and helpers (`BeaconRegistry.kt`, `DevSettings.kt`, `UwbRanger.kt`)

**Dependency Direction:**
- `06_utils` (utilities) depends on nothing
- `03_service`, `02_ble` depend on `06_utils` and `01_model`
- `05_ui` depends on `03_service`, `02_ble`, `06_utils`
- `04_firebase` (isolated) depends on nothing; consumed by `03_service`

**Initialization:**
- `SafeAlertApp.kt` (Application) initializes singletons in order:
  1. `DevSettings.init(this)`
  2. `BeaconRegistry.init(this)`
  3. `UwbCalibrator.init(this)`
  4. `FirebaseConfig.init()`

---

*Convention analysis: 2026-08-23*
