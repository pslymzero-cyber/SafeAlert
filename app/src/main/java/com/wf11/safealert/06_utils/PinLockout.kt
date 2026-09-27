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

    interface Store {
        var fails: Int
        var lockWallUntil: Long      // 0 = 잠금 없음
        var lockElapsedUntil: Long
        var lockBoot: Int
    }

    data class Now(val wallMs: Long, val elapsedMs: Long, val bootCount: Int)

    sealed interface Result {
        object Ok : Result
        data class Wrong(val triesLeft: Int) : Result
        data class Locked(val remainingMs: Long) : Result
    }

    fun remainingLockMs(now: Now): Long {
        if (store.lockWallUntil == 0L) return 0L
        val sameBoot = now.bootCount == store.lockBoot &&
            now.elapsedMs >= store.lockElapsedUntil - LOCK_MS   // 경과 시간이 잠근 시점보다 작으면 재부팅
        val left = if (sameBoot) store.lockElapsedUntil - now.elapsedMs
                   else store.lockWallUntil - now.wallMs
        return left.coerceIn(0L, LOCK_MS)
    }

    fun submit(correct: Boolean, now: Now): Result {
        val left = remainingLockMs(now)
        if (left > 0L) return Result.Locked(left)
        if (store.lockWallUntil != 0L) {   // 끝난 잠금 정리
            store.lockWallUntil = 0L
            store.lockElapsedUntil = 0L
        }
        if (correct) {
            store.fails = 0
            return Result.Ok
        }
        val fails = store.fails + 1
        if (fails >= MAX_FAILS) {
            store.fails = 0
            store.lockWallUntil = now.wallMs + LOCK_MS
            store.lockElapsedUntil = now.elapsedMs + LOCK_MS
            store.lockBoot = now.bootCount
            return Result.Locked(LOCK_MS)
        }
        store.fails = fails
        return Result.Wrong(MAX_FAILS - fails)
    }

    companion object {
        const val MAX_FAILS = 5
        const val LOCK_MS = 10 * 60_000L
    }
}
