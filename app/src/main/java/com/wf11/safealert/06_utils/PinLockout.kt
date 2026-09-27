package com.wf11.safealert.utils

/**
 * (v1.1.97) 설정 PIN 연속 오류 잠금 — 판정만 한다. 저장소와 시각은 호출부가 넣는다(Android 무의존).
 *
 * 연속 [MAX_FAILS] 회 틀리면 [LOCK_MS] 동안 입력을 받지 않고, 맞히면 실패 횟수를 0 으로 되돌린다.
 * 잠금 중에는 맞는 PIN 도 판정하지 않는다.
 *
 * 남은 시간은 같은 부팅 안에서는 부팅 후 경과 시간(elapsedRealtime)으로 재서 기기 시계를 바꿔도
 * 변하지 않는다. 재부팅 뒤에는 벽시계로 재고, 어느 쪽이든 [LOCK_MS] 를 넘지 않게 자른다.
 * ponytail: 재부팅 후 벽시계를 앞으로 돌리면 풀린다 — 막으려면 신뢰 가능한 서버 시각이 필요하다.
 */
class PinLockout(private val store: Store) {

    /** 저장 단위 — 한 번에 읽고 한 번에 쓴다(중간에 앱이 꺼져도 필드끼리 어긋나지 않게). */
    data class State(
        val fails: Int = 0,
        val lockWallUntil: Long = 0L,      // 0 = 잠금 없음
        val lockElapsedUntil: Long = 0L,
        val lockBoot: Int = 0
    )

    interface Store {
        fun load(): State
        fun save(s: State)
    }

    data class Now(val wallMs: Long, val elapsedMs: Long, val bootCount: Int)

    sealed interface Result {
        object Ok : Result
        data class Wrong(val triesLeft: Int) : Result
        data class Locked(val remainingMs: Long) : Result
    }

    fun remainingLockMs(now: Now): Long = remaining(store.load(), now)

    fun submit(correct: Boolean, now: Now): Result {
        val s = store.load()
        val left = remaining(s, now)
        if (left > 0L) return Result.Locked(left)
        if (correct) {
            if (s != State()) store.save(State())   // 실패 횟수·끝난 잠금 정리
            return Result.Ok
        }
        val fails = s.fails + 1
        if (fails >= MAX_FAILS) {
            store.save(State(0, now.wallMs + LOCK_MS, now.elapsedMs + LOCK_MS, now.bootCount))
            return Result.Locked(LOCK_MS)
        }
        store.save(State(fails = fails))   // 끝난 잠금은 여기서 함께 지워진다
        return Result.Wrong(MAX_FAILS - fails)
    }

    private fun remaining(s: State, now: Now): Long {
        if (s.lockWallUntil == 0L) return 0L
        val sameBoot = now.bootCount == s.lockBoot &&
            now.elapsedMs >= s.lockElapsedUntil - LOCK_MS   // 경과 시간이 잠근 시점보다 작으면 재부팅
        val left = if (sameBoot) s.lockElapsedUntil - now.elapsedMs
                   else s.lockWallUntil - now.wallMs
        return left.coerceIn(0L, LOCK_MS)
    }

    companion object {
        const val MAX_FAILS = 5
        const val LOCK_MS = 10 * 60_000L
    }
}
