package com.wf11.safealert.service

/**
 * 세이프존 원시 안/밖 기록. 낙상은 충격 순간(trigMs)의 세이프존 원시 안/밖으로 판정한다(A1) — FALL 이 온 시각이 아니다.
 * 표시는 시각 순서로 쌓고, KEEP_MS 전 시각에 걸려 있던 상태 하나만 남기고 더 오래된 표시는 버린다.
 */
internal class ZoneHistory {
    private val marks = ArrayDeque<Pair<Long, Boolean>>()

    /** 마지막 표시의 상태(표시가 없으면 밖). */
    val inside: Boolean get() = marks.lastOrNull()?.second ?: false

    /** 마지막 표시 시각(없으면 0). 안쪽이면 들어선 시각이다. */
    val since: Long get() = marks.lastOrNull()?.first ?: 0L

    fun reset(inside: Boolean, t: Long) {
        marks.clear()
        marks.addLast(t to inside)
    }

    fun mark(inside: Boolean, t: Long) {
        marks.addLast(t to inside)
        while (marks.size >= 2 && marks[1].first <= t - KEEP_MS) marks.removeFirst()
    }

    /** t 에 걸려 있던 상태: t 이하 마지막 표시, t 가 모든 표시보다 이르면 가장 오래된 표시. */
    fun insideAt(t: Long): Boolean = (marks.lastOrNull { it.first <= t } ?: marks.firstOrNull())?.second ?: false

    /** 충격 순간(t) 안이었고 충격 뒤 EXIT_LAG_MS 안(t 초과, t + EXIT_LAG_MS 이하)에 확정된 이탈이 없다 — 낙상의 구역 판정(A1·H8). */
    fun insideAtImpact(t: Long): Boolean = insideAt(t) && marks.none { !it.second && it.first > t && it.first <= t + EXIT_LAG_MS }

    companion object {
        /** FALL 은 충격 뒤 12~17초에 오고, 배치 전달·JudgeOrder 미룸이 더해진다 — 넉넉히 2분. */
        const val KEEP_MS = 120_000L
        /**
         * BleService 는 이탈을 약 10초 늦게 확정한다(약한 표본 3개 또는 10초 무수신, 재시작 구역 보류도 10초에 끝남). 그래서 충격 뒤 12초 안에
         * 확정된 이탈은 충격 때 이미 밖이었다고 본다(사용자 결정 "밖 기준"). MotionAnalyzer.POST_END_MS 이하여야 FALL 이 이 창이 지난 뒤에 온다.
         */
        const val EXIT_LAG_MS = 12_000L
    }
}
