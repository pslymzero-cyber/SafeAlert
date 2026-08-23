# Testing Patterns

**Analysis Date:** 2026-08-23

## Test Framework

**Runner:**
- JUnit 4 (version 4.13.2)
- AndroidJUnitRunner (instrumentation test runner configured in `build.gradle`)
- Kotlin 1.9.22 with coroutines support

**Assertion Library:**
- JUnit assertions (via `junit:junit:4.13.2`)
- Espresso for UI assertions (version 3.5.1)

**Run Commands:**
```bash
# Run all tests (unit + instrumentation)
./gradlew test
./gradlew androidTest

# Run specific test class
./gradlew test -k TestClassName
./gradlew androidTest -k TestClassName

# Coverage (if enabled)
./gradlew testDebugUnitTestCoverage
```

## Test File Organization

**Current Status:**
- **No test files exist yet.** Both `src/test/` and `src/androidTest/` directories are empty.
- Test infrastructure is configured but dormant (JUnit 4, Espresso dependencies available).

**Planned Location:**
- **Unit Tests (Local JVM):** `app/src/test/java/com/wf11/safealert/*/`
- **Instrumentation Tests (Android Device/Emulator):** `app/src/androidTest/java/com/wf11/safealert/*/`

**Naming Convention (when implemented):**
- Suffix `Test`: `BleFilterTest.kt`, `FirebaseManagerTest.kt`, `BeaconRegistryTest.kt`
- Match source class name with `Test` suffix in corresponding directory structure
- Example mapping:
  - Source: `app/src/main/java/com/wf11/safealert/02_ble/MedianFilter.kt`
  - Test: `app/src/test/java/com/wf11/safealert/ble/MedianFilterTest.kt` (unit) or `app/src/androidTest/...` (instrumentation)

## Test Structure

**Recommended Suite Organization (Kotlin):**
```kotlin
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class MedianFilterTest {
    
    private lateinit var filter: MedianFilter
    
    @Before
    fun setUp() {
        filter = MedianFilter(windowSize = 3)
    }
    
    @Test
    fun testPushReturnsMedianForOddWindowSize() {
        val result = filter.push("device1", -60)
        assertEquals(-60, result)
    }
    
    @Test(expected = IllegalStateException::class)
    fun testInvalidInputThrows() {
        // arrange, act, assert
    }
}
```

**Patterns:**
- **Setup:** `@Before` method initializes fixtures and mocks
- **Teardown:** `@After` cleanup (when needed) for resource release
- **Test naming:** `test<Scenario><Expected>()` or `<operation>_<condition>_<expectation>()`

## Mocking

**Framework:**
- JUnit 4's `@Before` for test doubles
- Manual mocks expected (no Mockito detected in dependencies)
- Spy pattern via composition for partial mocking

**Patterns (when tests are written):**

For singleton utilities (e.g., `BeaconRegistry`):
```kotlin
// Manual mock by providing test double in constructor/factory
class TestableBeaconRegistry(private val testPrefs: SharedPreferences) {
    // expose for testing while keeping production sealed
}
```

For Firebase operations (async callbacks):
```kotlin
@Test
fun testUploadBeaconSetCallsOnSuccess() {
    var successCalled = false
    FirebaseManager.uploadBeaconSet("test", "{}", 1, "sender") { success ->
        successCalled = success
    }
    // verify callback behavior
    assertTrue(successCalled)
}
```

For BLE operations (stateful):
```kotlin
class BleScannerTest {
    private lateinit var scanner: BleScanner
    private var capturedCallback: ScanCallback? = null
    
    @Before
    fun setUp() {
        // would need mock BluetoothLeScanner
        scanner = BleScanner(mockBluetoothScanner)
    }
}
```

**What to Mock:**
- Android framework components (`BluetoothLeScanner`, `SharedPreferences`, `Handler`)
- External services (`FirebaseDatabase`, `LocationManager`, `AudioManager`)
- Hardware APIs requiring emulator/device

**What NOT to Mock:**
- Pure business logic utilities (`MedianFilter`, `KalmanFilter`, `BeaconRegistry` serialization logic)
- Data classes and enums (`BeaconProfile`, `AppMode`)
- Utility functions without side effects

## Fixtures and Factories

**Test Data:**
When tests are implemented, use builder or factory patterns:

```kotlin
fun createTestBeaconProfile(
    uuid: String = "550e8400-e29b-41d4-a716-446655440000",
    label: String = "Test Beacon",
    type: String = "IBEACON"
) = BeaconProfile(
    uuid = uuid,
    label = label,
    type = type,
    addedAt = System.currentTimeMillis(),
    rssiOffset = 0,
    zoneMute = false,
    zoneEnterRssi = -65
)

fun createTestBeaconList(count: Int = 3) =
    (1..count).map { i -> createTestBeaconProfile(label = "Beacon $i") }
```

**Location:**
- Place fixtures in separate `*TestFixtures.kt` or inline in test class file
- Shared fixtures: `app/src/test/java/com/wf11/safealert/TestFixtures.kt` (unit tests)
- Android-specific: `app/src/androidTest/java/com/wf11/safealert/TestFixtures.kt`

## Coverage

**Requirements:** Not enforced (no coverage gates observed)

**View Coverage (when running):**
```bash
# After running tests, view coverage report
./gradlew testDebugUnitTestCoverage
# Find in: build/reports/coverage/debug/

# Instrumentation coverage
./gradlew createDebugCoverageReport
# Find in: build/reports/coverage/androidTest/debug/
```

**Target Areas (priority for future implementation):**
1. **Utilities (06_utils/):** `MedianFilter`, `KalmanFilter`, `BeaconRegistry` parsing/serialization
2. **Models (01_model/):** Data class serialization, enum behavior
3. **Firebase async operations:** Error callback paths, JSON parsing
4. **State management:** Alert state transitions, device detection lifecycle

## Test Types

**Unit Tests:**
- Scope: Pure functions, filters, data transformations (no Android framework)
- Location: `src/test/java/`
- Runners: JUnit 4 (local JVM)
- Examples to test:
  - `MedianFilter.push()` correctness for window calculations
  - `KalmanFilter` velocity and distance filtering
  - `BeaconRegistry.getAll()` parsing from SharedPreferences JSON
  - `FirebaseManager` serialization helpers (e.g., `sanitizeKey()`, `parseEchoBlob()`)

**Instrumentation Tests (Integration/Android):**
- Scope: Activities, Services, Firebase interactions, Android permissions
- Location: `src/androidTest/java/`
- Runners: AndroidJUnitRunner on emulator/device
- Examples to test:
  - `MainActivity` UI state updates from BroadcastReceiver
  - `BleService` lifecycle and broadcast sending
  - `FirebaseManager` actual database writes (or use test database)
  - Permissions handling in Activities

**E2E Tests:**
- Framework: Espresso 3.5.1 (available, no examples in codebase)
- Not currently implemented
- Could test: Full alert flow (start service → detect beacon → trigger sound → update UI)

## Common Patterns

**Async Testing:**

For coroutine-based operations:
```kotlin
@Test
fun testAsyncOperationCompletes() = runBlocking {
    // arrange
    val result = serviceUnderTest.fetchDataAsync()
    
    // act & assert
    assertEquals(expectedValue, result)
}
```

For callback-based operations (Firebase):
```kotlin
@Test
fun testFirebaseCallbackInvoked() {
    var resultCaptured: String? = null
    FirebaseManager.downloadBeaconSet("test-key") { json ->
        resultCaptured = json
    }
    
    // Note: Real test would use test database or mock callbacks
    Thread.sleep(500) // simulate async work (anti-pattern; use proper test helpers)
}
```

**Error Testing:**

Test both happy path and failure paths:
```kotlin
@Test
fun testParsingInvalidJsonReturnsEmpty() {
    val malformedJson = "invalid{"
    val result = BeaconRegistry.parseProfiles(malformedJson)
    
    assertEquals(emptyList(), result)  // should not throw
}

@Test(expected = IllegalArgumentException::class)
fun testInvalidRssiThrows() {
    val filter = MedianFilter()
    filter.push("device", 0)  // RSSI should be negative
}
```

## Android-Specific Testing Notes

**Permissions:**
- Instrumentation tests run with manifest permissions granted by default
- Runtime permissions (Android 6.0+) must be granted via `ActivityScenario` or `ActivityTestRule`

**Lifecycle Testing:**
- Use `ActivityScenario` for testing Activities with lifecycle events
- Example: Test `MainActivity` startup and permissions flow

**Service Testing:**
- Use `ServiceTestRule` or create service via Intent in test
- Mock/real `BroadcastReceiver` testing via `Context.sendBroadcast()`

**Asynchronous Operations:**
- Use `CountdownLatch` or `CompletableFuture` for callback-based APIs
- Espresso provides `IdlingResource` for waiting on custom async work

---

*Testing analysis: 2026-08-23*
