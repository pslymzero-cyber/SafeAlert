package com.wf11.safealert.utils

import com.wf11.safealert.utils.PinLockout.Companion.LOCK_MS
import com.wf11.safealert.utils.PinLockout.Now
import com.wf11.safealert.utils.PinLockout.Result
import org.junit.Assert.assertEquals
import org.junit.Test

/** (v1.1.97) 설정 PIN 연속 오류 잠금 판정. */
class PinLockoutTest {

    private class MemStore : PinLockout.Store {
        var s = PinLockout.State()
        var saves = 0
        override fun load() = s
        override fun save(s: PinLockout.State) { this.s = s; saves++ }
    }

    private val store = MemStore()
    private val lockout = PinLockout(store)
    private val t0 = Now(wallMs = 1_790_000_000_000L, elapsedMs = 3_600_000L, bootCount = 7)

    private fun Now.plus(ms: Long) = copy(wallMs = wallMs + ms, elapsedMs = elapsedMs + ms)

    private fun failTimes(n: Int, now: Now): Result {
        var r: Result = Result.Ok
        repeat(n) { r = lockout.submit(false, now) }
        return r
    }

    @Test
    fun 다섯번_연속으로_틀리면_10분_잠긴다() {
        assertEquals(Result.Wrong(1), failTimes(4, t0))
        assertEquals(Result.Locked(LOCK_MS), lockout.submit(false, t0))
        assertEquals(LOCK_MS, lockout.remainingLockMs(t0))
        assertEquals("제출 한 번에 저장은 한 번(잠금 포함)", 5, store.saves)
    }

    @Test
    fun 잠금_중에는_맞는_PIN도_판정하지_않는다() {
        failTimes(5, t0)
        val mid = t0.plus(60_000L)
        assertEquals(Result.Locked(LOCK_MS - 60_000L), lockout.submit(true, mid))
        assertEquals("잠금 중 입력이 실패로 쌓이지 않는다", 0, store.s.fails)
        assertEquals(LOCK_MS - 60_000L, lockout.remainingLockMs(mid))
    }

    @Test
    fun 잠금은_10분_뒤_풀리고_다시_5회를_시도할_수_있다() {
        failTimes(5, t0)
        val after = t0.plus(LOCK_MS)
        assertEquals(0L, lockout.remainingLockMs(after))
        assertEquals(Result.Wrong(4), lockout.submit(false, after))
        assertEquals(Result.Ok, lockout.submit(true, after))
    }

    @Test
    fun 맞히면_실패_횟수가_초기화된다() {
        failTimes(3, t0)
        assertEquals(Result.Ok, lockout.submit(true, t0))
        assertEquals(0, store.s.fails)
        assertEquals("초기화 뒤에는 4번 틀려도 잠기지 않는다", Result.Wrong(1), failTimes(4, t0))
    }

    @Test
    fun 같은_부팅에서_기기_시계를_앞으로_돌려도_풀리지_않는다() {
        failTimes(5, t0)
        val clockForward = t0.copy(wallMs = t0.wallMs + 24 * 3_600_000L, elapsedMs = t0.elapsedMs + 1_000L)
        assertEquals(LOCK_MS - 1_000L, lockout.remainingLockMs(clockForward))
    }

    @Test
    fun 재부팅_뒤에는_벽시계로_재고_10분을_넘기지_않는다() {
        failTimes(5, t0)
        val rebooted = Now(wallMs = t0.wallMs + 120_000L, elapsedMs = 30_000L, bootCount = 8)
        assertEquals(LOCK_MS - 120_000L, lockout.remainingLockMs(rebooted))
        val clockBack = rebooted.copy(wallMs = t0.wallMs - 3_600_000L)
        assertEquals("시계를 뒤로 돌려도 10분을 넘지 않는다", LOCK_MS, lockout.remainingLockMs(clockBack))
    }

    @Test
    fun 부팅_횟수를_못_읽어도_경과_시간이_줄면_재부팅으로_본다() {
        val noBoot = t0.copy(bootCount = 0)
        repeat(5) { lockout.submit(false, noBoot) }
        val rebooted = Now(wallMs = t0.wallMs + 60_000L, elapsedMs = 5_000L, bootCount = 0)
        assertEquals(LOCK_MS - 60_000L, lockout.remainingLockMs(rebooted))
    }
}
