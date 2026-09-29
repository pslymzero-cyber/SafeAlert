package com.wf11.safealert.service

import kotlin.math.acos
import kotlin.math.sqrt

/**
 * 가속도 샘플(중력 포함, m/s^2)로 활동 초와 낙상을 판정한다 (v1.1.99).
 *
 * 순수 JVM 로직. 활동 초: 1초 창의 |a| 표준편차가 ACTIVE_STD 이상이거나, 직전 창 평균 벡터와의
 * 각도 차가 ACTIVE_ANGLE_DEG 이상이면 활동. 최근 10개 창 중 3개 이상 활동이면 MOVED.
 * 샘플이 없는 창은 정지로 센다 (D-07).
 *
 * 낙상: 자유낙하(0.5 G 미만 60 ms 이상) 직후 1초 안의 충격(2.5 G 초과), 충격 2~12초 뒤 구간에서
 * 자세가 45도 이상 바뀌었고 활동 초가 3개 미만이면 FALL. 임계값은 문헌 범위의 보수값이라 현장 보정 대상이다.
 */
class MotionAnalyzer {

    enum class Signal { NONE, MOVED, FALL }

    companion object {
        const val G = 9.80665
        const val ACTIVE_STD = 0.3
        const val ACTIVE_ANGLE_DEG = 10.0
        const val MOVE_WINDOWS = 10
        const val MOVE_MIN_ACTIVE = 3
        const val FREE_FALL_G = 0.5
        const val FREE_FALL_MIN_MS = 60L
        const val IMPACT_G = 2.5
        const val IMPACT_WINDOW_MS = 1000L
        const val POST_START_MS = 2000L
        const val POST_END_MS = 12000L
        const val POSTURE_DEG = 45.0
        const val POST_MAX_ACTIVE = 3
    }

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

    fun add(tMs: Long, x: Float, y: Float, z: Float): Signal {
        val ax = x.toDouble(); val ay = y.toDouble(); val az = z.toDouble()
        val mag = sqrt(ax * ax + ay * ay + az * az)

        var moved = false
        val idx = tMs / 1000
        if (curIdx < 0) {
            curIdx = idx
        } else if (idx > curIdx) {
            moved = closeWindow()
            curIdx = idx
        }
        n++; sumM += mag; sumM2 += mag * mag; sx += ax; sy += ay; sz += az

        val fell = detectFall(tMs, ax, ay, az, mag)
        return if (fell) Signal.FALL else if (moved) Signal.MOVED else Signal.NONE
    }

    /** 현재 창을 닫고 MOVED 조건 충족 여부를 돌려준다. */
    private fun closeWindow(): Boolean {
        val cnt = n.toDouble()
        val mx = sx / cnt; val my = sy / cnt; val mz = sz / cnt
        val mm = sumM / cnt
        val std = sqrt((sumM2 / cnt - mm * mm).coerceAtLeast(0.0))
        var active = std >= ACTIVE_STD
        if (!active && hasPrev) active = angleDeg(mx, my, mz, prevX, prevY, prevZ) >= ACTIVE_ANGLE_DEG

        if (lastClosedIdx >= 0) {
            val gap = curIdx - lastClosedIdx
            activeMask = if (gap >= 64) 0L else activeMask shl gap.toInt()
        }
        if (active) activeMask = activeMask or 1L

        if (candidate) {
            val start = curIdx * 1000
            if (active && start >= impactT + POST_START_MS && start + 1000 <= impactT + POST_END_MS) postActive++
        }

        hasPrev = true; prevX = mx; prevY = my; prevZ = mz
        lastClosedIdx = curIdx
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
            } else if (mag > IMPACT_G * G && !candidate) {
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

    private fun angleDeg(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double): Double {
        val na = sqrt(ax * ax + ay * ay + az * az)
        val nb = sqrt(bx * bx + by * by + bz * bz)
        if (na < 1e-6 || nb < 1e-6) return 0.0
        val c = ((ax * bx + ay * by + az * bz) / (na * nb)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(c))
    }
}
