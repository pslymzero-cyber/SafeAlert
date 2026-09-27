package com.wf11.safealert.firebase

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** (v1.1.98) 로그인 전 경보 기록 보류 큐. org.json·SharedPreferences 가 필요해 Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingAlertsTest {

    private val prefs get() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("pending_alerts_test", Context.MODE_PRIVATE)

    private fun rec(n: Int): Map<String, Any> = mapOf(
        "timestamp" to 1_790_000_000_000L + n, "deviceId" to "WF11-CB-0$n", "walkerId" to "WF11-SA-1",
        "rssi" to -60 - n, "alertLevel" to "DANGER", "myRole" to "FORKLIFT", "peerRole" to "WALKER", "site" to "WF11"
    )

    @Test
    fun `보류한 순서대로 꺼내고 값의 타입이 유지된다`() {
        val q = PendingAlerts(prefs)
        q.add("https://x.firebaseio.com/wf11/alerts/WF11/20260927/a", rec(1))
        q.add("https://x.firebaseio.com/wf11/alerts/WF11/20260927/b", rec(2))
        val out = q.drain()
        assertEquals(listOf("a", "b"), out.map { it.first.substringAfterLast('/') })
        val (_, d) = out[0]
        assertEquals(1_790_000_000_001L, (d["timestamp"] as Number).toLong())
        assertEquals(-61, (d["rssi"] as Number).toInt())
        assertEquals("WF11-CB-01", d["deviceId"])
        assertEquals("꺼낸 뒤에는 비어 있다", 0, q.size())
    }

    @Test
    fun `앱이 다시 켜져도 보류분이 남는다`() {
        PendingAlerts(prefs).add("https://x.firebaseio.com/wf11/alerts/20260927/a", rec(1))
        assertEquals(1, PendingAlerts(prefs).drain().size)
    }

    @Test
    fun `상한을 넘으면 오래된 것부터 버린다`() {
        val q = PendingAlerts(prefs, max = 3)
        for (n in 1..5) q.add("https://x.firebaseio.com/wf11/alerts/20260927/$n", rec(n))
        val out = q.drain()
        assertEquals(listOf("3", "4", "5"), out.map { it.first.substringAfterLast('/') })
        assertTrue(out.all { !it.second.containsKey("uid") })
    }
}
