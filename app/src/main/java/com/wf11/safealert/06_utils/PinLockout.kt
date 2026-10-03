package com.wf11.safealert.utils

/**
 * Settings PIN lockout after consecutive failures — decision only.
 * The caller supplies storage and time (no Android dependency).
 *
 * After [MAX_FAILS] consecutive failures, input is refused for [LOCK_MS]; a correct PIN resets the count to 0.
 * While locked, even a correct PIN is not evaluated.
 *
 * Within the same boot the remaining time is measured with elapsedRealtime, so changing the device clock
 * has no effect. After a reboot it uses the wall clock; either way it is clipped to at most [LOCK_MS].
 * ponytail: moving the wall clock forward after a reboot lifts the lock;
 * stopping that needs a trusted server time.
 */
class PinLockout(private val store: Store) {

    /** Storage unit — read and written in one go (fields stay consistent even if the app dies midway). */
    data class State(
        val fails: Int = 0,
        val lockWallUntil: Long = 0L,      // 0 = not locked
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
            if (s != State()) store.save(State())   // clear failure count and any expired lock
            return Result.Ok
        }
        val fails = s.fails + 1
        if (fails >= MAX_FAILS) {
            store.save(State(0, now.wallMs + LOCK_MS, now.elapsedMs + LOCK_MS, now.bootCount))
            return Result.Locked(LOCK_MS)
        }
        store.save(State(fails = fails))   // an expired lock is cleared here too
        return Result.Wrong(MAX_FAILS - fails)
    }

    private fun remaining(s: State, now: Now): Long {
        if (s.lockWallUntil == 0L) return 0L
        val sameBoot = now.bootCount == s.lockBoot &&
            now.elapsedMs >= s.lockElapsedUntil - LOCK_MS   // elapsed time before the lock point means a reboot
        val left = if (sameBoot) s.lockElapsedUntil - now.elapsedMs
                   else s.lockWallUntil - now.wallMs
        return left.coerceIn(0L, LOCK_MS)
    }

    companion object {
        const val MAX_FAILS = 5
        const val LOCK_MS = 10 * 60_000L
    }
}
