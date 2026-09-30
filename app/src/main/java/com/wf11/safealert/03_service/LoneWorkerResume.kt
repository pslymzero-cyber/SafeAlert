package com.wf11.safealert.service

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import kotlin.math.abs

/**
 * 단독 작업자 감시의 재시작 이어가기 저장소 (v1.1.99).
 *
 * 저장 대상: 사고 의심(30초 셈 기준·끝), 열린 확인 창 종류, 충전 여부, 지님 확정, 무동작 기준 시각(사이렌 멈춤을 뺀 값),
 * 세이프존 상태(정착 여부·구역 진입 시각, 구역 밖이면 없음). 대기 = 충전 안 함이고 지님 확정 아님.
 * 본인 SOS 는 저장하지 않는다(SosLedger 가 복원하고 그쪽이 이긴다).
 * 시각은 저장 시점 elapsedRealtime 그대로 두고 부팅 수·저장 elapsed·저장 벽시계를 함께 적는다. 같은 부팅이면
 * elapsed 값을 그대로 쓰고(벽시계 변경과 무관), 다른 부팅(또는 부팅 수를 모름)이면 벽시계 경과(음수는 0)만큼 옮긴다.
 * 재시작 때 확인 창은 다시 띄우고 응답 시간은 처음부터, 트리거 뒤 5분이 지난 사고 의심은 버린다(LoneWorkerLogic.startFrom).
 * 저장된 충전 여부와 지금 전원이 다르면 재시작 전원 보류다(RestartHold).
 * [중지]·사용자 중지 판정이면 clearOnUserStop 으로 지운다. 시스템 종료·재시작 대비로 감시 정지만으로는 지우지 않는다.
 */
class LoneWorkerResume(private val ctx: Context) {

    /** 시각은 전부 elapsedRealtime ms. check 는 열린 확인 창 종류("fall"·"still"), 없으면 빈 값. zoneSince 가 null 이면 구역 밖. */
    data class State(
        val accidentHold: Long?,
        val accidentUntil: Long?,
        val check: String,
        val charging: Boolean,
        val carried: Boolean,
        val stillBase: Long,
        val zoneSettled: Boolean,
        val zoneSince: Long?
    )

    companion object {
        const val KEY = "lw_resume"
        private const val PREFS = "safealert_prefs"
        private const val VERSION = "v2"
        private const val FIELDS = 12
        /** 무동작 기준 시각만 바뀐 경우는 이만큼 바뀌어야 다시 저장한다(움직이는 동안 초당 쓰기 방지). */
        private const val STILL_SAVE_MS = 10_000L
        private val CHECKS = setOf("", "fall", "still")

        fun encode(s: State, elapsedNow: Long, wallNow: Long, boot: Int): String {
            fun t(v: Long?) = v?.toString() ?: ""
            fun b(v: Boolean) = if (v) "1" else "0"
            return listOf(VERSION, boot.toString(), elapsedNow.toString(), wallNow.toString(),
                t(s.accidentHold), t(s.accidentUntil), s.check, b(s.charging), b(s.carried), s.stillBase.toString(),
                b(s.zoneSettled), t(s.zoneSince)).joinToString("|")
        }

        /** 형식·칸 수·숫자·종류 값이 어긋나면 null(이어가기 없이 새로 시작한다). boot 는 지금 부팅 수(모르면 -1). */
        fun decode(raw: String?, elapsedNow: Long, wallNow: Long, boot: Int): State? {
            if (raw == null) return null
            return runCatching {
                val f = raw.split("|")
                require(f.size == FIELDS && f[0] == VERSION && f[6] in CHECKS)
                val savedBoot = f[1].toInt()
                val savedElapsed = f[2].toLong()
                val savedWall = f[3].toLong()
                val sameBoot = boot >= 0 && savedBoot == boot && elapsedNow >= savedElapsed
                val shift = if (sameBoot) 0L else elapsedNow - maxOf(0L, wallNow - savedWall) - savedElapsed
                fun t(v: String): Long? = if (v.isEmpty()) null else v.toLong() + shift
                fun past(v: String): Long? = t(v)?.let { minOf(it, elapsedNow) }
                fun b(v: String): Boolean = when (v) { "1" -> true; "0" -> false; else -> error(v) }
                State(past(f[4]), t(f[5]), f[6], b(f[7]), b(f[8]), past(f[9])!!, b(f[10]), past(f[11]))
            }.getOrNull()
        }

        /** 저장할지: 처음이거나 기준 말고 다른 칸이 바뀌었거나, 지님·정착 구역 밖에서 기준이 STILL_SAVE_MS 이상 바뀌었다. */
        fun shouldSave(prev: State?, s: State): Boolean {
            if (prev == null || prev.copy(stillBase = s.stillBase) != s) return true
            return s.carried && !s.zoneSettled && abs(s.stillBase - prev.stillBase) >= STILL_SAVE_MS
        }

        /** 사용자가 멈췄다: 실행 복원 키와 재시작 상태를 함께 지운다. 같은 editor 를 돌려준다(commit 은 호출한 쪽). */
        fun clearOnUserStop(editor: SharedPreferences.Editor): SharedPreferences.Editor =
            editor.remove("running_mode").remove("running_since").remove("running_category").remove(KEY)
    }

    private var last: State? = null
    private val boot: Int by lazy {
        runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)
    }

    fun load(nowMs: Long): State? {
        last = null
        val raw = runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) }.getOrNull()
        return decode(raw, nowMs, System.currentTimeMillis(), boot)
    }

    /** shouldSave 일 때만 저장한다. nowMs 는 s 의 시각과 같은 elapsedRealtime 기준. */
    fun save(s: State, nowMs: Long) {
        if (!shouldSave(last, s)) return
        last = s
        runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, encode(s, nowMs, System.currentTimeMillis(), boot)).apply()
        }
    }
}
