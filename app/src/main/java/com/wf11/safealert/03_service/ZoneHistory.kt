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

    companion object {
        /** FALL 은 충격 뒤 12~17초에 오고, 배치 전달·JudgeOrder 미룸이 더해진다 — 넉넉히 2분. */
        const val KEEP_MS = 120_000L
    }
}
