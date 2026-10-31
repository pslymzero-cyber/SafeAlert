package com.wf11.safealert.utils

import com.wf11.safealert.ble.BleConstants
import com.wf11.safealert.service.repoFile
import com.wf11.safealert.utils.AiJudge.run
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

/** On-phone AI judgment: the shipped model files compute their own checks, and the input / text definitions hold. */
@RunWith(RobolectricTestRunner::class)
class AiJudgeTest {

    @Test
    fun modelsMatchTheirChecks() {
        for ((file, kind) in listOf("prox" to "prox", "fall" to "fall")) {
            val m = AiJudge.parse(repoFile("app/src/main/assets/ai/$file.json"))
            assertNotNull(file, m)
            assertEquals(kind, m!!.kind)
            assertTrue("$file has checks", m.checks.isNotEmpty())
            for ((x, y) in m.checks) assertEquals("$file ${x.toList()}", y, m.run(x), 1e-4)
        }
        assertNull(AiJudge.parse("{}"))
        val swish = """{"kind":"fall","inputs":["a"],"fill":[0],"mean":[0],"std":[1],"layers":[{"w":[[1]],"b":[0],"act":"swish"}]}"""
        assertNull(AiJudge.parse(swish))
        assertNotNull(AiJudge.parse(swish.replace("swish", "relu")))
    }

    @Test
    fun featuresLevelAndText() {
        val f = AiJudge.proxFeatures(-60, -61, -62, -63, -64, -65, 0.5, intArrayOf(-70, -68, -63), doubleArrayOf(0.24, 0.36, 1.0))
        assertEquals(-60.0, f["rssi"]!!, 1e-9); assertEquals(-65.0, f["avg1s"]!!, 1e-9); assertEquals(0.5, f["kf_vel"]!!, 1e-9)
        assertEquals(-67.0, f["seq_mean"]!!, 1e-9); assertEquals(sqrt(26.0 / 3), f["seq_std"]!!, 1e-9)
        assertEquals(-70.0, f["seq_min"]!!, 1e-9); assertEquals(-63.0, f["seq_max"]!!, 1e-9)
        assertEquals(3.5, f["seq_slope"]!!, 1e-9); assertEquals((0.2 + 0.4 + 1.0) / 3, f["seq_vel"]!!, 1e-9)
        val e = AiJudge.proxFeatures(-60, -61, -62, -63, -64, -65, 0.5, IntArray(0), DoubleArray(0))
        assertEquals(-64.0, e["seq_mean"]!!, 1e-9); assertEquals(-64.0, e["seq_min"]!!, 1e-9); assertEquals(-64.0, e["seq_max"]!!, 1e-9)
        assertEquals(0.0, e["seq_std"]!!, 1e-9); assertEquals(0.0, e["seq_slope"]!!, 1e-9); assertEquals(0.5, e["seq_vel"]!!, 1e-9)
        // Roles and phone model: no roles = all four flags 0 and no model column (also with an empty ring); the names equal the shipped prox model's inputs
        for (x in listOf(f, e)) {
            assertEquals(listOf(0.0, 0.0, 0.0, 0.0), listOf("me_epj", "me_fk", "peer_epj", "peer_fk").map { x[it] })
            assertTrue(x.keys.none { it.startsWith("mdl:") })
        }
        assertEquals(e.keys, AiJudge.parse(repoFile("app/src/main/assets/ai/prox.json"))!!.inputs.toSet())
        val r = AiJudge.proxFeatures(-60, -61, -62, -63, -64, -65, 0.5, IntArray(0), DoubleArray(0), "EPJ", "FORKLIFT", "samsung SM-S918N")
        assertEquals(listOf(1.0, 0.0, 0.0, 1.0), listOf("me_epj", "me_fk", "peer_epj", "peer_fk").map { r[it] })
        assertEquals(1.0, r["mdl:samsung SM-S918N"]!!, 1e-9)

        DevSettings.init(RuntimeEnvironment.getApplication())
        assertEquals(BleConstants.LEVEL_DANGER, AiJudge.level(8.0f, true))
        assertEquals(BleConstants.LEVEL_WARNING, AiJudge.level(8.1f, true))
        assertEquals(BleConstants.LEVEL_SAFE, AiJudge.level(15.1f, true))
        assertEquals(BleConstants.LEVEL_DANGER, AiJudge.level(3.0f, false))
        assertEquals(BleConstants.LEVEL_WARNING, AiJudge.level(3.1f, false))
        assertEquals(BleConstants.LEVEL_SAFE, AiJudge.level(5.1f, false))

        assertEquals("약 2.6m · 위험", AiJudge.proxText(2.6f, BleConstants.LEVEL_DANGER))
        assertEquals("약 12m · 안전", AiJudge.proxText(12.4f, BleConstants.LEVEL_SAFE))
        assertEquals("약 4.0m · 경고", AiJudge.proxText(4.0f, BleConstants.LEVEL_WARNING))

        val d = 1_000_000_000L
        val impT = d - 5_000
        val hms = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(impT))
        try {
            AiJudge.lastFall = AiJudge.FallAi(impT, 0.123, false, d)
            val line = AiJudge.fallLine(d + 59_000)!!
            assertTrue(line, line.startsWith("충격 $hms") && "AI 넘어짐 12%" in line && line.endsWith("규칙: 넘어짐 아님"))
            assertNull(AiJudge.fallLine(d + 61_000))
            AiJudge.lastFall = AiJudge.FallAi(impT, 0.123, true, d)
            assertTrue(AiJudge.fallLine(d + 1_000)!!.endsWith("규칙: 넘어짐"))
            assertEquals("AI 판단: 넘어짐일 확률 12%", AiJudge.checkLine(d + 170_000))
            assertNull(AiJudge.checkLine(d + 181_000))
        } finally {
            AiJudge.lastFall = null
        }
    }
}
