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

    val active: Boolean get() = at != Long.MIN_VALUE

    fun reset() {
        at = Long.MIN_VALUE
        spent = false
    }

    /**
     * 멈춤을 지금(상한이 먼저면 상한 시각에) 끝냈다고 본 무동작 기준: 멈춤 전까지 쌓인 시간만 남긴다
     * (멈춤 중 기준이 올랐으면 끝 시각부터).
     */
    fun base(stillBase: Long, nowMs: Long, stillMs: Long): Long =
        if (!active) stillBase
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
 * 전원 보류: 저장한 충전 값과 지금 전원이 다르면 디바운스가 재시작 차이를 확정하거나 버릴 때까지(대기가 다시 시작되면
 * 그 시작부터 POWER_HOLD_MS 안에서 이어감) 확인 창을 새로 열지도, 복원한 확인 창을 띄우지도 않는다(E9).
 * 복원한 창은 종류만 들고 있다가 보류가 끝난 뒤 연다 — 들고 있는 창은 열린 창과 같은 규칙으로 버린다(Logic.closeCheck).
 * 구역 보류: 복원한 안전구역 안 상태를 구역 보고 없이 유지하는 한도(C3). 보류 중에는 정착으로 올리지 않는다.
 */
class RestartHold {
    companion object {
        /** 대기 시작마다의 안전망. 보통은 디바운스 확정·버림 신호로 먼저 끝난다. */
        const val POWER_HOLD_MS = PowerDebounce.CONFIRM_MS + 1_000L
    }

    private var powerUntil = Long.MIN_VALUE
    private var zoneUntil = Long.MIN_VALUE
    /** 재시작 때 원시 전원(보류 중 낙상의 세이프존 충전 판정). */
    private var plugged = false

    /** 전원 보류 중 들고 있는 복원 확인 창 종류("still"·"fall"). 없으면 빈 문자열. */
    var check = ""
        private set

    fun reset() {
        powerUntil = Long.MIN_VALUE
        zoneUntil = Long.MIN_VALUE
        check = ""
        plugged = false
    }

    fun holdPower(nowMs: Long, plugged: Boolean) {
        powerUntil = nowMs + POWER_HOLD_MS
        this.plugged = plugged
    }

    /**
     * 디바운스 대기(시작 시각, 없으면 null). 대기가 없으면(버렸거나 확정) 보류를 끝내고, 보류 안에서 시작한 대기면
     * 그 시작부터 POWER_HOLD_MS 까지 잇는다. 보류 끝 뒤에 시작한 대기는 재시작 차이가 아니다.
     */
    fun powerPending(since: Long?) {
        if (powerUntil == Long.MIN_VALUE) return
        powerUntil = if (since == null || since >= powerUntil) Long.MIN_VALUE else maxOf(powerUntil, since + POWER_HOLD_MS)
    }

    /** 시각 atMs 의 충전 값: 전원 보류 중이면 재시작 때 원시 전원, 아니면 charging. */
    fun chargingAt(atMs: Long, charging: Boolean): Boolean = if (atMs < powerUntil) plugged else charging

    fun holdCheck(kind: String) {
        check = kind
    }

    fun powerHeld(nowMs: Long): Boolean = nowMs < powerUntil

    fun powerEnd(nowMs: Long): Long? = if (powerHeld(nowMs)) powerUntil else null

    /**
     * 디바운스가 확정한 전원 변화(atMs = 첫 변화 시각). 보류 중 시작된 변화(재시작 때 적용한 변화)인지 돌려주고
     * 보류를 끝낸다. 들고 있던 창을 연결로 버리는 것은 Logic.closeCheck 한 곳이다.
     */
    fun powerSettled(atMs: Long): Boolean {
        val restart = atMs < powerUntil
        powerUntil = Long.MIN_VALUE
        return restart
    }

    /** 들고 있던 확인 창 종류를 한 번 돌려주고 비운다. */
    fun takeCheck(): String? = check.ifEmpty { null }?.also { check = "" }

    /** 들고 있던 확인 창을 버린다(kind 가 있으면 그 종류만). Logic.closeCheck 만 부른다. */
    fun dropCheck(kind: String = "") {
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
