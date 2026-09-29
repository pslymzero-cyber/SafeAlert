package com.wf11.safealert.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 단독 작업자 감시의 센서 접착부 (v1.1.99): 가속도·걸음 센서 등록, 센서 시각 변환, 가속도 신호 공백 검사.
 *
 * 가속도 표본은 MotionAnalyzer 로 넘겨 MOVED 는 onMoved, 낙상·4 G 충격은 충격 표본 시각으로 onAccident,
 * 닫힌 1초 창은 onWindow 로 LoneWorkerLogic 에 전한다. 걸음(TYPE_STEP_DETECTOR)은 onStep 으로 전한다.
 * 걸음 센서는 API 29+ 에서 신체 활동 권한이 있어야 등록한다. 모든 콜백은 handler(메인) 에서 돈다.
 */
class LoneWorkerSensors(
    private val ctx: Context,
    private val handler: Handler,
    private val logic: () -> LoneWorkerLogic,
    private val onEvent: (Long) -> Unit
) : SensorEventListener {

    companion object {
        private const val TAG = "LoneWorkerSensors"
        /** 센서 시각이 지금보다 이만큼 넘게 벗어나면 도착 시각 기준으로 옮긴다. */
        private const val SKEW_OK_MS = 60_000L
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

    /** 가속도 센서 자체가 없음(등록 실패와 구분). */
    var noSensor = false
        private set
    var registered = false
        private set
    /** 웨이크업 가속도 센서가 없어 일반 센서로 대신 등록했다. */
    var fallbackWake = false
        private set
    val stalled: Boolean get() = stall.stalled
    val stepsAvailable: Boolean get() = stepRegistered
    /** 걸음 센서는 있는데 신체 활동 권한이 없다. */
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
        var s = m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
        var wake = true
        if (s == null) {
            s = m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            wake = false
        }
        if (s == null) {
            Log.w(TAG, "가속도 센서 없음 — 사고·무동작 판정 끔")
            noSensor = true
            return
        }
        val impactG = MotionAnalyzer.impactGFor(s.maximumRange)
        val shockG = MotionAnalyzer.impactGFor(s.maximumRange, MotionAnalyzer.SHOCK_G)
        Log.i(TAG, "가속도 센서 wakeUp=${s.isWakeUpSensor} fifoMax=${s.fifoMaxEventCount} range=${s.maximumRange} impactG=$impactG shockG=$shockG")
        analyzer = MotionAnalyzer(impactG, shockG) { w -> logic().onWindow(w.copy(endMs = w.endMs + accelSkew)) }
        if (!m.registerListener(this, s, 20_000, 5_000_000, handler)) {
            Log.w(TAG, "가속도 센서 등록 실패 — 판정 유지, 다시 등록")
            return
        }
        accel = s
        registered = true
        fallbackWake = !wake
    }

    private fun unregisterAccel() {
        if (registered) runCatching { sm?.unregisterListener(this, accel) }
        registered = false
        fallbackWake = false
    }

    /** 걸음 센서 등록을 권한·기능 상태에 맞춘다(권한이 새로 생기면 등록, 사라지면 해제). */
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
            stepRegistered = m?.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL, 5_000_000, handler) == true
            Log.i(TAG, "걸음 센서 등록=$stepRegistered wakeUp=${s?.isWakeUpSensor}")
        } else if (!want && stepRegistered) {
            runCatching { m?.unregisterListener(this, s) }
            stepRegistered = false
        }
        logic().stepsAvailable = stepRegistered
    }

    /** 가속도 신호 공백 검사: 끊겼으면 같은 백오프로 다시 등록한다 (RR08). */
    fun checkStall(t: Long) {
        if (stall.check(t) == SensorStall.Action.REREGISTER) {
            Log.w(TAG, "가속도 센서 신호 없음 또는 미등록 — 다시 등록")
            unregisterAccel()
            registerAccel()
        }
    }

    /** 센서 시각(ms)을 elapsed 기준으로 옮기는 보정값. 벗어난 동안에만 도착 시각에 맞춰 다시 잡는다. */
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
                // 이 앱의 진동 구간 표본은 활동 통계에서만 뺀다. 낙상·충격 감지에는 그대로 넣는다 (v1.1.99, F07·RR02)
                when (analyzer.add(rawMs, v[0], v[1], v[2], masked = VibrationHelper.window.covers(rawMs))) {
                    MotionAnalyzer.Signal.MOVED -> logic().onMoved(t)
                    MotionAnalyzer.Signal.FALL, MotionAnalyzer.Signal.IMPACT -> logic().onAccident(analyzer.eventMs + accelSkew)
                    MotionAnalyzer.Signal.NONE -> {}
                }
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                stepSkew = skewFor(rawMs, t, stepSkew)
                logic().onStep(rawMs + stepSkew)
            }
            else -> return
        }
        onEvent(t)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
