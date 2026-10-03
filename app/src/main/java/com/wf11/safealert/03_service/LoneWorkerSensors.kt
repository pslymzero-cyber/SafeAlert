package com.wf11.safealert.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.sqrt

/**
 * Sensor glue for lone-worker monitoring: accelerometer / step sensor registration, sensor time conversion, accelerometer
 * signal gap check, flush.
 *
 * Accelerometer samples go through MotionAnalyzer; MOVED is passed to LoneWorkerLogic via onMoved, a fall via onAccident with
 * the impact sample time, and each closed 1 s window (walk-like or not) via onWindow. Steps (TYPE_STEP_DETECTOR) go to onStep
 * with the corrected time and whether the app was vibrating then — only steps inside walk-like windows count. Vibration spans
 * are judged on times converted to the elapsed base, for both accelerometer and steps. The step sensor is registered only
 * with the physical activity permission on API 29+.
 * The gyro is registered separately, for measurement logs only, while a peer siren vibrates on this device (not used for
 * judgment).
 * All callbacks run on handler (main).
 */
class LoneWorkerSensors(
    private val ctx: Context,
    private val handler: Handler,
    private val logic: () -> LoneWorkerLogic,
    private val onEvent: (Long) -> Unit
) : SensorEventListener2 {

    companion object {
        private const val TAG = "LoneWorkerSensors"
        /** If the sensor time deviates from now by more than this, rebase it on the arrival time. */
        private const val SKEW_OK_MS = 60_000L
        /** An unfinished flush request is re-sent after this long. */
        private const val FLUSH_RETRY_MS = 2_000L
        /** Maximum sensor batch latency (us). */
        private const val MAX_BATCH_US = (LoneWorkerLogic.MAX_BATCH_MS * 1_000L).toInt()
    }

    private var sm: SensorManager? = null
    private var accel: Sensor? = null
    private var stepSensor: Sensor? = null
    private var analyzer = MotionAnalyzer()
    private val stall = SensorStall()
    private var on = false
    private var accelSkew = 0L
    private var stepSkew = 0L
    private var stepRegistered = false
    /** Last logged step sensor registration result; logged again only when it changes. */
    private var stepLogged: Boolean? = null
    /** Time of the flush request awaiting completion; MIN_VALUE if none. */
    private var flushAt = Long.MIN_VALUE

    /** No accelerometer at all (as opposed to a registration failure). */
    var noSensor = false
        private set
    var registered = false
        private set
    /** Accelerometer maximum range (m/s^2); 0 before registration — used to correct the safe-zone fall impact threshold. */
    var rangeMs2 = 0f
        private set
    val stalled: Boolean get() = stall.stalled
    /** A registered sensor is non-wakeup, so the CPU must be kept awake while monitoring. */
    val needsWake: Boolean
        get() = sensorsNeedCpuWake(registered, accel?.isWakeUpSensor == true, stepRegistered, stepSensor?.isWakeUpSensor == true)
    /** Step sensor present but physical activity permission missing. */
    var stepPermissionMissing = false
        private set

    private fun now() = SystemClock.elapsedRealtime()

    fun resetStall(t: Long) = stall.reset(t)

    fun register() {
        on = true
        registerAccel()
        refreshSteps()
    }

    fun unregister() {
        on = false
        unregisterAccel()
        refreshSteps()
    }

    private fun manager(): SensorManager? =
        sm ?: runCatching { ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager }.getOrNull()?.also { sm = it }

    private fun registerAccel() {
        if (registered) return
        val m = manager()
        if (m == null) {
            Log.w(TAG, "센서 서비스 없음 — 사고·무동작 판정 끔")
            noSensor = true
            return
        }
        val s = m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true) ?: m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (s == null) {
            Log.w(TAG, "가속도 센서 없음 — 사고·무동작 판정 끔")
            noSensor = true
            return
        }
        rangeMs2 = s.maximumRange
        val impactG = MotionAnalyzer.impactGFor(rangeMs2)
        Log.i(TAG, "가속도 센서 wakeUp=${s.isWakeUpSensor} fifoMax=${s.fifoMaxEventCount} range=${s.maximumRange} impactG=$impactG")
        analyzer = MotionAnalyzer(impactG) { w -> logic().onWindow(w.copy(endMs = w.endMs + accelSkew)) }
        if (!m.registerListener(this, s, 20_000, MAX_BATCH_US, handler)) {
            Log.w(TAG, "가속도 센서 등록 실패 — 판정 유지, 다시 등록")
            return
        }
        accel = s
        registered = true
    }

    private fun unregisterAccel() {
        if (registered) runCatching { sm?.unregisterListener(this, accel) }
        registered = false
    }

    /**
     * Syncs step sensor registration with the permission and feature state (register when permission appears, unregister when it is gone).
     */
    fun refreshSteps() {
        val m = manager()
        val s = stepSensor ?: m?.let {
            it.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR, true) ?: it.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        }?.also { stepSensor = it }
        val perm = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        stepPermissionMissing = s != null && !perm
        val want = on && s != null && perm
        if (want && !stepRegistered) {
            stepRegistered = m?.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL, MAX_BATCH_US, handler) == true
            if (stepLogged != stepRegistered) {
                stepLogged = stepRegistered
                Log.i(TAG, "걸음 센서 등록=$stepRegistered wakeUp=${s?.isWakeUpSensor} " +
                    "fifoMax=${s?.fifoMaxEventCount} fifoReserved=${s?.fifoReservedEventCount}")
            }
        } else if (!want && stepRegistered) {
            runCatching { m?.unregisterListener(this, s) }
            stepRegistered = false
        }
        logic().stepsAvailable = stepRegistered
    }

    private val gyroGate = GyroGate()
    private val gyroStats = GyroStats()
    private val gyroListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val v = event.values
            gyroStats.add(event.timestamp / 1_000_000L, v[0], v[1], v[2])?.let { Log.i(TAG, it) }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /**
     * Logs gyro measurements every second, only while a peer siren vibrates on this device. Not used for judgment: only sample
     * count, mean and max angular velocity (no personal data or location). One registration-result line per siren (GyroGate),
     * plus one line for the remaining bucket when turned off.
     * Skipped silently without a gyro. Turned off only at siren end and monitor stop (LoneWorkerMonitor.stop) — even if feature
     * off takes the sensors down, measurement continues while the siren vibrates.
     */
    fun gyroLog(siren: Boolean, vibrating: Boolean) {
        if (!gyroGate.update(siren, vibrating)) return
        if (!gyroGate.on) {
            runCatching { sm?.unregisterListener(gyroListener) }
            gyroStats.flush()?.let { Log.i(TAG, it) }
            return
        }
        val m = manager()
        val s = m?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (m == null || s == null) {
            gyroGate.registered(false)
            return
        }
        gyroStats.reset()
        val ok = m.registerListener(gyroListener, s, SensorManager.SENSOR_DELAY_GAME, handler)
        if (gyroGate.registered(ok)) {
            Log.i(TAG, String.format(Locale.US, "gyro register ok=%b range=%.3f res=%.5f rad/s", ok, s.maximumRange, s.resolution))
        }
    }

    /** Accelerometer signal gap check: if lost, re-register with the same backoff. */
    fun checkStall(t: Long) {
        if (stall.check(t) == SensorStall.Action.REREGISTER) {
            Log.w(TAG, "가속도 센서 신호 없음 또는 미등록 — 다시 등록")
            unregisterAccel()
            registerAccel()
        }
    }

    /**
     * A deadline is waiting for sensor data: ask for batched events to be delivered now. No new request while one is pending;
     * request again once FLUSH_RETRY_MS has passed.
     */
    fun flush() {
        val m = sm ?: return
        if (!registered && !stepRegistered) return
        val t = now()
        if (flushAt != Long.MIN_VALUE && t - flushAt < FLUSH_RETRY_MS) return
        flushAt = t
        if (!runCatching { m.flush(this) }.getOrDefault(false)) flushAt = Long.MIN_VALUE
    }

    /** Flush complete: for the step sensor, all steps up to the request time have arrived. */
    override fun onFlushCompleted(sensor: Sensor) {
        if (sensor.type == Sensor.TYPE_STEP_DETECTOR && flushAt != Long.MIN_VALUE) {
            logic().stepsFlushed(flushAt)
            flushAt = Long.MIN_VALUE
        }
        onEvent(now())
    }

    /** Offset shifting sensor time (ms) to the elapsed base. Re-anchored to the arrival time only while it deviates. */
    private fun skewFor(rawMs: Long, t: Long, cur: Long): Long =
        if (rawMs + cur in t - SKEW_OK_MS..t) cur else t - rawMs

    override fun onSensorChanged(event: SensorEvent) {
        val t = now()
        val rawMs = event.timestamp / 1_000_000L
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                stall.onEvent(t)
                accelSkew = skewFor(rawMs, t, accelSkew)
                val v = event.values
                // Samples during this app's vibration are excluded from activity stats only; fall detection still gets them
                when (analyzer.add(rawMs, v[0], v[1], v[2], masked = VibrationHelper.window.covers(rawMs + accelSkew))) {
                    MotionAnalyzer.Signal.MOVED -> logic().onMoved(rawMs + accelSkew)
                    MotionAnalyzer.Signal.FALL -> logic().onAccident(analyzer.eventMs + accelSkew, analyzer.fallShape)
                    MotionAnalyzer.Signal.NONE -> {}
                }
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                stepSkew = skewFor(rawMs, t, stepSkew)
                val at = rawMs + stepSkew
                logic().onStep(at, VibrationHelper.window.covers(at))
            }
            else -> return
        }
        onEvent(t)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

/**
 * Aggregates gyro angular velocity magnitude per 1 s bucket (second of sensor
 * time) into sample count, mean and max (pure, measurement log only).
 */
class GyroStats {
    private var sec = Long.MIN_VALUE
    private var n = 0
    private var sum = 0.0
    private var max = 0.0

    fun reset() {
        sec = Long.MIN_VALUE
        n = 0
        sum = 0.0
        max = 0.0
    }

    /**
     * One sample (rad/s). Returns the previous bucket's line when the bucket changes — null for the first sample or the same bucket.
     */
    fun add(tMs: Long, x: Float, y: Float, z: Float): String? {
        val s = tMs / 1_000L
        var line: String? = null
        if (s != sec) {
            line = flush()
            sec = s
        }
        val m = sqrt((x * x + y * y + z * z).toDouble())
        n++
        sum += m
        max = maxOf(max, m)
        return line
    }

    /**
     * If there are samples, returns the current bucket's line and clears it — when the bucket changes and when measurement turns off.
     */
    fun flush(): String? {
        val line = if (n > 0) String.format(Locale.US, "gyro 1s n=%d mean=%.3f max=%.3f rad/s", n, sum / n, max) else null
        reset()
        return line
    }
}

/**
 * Gyro measurement request (pure). Registers only while a peer siren vibrates on this device; the registration attempt is
 * logged once per siren, and after a failure (including no gyro) it is not retried during that siren. Resets when the siren ends.
 */
class GyroGate {
    /** Registration result for this siren; null if not tried yet. */
    private var result: Boolean? = null

    /** Whether measurement registration is wanted. */
    var on = false
        private set

    /** Applies the siren / vibration state; true if on changed. */
    fun update(siren: Boolean, vibrating: Boolean): Boolean {
        if (!siren) result = null
        val want = siren && vibrating && result != false
        if (want == on) return false
        on = want
        return true
    }

    /** Records the registration result; true if it is the first result for this siren (one log line). Turns off on failure. */
    fun registered(ok: Boolean): Boolean {
        val first = result == null
        result = ok
        if (!ok) on = false
        return first
    }
}
