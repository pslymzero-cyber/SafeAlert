package com.wf11.safealert.service

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * PCM (16-bit mono) generator that synthesizes the SOS siren and the check-stage beeps in code.
 *
 * Pure JVM logic. Playback is a looping AudioTrack (USAGE_ALARM); this only builds one period of the waveform.
 * Tune the character of the sound in the field with the constants below.
 * SAMPLE_RATE is 22.05 kHz to keep the static AudioTrack buffer small (content stays at or below 3.9 kHz).
 */
object SirenGenerator {

    const val SAMPLE_RATE = 22_050

    // Siren wail: a sweep that rises from low to high pitch and comes back down
    const val WAIL_LOW_HZ = 650.0
    const val WAIL_HIGH_HZ = 1300.0
    const val WAIL_CYCLE_MS = 1800
    const val HARMONIC_MIX = 0.3
    const val AMPLITUDE = 0.9
    /** Peak amplitude of the check-stage beeps. Kept clearly below the siren (about -5 dB). */
    const val BEEP_AMPLITUDE = 0.5

    // Check stage: three short beeps (clearly distinct from the siren)
    const val BEEP_HZ = 880.0
    const val BEEP_ON_MS = 150
    const val BEEP_OFF_MS = 150
    const val BEEP_COUNT = 3
    const val BEEP_PAUSE_MS = 700
    const val BEEP_FADE_MS = 5

    private const val PEAK = 32767.0

    private val wail: ShortArray by lazy { buildWail() }
    private val beep: ShortArray by lazy { buildBeep() }

    /** Cosine sweep: LOW at period start, HIGH at the halfway point, back to LOW at the end. */
    fun wailFreqAt(tSec: Double): Double {
        val cycle = WAIL_CYCLE_MS / 1000.0
        val frac = (tSec % cycle) / cycle
        return WAIL_LOW_HZ + (WAIL_HIGH_HZ - WAIL_LOW_HZ) * (0.5 - 0.5 * cos(2 * PI * frac))
    }

    /**
     * Builds both buffers ahead of time in the background so the first playback is not delayed by computation on the main thread.
     */
    fun prewarm() {
        Thread({ wail.size; beep.size }, "siren-prewarm").apply { isDaemon = true }.start()
    }

    /** One period for looped playback. Callers must not modify the array. */
    fun wailCycle(): ShortArray = wail

    /** One period of the check-stage beeps (including the trailing silence). Callers must not modify the array. */
    fun checkBeepCycle(): ShortArray = beep

    private fun buildWail(): ShortArray {
        val size = SAMPLE_RATE * WAIL_CYCLE_MS / 1000
        // The total number of phase rotations in one period must be an integer, or the loop seam clicks
        var cycles = 0.0
        for (i in 0 until size) cycles += wailFreqAt(i.toDouble() / SAMPLE_RATE) / SAMPLE_RATE
        val scale = cycles.roundToInt().coerceAtLeast(1) / cycles
        val raw = DoubleArray(size)
        var phase = 0.0
        for (i in 0 until size) {
            raw[i] = sin(phase) + HARMONIC_MIX * sin(3 * phase)
            phase += 2 * PI * wailFreqAt(i.toDouble() / SAMPLE_RATE) * scale / SAMPLE_RATE
        }
        return toPcm(raw, AMPLITUDE)
    }

    /** Scales so the absolute peak equals amplitude, then clamps to -32767..32767 and converts to Short. */
    private fun toPcm(raw: DoubleArray, amplitude: Double): ShortArray {
        var peak = 0.0
        for (v in raw) peak = maxOf(peak, kotlin.math.abs(v))
        val k = if (peak > 0.0) amplitude * PEAK / peak else 0.0
        return ShortArray(raw.size) { (raw[it] * k).roundToInt().coerceIn(-32767, 32767).toShort() }
    }

    private fun buildBeep(): ShortArray {
        val totalMs = BEEP_COUNT * BEEP_ON_MS + (BEEP_COUNT - 1) * BEEP_OFF_MS + BEEP_PAUSE_MS
        val out = DoubleArray(SAMPLE_RATE * totalMs / 1000)
        val fade = (SAMPLE_RATE * BEEP_FADE_MS / 1000).coerceAtLeast(1)
        for (k in 0 until BEEP_COUNT) {
            val startMs = k * (BEEP_ON_MS + BEEP_OFF_MS)
            val from = SAMPLE_RATE * startMs / 1000
            val to = SAMPLE_RATE * (startMs + BEEP_ON_MS) / 1000
            val len = to - from
            for (i in 0 until len) {
                val gain = minOf(1.0, i.toDouble() / fade, (len - 1 - i).toDouble() / fade)
                out[from + i] = sin(2 * PI * BEEP_HZ * i / SAMPLE_RATE) * gain
            }
        }
        return toPcm(out, BEEP_AMPLITUDE)
    }
}
