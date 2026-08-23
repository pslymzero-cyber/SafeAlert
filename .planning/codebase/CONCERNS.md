# Codebase Concerns

<!-- refreshed: 2026-08-23 -->

**Analysis Date:** 2026-08-23

## Tech Debt

**Monolithic BleService Architecture:**
- Issue: `BleService.kt` is 3,899 lines with 80+ functions handling BLE scanning, alert state management, UWB distance measurement, audio/vibration muting, overlay UI, Firebase integration, calibration, zone management, and device lifecycle. Every new feature is bolted onto this single class.
- Files: `app/src/main/java/com/wf11/safealert/03_service/BleService.kt`
- Impact: Impossible to test components in isolation. Changes cascade unpredictably. Adding new features requires deep context of 30+ mutable maps and complex state transitions. High cognitive load leads to bugs.
- Fix approach: Refactor into feature-specific handlers (AlertStateMachine, UwbDistanceManager, CalibrationEngine, etc.) with clear state ownership. Extract data models for device tracking state. Use dependency injection to decouple components.

**Complex State Management with 30+ Mutable Maps:**
- Issue: State for each detected device is scattered across 40+ separate maps:
  - Alert tracking: `alertState`, `kalmanFilters`, `shadowFusionMap`, `trackingStateMap`
  - Hysteresis: `crossingStartMap`, `departingStartMap`, `recedingStartMap`, `recedePeakMap`
  - Streak counters: `rushFrameMap`, `dangerContactStreakMap`, `warningContactStreakMap`
  - Audio/display: `mutedDevices`, `dwellMutedLevelsMap`, `dwellLevelMap`, `suddenLabelMap`
  - UWB: `peerUwbSeenMap`, `uwbSampleAtMsMap`, `uwbSafeStreakMap`
  - Calibration: `echoDiffLive`, `firebaseLastSaveMap`
  - Filtering: `reverseRssiHist`, `oneSecBuffer`, `wakeRssiMap`, `lastKfVelMap`
  - Display: `pendingDisplayMap`, `deviceCategoryMap`, `deviceStateMap`, `deviceTurnMap`
  - Plus: `filterPreserveMap`, `timeGateWaiveSet`, `forwardBiasLatchMap`, `fastApproachStreakMap`, `peerInZoneMap`, `reverseRssiHist`, `reversePrepUntil`, `approachStreakStartMap`
- Files: `app/src/main/java/com/wf11/safealert/03_service/BleService.kt` (lines 384-655)
- Impact: No single source of truth for device state. Risk of maps becoming out-of-sync if cleanup paths diverge. Memory fragmentation from 40+ separate allocations per device. Synchronization complexity — volatile markers on some variables but not systematic. Garbage collection pressure when many devices are tracked.
- Fix approach: Create a `DeviceTrackingState` data class holding all state for one device (alert level, kalman filter, timestamps, streaks, mute status). Store `Map<String, DeviceTrackingState>` and centralize lifecycle cleanup in `DeviceTrackingState.clear()`.

**Rapid Release Cycle Without Test Coverage:**
- Issue: 70+ releases in ~3 months (v1.0.1 to v1.1.70), with complex algorithm changes in nearly every version (Kalman presets, UWB gate changes, shadow fusion, echo calibration, proximity logic). No unit or integration tests.
- Files: Repository history; `app/build.gradle` shows versionName="1.1.70" versionCode 126
- Impact: Each release is a blind push. Regressions are discovered by users, not CI. Memory of why specific thresholds were chosen is lost. Complex logic like `applyDepartingHysteresis` (line 1386) and `processAlert` (line 1406+) cannot be verified. Rollback path requires git history spelunking.
- Fix approach: Add unit tests for filters (KalmanFilter, MedianFilter, RssiPreFilter), alert logic (level calculation, hysteresis), and UWB decision gates. Start with critical paths: `calcLevelWithHysteresis`, `processAlert` state transitions, UWB Case A/B judgment. Freeze algorithm constants in test data to prevent silent changes.

## Known Bugs

**UWB State Inconsistency Under High Concurrent Load:**
- Symptoms: Multiple devices with UWB capability cause session conflicts. Single-session hardware (most phones) cannot serve multiple UWB pages simultaneously, leading to RSSI fallback and visibility into fewer devices at once. Case A (UWB judgement) and Case B (RSSI fallback) decisions may flip unexpectedly when 6+ devices are in range.
- Files: `app/src/main/java/com/wf11/safealert/06_utils/UwbRanger.kt` (multicast coordination); `app/src/main/java/com/wf11/safealert/03_service/BleService.kt` (judgeUwbOnly line 2595, uwbJudgeModeExclusive line 2554)
- Trigger: Deploy in warehouse with 8+ forklift/walker pairs all within 15m range. Observe siren cutting in/out on same device pair every 1-2s as UWB session flops between pairs.
- Workaround: Disable UWB via DevSettings.uwbExclusiveJudgeEnabled toggle. Fallback to pure RSSI (Case B), which is deterministic.
- Root cause: UwbRanger selects which pair gets the single UWB session by role (vehicle > pedestrian) and fullId tiebreak, but continuous device motion causes rank changes every frame. Session teardown/re-creation latency (250ms rejoin) creates gaps where no accurate distance is available, forcing RSSI assumption that contradicts actual UWB state.

**Device Lost Cleanup Race Condition:**
- Symptoms: After prolonged scanning (>2 hours), app becomes sluggish. Memory usage grows. Late-bound alert sounds cut out or stutter. Overlay rendering lags.
- Files: `app/src/main/java/com/wf11/safealert/03_service/BleService.kt` (onDeviceLost line 1127, healthCheck line 3143)
- Trigger: Keep 20 devices rotating in/out of BLE range for several hours. Observe heap size climbing. Some onDeviceLost callbacks may be skipped if BleService crashes or receiver is temporarily unregistered.
- Root cause: Maps are not cleared atomically. If onDeviceLost is called while processAlert is executing for the same deviceId, one function may clear a map entry while the other is reading it. filterPreserveMap (line 1428) holds snapshot for 30s, but if device doesn't re-enter within window, the entry leaks (only pruned by healthCheck every ~1min).
- Fix approach: Use `synchronized(deviceStateLock) { ... }` or atomic clear operations. Make filterPreserveMap TTL-aware in a periodic sweep instead of defer-clear on re-entry. Audit all map.remove() calls to ensure they're called exactly once per device lifecycle.

**Kalman Filter Warm-Up Injection Overflow:**
- Symptoms: First alert for a newly detected device may not trigger if signal starts very weak (RSSI << -100). Cold-start injection attempts to seed kf.injectWarmup() but if inputRssi is saturated at preset min, filter never reaches WARNING level even as device approaches.
- Files: `app/src/main/java/com/wf11/safealert/03_service/BleService.kt` (injectWarmup call line 1454); `app/src/main/java/com/wf11/safealert/02_ble/KalmanFilter.kt` (injectWarmup logic)
- Trigger: Start app in dead zone (RF-shielded area), walk toward metal warehouse. First detection RSSI=-120. Device is 15m away but initial alert is delayed until RSSI improves naturally.
- Workaround: Wait 2-3 seconds for Kalman to converge, or move slightly closer to trigger stronger initial signal.
- Impact: Reduces reaction time to slow-moving threats (forklift backing up slowly into warehouse).

## Security Considerations

**No Code Obfuscation or Minification in Release Builds:**
- Risk: Reverse engineering app is trivial. Algorithm thresholds (RSSI danger levels, alert distances, role detection heuristics) can be extracted and spoofed by malicious actors with cheap BLE devices.
- Files: `app/build.gradle` (lines 31-39): `minifyEnabled false`, no ProGuard rules
- Current mitigation: Firebase integration and signed APK prevent casual repackaging, but APK can still be decompiled by tools like APK Studio.
- Recommendations: Enable ProGuard/R8 minification in release builds. Add `minifyEnabled true` and define sensitive class/method name obfuscation rules. Bump targetSdk 35+ to enable R8 by default.

**Unencrypted Alert Logging to Firebase:**
- Risk: Serialized alert data (deviceId, walkerId, rssi, level, timestamp) flows to Firebase Realtime Database. If Firebase Rules are misconfigured, logs are world-readable. Alert logs can reveal workplace patterns (when incidents spike, which areas are dangerous).
- Files: `app/src/main/java/com/wf11/safealert/04_firebase/FirebaseManager.kt` (saveAlert line 16, uploadBeaconSet line 48)
- Current mitigation: Firebase Rules default to authenticated read/write, but if misconfigured or if user's Google account is compromised, data is exposed.
- Recommendations: (1) Encrypt payload before transmission: AES-GCM with key stored in AndroidKeyStore. (2) Implement Firebase Rules to enforce UID ownership (each user sees only their own alerts). (3) Add data retention policy (auto-delete after 30 days in Firebase). (4) Log only alert level, not full RSSI; omit deviceId if not essential.

**Broadcast Receiver Permissions Not Scope-Limited:**
- Risk: BroadcastReceivers registered for implicit intents (`BluetoothAdapter.ACTION_STATE_CHANGED`, volume changes, screen state) can be spoofed by other apps if permissions are not enforced.
- Files: `app/src/main/java/com/wf11/safealert/03_service/BleService.kt` (registerReceiver calls line 852-861); `app/src/main/AndroidManifest.xml` (not reading intent flags)
- Current mitigation: Android 12+ requires `RECEIVER_NOT_EXPORTED` flag (or explicit intent), but code doesn't enforce.
- Recommendations: Use `registerReceiver(..., IntentFilter(), Context.RECEIVER_EXPORTED, null)` for system broadcasts that must be public. Consider replacing implicit broadcast listening with direct system API calls (e.g., BluetoothAdapter.isEnabled() instead of listening for state change).

**UWB/BLE Payload Decoding Without Validation:**
- Risk: Remote device payload (16-bit role/state) is decoded without length/format validation. If peer sends malformed ServiceData, `decodeCategory()`, `decodeState()`, `decodeRisk()` may read out-of-bounds or decode garbage, triggering spurious alerts.
- Files: `app/src/main/java/com/wf11/safealert/03_service/BleService.kt` (processAlert line 1410-1412); `app/src/main/java/com/wf11/safealert/02_ble/BleConstants.kt` (decoders)
- Current mitigation: Kotlin type system ensures return values are Int, but no range check post-decode.
- Recommendations: Add enum validation after decode. If decoded value is outside expected CATEGORY/STATE/RISK ranges, log warning and treat as UNKNOWN (safe default).

## Performance Bottlenecks

**Real-Time Kalman Filtering on Main Thread:**
- Problem: Every BLE scan result triggers `processAlert()` which runs 2D Kalman update (line 1511), median filter (line 1473), EMA pre-filter (line 1507), P-EMA post-filter (line 1519), and 5+ threshold gates, all on the scan callback thread (main thread in API 31+). With 20+ devices in range, this can cause frame drops in UI.
- Files: `app/src/main/java/com/wf11/safealert/03_service/BleService.kt` (processAlert lines 1406-2554); `app/src/main/java/com/wf11/safealert/02_ble/KalmanFilter.kt`
- Cause: BleService.onDeviceDetected callback (line 1092) is invoked on scan callback thread. processAlert does not dispatch to background thread.
- Improvement path: Move heavy math (Kalman, filters, calibration lookups) to a background coroutine. Pass raw RSSI to background job, receive decision via callback. Use WorkQueue to debounce rapid-fire detections (already 120ms FREQUENT throttle in v1.1.41 helps, but not systematic).

**UWB Ranging Session Churn with High Device Count:**
- Problem: With 6+ devices in range, UwbRanger continuously creates/destroys ranging sessions to switch which device is being measured (multicast limit). Session creation takes 200-500ms, during which distance data is stale. Rapid device motion causes rank flips and thrashing.
- Files: `app/src/main/java/com/wf11/safealert/06_utils/UwbRanger.kt` (session management, REJOIN_DELAY_MS=250 line 73)
- Cause: Single-session hardware constraint (most phones) + priority switching logic based on RSSI rank at each frame.
- Improvement path: Pre-compute stable rank order (e.g., once per 1s instead of every frame). Use session timeout/release to reduce teardown delay. Consider switching to MultiDW to measure multiple devices in one round-trip (if hardware supports).

**Echo Calibration Histogram Accumulation Without Decay:**
- Problem: `echoDiffLive` map (line 108) accumulates histogram buckets for every peer pair without windowing. Over days, size grows unbounded, increasing lookup latency in `echoCalAppliedDb()`.
- Files: `app/src/main/java/com/wf11/safealert/03_service/BleService.kt` (echoDiffLive, persistEchoAll, loadEchoPriors)
- Cause: No age-based eviction. Old histograms remain in memory even if device pairs haven't been seen in weeks.
- Improvement path: Add timestamp to EchoDiffStats, purge entries older than 24h in persistEchoAll. Compact histograms to top N buckets only.

## Fragile Areas

**Alert State Escalation Logic:**
- Files: `app/src/main/java/com/wf11/safealert/03_service/BleService.kt` (processAlert lines 1700-2500, calcLevelWithHysteresis line 1228, applyDepartingHysteresis line 1386)
- Why fragile: Alert level is determined by a cascade of conditions:
  1. Hard gate (heuristic RSSI/2-channel check) — can suppress valid alerts
  2. Time-Gate (approaching-time coherence) — adds latency, breaks fast threats
  3. TTC estimate (lines 1315-1333) — depends on velocity which is noisy for slow approach
  4. Payload risk offset (line 1254) — role/state decoding is complex
  5. Hysteresis (applyDepartingHysteresis) — holds DANGER even after departure, can mask new approach
  6. Shadow fusion boost (line 1499) — IMU-driven secondary, adds another variable
  7. Echo calibration (line 1604) — dynamic offset that can shift thresholds mid-alert
  
  Any gate failure = missed alert. Complex interdependencies mean changes to one gate affect others unexpectedly. History of bugs (v1.1.32→v1.1.46 UWB gate churning) shows this area is high-risk.
- Safe modification: Add comprehensive unit tests for each gate in isolation. Test combinations of conditions. Document intended behavior before code review. Use feature flags to rollback individual gates without full revert.
- Test coverage: Zero. This logic has no tests, only integration testing via manual warehouse demos.

**UWB Case A/B Judgment Flip:**
- Files: `app/src/main/java/com/wf11/safealert/03_service/BleService.kt` (uwbJudgeModeExclusive line 2554, judgeUwbOnly line 2595)
- Why fragile: Case A (UWB authority) vs Case B (RSSI fallback) decision depends on:
  - UWB ranging session exists and is active (not stuck in zombie state)
  - Distance samples are fresh (< 1s old, line 2566)
  - Device pair is in UWB hardware capability list (context-dependent)
  - No session errors in recent ~10s window
  
  Edge case: Session is opening but not yet measuring → Case A false positive → instant DANGER even if distance unknown. Session closes → no callback → Case B takes over late, creating jitter. Memory of session state (peerUwbSeenMap) can become stale if device goes to sleep.
- Safe modification: Add explicit session-state enum (INIT, ACTIVE, STALE, CLOSED) with strict transitions. Require N consecutive fresh samples before committing to Case A. Log all Case A/B flips for debugging.
- Test coverage: Zero.

**Reverse Approach Detection Heuristic:**
- Files: `app/src/main/java/com/wf11/safealert/03_service/BleService.kt` (reverseRssiHist, reversePrepUntil, window logic lines 1540-1558)
- Why fragile: Attempts to detect rapid signal increase (reverse/backing up) by measuring trend in first half of 1.2s window vs current level. Conditions:
  - First half trend must be stable/weak (< tolerance, default unspecified)
  - Second half must show rise > threshold (default unspecified)
  - Remote must be in FORWARD state (not IDLE)
  
  Problem: Thresholds are hardcoded in DevSettings with no visibility into actual performance. If warehouse has reflections/echoes, trend may flip spuriously. If device stops moving mid-window, trend reverses but intent is unclear. Latch duration (reversePrepHoldMs) prevents quick re-fire but no metrics on false positive rate.
- Safe modification: Instrument with counters: how often latch fires, how often it correctly predicts collision vs false alarm. Add visualization to developer settings. Require evidence from multiple sources (IMU jerk, velocity direction from multiple frames) before trusting reverse prep.
- Test coverage: Zero.

**Dwell Mute Timeout Logic:**
- Files: `app/src/main/java/com/wf11/safealert/03_service/BleService.kt` (dwellLevelMap, dwellSinceMap, dwellMutedLevelsMap, updateDwellMute line 2985, isDwellMuted line 3010)
- Why fragile: Mute timeout per device per level requires tracking entry time for each level independently. If device oscillates between WARNING/DANGER, entry times overlap and cleanup logic can leave stale mutes. If device is lost during dwell period, onDeviceLost clears but dwell TTL has no backup cleanup.
- Safe modification: Make dwell mute tuple-keyed: (deviceId, level, entryTime). Auto-clear via scheduled task every 10s instead of defer-clear. Add explicit dwell-mute state to DeviceTrackingState.

## Scaling Limits

**Map-Based State Scalability:**
- Current capacity: Tested with 20-30 concurrent devices in range. Each device multiplies by 40+ map entries.
- Limit: Android memory budget for foreground service ~300MB. With 100 maps of Int/Long/String values per device, 50 devices = 2MB state overhead (acceptable), but GC pressure increases. Beyond 50 concurrent devices, expect 200+ms GC pauses.
- Scaling path: (1) Lazy-load maps only for active alerts (alertState only), not all 40. (2) Use memory-efficient data structures: IntMap/LongMap from Kotlin collections for numeric keys. (3) Implement age-based eviction: auto-forget devices after 24h no detection. (4) Store historical data (alerts, calibration) in SQLite instead of in-memory maps.

**UWB Hardware Multicast Limit:**
- Current capacity: UwbRanger.MULTICAST_MAX = 6 devices (line 77). If 1 phone is measuring 6 targets simultaneously, it cannot measure a 7th without dropping one.
- Limit: 7th device detection is delayed 250ms+ (REJOIN_DELAY_MS), during which no UWB distance is available. If 7th device is moving fast, detection latency misses window.
- Scaling path: (1) Upgrade hardware capability detection: query device's actual max and cap MULTICAST_MAX accordingly. (2) Implement priority queue: measure most-dangerous (closest, fastest approach) 6 only. (3) Use multi-frame round-robin for devices 7+: measure top 6 one frame, next 6 following frame, reorder each frame.

**BLE Scan Result Rate Under High Density:**
- Current capacity: Kalman-per-device overhead ~1KB, filters ~500B. OnDeviceDetected callback bursts to 5-20 Hz per device with 20+ devices = 100-400 callbacks/s. Android scan callback thread can handle ~1000/s before dropping, so headroom exists but utilities like overlay rendering on main thread may struggle.
- Scaling path: (1) Request lower scan duty cycle (not continuous 120ms). (2) Batch callbacks: collect detections, process 10 at a time in background. (3) Profile overlay rendering — may be the bottleneck, not Kalman.

## Dependencies at Risk

**androidx.core.uwb:uwb:1.0.0-alpha09:**
- Risk: Pre-release library. No stability guarantees. API may change, bugs may not be fixed in this branch.
- Impact: UWB functionality (30-40% of alert logic in recent versions) is built on unstable foundation. If Google shifts UWB API in upcoming release, major refactoring required.
- Migration plan: (1) Monitor androidx.core.uwb release notes for 1.0.0-beta or stable. (2) Plan upgrade path: extract UwbRanger interface so UwbRanger implementation can be swapped. (3) Test against beta versions early. (4) Have fallback plan to disable UWB and revert to RSSI-only builds if library becomes unmaintained.

**com.google.firebase:firebase-database-ktx:**
- Risk: Dependency on Google Firebase Realtime Database. If service degrades or changes pricing, app loses alert logging and beacon sharing.
- Impact: Lost alerts cannot be replayed/investigated. Beacon sharing feature breaks.
- Migration plan: Implement local-first storage (SQLite) with optional cloud sync. Firebase can be replaced with generic REST API to different backend.

**Google Play Services Bluetooth:**
- Risk: App is tied to vendor-specific BLE APIs. Android version upgrades may change behavior (scan throttling, address randomization).
- Impact: Alert latency is device-specific and hard to predict.
- Mitigation: Comprehensive device-by-device testing on Android 12, 13, 14, 15+ during each major release.

## Missing Critical Features

**No Offline Mode:**
- Problem: Alert logging depends on Firebase. If network is down, alerts are not logged (sendFailure callback is silent, line 27 FirebaseManager just logs error).
- Blocks: Cannot audit near-miss incidents if network was flaky during shift. Cannot correlate app-side decision with on-site camera footage if timestamp is unknown.
- Solution: Implement local SQLite queue. Save alerts locally even if Firebase write fails. Sync queue when network returns. Expose "Pending Sync" indicator in UI.

**No Geofencing-Based Profiles:**
- Problem: Single set of alert thresholds for all locations. High-noise warehouse requires different calibration than quiet office.
- Blocks: Cannot optimize for site-specific RF environment without global recalibration every time job site changes.
- Solution: Implement location-based profiles. On-device GPS or Bluetooth beacons to detect site entry. Load pre-calibrated RSSI thresholds (echoCal, UWB offsets) from Firebase per site. Allow admin to upload profiles.

**No Decision Tree Transparency:**
- Problem: User/safety officer cannot see why alert was issued or suppressed. If safety incident occurs, no audit trail of decision logic.
- Blocks: Cannot debug why alert missed a hazard or falsely alarmed.
- Solution: Add AlertDecisionLog that records every gate decision (hard gate: PASS/BLOCK, time-gate: latency, TTC: value, payload offset: value, final level: SAFE/WARNING/DANGER). Log to local DB and sync to server. Expose in developer UI.

## Test Coverage Gaps

**Alert Escalation/De-Escalation Logic - 0% coverage:**
- What's not tested: The entire processAlert function (lines 1406-2554) contains ~1000 lines of complex conditional logic:
  - Kalman filter state management
  - Hysteresis state machine (APPROACHING, CROSSING, DEPARTING)
  - Hard gate, time-gate, TTC, payload offset gates
  - Shadow fusion IMU boost
  - Echo calibration application
  - UWB vs RSSI judgment
  - Dwell mute timeout
  
  None of this is unit tested. Only integration-tested manually in warehouses by pslym over 70+ releases.
- Files: `app/src/main/java/com/wf11/safealert/03_service/BleService.kt` (lines 1406-2554)
- Risk: Regressions are found by users, not CI. Thresholds can be changed accidentally. Complex state machines can deadlock (impossible states).
- Priority: **High** — This is the safety-critical path. Every change should be regression-tested.

**Kalman Filter Edge Cases - 0% coverage:**
- What's not tested: KalmanFilter.kt 152 lines. Filter is used for every device velocity/acceleration estimation:
  - Cold-start warmup injection with seedVel (line 1454)
  - Adaptive Q factor from IMU (line 1511)
  - Preset switching (FAST/NORMAL/SMOOTH) mid-stream (line 1468)
  - Reset on device re-entry (line 1437)
  
  No tests for: filter NaN/Inf handling, covariance explosion, negative velocities from reflection noise.
- Files: `app/src/main/java/com/wf11/safealert/02_ble/KalmanFilter.kt`
- Risk: Invalid math (NaN) can propagate to alert logic, causing crashes or silent errors.
- Priority: **Medium** — Math-critical but downstream from tested RSSI input.

**UWB Session State Machine - 0% coverage:**
- What's not tested: UwbRanger.kt session lifecycle:
  - Session creation with address derivation (line 44, sessionId from address)
  - Multicast dynamic add/remove of controlees (line 35)
  - Case A/B judgment state transitions (line 2554)
  - Zombie session detection (line 72, RESTART_BACKOFF_MS)
  - Re-join debounce (line 73, REJOIN_DELAY_MS)
  
  No mocks for UwbManager or RangingResults. No injection of range failures.
- Files: `app/src/main/java/com/wf11/safealert/06_utils/UwbRanger.kt`
- Risk: Session state can become inconsistent. Range data stalecheck (freshUwbDistM line 2566) may not work as intended if liveInitError is set but session is still active.
- Priority: **High** — UWB is critical path for v1.1.30+. Bugs here cause siren flapping.

**Firebase Integration Error Paths - 0% coverage:**
- What's not tested: FirebaseManager.kt handles failures in addOnFailureListener callbacks (lines 27, 59, 78). No tests for:
  - Network timeout
  - Auth failure (permission denied)
  - Database offline
  - Quota exceeded
  
  Callbacks just log errors. No retry, no user notification.
- Files: `app/src/main/java/com/wf11/safealert/04_firebase/FirebaseManager.kt`
- Risk: Silent failures. User thinks alerts are being logged but Firebase is down. Safety officer has no record of incidents.
- Priority: **Medium** — Not critical to alert logic but critical to compliance/audit.

**Permission Runtime Checks - Partially tested:**
- What's not tested: Dynamic permission scenarios:
  - User grants BLE_CONNECT but not BLUETOOTH_SCAN (split permissions)
  - User revokes permission mid-scan
  - User enables then disables battery optimization
  - UWB_RANGING permission absent on non-UWB hardware
  
  Permissions are checked at startup (hasAllPermissions) but not re-checked after user returns from settings.
- Files: `app/src/main/java/com/wf11/safealert/03_service/BleService.kt` (onStartCommand line 923, permission checks scattered)
- Risk: If user revokes permission, app may crash when trying to scan (unhandled exception in BluetoothLeScanner callback).
- Priority: **Low** — Edge case but Android 12+ requires handling.

---

*Concerns audit: 2026-08-23*
