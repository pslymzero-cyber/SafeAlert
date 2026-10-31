package com.wf11.safealert.service

import android.Manifest
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import com.wf11.safealert.firebase.JudgeLog
import com.wf11.safealert.ui.JudgeQuestion
import com.wf11.safealert.utils.AiJudge
import java.util.Locale
import kotlin.math.sqrt

/**
 * Sensor glue for lone-worker monitoring: accelerometer / step sensor registration, sensor time conversion, accelerometer
 * signal gap check, flush.
 *
 * Accelerometer samples go through MotionAnalyzer; MOVED is passed to LoneWorkerLogic via onMoved (with when the phone last
 * really moved, MotionAnalyzer.lastActiveMs), a fall via onAccident with the impact sample time, and each closed 1 s window
 * (walk-like or not) via onWindow. Steps (TYPE_STEP_DETECTOR) go to onStep
 * with the corrected time and whether the app was vibrating then — only steps inside walk-like windows count. Vibration spans
 * are judged on times converted to the elapsed base, for both accelerometer and steps. The step sensor is registered only
 * with the physical activity permission on API 29+.
 * The gyro is registered separately, for measurement logs only, while a peer alert vibrates on this device (not used for
 * judgment).
 * All callbacks run on handler (main).
 */
class LoneWorkerSensors(
    private val ctx: Context,
    private val handler: Handler,
    private val logic: () -> LoneWorkerLogic,
    /** This phone's id and role name, for the judge-data record. */
    private val judgeWho: () -> Pair<String, String>,
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
        /**
         * Raw acceleration kept for the judge-data record: 4096 samples (about 80 s at 50 Hz). Cand slice = impact-3 s .. impact+12 s;
         * stop slice = stop-10 s .. stop+30 s at about 25 Hz.
         * ponytail: a sensor much faster than 50 Hz fills the ring sooner, so a stop 10 s before its 30 s wait is gone and that stop is skipped.
         */
        private const val ACC_RING = 4096
        private const val ACC_BEFORE_MS = 3_000L
        private const val ACC_AFTER_MS = 12_000L
        private const val STOP_BEFORE_MS = 10_000L
        private const val STOP_SPAN_MS = MotionAnalyzer.STOP_AFTER_MS
        private const val STOP_MIN_GAP_MS = 36L
    }

    private val accT = LongArray(ACC_RING)
    private val accX = FloatArray(ACC_RING)
    private val accY = FloatArray(ACC_RING)
    private val accZ = FloatArray(ACC_RING)
    private var accN = 0L
    private var accRecording = false

    private class AccSlice(val acc: String, val t0: Long, val dt: Double?)

    /**
     * Raw ring samples with sensor time in from..to (at least minGapMs apart) as "x,y,z;..." mg, the first sample's elapsed time and the
     * mean spacing (the sensor can run faster than 50 Hz); null when there is none or the text is over JudgeLog.ACC_MAX.
     */
    private fun accSlice(from: Long, to: Long, minGapMs: Long): AccSlice? {
        val n = minOf(accN, ACC_RING.toLong()).toInt()
        val start = if (accN > ACC_RING) (accN % ACC_RING).toInt() else 0
        val sb = StringBuilder()
        var first = Long.MIN_VALUE
        var last = Long.MIN_VALUE
        var count = 0
        for (i in 0 until n) {
            val j = (start + i) % ACC_RING
            val ts = accT[j]
            if (ts < from || ts > to) continue
            if (count > 0 && ts - last < minGapMs) continue
            if (count++ == 0) first = ts else sb.append(';')
            last = ts
            sb.append(Math.round(accX[j] / MotionAnalyzer.G * 1000)).append(',')
                .append(Math.round(accY[j] / MotionAnalyzer.G * 1000)).append(',')
                .append(Math.round(accZ[j] / MotionAnalyzer.G * 1000))
        }
        if (count == 0 || sb.length > JudgeLog.ACC_MAX) return null
        return AccSlice(sb.toString(), first + accelSkew, if (count >= 2) (last - first).toDouble() / (count - 1) else null)
    }

    /** Judge-data (recording only): the phone stayed still for 30 s after stopping at s (sensor time) - its waveform around the stop. */
    private fun judgeStop(s: Long) {
        val n = minOf(accN, ACC_RING.toLong()).toInt()
        if (n == 0) return
        val start = if (accN > ACC_RING) (accN % ACC_RING).toInt() else 0
        if (accT[start] > s - STOP_BEFORE_MS) return // recording just turned on, or the ring is shorter than 40 s
        val sl = accSlice(s - STOP_BEFORE_MS, s + STOP_SPAN_MS, STOP_MIN_GAP_MS) ?: return
        val who = judgeWho()
        val l = logic()
        JudgeLog.stop(who.first, who.second, s + accelSkew, sl.acc, sl.t0, sl.dt, l.charging, l.zoneInAt(s + accelSkew), l.mounted)
    }

    /** Judge-data (recording only): the candidate's decision plus, if kept, its raw acceleration slice. */
    private fun judgeCand(c: MotionAnalyzer.Candidate) {
        // On-phone AI fall probability (display + record only): kept for the status line / check window even when recording is off
        val aiP = if (JudgeLog.on || JudgeLog.aiShow) AiJudge.fallP(c) else null
        if (aiP != null) AiJudge.lastFall = AiJudge.FallAi(JudgeQuestion.wallOf(c.impactT + accelSkew), aiP, c.pass, System.currentTimeMillis())
        if (!JudgeLog.on) return
        var acc: String? = null
        var accT0: Long? = null
        var accDt: Double? = null
        if (JudgeLog.accOn) {
            accSlice(c.impactT - ACC_BEFORE_MS, c.impactT + ACC_AFTER_MS, 0L)?.let { acc = it.acc; accT0 = it.t0; accDt = it.dt }
        }
        val who = judgeWho()
        JudgeLog.fallCand(who.first, who.second, c.impactT + accelSkew, c.ffMs, c.peakG, c.impactG, c.deg, c.postN,
            c.postActive, c.preValid, c.pass, c.why, acc, accT0, accDt, aiP = aiP)
    }

    /** After-impact judgment: the accident window time (inside analyzer.add, before the callback's end judgment). */
    private fun judgePost(p: MotionAnalyzer.Post) {
        val l = logic()
        l.onPost(p.impT + accelSkew, p.out, p.impT + p.atMs + accelSkew, MotionAnalyzer.FallShape(p.ffMs, p.peakG, p.tiltDeg), p.superseded)
        if (JudgeLog.on) {
            val who = judgeWho()
            JudgeLog.post(who.first, who.second, p.impT + accelSkew, if (l.mounted) "mounted" else p.out, p.atMs, p.tiltDeg, p.stdMg, p.run, p.ffMs,
                sup = p.superseded)
        }
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
    /**
     * Impact time (elapsed) of a fall candidate waiting for the 12 s decision, else null. Same conversion as the one passed
     * to onAccident (analyzer.eventMs + accelSkew), so it equals the buzz key of the confirmed FALL or IMPACT. accelSkew is re-fitted
     * only when sensor time drifts over 60 s.
     */
    val pendingImpactAt: Long? get() = analyzer.pendingImpactMs?.plus(accelSkew)
    val pendingShape: MotionAnalyzer.FallShape get() = analyzer.pendingShape
    /** Impact time (elapsed) while its after-impact judgment still runs (the count goes on after the 12 s decision), else null. */
    val pendingPostAt: Long? get() = analyzer.pendingPostMs?.plus(accelSkew)
    val pendingPostShape: MotionAnalyzer.FallShape get() = analyzer.pendingPostShape
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
        registerScreen()
    }

    fun unregister() {
        on = false
        unregisterAccel()
        refreshSteps()
        unregisterScreen()
    }

    /**
     * Screen unlock (ACTION_USER_PRESENT) is a sign that someone has a dropped phone again (MotionAnalyzer.onScreen). Only on a device
     * with a screen lock: without one the screen also lights up for calls and notifications. Delivered on the sensor handler.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val secure = runCatching { (ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure }.getOrDefault(false)
            if (!secure) return
            analyzer.onScreen()
            onEvent(now())
        }
    }
    private var screenRegistered = false

    private fun registerScreen() {
        if (screenRegistered) return
        screenRegistered = runCatching {
            // EXPORTED on purpose: ACTION_USER_PRESENT is a protected system broadcast (no other app can send it), and on API 26-32
            // NOT_EXPORTED adds an app-only permission that SystemUI's broadcast does not hold, so it would never arrive
            ContextCompat.registerReceiver(ctx, screenReceiver, IntentFilter(Intent.ACTION_USER_PRESENT), null, handler,
                ContextCompat.RECEIVER_EXPORTED)
        }.isSuccess
    }

    private fun unregisterScreen() {
        if (!screenRegistered) return
        runCatching { ctx.unregisterReceiver(screenReceiver) }
        screenRegistered = false
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
        analyzer = MotionAnalyzer(impactG, onWindow = { w -> logic().onWindow(w.copy(endMs = w.endMs + accelSkew)) },
            onCandidate = ::judgeCand, onPost = ::judgePost)
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
     * Logs gyro measurements every second, only while a peer alert vibrates on this device. Not used for judgment: only sample
     * count, mean and max angular velocity (no personal data or location). One registration-result line per alert (GyroGate),
     * plus one line for the remaining bucket when turned off.
     * Skipped silently without a gyro. Turned off only at the end of the alert and monitor stop (LoneWorkerMonitor.stop) — even if feature
     * off takes the sensors down, measurement continues while the alert vibrates.
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
                val recording = JudgeLog.on && JudgeLog.accOn
                if (recording && !accRecording) accN = 0 // recording just turned on: the ring must not hold older samples (a gap would pass for a stop)
                accRecording = recording
                if (recording) { // Raw ring for the judge-data record (before add: the decision sample is already in)
                    val j = (accN % ACC_RING).toInt()
                    accT[j] = rawMs; accX[j] = v[0]; accY[j] = v[1]; accZ[j] = v[2]
                    accN++
                }
                // Samples during this app's vibration are excluded from activity stats only; fall detection still gets them
                when (analyzer.add(rawMs, v[0], v[1], v[2], masked = VibrationHelper.window.covers(rawMs + accelSkew))) {
                    MotionAnalyzer.Signal.MOVED -> logic().onMoved(rawMs + accelSkew, analyzer.lastActiveMs + accelSkew)
                    MotionAnalyzer.Signal.FALL -> logic().onAccident(analyzer.eventMs + accelSkew, analyzer.fallShape, byPost = true)
                    MotionAnalyzer.Signal.IMPACT -> logic().onAccident(analyzer.eventMs + accelSkew, analyzer.fallShape, pass = false, byPost = true)
                    MotionAnalyzer.Signal.NONE -> {}
                }
                if (JudgeLog.on && JudgeLog.accOn) analyzer.takeStop(rawMs)?.let(::judgeStop)
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
 * Gyro measurement request (pure). Registers only while a peer alert vibrates on this device; the registration attempt is
 * logged once per alert, and after a failure (including no gyro) it is not retried during that alert. Resets when the alert ends.
 */
class GyroGate {
    /** Registration result for this alert; null if not tried yet. */
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

    /** Records the registration result; true if it is the first result for this alert (one log line). Turns off on failure. */
    fun registered(ok: Boolean): Boolean {
        val first = result == null
        result = ok
        if (!ok) on = false
        return first
    }
}
