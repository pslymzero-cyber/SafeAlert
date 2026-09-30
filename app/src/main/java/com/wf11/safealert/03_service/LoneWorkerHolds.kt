package com.wf11.safealert.service

/**
 * 동료 사이렌이 이 기기에서 진동하는 동안의 무동작 셈 멈춤(C2, 순수, v1.1.99).
 * 시작·끝은 그것을 처음 본 tick 이다.
 * 상한(D1): 멈춘 시간이 stillMs 에 이르면 사이렌이 계속 울려도 상한 시각에 멈춤을 끝낸 것으로 기준을 옮기고
 * (멈춤 전 쌓인 시간 유지), 이 기기의 사이렌 진동이 한 번 꺼질 때까지 다시 멈추지 않는다. 다시 켜지면 새 멈춤이다.
 */
class SirenPause {
    /** 멈춤 시작 시각. 멈춤이 없으면 MIN_VALUE. */
    private var at = Long.MIN_VALUE
    /** 상한으로 멈춤을 끝냈고 진동이 아직 꺼지지 않았다. */
    private var spent = false

    private val active: Boolean get() = at != Long.MIN_VALUE

    /** 이 마감을 멈춤이 가린다 — 멈춤 시작 이하의 지난 마감은 가리지 않는다(S1, 데이터를 기다려 판정). */
    fun covers(deadline: Long): Boolean = active && deadline > at

    fun reset() {
        at = Long.MIN_VALUE
        spent = false
    }

    /**
     * 멈춤을 지금(상한이 먼저면 상한 시각에) 끝냈다고 본 무동작 기준: 멈춤 전까지 쌓인 시간만 남긴다
     * (멈춤 중 기준이 올랐으면 끝 시각부터). 멈춤이 가리지 않는 마감(멈춤 전에 지난 마감)이면 기준 그대로(S1).
     */
    fun base(stillBase: Long, nowMs: Long, stillMs: Long): Long =
        if (!covers(stillBase + stillMs)) stillBase
        else maxOf(stillBase, minOf(nowMs, at + stillMs) - maxOf(0L, at - stillBase))

    /** 진동이 켜졌으면 멈춤을 시작하고, 꺼졌거나 상한에 이르렀으면 멈춤을 끝낸다. 새 무동작 기준을 돌려준다. */
    fun update(vibrating: Boolean, nowMs: Long, stillBase: Long, stillMs: Long): Long {
        if (!vibrating) spent = false
        if (vibrating && !active && !spent) {
            at = nowMs
        } else if (active && (!vibrating || nowMs - at >= stillMs)) {
            val b = base(stillBase, nowMs, stillMs)
            at = Long.MIN_VALUE
            spent = vibrating
            return b
        }
        return stillBase
    }
}

/**
 * 서비스 재시작 뒤의 보류(순수, v1.1.99).
 * 전원 보류: 저장한 충전 값과 지금 전원이 다르면 재시작 ~ 재시작 + POWER_HOLD_MS 창 동안 확인 창을 새로 열지도,
 * 복원한 확인 창을 띄우지도 않는다(E9). 디바운스가 확정하면 그 확정 시각(첫 변화 + DEBOUNCE_MS)에 끝나고, 아니면
 * 창 끝에 끝난다 — 창 끝에 창 안에서 시작한 대기가 남아 있으면 그 대기가 확정되거나 버려질 때까지 한 번 늘린다
 * (늦어도 창 끝 + CONFIRM_MS 전, 창 끝 뒤 시작한 대기로는 늘리지 않음, L1). 재시작 변화는 보류 중에 확정된
 * 첫 변화 하나이고 그 뒤 변화는 창 안이라도 실제 변화다(L2).
 * 복원한 창은 종류만 들고 있다가 보류 끝에 연다 — 들고 있는 창은 열린 창과 같은 규칙으로 버린다.
 * 구역 보류: 복원한 안전구역 안 상태를 구역 보고 없이 유지하는 한도(C3). 보류 중에는 정착으로 올리지 않는다.
 */
class RestartHold {
    companion object {
        /** 재시작 전원 창 길이: 디바운스 확인 시각(CONFIRM_MS) + 여유 1초. */
        const val POWER_HOLD_MS = PowerDebounce.CONFIRM_MS + 1_000L
    }

    /** 전원 보류 끝(연장·확정·버림으로 바뀜). 끝난 뒤에도 그 시각을 남긴다. */
    private var powerUntil = Long.MIN_VALUE
    private var zoneUntil = Long.MIN_VALUE
    /** 재시작 전원 창 끝 — 연장 기준, 첫 확정에서 지운다. */
    private var powerWindowEnd = Long.MIN_VALUE

    /** 전원 보류 중 들고 있는 복원 확인 창 종류("still"·"fall"). 없으면 빈 문자열. */
    var check = ""
        private set

    fun reset() {
        powerUntil = Long.MIN_VALUE
        zoneUntil = Long.MIN_VALUE
        powerWindowEnd = Long.MIN_VALUE
        check = ""
    }

    fun holdPower(nowMs: Long) {
        powerUntil = nowMs + POWER_HOLD_MS
        powerWindowEnd = powerUntil
    }

    fun holdCheck(kind: String) {
        check = kind
    }

    fun powerHeld(nowMs: Long): Boolean = nowMs < powerUntil

    fun powerEnd(nowMs: Long): Long? = if (powerHeld(nowMs)) powerUntil else null

    /** 원시 값으로 디바운스 대기가 바뀌었다(pendingAt 없으면 MIN_VALUE) — 보류 중일 때만 끝을 다시 정한다. */
    fun powerWait(pendingAt: Long, tMs: Long) {
        if (!powerHeld(tMs)) return
        val inWindow = pendingAt != Long.MIN_VALUE && pendingAt < powerWindowEnd
        powerUntil = maxOf(powerWindowEnd, if (inWindow) pendingAt + PowerDebounce.CONFIRM_MS else tMs)
    }

    /** 디바운스가 확정한 전원 변화(atMs = 첫 변화 시각) — 첫 확정이고 첫 변화가 창 안이면 재시작 변화로 true, 보류는 atMs + DEBOUNCE_MS 에 끝난다. */
    fun powerSettled(atMs: Long): Boolean {
        val restart = atMs < powerWindowEnd
        powerWindowEnd = Long.MIN_VALUE
        if (restart) powerUntil = atMs + PowerDebounce.DEBOUNCE_MS
        return restart
    }

    /** 들고 있던 확인 창 종류를 한 번 돌려주고 비운다. */
    fun takeCheck(): String? = check.ifEmpty { null }?.also { check = "" }

    /** 들고 있던 확인 창을 버린다(kind 가 있으면 그 종류만). */
    fun dropCheck(kind: String) {
        if (kind.isEmpty() || check == kind) check = ""
    }

    fun holdZone(untilMs: Long) {
        zoneUntil = untilMs
    }

    val zoneHeld: Boolean get() = zoneUntil != Long.MIN_VALUE

    fun zoneReported() {
        zoneUntil = Long.MIN_VALUE
    }

    /** 구역 보류 한도가 지났으면 그 끝 시각을 한 번 돌려주고 지운다. */
    fun zoneExpired(nowMs: Long): Long? =
        if (zoneHeld && nowMs >= zoneUntil) zoneUntil.also { zoneUntil = Long.MIN_VALUE } else null
}
