package com.wf11.safealert.service

/**
 * 이 앱이 건 진동 구간의 최근 10초 기록 (v1.1.99). 순수 클래스(안드로이드 무관).
 *
 * 진동 모터의 흔들림을 가속도 센서가 움직임으로 읽지 않도록, 진동이 실제로 켜져 있던 구간(+200ms)에 든
 * 센서 표본을 활동 통계에서 뺀다. 센서 표본은 묶여서 최대 5초 늦게 도착하므로 "지금 진동 중인가"가 아니라
 * 표본 시각이 과거 진동 구간에 들었는지를 본다 — 그래서 최근 구간을 10초 동안 기억한다.
 * 반복 진동은 켜진 부분(on)만 덮고 쉬는 부분은 덮지 않는다. 센서 이벤트 시각이 elapsedRealtime 기준이라고
 * 가정한다. 다른 앱이 건 진동은 볼 수 없다(한계).
 */
class VibrationWindow {
    companion object {
        const val GRACE_MS = 200L
        const val KEEP_MS = 10_000L
    }

    /** end 가 Long.MAX_VALUE 면 열린 반복. 한 번 진동은 period = Long.MAX_VALUE. */
    private class Seg(val start: Long, var end: Long, val onMs: Long, val periodMs: Long)

    private val segs = ArrayList<Seg>()

    @Synchronized fun oneShot(nowMs: Long, durMs: Long) {
        prune(nowMs)
        segs.add(Seg(nowMs, nowMs + durMs, durMs, Long.MAX_VALUE))
    }

    /** 다시 부르면 위상이 새로 시작한다(파형을 다시 건 것과 같다). */
    @Synchronized fun loopStart(nowMs: Long, onMs: Long, periodMs: Long) {
        prune(nowMs)
        closeOpenLoops(nowMs)
        segs.add(Seg(nowMs, Long.MAX_VALUE, onMs, periodMs))
    }

    @Synchronized fun loopStop(nowMs: Long) = closeOpenLoops(nowMs)

    /** 진동을 취소한 시각. 그 시점에 아직 이어지던 모든 구간을 끝낸다. */
    @Synchronized fun cut(nowMs: Long) {
        for (s in segs) if (s.end > nowMs) s.end = nowMs
    }

    @Synchronized fun covers(tMs: Long): Boolean {
        for (s in segs) {
            if (tMs < s.start) continue
            if (s.end != Long.MAX_VALUE && tMs > s.end + GRACE_MS) continue
            // 한 번 진동은 끝 시각 검사만으로 충분하다. 반복은 켜진 부분(+여유)만 덮는다.
            if (s.periodMs == Long.MAX_VALUE || (tMs - s.start) % s.periodMs < s.onMs + GRACE_MS) return true
        }
        return false
    }

    private fun closeOpenLoops(nowMs: Long) {
        for (s in segs) if (s.end == Long.MAX_VALUE) s.end = nowMs
    }

    private fun prune(nowMs: Long) {
        segs.removeAll { it.end != Long.MAX_VALUE && it.end + GRACE_MS < nowMs - KEEP_MS }
    }
}
