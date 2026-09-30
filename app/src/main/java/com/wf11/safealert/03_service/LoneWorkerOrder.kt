package com.wf11.safealert.service

/**
 * 판정 순서 장치 (순수, v1.1.99). 마감 판정·전원 변화 적용·센서 입력 반영의 순서를 이 한 곳에서 지킨다 —
 * 같은 입력(센서 값·전원 원시 값과 그 시각)이면 센서 묶음 도착 시각·확인 tick 시각과 무관하게 같은 판정이 나온다.
 *
 * 규칙 1(M1·N1): 마감은 그 전(같은 시각 포함)에 시작한 전원 변화가 확정·버림되고 적용된 뒤에 판정한다. 마감까지의
 * 데이터는 다 왔는데 전원 대기로만 막혔으면, 그 뒤 도착한 센서 입력은 콜백 단위·도착 순서대로 보관했다가 판정 뒤에 재생한다.
 * 규칙 2(Y2·Q1): 지났고 아직 판정 안 된 마감보다 늦게 시작한 전원 변화는 2초 안정돼도 그 판정 뒤에 적용한다(효과 시각은
 * 첫 변화 시각). 재시작 창 안에서 시작한 변화는 기다리지 않는다(E9·L1).
 * 규칙 3(C5): 마감은 그 시각까지의 센서 데이터가 들어온 뒤(없으면 LATE_MS 뒤)에 판정한다.
 * 교착 없음: 변화 P 는 마감 D ≥ P 를 막고 마감 D 는 변화 P > D 를 막아 시각 순으로 엄격히 갈리고, 남는 막힘은
 * 2초가 안 된 디바운스 대기뿐이라 확정 확인(confirmAt) 또는 버림에서 풀린다.
 */
class JudgeOrder(
    private val hold: RestartHold,
    private val deadlines: (Long) -> List<Long>,
    private val sensedTo: () -> Long,
    private val apply: (Boolean, Long) -> Unit
) {
    val power = PowerDebounce()
    /** 2초 안정됐지만 아직 로직에 적용하지 않은 전원 변화(값, 첫 변화 시각) — 순서대로 적용한다. */
    private val stable = ArrayDeque<Pair<Boolean, Long>>()
    /** 보관한 센서 입력. end 는 센서 콜백 하나의 끝 표지. */
    private val held = ArrayDeque<() -> Unit>()
    private val end: () -> Unit = {}
    /** 전원 대기로만 막힌 마감이 있어 센서 입력을 보관하는 중. */
    var holding = false
        private set

    fun reset(charging: Boolean) {
        power.seed(charging)
        stable.clear()
        held.clear()
        holding = false
    }

    /** at 이전(같은 시각 포함)에 시작한 전원 변화가 아직 적용되지 않았다(대기 중이거나 안정됐지만 미룸). */
    private fun unappliedBy(at: Long): Boolean = power.pendingBy(at) || stable.any { it.second <= at }

    /** 지났고 마감까지의 데이터가 들어왔거나 LATE_MS 가 지났다(C5). */
    private fun ready(at: Long, nowMs: Long): Boolean =
        nowMs >= at && (sensedTo() >= at || nowMs >= at + LoneWorkerLogic.LATE_MS)

    /** 마감 at 을 지금 판정해도 되나(판정 시각 게이트 한 곳). */
    fun due(at: Long, nowMs: Long): Boolean = ready(at, nowMs) && !unappliedBy(at)

    /** 데이터는 준비됐는데 전원 대기로만 막힌 마감이 있다. */
    private fun blocked(nowMs: Long): Boolean = (power.pending || stable.isNotEmpty()) &&
        deadlines(nowMs).any { ready(it, nowMs) && unappliedBy(it) }

    /** 다음에 tick 이 필요한 시각: 마감(아직이면 그 시각, 지났으면 LATE_MS 뒤)과 확정 확인 중 지금보다 뒤인 가장 이른 것. */
    fun nextCheckAt(nowMs: Long): Long? = (deadlines(nowMs).map { if (nowMs < it) it else it + LoneWorkerLogic.LATE_MS } +
        listOfNotNull(power.confirmAt)).filter { it > nowMs }.minOrNull()

    fun waitingToJudge(nowMs: Long): Boolean = deadlines(nowMs).any { nowMs >= it && !due(it, nowMs) }

    fun waitingOnSensors(nowMs: Long): Boolean =
        deadlines(nowMs).any { nowMs >= it && !due(it, nowMs) && sensedTo() < it }

    fun dueNow(nowMs: Long): Boolean = deadlines(nowMs).any { due(it, nowMs) }

    /** 2초 안정된 변화를 디바운스에서 꺼내고, 미룰 것 없는 변화를 순서대로 적용한다(규칙 2). 하나라도 적용했으면 true. */
    fun settle(nowMs: Long): Boolean {
        power.poll(nowMs)?.let { stable.addLast(it) }
        var applied = false
        while (true) {
            val (on, at) = stable.firstOrNull() ?: break
            if (!hold.inWindow(at) && deadlines(nowMs).any { nowMs >= it && it < at }) break
            stable.removeFirst()
            apply(on, at)
            applied = true
        }
        return applied
    }

    /** 전원 원시 값: 안정된 변화를 먼저 꺼내 적용하고(반대 값이 그것을 흔들림으로 버리지 않게) 원시 값을 넣는다. 적용했거나 대기가 바뀌었으면 true. */
    fun raw(on: Boolean, tMs: Long, sticky: Boolean): Boolean {
        val settled = settle(tMs)
        val changed = power.raw(on, tMs, sticky)
        if (changed) hold.powerWait(power, tMs)
        return settled || changed
    }

    /** 보관 중이면 센서 입력을 보관하고 true(호출자는 반영하지 않는다). */
    fun keep(input: () -> Unit): Boolean {
        if (holding) held.addLast(input)
        return holding
    }

    /** 센서 콜백 하나가 끝났다: 막혔으면 보관을 시작·유지하고, 풀렸으면 판정 뒤 재생한다. */
    fun eventEnd(nowMs: Long, decide: (Long) -> Unit) {
        if (holding && held.lastOrNull() !== end) held.addLast(end)
        release(nowMs, decide)
    }

    /** tick 판정: 판정 → 풀린 미룬 변화 적용 → 다시 판정 … 그다음 보관 입력 재생. */
    fun judge(nowMs: Long, decide: (Long) -> Unit) {
        decideAll(nowMs, decide)
        release(nowMs, decide)
    }

    private fun decideAll(nowMs: Long, decide: (Long) -> Unit) {
        do decide(nowMs) while (settle(nowMs))
    }

    /** 막혔으면 보관(시작·유지), 풀렸으면 [판정 → 콜백 하나 재생] 을 반복하고 마지막에 한 번 더 판정한다. */
    private fun release(nowMs: Long, decide: (Long) -> Unit) {
        if (!holding) {
            holding = blocked(nowMs)
            return
        }
        while (true) {
            holding = blocked(nowMs)
            if (holding) return
            decideAll(nowMs, decide)
            if (held.isEmpty()) return
            while (true) {
                val input = held.removeFirstOrNull() ?: break
                if (input === end) break
                input()
            }
        }
    }
}
