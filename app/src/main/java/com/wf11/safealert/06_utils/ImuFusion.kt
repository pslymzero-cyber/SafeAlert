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

    // [v1.0.29 다이나믹 페이로드] 3-State 모션 감지 추가 레이어.
    //   STATE_* 값은 BleConstants.MOTION_STATE_* 와 동일 — ServiceData 1Byte로 송신된다.
    //   기존 정지판정(isStationary)·Q스케일(adaptiveQFactor)은 일절 불변, 위에 얹기만 한다.
    private const val STATE_STATIONARY = 0x00
    private const val STATE_NORMAL     = 0x01
    private const val STATE_SUDDEN     = 0x02
    // 급정거: 선형가속도 크기(m/s²)가 이 값 초과 (빠른 이동 FAST_THRESHOLD=2.5 보다 큰 충격)
    private const val SUDDEN_ACCEL_THRESHOLD = 5.0f
    // 급회전: 자이로 Z축 회전율(rad/s) 절댓값이 이 값 초과
    private const val SUDDEN_GYRO_THRESHOLD  = 1.5f
    // 0x02 진입 후 이 시간 동안 0x02 유지 → 잦은 깜빡임 방지(Hysteresis 디바운스)
    private const val SUDDEN_HOLD_MS         = 1500L

    private var sensorManager: SensorManager? = null
    @Volatile private var isRunning = false

    // 연속 정지 프레임 카운터 (lock으로 보호)
    @Volatile private var stationaryFrameCount = 0

    // [v1.0.27] 정지↔이동 상태 변화 통지 훅 — BleService 가 동적 스캔 모드 제어용으로 구독.
    //   isStationary 판정식·임계값은 일절 불변. 상태가 '바뀌는 순간'만 콜백으로 알린다.
    //   이동 감지 시 0프레임(진짜 0초) 지연으로 호출 → 즉시 LOW_LATENCY 복귀 보장.
    @Volatile var onStationaryChanged: ((Boolean) -> Unit)? = null
    @Volatile private var lastNotifiedStationary = false

    // [v1.0.29] 3-State 모션 상태 변화 통지 훅 — BleService 가 구독해
    //   BleAdvertiser.updateState() 로 ServiceData 상태 코드를 갱신한다.
    @Volatile var onMotionStateChanged: ((Int) -> Unit)? = null
    @Volatile private var lastNotifiedMotionState = STATE_STATIONARY
    // 마지막 '급변(급정거/급회전)' 감지 시각(elapsedRealtime). HOLD 윈도 동안 0x02 유지.
    @Volatile private var lastSuddenMs = 0L

    private val accelBuffer = ArrayDeque<Float>()
    private val lock = Any()

    // [v1.0.35] 방위각(Azimuth) — 지자기/회전벡터 센서로 산출한 현재 진행 방위각(0~360°).
    //   송신단이 ServiceData Byte 2 로 공유 → 수신단의 '방향 벡터 필터'에 쓰인다.
    //   hasAzimuth=false(센서 없음)면 BleService 가 방향 필터를 비활성(안전: 억제 안 함).
    @Volatile var azimuthDeg: Float = 0f
        private set
    @Volatile var hasAzimuth: Boolean = false
        private set
    private val rotationMatrix    = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // [v1.0.35] 회전 벡터 → 방위각(Azimuth) 산출. ServiceData Byte 2 로 공유된다.
            if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                val deg = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                azimuthDeg = ((deg % 360f) + 360f) % 360f   // 방위각 0~360° 정규화
                return
            }

            // [v1.0.29] 자이로: Z축 회전율 급증 → 급회전(0x02) 트리거
            if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
                if (abs(event.values[2]) > SUDDEN_GYRO_THRESHOLD) {
                    lastSuddenMs = SystemClock.elapsedRealtime()
                }
                evaluateMotionState()
                return
            }

            // ── 이하 선형 가속도 경로 (v1.0.27 로직 100% 보존) ──────────────
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

            // [v1.0.29] 가속도 급변(급정거) → 0x02 트리거 후 모션 상태 재평가
            if (mag > SUDDEN_ACCEL_THRESHOLD) {
                lastSuddenMs = SystemClock.elapsedRealtime()
            }
            evaluateMotionState()
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    // [v1.0.29] 현재 모션 상태를 평가해 '바뀌는 순간'만 콜백으로 통지(가속도/자이로 공용).
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
        // [v1.0.29] 자이로스코프 추가 등록 — 급회전(0x02) 감지용. 없으면 가속도 급변만으로 판정.
        val gyro = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (gyro != null) {
            sensorManager?.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME)
            Log.d(TAG, "자이로스코프 등록 — 3-State 급정거/급회전 감지 활성")
        } else {
            Log.w(TAG, "자이로 센서 없음 — 가속도 급변만으로 0x02 판정")
        }
        // [v1.0.35] 회전 벡터 센서 등록 — 방위각(Byte 2) 측정용. 없으면 방향 공유 비활성.
        //   SENSOR_DELAY_UI(~16Hz)면 방향 추종에 충분하고 배터리에도 유리.
        val rot = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rot != null) {
            sensorManager?.registerListener(listener, rot, SensorManager.SENSOR_DELAY_UI)
            hasAzimuth = true
            Log.d(TAG, "회전벡터 센서 등록 — 방위각 공유(Byte 2) 활성")
        } else {
            hasAzimuth = false
            Log.w(TAG, "회전벡터 센서 없음 — 방향 정보 공유 비활성")
        }
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
        // [v1.0.29] 3-State 상태 리셋
        lastSuddenMs = 0L
        lastNotifiedMotionState = STATE_STATIONARY
        // [v1.0.35] 방위각 상태 리셋
        hasAzimuth = false
        azimuthDeg = 0f
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

    /**
     * v1.0.29 3-State 모션 상태 (0x00 정지 / 0x01 일반 이동 / 0x02 급정거·급회전).
     * 급변 감지 후 SUDDEN_HOLD_MS 동안 0x02 유지(Hysteresis) → 잦은 깜빡임 방지.
     * 그 외에는 기존 isStationary 판정을 그대로 따른다(정지=0x00, 이동=0x01).
     */
    val motionState: Int
        get() {
            if (SystemClock.elapsedRealtime() - lastSuddenMs < SUDDEN_HOLD_MS) return STATE_SUDDEN
            return if (isStationary) STATE_STATIONARY else STATE_NORMAL
        }

    fun debugString(): String =
        "score=%.2f isStationary=$isStationary Q×=%.1f".format(
            motionScore, if (isStationary) 1 else 0, adaptiveQFactor)
}
