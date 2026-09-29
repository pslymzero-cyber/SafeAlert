package com.wf11.safealert.service

import com.wf11.safealert.service.SirenGenerator.AMPLITUDE
import com.wf11.safealert.service.SirenGenerator.BEEP_COUNT
import com.wf11.safealert.service.SirenGenerator.BEEP_HZ
import com.wf11.safealert.service.SirenGenerator.SAMPLE_RATE
import com.wf11.safealert.service.SirenGenerator.WAIL_HIGH_HZ
import com.wf11.safealert.service.SirenGenerator.WAIL_LOW_HZ
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * SirenGenerator PCM 파형 테스트 (v1.1.99, D-09). 순수 JVM.
 */
class SirenGeneratorTest {

    // 구간 내 부호 변화 횟수로 추정한 주파수(Hz). 0 은 직전 부호를 유지한다.
    private fun zeroCrossHz(pcm: ShortArray, from: Int, to: Int): Double {
        var crossings = 0
        var prev = 0
        for (i in from until to) {
            val s = pcm[i].toInt()
            val sign = if (s > 0) 1 else if (s < 0) -1 else 0
            if (sign != 0) {
                if (prev != 0 && sign != prev) crossings++
                prev = sign
            }
        }
        return crossings / 2.0 / ((to - from).toDouble() / SAMPLE_RATE)
    }

    @Test fun wail_cycle_size_matches_constants() {
        assertEquals(SAMPLE_RATE * SirenGenerator.WAIL_CYCLE_MS / 1000, SirenGenerator.wailCycle().size)
    }

    @Test fun wail_freq_stays_in_range_and_reaches_both_ends() {
        var lo = Double.MAX_VALUE
        var hi = 0.0
        var t = 0.0
        while (t < SirenGenerator.WAIL_CYCLE_MS / 1000.0) {
            val f = SirenGenerator.wailFreqAt(t)
            assertTrue(f >= WAIL_LOW_HZ - 1e-6 && f <= WAIL_HIGH_HZ + 1e-6)
            lo = minOf(lo, f); hi = maxOf(hi, f)
            t += 0.001
        }
        assertTrue(lo <= WAIL_LOW_HZ + 1.0)
        assertTrue(hi >= WAIL_HIGH_HZ - 1.0)
    }

    @Test fun wail_pcm_windows_follow_the_sweep() {
        val pcm = SirenGenerator.wailCycle()
        val win = SAMPLE_RATE / 10
        var from = 0
        while (from + win <= pcm.size) {
            val hz = zeroCrossHz(pcm, from, from + win)
            assertTrue("window $from hz=$hz", hz >= WAIL_LOW_HZ * 0.95 && hz <= WAIL_HIGH_HZ * 1.05)
            from += win
        }
    }

    @Test fun wail_amplitude_is_bounded_and_loud_enough() {
        val pcm = SirenGenerator.wailCycle()
        val target = Math.round(AMPLITUDE * 32767).toInt()
        var peak = 0
        for (s in pcm) {
            assertTrue(s.toInt() != Short.MIN_VALUE.toInt())
            peak = maxOf(peak, abs(s.toInt()))
        }
        assertTrue("peak=$peak", abs(peak - target) <= 1)
    }

    @Test fun wail_loop_seam_is_click_free() {
        val pcm = SirenGenerator.wailCycle()
        var maxDelta = 0
        for (i in 1 until pcm.size) maxDelta = maxOf(maxDelta, abs(pcm[i] - pcm[i - 1]))
        assertTrue(abs(pcm[0] - pcm[pcm.size - 1]) <= maxDelta)
    }

    @Test fun check_beep_has_expected_shape() {
        val pcm = SirenGenerator.checkBeepCycle()
        val totalMs = BEEP_COUNT * SirenGenerator.BEEP_ON_MS +
            (BEEP_COUNT - 1) * SirenGenerator.BEEP_OFF_MS + SirenGenerator.BEEP_PAUSE_MS
        assertEquals(SAMPLE_RATE * totalMs / 1000, pcm.size)
        assertEquals(0, pcm[0].toInt())
        assertEquals(0, pcm[pcm.size - 1].toInt())

        // 10 ms 블록 RMS 로 톤 구간(무음에서 소리로의 전이) 개수를 센다
        val block = SAMPLE_RATE / 100
        var segments = 0
        var loud = false
        var i = 0
        while (i + block <= pcm.size) {
            var sum = 0.0
            for (j in i until i + block) sum += pcm[j].toDouble() * pcm[j]
            val isLoud = sqrt(sum / block) > 500
            if (isLoud && !loud) segments++
            loud = isLoud
            i += block
        }
        assertEquals(BEEP_COUNT, segments)

        val toneEnd = SAMPLE_RATE * SirenGenerator.BEEP_ON_MS / 1000
        val hz = zeroCrossHz(pcm, 0, toneEnd)
        assertTrue("hz=$hz", hz >= BEEP_HZ * 0.95 && hz <= BEEP_HZ * 1.05)
    }

    @Test fun check_beep_is_clearly_quieter_than_the_siren() {
        fun peakOf(a: ShortArray): Int = a.maxOf { abs(it.toInt()) }
        val wailPeak = peakOf(SirenGenerator.wailCycle())
        val beepPeak = peakOf(SirenGenerator.checkBeepCycle())
        assertTrue(beepPeak <= Math.round(SirenGenerator.BEEP_AMPLITUDE * 32767) + 1)
        assertTrue(beepPeak <= 0.6 * wailPeak)
    }
}
