package com.wf11.safealert.utils

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * IMU fusion — motion detection from linear acceleration
 *
 * BLE RSSI can jump suddenly from body-block or reflections.
 * The accelerometer cross-checks real movement to suppress false TTC alarms,
 * and Kalman Q adapts: stronger smoothing when stationary, faster tracking when moving fast.
 *
 * [motionScore]
 *   ~0.0  : stationary   (RSSI change = noise or body-block)
 *   ~1.0  : normal walking
 *   ~2.0+ : fast motion  (RSSI change = likely a real approach)
 */
object ImuFusion {

    private const val TAG = "ImuFusion"

    // ~1 s window @ 50Hz
    private const val WINDOW_SIZE          = 50
    private const val STATIONARY_THRESHOLD = 0.15f   // m/s² RMS; at or below → stationary candidate
    private const val FAST_THRESHOLD       = 2.5f    // m/s² RMS; at or above → fast motion

    /**
     * Confirmed-stationary window (samples).
     * Must stay at or below STATIONARY_THRESHOLD for ~0.5 s (25 samples @ 50Hz) in a row
     * to be declared confirmed stationary → blocks multipath-fading ghost alarms at the source.
     * Resets to 0 immediately on motion (mag >= STATIONARY_THRESHOLD).
     */
    private const val STATIONARY_CONFIRM_FRAMES = 25

    // 3-state motion detection layer for the dynamic payload.
    //   STATE_* values equal BleConstants.MOTION_STATE_* and are sent in the 1-byte ServiceData.
    //   Sits on top of the stationary check (isStationary) and Q scale (adaptiveQFactor) without changing them.
    private const val STATE_STATIONARY = 0x00
    private const val STATE_NORMAL     = 0x01
    private const val STATE_SUDDEN     = 0x02
    // Sudden stop: linear acceleration magnitude (m/s²) above this (harder than fast motion, FAST_THRESHOLD=2.5)
    private const val SUDDEN_ACCEL_THRESHOLD = 5.0f
    // Sharp turn: absolute gyro Z-axis rate (rad/s) above this
    private const val SUDDEN_GYRO_THRESHOLD  = 1.5f
    // Hold 0x02 for this long after entering it → prevents frequent flicker (hysteresis debounce)
    private const val SUDDEN_HOLD_MS         = 1500L

    // Cornering (sharp turn) threshold — cornering when |heading rate| (deg/s) is at or above this.
    //   Gentle walking/driving curves (<~40°/s) are normal; only sharp curves and turning in place (>~60°/s) count.
    private const val CORNERING_RATE_THRESHOLD = 60f
    // EMA smoothing factor for the cornering turn rate (noise suppression). Near 1 = responsive, 0 = insensitive.
    private const val TURN_RATE_EMA          = 0.4f
    // Turn-direction broadcast threshold (deg/s) — left/right turn when |heading rate| is at or above this.
    //   Lower than the cornering threshold (60°/s) so gentle lane changes and turn entries are broadcast early
    //   (for peer display and alerts).
    private const val TURN_DETECT_THRESHOLD  = 20f
    // Local mirror of BleConstants TURN_* (ImuFusion does not import BleConstants).
    private const val TURN_STRAIGHT = 0b00
    private const val TURN_LEFT     = 0b01
    private const val TURN_RIGHT    = 0b10

    private var sensorManager: SensorManager? = null
    @Volatile private var isRunning = false

    // Consecutive stationary frame counter (guarded by lock)
    @Volatile private var stationaryFrameCount = 0

    // Stationary↔moving change hook — BleService subscribes.
    //   The isStationary formula and thresholds are untouched; it fires only at the moment the state changes.
    //   On motion it fires with zero-frame (truly 0 s) delay → BleService wakes advertising at once
    //   (keepAdvertiseWhileMoving). Its scan eco toggle is a no-op: the rest scan mode equals the active one.
    @Volatile var onStationaryChanged: ((Boolean) -> Unit)? = null
    @Volatile private var lastNotifiedStationary = false

    // 3-state motion change hook — BleService subscribes and updates the ServiceData state code
    //   via BleAdvertiser.updateState().
    @Volatile var onMotionStateChanged: ((Int) -> Unit)? = null
    @Volatile private var lastNotifiedMotionState = STATE_STATIONARY
    // Time of the last sudden change (sudden stop/sharp turn), elapsedRealtime. 0x02 is held during the HOLD window.
    @Volatile private var lastSuddenMs = 0L

    private val accelBuffer = ArrayDeque<Float>()
    private val lock = Any()

    // Cornering detection from the game rotation vector (gyro + accelerometer, no magnetometer → immune to
    //   magnetic interference). Not transmitted (internal only); used only to judge whether my equipment is
    //   turning sharply. Exposes the rate of change (deg/s) of heading (travel direction, deg) as
    //   turnRateDegPerSec.
    private val gameRotMatrix = FloatArray(9)
    private val gameOrient    = FloatArray(3)
    @Volatile private var lastHeadingDeg = Float.NaN
    @Volatile private var lastHeadingMs  = 0L
    @Volatile var turnRateDegPerSec: Float = 0f
        private set
    @Volatile var hasGameRotation: Boolean = false
        private set
    /** Whether cornering now — |turn rate| ≥ CORNERING_RATE_THRESHOLD; triggers the BleService Time-Gate extension. */
    val isCornering: Boolean get() = hasGameRotation && abs(turnRateDegPerSec) >= CORNERING_RATE_THRESHOLD

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // Game rotation vector → heading rate (cornering). Not transmitted (internal only).
            //   No magnetometer, so immune to magnet interference.
            //   Not used to raise alarms; only to decide the Time-Gate extension.
            if (event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
                SensorManager.getRotationMatrixFromVector(gameRotMatrix, event.values)
                SensorManager.getOrientation(gameRotMatrix, gameOrient)
                val headingDeg = Math.toDegrees(gameOrient[0].toDouble()).toFloat()
                val nowMs = SystemClock.elapsedRealtime()
                if (!lastHeadingDeg.isNaN() && lastHeadingMs != 0L) {
                    val dtSec = (nowMs - lastHeadingMs) / 1000f
                    if (dtSec > 0.001f) {
                        // Wrap the heading difference to -180..180 (removes the 360° boundary jump)
                        var dHeading = headingDeg - lastHeadingDeg
                        while (dHeading > 180f)  dHeading -= 360f
                        while (dHeading < -180f) dHeading += 360f
                        val rate = dHeading / dtSec
                        // EMA smoothing — suppresses momentary spikes from sensor noise
                        turnRateDegPerSec = TURN_RATE_EMA * rate + (1f - TURN_RATE_EMA) * turnRateDegPerSec
                    }
                }
                lastHeadingDeg = headingDeg
                lastHeadingMs  = nowMs
                return
            }

            // Gyro: Z-axis rate spike → sharp turn (0x02) trigger
            if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
                if (abs(event.values[2]) > SUDDEN_GYRO_THRESHOLD) {
                    lastSuddenMs = SystemClock.elapsedRealtime()
                }
                evaluateMotionState()
                return
            }

            // ── Linear acceleration path below ──────────────
            val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
            val mag = sqrt(x * x + y * y + z * z)
            var transition: Boolean? = null   // null = no change / true·false = new state
            synchronized(lock) {
                if (accelBuffer.size >= WINDOW_SIZE) accelBuffer.removeFirst()
                accelBuffer.addLast(mag)
                // Confirmed-stationary counter: increments while at or below the threshold (capped), resets immediately above it
                stationaryFrameCount = if (mag < STATIONARY_THRESHOLD) {
                    (stationaryFrameCount + 1).coerceAtMost(STATIONARY_CONFIRM_FRAMES)
                } else {
                    0   // even slight vibration switches to moving immediately
                }
                // Detect a state change (same formula reused) — record only at the moment it changes
                val nowStationary = stationaryFrameCount >= STATIONARY_CONFIRM_FRAMES
                if (nowStationary != lastNotifiedStationary) {
                    lastNotifiedStationary = nowStationary
                    transition = nowStationary
                }
            }
            // Invoke callbacks outside the lock — so subscriber code can't hold or delay the lock
            transition?.let { onStationaryChanged?.invoke(it) }

            // Sudden acceleration change (sudden stop) → trigger 0x02, then re-evaluate the motion state
            if (mag > SUDDEN_ACCEL_THRESHOLD) {
                lastSuddenMs = SystemClock.elapsedRealtime()
            }
            evaluateMotionState()
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    // Evaluate the motion state and notify via callback only when it changes (shared by accelerometer and gyro).
    private fun evaluateMotionState() {
        val s = motionState
        if (s != lastNotifiedMotionState) {
            lastNotifiedMotionState = s
            onMotionStateChanged?.invoke(s)
        }
    }

    fun init(context: Context) {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        if (sensor == null) {
            Log.w(TAG, "LINEAR_ACCELERATION 센서 없음 — 중립 모드(score=1.0)로 동작")
            return
        }
        sensorManager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        // Also register the gyroscope for sharp-turn (0x02) detection; without it, only sudden acceleration counts.
        val gyro = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (gyro != null) {
            sensorManager?.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME)
            Log.d(TAG, "자이로스코프 등록 — 3-State 급정거/급회전 감지 활성")
        } else {
            Log.w(TAG, "자이로 센서 없음 — 가속도 급변만으로 0x02 판정")
        }
        // Register the game rotation vector (GAME_ROTATION_VECTOR) — for cornering detection (internal only,
        //   not transmitted). No magnetometer, so immune to magnetic interference. Without it, no cornering detection
        //   (Time-Gate keeps its normal value — safe).
        //   Battery: SENSOR_DELAY_NORMAL (~5Hz) instead of SENSOR_DELAY_GAME (~50Hz) cuts CPU wakeups to ~1/10.
        //     The turn rate is differentiated over the real event interval (dt), so deg/s values hold; only the
        //     EMA response gets somewhat slower (cornering detection still OK).
        val gameRot = sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        if (gameRot != null) {
            sensorManager?.registerListener(listener, gameRot, SensorManager.SENSOR_DELAY_NORMAL)
            hasGameRotation = true
            Log.d(TAG, "게임회전벡터 등록 — 코너링(급회전) 내부 감지 활성")
        } else {
            hasGameRotation = false
            Log.w(TAG, "게임회전벡터 센서 없음 — 코너링 감지 비활성(Time-Gate 평상값)")
        }
        isRunning = true
        Log.d(TAG, "IMU 융합 초기화 (SENSOR_DELAY_GAME, ${WINDOW_SIZE}샘플 창)")
    }

    fun stop() {
        sensorManager?.unregisterListener(listener)
        synchronized(lock) {
            accelBuffer.clear()
            stationaryFrameCount = 0
            lastNotifiedStationary = false   // keeps the first notification after the next init consistent
        }
        // Reset the 3-state motion state
        lastSuddenMs = 0L
        lastNotifiedMotionState = STATE_STATIONARY
        // Reset the cornering (game rotation vector) state
        hasGameRotation   = false
        turnRateDegPerSec = 0f
        lastHeadingDeg    = Float.NaN
        lastHeadingMs     = 0L
        isRunning = false
        Log.d(TAG, "IMU 융합 중지")
    }

    /**
     * RMS acceleration (m/s²).
     * No sensor / buffer < 5 samples → returns the neutral 1.0f (no alert suppression)
     */
    val motionScore: Float
        get() {
            val buf = synchronized(lock) { accelBuffer.toList() }
            if (buf.size < 5) return 1.0f
            val rms = sqrt(buf.sumOf { it.toDouble() * it }.toFloat() / buf.size)
            return rms
        }

    /**
     * Whether confirmed stationary.
     * True only after STATIONARY_CONFIRM_FRAMES (25 samples ≈ 0.5 s) in a row at or below the threshold.
     * More stable than an instant check (motionScore < threshold): not shaken by momentary vibration or shocks.
     * → Used to stop TTC calculation and to freeze Kalman Q
     */
    val isStationary: Boolean get() = stationaryFrameCount >= STATIONARY_CONFIRM_FRAMES

    /**
     * Kalman process-noise Q scale factor (0.01 ~ 3.0):
     * - confirmed stationary → Q×0.01: Kalman nearly frozen (blocks multipath-fading ghost alarms)
     * - pre-stationary       → Q×0.3: strong smoothing (suppresses body-block)
     * - fast motion          → Q×3.0: fast response (tracks real approaches quickly)
     */
    val adaptiveQFactor: Double
        get() {
            if (isStationary) return 0.01   // confirmed stationary: freeze Kalman
            val s = motionScore
            return when {
                s < STATIONARY_THRESHOLD -> 0.3
                s > FAST_THRESHOLD       -> 3.0
                else -> 0.3 + (s - STATIONARY_THRESHOLD).toDouble() /
                              (FAST_THRESHOLD - STATIONARY_THRESHOLD) * 2.7
            }
        }

    /**
     * 3-state motion state (0x00 stationary / 0x01 normal motion / 0x02 sudden stop or sharp turn).
     * After a sudden change, 0x02 is held for SUDDEN_HOLD_MS (hysteresis) → prevents frequent flicker.
     * Otherwise follows the isStationary check (stationary = 0x00, moving = 0x01).
     */
    val motionState: Int
        get() {
            if (SystemClock.elapsedRealtime() - lastSuddenMs < SUDDEN_HOLD_MS) return STATE_SUDDEN
            return if (isStationary) STATE_STATIONARY else STATE_NORMAL
        }

    /**
     * Turn direction (TURN_*) — based on the derivative of the GAME_ROTATION_VECTOR heading (turnRateDegPerSec).
     *   Without game rotation vector support (hasGameRotation=false), falls back to straight (no false detection).
     *   Left/right only when |turn rate| ≥ TURN_DETECT_THRESHOLD. The sign depends on mounting orientation
     *   → verify in the field. (Assumes clockwise/right turn is positive; if reversed, just flip the sign below.)
     *   Broadcast via BleAdvertiser.updateTurn() → receivers use it to show the peer entering a turn and for alerts.
     */
    val turnDirection: Int
        get() = when {
            !hasGameRotation                            -> TURN_STRAIGHT
            turnRateDegPerSec >=  TURN_DETECT_THRESHOLD -> TURN_RIGHT
            turnRateDegPerSec <= -TURN_DETECT_THRESHOLD -> TURN_LEFT
            else                                        -> TURN_STRAIGHT
        }
}
