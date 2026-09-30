package com.wf11.safealert.service

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.abs

/**
 * 단독 작업자 감시의 재시작 이어가기 저장소 (v1.1.99).
 *
 * 저장 대상: 사고 의심(시작·끝·30초 셈 기준), 열린 확인 창(종류·연 시각), 충전 여부, 지님 확정, 무동작 기준 시각.
 * 대기 = 충전 안 함이고 지님 확정 아님. 본인 SOS 는 저장하지 않는다(SosLedger 가 복원하고 그쪽이 이긴다).
 * 시각은 벽시계로 저장해 재부팅(elapsedRealtime 초기화)을 넘어도 경과를 잰다.
 * 재시작 때 확인 창은 다시 띄우고 응답 시간은 처음부터, 트리거 뒤 5분이 지난 사고 의심은 버린다(LoneWorkerLogic.resume).
 * 저장된 충전 여부와 지금 전원이 다르면 모니터가 지금 시각의 실제 전원 변화로 적용한다.
 * [중지]·사용자 중지 판정이면 clearOnUserStop 으로 지운다. 시스템 종료·재시작 대비로 감시 정지만으로는 지우지 않는다.
 */
class LoneWorkerResume(private val ctx: Context) {

    /** 시각은 전부 elapsedRealtime ms. check 는 열린 확인 창 종류("fall"·"still"), 없으면 빈 값. */
    data class State(
        val accidentFrom: Long?,
        val accidentUntil: Long?,
        val accidentHold: Long?,
        val check: String,
        val checkAt: Long?,
        val charging: Boolean,
        val carried: Boolean,
        val stillBase: Long
    )

    companion object {
        const val KEY = "lw_resume"
        private const val PREFS = "safealert_prefs"
        private const val VERSION = "v1"
        private const val FIELDS = 9
        /** 무동작 기준 시각만 바뀐 경우는 이만큼 바뀌어야 다시 저장한다(움직이는 동안 초당 쓰기 방지). */
        private const val STILL_SAVE_MS = 10_000L
        private val CHECKS = setOf("", "fall", "still")

        fun encode(s: State, elapsedNow: Long, wallNow: Long): String {
            fun w(t: Long?) = t?.let { (wallNow - (elapsedNow - it)).toString() } ?: ""
            fun b(v: Boolean) = if (v) "1" else "0"
            return listOf(VERSION, w(s.accidentFrom), w(s.accidentUntil), w(s.accidentHold), s.check, w(s.checkAt),
                b(s.charging), b(s.carried), w(s.stillBase)).joinToString("|")
        }

        /** 형식·필드 수·숫자·종류 값이 어긋나면 null(이어가기 없이 새로 시작한다). */
        fun decode(raw: String?, elapsedNow: Long, wallNow: Long): State? {
            val f = raw?.split("|") ?: return null
            if (f.size != FIELDS || f[0] != VERSION || f[4] !in CHECKS) return null
            fun t(v: String): Long? = if (v.isEmpty()) null else v.toLongOrNull()?.let { elapsedNow - (wallNow - it) }
            fun b(v: String): Boolean? = when (v) { "1" -> true; "0" -> false; else -> null }
            for (i in listOf(1, 2, 3, 5, 8)) if (f[i].isNotEmpty() && f[i].toLongOrNull() == null) return null
            return State(
                t(f[1]), t(f[2]), t(f[3]), f[4], t(f[5]),
                b(f[6]) ?: return null, b(f[7]) ?: return null, t(f[8]) ?: return null
            )
        }

        /** 사용자가 멈췄다: 실행 복원 키와 재시작 상태를 함께 지운다. 같은 editor 를 돌려준다(commit 은 호출한 쪽). */
        fun clearOnUserStop(editor: SharedPreferences.Editor): SharedPreferences.Editor =
            editor.remove("running_mode").remove("running_since").remove("running_category").remove(KEY)
    }

    private var last: State? = null

    fun load(nowMs: Long): State? {
        last = null
        val raw = runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) }.getOrNull()
        return decode(raw, nowMs, System.currentTimeMillis())
    }

    /** 직전 저장값과 달라졌을 때만 저장한다. nowMs 는 s 의 시각과 같은 elapsedRealtime 기준. */
    fun save(s: State, nowMs: Long) {
        val prev = last
        if (prev != null && prev == s.copy(stillBase = prev.stillBase) && abs(s.stillBase - prev.stillBase) < STILL_SAVE_MS) return
        last = s
        runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, encode(s, nowMs, System.currentTimeMillis())).apply()
        }
    }
}
