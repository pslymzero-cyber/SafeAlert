package com.wf11.safealert.service

/**
 * 걷는 모양 걸음 증거 층 (v1.1.99). 안드로이드 의존이 없는 순수 로직이다. 시각은 전부 elapsedRealtime 기준 ms 다.
 *
 * 걸음 센서가 낸 걸음은 그 걸음 시각을 덮는 닫힌 1초 가속도 창(끝-1000 <= t < 끝)이 걷는 모양일 때만 받아들인다.
 * 창이 닫히기 전에 온 걸음은 보류했다가 그 창이 닫힐 때 판정하고, 덮는 창 없이 더 뒤 창이 닫히면 버린다.
 * 앱 자신이 진동하는 동안의 걸음은 버린다. 이미 닫힌 끝 이하의 창은 무시한다.
 * closedTo(닫힌 창 끝)·stepSeenTo(걸음 전달 시각)는 마감 판정이 기다리는 센서 데이터 도착 범위다.
 */
class WalkingSteps {

    private class Win(val endMs: Long, val walking: Boolean)

    private companion object {
        const val WINDOW_MS = 1_000L
        const val KEEP_MS = 60_000L
    }

    private val windows = ArrayDeque<Win>()
    private val pending = ArrayDeque<Long>()
    private val steps = ArrayDeque<Long>()

    var closedTo = Long.MIN_VALUE
        private set
    var stepSeenTo = Long.MIN_VALUE
        private set

    fun reset() {
        windows.clear()
        pending.clear()
        steps.clear()
        closedTo = Long.MIN_VALUE
        stepSeenTo = Long.MIN_VALUE
    }

    /** 닫힌 1초 창. 이미 닫힌 끝 이하면 null. 아니면 이 창으로 판정이 끝나 받아들인 보류 걸음 시각들. */
    fun onWindow(endMs: Long, walking: Boolean): List<Long>? {
        if (endMs <= closedTo) return null
        closedTo = endMs
        windows.addLast(Win(endMs, walking))
        while (endMs - windows.first().endMs > KEEP_MS) windows.removeFirst()
        val judged = pending.filter { it < endMs }
        if (judged.isEmpty()) return emptyList()
        pending.removeAll { it < endMs }
        return judged.filter { walking && it >= endMs - WINDOW_MS }.onEach { keep(it) }
    }

    /** 걸음 1건. 곧바로 받아들이면 그 시각, 보류하거나 버리면 null. */
    fun onStep(tMs: Long, vibrating: Boolean): Long? {
        if (tMs > stepSeenTo) stepSeenTo = tMs
        if (vibrating) return null
        if (tMs >= closedTo) {
            pending.addLast(tMs)
            while (tMs - pending.first() > KEEP_MS) pending.removeFirst()
            return null
        }
        val w = windows.lastOrNull { tMs >= it.endMs - WINDOW_MS && tMs < it.endMs } ?: return null
        if (!w.walking) return null
        keep(tMs)
        return tMs
    }

    /** 걸음 센서 flush 완료: 요청 시각까지의 걸음은 다 왔다. */
    fun stepsFlushed(tMs: Long) {
        if (tMs > stepSeenTo) stepSeenTo = tMs
    }

    /** 받아들인 걸음 중 from..to(양 끝 포함). */
    fun stepsIn(from: Long, to: Long): Int = steps.count { it in from..to }

    /** 시작이 from 이상이고 끝이 to 이하인 걷는 모양 창 수. */
    fun strongIn(from: Long, to: Long): Int =
        windows.count { it.walking && it.endMs - WINDOW_MS >= from && it.endMs <= to }

    /** 가장 최근 창부터 거꾸로 이어진 걷는 모양 창 중 시작이 from 이상인 것들의 길이(ms). 최근 창이 걷는 모양 아니면 0. */
    fun runSince(from: Long): Long {
        var n = 0
        var next = Long.MIN_VALUE
        for (i in windows.indices.reversed()) {
            val w = windows[i]
            if (!w.walking || w.endMs - WINDOW_MS < from) break
            if (next != Long.MIN_VALUE && w.endMs != next - WINDOW_MS) break
            n++
            next = w.endMs
        }
        return n * WINDOW_MS
    }

    private fun keep(t: Long) {
        steps.addLast(t)
        while (t - steps.first() > KEEP_MS) steps.removeFirst()
    }
}
