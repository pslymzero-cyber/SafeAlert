package com.wf11.safealert.service

import kotlin.math.acos
import kotlin.math.sqrt

/**
 * 가속도 샘플(중력 포함, m/s^2)로 활동 초·걷는 모양 창·낙상을 판정한다 (v1.1.99).
 *
 * 순수 JVM 로직. 활동 초: 1초 창의 |a| 표준편차가 ACTIVE_STD 이상이거나, 직전 창 평균 벡터와의
 * 각도 차가 ACTIVE_ANGLE_DEG 이상이면 활동. 최근 10개 창 중 3개 이상 활동이면 MOVED.
 * 샘플이 없는 창은 정지로 센다 (D-07). 샘플 공백은 MOVED 판정 전에 빈 창으로 밀어 넣는다 (v1.1.99).
 * 걷는 모양 창: |a| 표준편차가 걷기 수준(STRONG_STD) 이상. 각도는 보지 않는다 — 쓰러진 채 뒤척임·자세 변화는 제외.
 *
 * 낙상(FALL)이 사고 감지의 유일한 신호다: 자유낙하(0.5 G 미만 60 ms 이상) 직후 1초 안의 충격(2.5 G 초과),
 * 충격 2~12초 뒤 구간에서 자세가 45도 이상 바뀌었고 활동 초가 3개 미만. 임계값은 문헌 범위의 보수값이라 현장 보정 대상이다.
 * 낙상 충격 임계값은 센서 측정 범위가 2.5 G 미만인 기기(2 G 센서)에서는 범위에 맞춰 낮춘다 (impactGFor).
 * eventMs 에 충격 표본의 센서 시각을 남긴다(판정 시각이 아니라 충격 시각).
 *
 * 이 앱 자신의 진동 구간 표본(masked)은 활동 통계에서만 뺀다. 낙상 판정은 모든 표본을 본다 —
 * 진동 모터 가속도는 충격 임계값보다 훨씬 작고, 알람 중 낙상을 놓치는 쪽이 더 나쁘다 (v1.1.99).
 *
 * 닫힌 1초 창마다 걷는 모양 여부를 onWindow 로 알린다.
 */
class MotionAnalyzer(
    private val impactG: Double = IMPACT_G,
    private val onWindow: (Window) -> Unit = {}
) {

    enum class Signal { NONE, MOVED, FALL }

    /** 닫힌 1초 창: endMs(센서 시각), 표본이 있고 걷기 수준으로 흔들렸는지(걷는 모양). */
    data class Window(val endMs: Long, val strong: Boolean)

    companion object {
        const val G = 9.80665
        const val ACTIVE_STD = 0.3
        const val ACTIVE_ANGLE_DEG = 10.0
        /** 걷기 수준 흔들림(m/s^2, |a| 표준편차). 현장 보정 대상. */
        const val STRONG_STD = 1.5
        const val MOVE_WINDOWS = 10
        const val MOVE_MIN_ACTIVE = 3
        const val FREE_FALL_G = 0.5
        const val FREE_FALL_MIN_MS = 60L
        const val IMPACT_G = 2.5
        /** 측정 범위를 G 로 나눈 값이 이보다 작으면 g 단위로 보고한 것으로 해석한다. 현장 보정 대상. */
        const val MIN_RANGE_G = 1.5
        /** 움직임·걷는 모양 판정 창 길이(센서 시각 ms). */
        const val WINDOW_MS = 1_000L
        const val IMPACT_WINDOW_MS = 1000L
        const val POST_START_MS = 2000L
        const val POST_END_MS = 12000L
        const val POSTURE_DEG = 45.0
        const val POST_MAX_ACTIVE = 3

        /**
         * 센서 최대 범위(m/s^2)가 MIN_RANGE_G 이상 IMPACT_G 미만이면 범위의 90% 를 낙상 충격 임계로 쓴다. 아니면 IMPACT_G.
         * 범위를 G 로 나눈 값이 MIN_RANGE_G 보다 작으면 g 단위로 보고한 것으로 해석한다.
         */
        fun impactGFor(maxRangeMs2: Float): Double {
            val rangeG = (maxRangeMs2 / G).let { if (it < MIN_RANGE_G) maxRangeMs2.toDouble() else it }
            return if (rangeG >= MIN_RANGE_G && rangeG < IMPACT_G) 0.9 * rangeG else IMPACT_G
        }
    }

    /** 마지막 FALL 신호의 충격 표본 센서 시각(ms). */
    var eventMs = 0L
        private set

    // 1초 창 누적
    private var curIdx = -1L
    private var n = 0
    private var sumM = 0.0
    private var sumM2 = 0.0
    private var sx = 0.0
    private var sy = 0.0
    private var sz = 0.0

    // 직전 닫힌 창
    private var lastClosedIdx = -1L
    private var hasPrev = false
    private var prevX = 0.0
    private var prevY = 0.0
    private var prevZ = 0.0
    private var activeMask = 0L

    // 자유낙하 추적
    private var ffStart = -1L
    private var ffPreValid = false
    private var ffPreX = 0.0
    private var ffPreY = 0.0
    private var ffPreZ = 0.0
    private var armedEnd = -1L
    private var armedPreValid = false
    private var armedPreX = 0.0
    private var armedPreY = 0.0
    private var armedPreZ = 0.0

    // 충격 후보
    private var candidate = false
    private var impactT = 0L
    private var candPreValid = false
    private var candPreX = 0.0
    private var candPreY = 0.0
    private var candPreZ = 0.0
    private var postN = 0
    private var postX = 0.0
    private var postY = 0.0
    private var postZ = 0.0
    private var postActive = 0

    fun reset() {
        curIdx = -1L; n = 0; sumM = 0.0; sumM2 = 0.0; sx = 0.0; sy = 0.0; sz = 0.0
        lastClosedIdx = -1L; hasPrev = false; activeMask = 0L
        ffStart = -1L; ffPreValid = false; armedEnd = -1L; armedPreValid = false
        candidate = false; postN = 0; postX = 0.0; postY = 0.0; postZ = 0.0; postActive = 0
    }

    fun add(tMs: Long, x: Float, y: Float, z: Float, masked: Boolean = false): Signal {
        val ax = x.toDouble(); val ay = y.toDouble(); val az = z.toDouble()
        val mag = sqrt(ax * ax + ay * ay + az * az)

        var moved = false
        val idx = tMs / WINDOW_MS
        if (curIdx < 0) {
            curIdx = idx
        } else if (idx > curIdx) {
            moved = closeWindow(idx)
            curIdx = idx
        }
        if (!masked) { n++; sumM += mag; sumM2 += mag * mag; sx += ax; sy += ay; sz += az }

        val fell = detectFall(tMs, ax, ay, az, mag)
        if (fell) {
            eventMs = impactT
            return Signal.FALL
        }
        return if (moved) Signal.MOVED else Signal.NONE
    }

    /** 현재 창을 닫고 MOVED 조건 충족 여부를 돌려준다. nextIdx 는 새 샘플이 여는 창 번호. */
    private fun closeWindow(nextIdx: Long): Boolean {
        // 표본이 하나도 쌓이지 않은 창(전부 자체 진동 구간)은 정지로 세고 직전 평균 벡터를 바꾸지 않는다
        val cnt = n.toDouble()
        val has = n > 0
        val mx = if (has) sx / cnt else prevX
        val my = if (has) sy / cnt else prevY
        val mz = if (has) sz / cnt else prevZ
        val mm = if (has) sumM / cnt else 0.0
        val std = if (has) sqrt((sumM2 / cnt - mm * mm).coerceAtLeast(0.0)) else 0.0
        var active = has && std >= ACTIVE_STD
        if (!active && has && hasPrev) active = angleDeg(mx, my, mz, prevX, prevY, prevZ) >= ACTIVE_ANGLE_DEG
        onWindow(Window((curIdx + 1) * WINDOW_MS, has && std >= STRONG_STD))

        if (lastClosedIdx >= 0) {
            val gap = curIdx - lastClosedIdx
            activeMask = if (gap >= 64) 0L else activeMask shl gap.toInt()
        }
        if (active) activeMask = activeMask or 1L
        // 닫힌 창과 새 창 사이의 빈 창은 정지로 밀어 넣은 뒤 판정한다 (D-07)
        val empty = nextIdx - curIdx - 1
        if (empty > 0) activeMask = if (empty >= 64) 0L else activeMask shl empty.toInt()

        if (candidate) {
            val start = curIdx * WINDOW_MS
            if (active && start >= impactT + POST_START_MS && start + WINDOW_MS <= impactT + POST_END_MS) postActive++
        }

        if (has) { hasPrev = true; prevX = mx; prevY = my; prevZ = mz }
        lastClosedIdx = nextIdx - 1
        n = 0; sumM = 0.0; sumM2 = 0.0; sx = 0.0; sy = 0.0; sz = 0.0
        val mask = (1L shl MOVE_WINDOWS) - 1
        return java.lang.Long.bitCount(activeMask and mask) >= MOVE_MIN_ACTIVE
    }

    private fun detectFall(t: Long, x: Double, y: Double, z: Double, mag: Double): Boolean {
        // 자유낙하 구간 추적
        if (mag < FREE_FALL_G * G) {
            if (ffStart < 0) {
                ffStart = t
                ffPreValid = hasPrev
                ffPreX = prevX; ffPreY = prevY; ffPreZ = prevZ
            }
        } else if (ffStart >= 0) {
            if (t - ffStart >= FREE_FALL_MIN_MS) {
                armedEnd = t
                armedPreValid = ffPreValid
                armedPreX = ffPreX; armedPreY = ffPreY; armedPreZ = ffPreZ
            }
            ffStart = -1L
        }

        // 자유낙하 직후 충격
        if (armedEnd >= 0) {
            if (t - armedEnd > IMPACT_WINDOW_MS) {
                armedEnd = -1L
            } else if (mag > impactG * G && !candidate) {
                candidate = true
                impactT = t
                candPreValid = armedPreValid
                candPreX = armedPreX; candPreY = armedPreY; candPreZ = armedPreZ
                postN = 0; postX = 0.0; postY = 0.0; postZ = 0.0; postActive = 0
                armedEnd = -1L
            }
        }

        if (!candidate) return false
        if (t >= impactT + POST_START_MS && t < impactT + POST_END_MS) {
            postN++; postX += x; postY += y; postZ += z
            return false
        }
        if (t < impactT + POST_END_MS) return false

        // 판정 시점
        candidate = false
        if (postN == 0 || postActive >= POST_MAX_ACTIVE) return false
        // 낙하 전 자세를 모르면(서비스 시작 직후) 자세 변화로 본다 — 놓치는 쪽이 더 나쁘다 (D-07)
        val posture = !candPreValid ||
            angleDeg(postX / postN, postY / postN, postZ / postN, candPreX, candPreY, candPreZ) >= POSTURE_DEG
        return posture
    }
}

private fun angleDeg(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double): Double {
    val na = sqrt(ax * ax + ay * ay + az * az)
    val nb = sqrt(bx * bx + by * by + bz * bz)
    if (na < 1e-6 || nb < 1e-6) return 0.0
    val c = ((ax * bx + ay * by + az * bz) / (na * nb)).coerceIn(-1.0, 1.0)
    return Math.toDegrees(acos(c))
}
