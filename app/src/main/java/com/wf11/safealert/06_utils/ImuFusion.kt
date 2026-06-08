package com.wf11.safealert.utils

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.sqrt

/**
 * IMU 융합 — 선형 가속도 기반 이동 감지
 *
 * BLE RSSI는 몸체 가림(body-block)이나 반사로 갑자기 변할 수 있음.
 * 가속도계로 실제 이동 여부를 교차 검증해 TTC 오경보를 억제하고,
 * 칼만 Q를 적응적으로 조정해 정지 시 더 강한 평활화 / 빠른 이동 시 더 빠른 추적.
 *
 * [motionScore]
 *   ~0.0  : 정지      (RSSI 변화 = 노이즈 or body-block)
 *   ~1.0  : 보통 걷기
 *   ~2.0+ : 빠른 이동 (RSSI 변화 = 실제 접근 가능성 높음)
 */
object ImuFusion {

    private const val TAG = "ImuFusion"

    // ~1초 창 @ 50Hz
    private const val WINDOW_SIZE          = 50
    private const val STATIONARY_THRESHOLD = 0.15f   // m/s² RMS 이하 → 정지 후보
    private const val FAST_THRESHOLD       = 2.5f    // m/s² RMS 이상 → 빠른 이동

    /**
     * 확정 정지 판정 창 (샘플 수).
     * ~0.5초(25샘플 @ 50Hz) 이상 연속으로 STATIONARY_THRESHOLD 이하여야
     * '확정 정지'로 선언 → 다중경로 페이딩 Ghost Alarm 원천 차단.
     * 움직임 감지(mag >= STATIONARY_THRESHOLD) 시 즉시 0으로 리셋.
     */
    private const val STATIONARY_CONFIRM_FRAMES = 25

    private var sensorManager: SensorManager? = null
    @Volatile private var isRunning = false

    // 연속 정지 프레임 카운터 (lock으로 보호)
    @Volatile private var stationaryFrameCount = 0

    // [v1.0.27] 정지↔이동 상태 변화 통지 훅 — BleService 가 동적 스캔 모드 제어용으로 구독.
    //   isStationary 판정식·임계값은 일절 불변. 상태가 '바뀌는 순간'만 콜백으로 알린다.
    //   이동 감지 시 0프레임(진짜 0초) 지연으로 호출 → 즉시 LOW_LATENCY 복귀 보장.
    @Volatile var onStationaryChanged: ((Boolean) -> Unit)? = null
    @Volatile private var lastNotifiedStationary = false

    private val accelBuffer = ArrayDeque<Float>()
    private val lock = Any()

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
            val mag = sqrt(x * x + y * y + z * z)
            var transition: Boolean? = null   // [v1.0.27] null=변화없음 / true·false=새 상태
            synchronized(lock) {
                if (accelBuffer.size >= WINDOW_SIZE) accelBuffer.removeFirst()
                accelBuffer.addLast(mag)
                // 확정 정지 카운터: 임계 이하면 증가(상한 고정), 초과 시 즉시 리셋
                stationaryFrameCount = if (mag < STATIONARY_THRESHOLD) {
                    (stationaryFrameCount + 1).coerceAtMost(STATIONARY_CONFIRM_FRAMES)
                } else {
                    0   // 미세한 진동도 즉시 '이동 중'으로 전환
                }
                // [v1.0.27] 상태 변화 감지(판정식 그대로 재사용) — 바뀐 순간만 기록
                val nowStationary = stationaryFrameCount >= STATIONARY_CONFIRM_FRAMES
                if (nowStationary != lastNotifiedStationary) {
                    lastNotifiedStationary = nowStationary
                    transition = nowStationary
                }
            }
            // [v1.0.27] 콜백은 lock 밖에서 호출 — 구독자 코드가 lock 을 점유·지연시키지 않게
            transition?.let { onStationaryChanged?.invoke(it) }
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    fun init(context: Context) {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        if (sensor == null) {
            Log.w(TAG, "LINEAR_ACCELERATION 센서 없음 — 중립 모드(score=1.0)로 동작")
            return
        }
        sensorManager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        isRunning = true
        Log.d(TAG, "IMU 융합 초기화 (SENSOR_DELAY_GAME, ${WINDOW_SIZE}샘플 창)")
    }

    fun stop() {
        sensorManager?.unregisterListener(listener)
        synchronized(lock) {
            accelBuffer.clear()
            stationaryFrameCount = 0
            lastNotifiedStationary = false   // [v1.0.27] 다음 init 시 첫 통지 정합성 보장
        }
        isRunning = false
        Log.d(TAG, "IMU 융합 중지")
    }

    /**
     * RMS 가속도 (m/s²).
     * 센서 없음 / 버퍼 < 5샘플 → 중립값 1.0f 반환 (경보 억제 없음)
     */
    val motionScore: Float
        get() {
            val buf = synchronized(lock) { accelBuffer.toList() }
            if (buf.size < 5) return 1.0f
            val rms = sqrt(buf.sumOf { it.toDouble() * it }.toFloat() / buf.size)
            return rms
        }

    /**
     * 확정 정지 여부.
     * STATIONARY_CONFIRM_FRAMES(25샘플 ≈ 0.5초) 연속 임계 이하일 때만 true.
     * 즉각 판단(motionScore < threshold) 대비 안정적: 순간 진동·충격에 흔들리지 않음.
     * → TTC 계산 중단 + 칼만 Q 동결 판단에 사용
     */
    val isStationary: Boolean get() = stationaryFrameCount >= STATIONARY_CONFIRM_FRAMES

    /**
     * 칼만 프로세스 노이즈 Q 스케일팩터 (0.01 ~ 3.0):
     * - 확정 정지  → Q×0.01: 칼만 거의 동결 (다중경로 페이딩 Ghost Alarm 차단)
     * - 정지 전 단계 → Q×0.3: 강한 평활화 (body-block 억제)
     * - 빠른 이동  → Q×3.0: 빠른 응답 (실제 접근 신속 추적)
     */
    val adaptiveQFactor: Double
        get() {
            if (isStationary) return 0.01   // 확정 정지: 칼만 동결
            val s = motionScore
            return when {
                s < STATIONARY_THRESHOLD -> 0.3
                s > FAST_THRESHOLD       -> 3.0
                else -> 0.3 + (s - STATIONARY_THRESHOLD).toDouble() /
                              (FAST_THRESHOLD - STATIONARY_THRESHOLD) * 2.7
            }
        }

    fun debugString(): String =
        "score=%.2f isStationary=$isStationary Q×=%.1f".format(
            motionScore, if (isStationary) 1 else 0, adaptiveQFactor)
}
