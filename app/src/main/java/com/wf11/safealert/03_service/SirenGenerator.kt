package com.wf11.safealert.service

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 구조 요청 사이렌과 확인 단계 비프를 코드로 만든 PCM(16비트 모노) 생성기 (v1.1.99, D-09).
 *
 * 순수 JVM 로직이다. 재생은 AudioTrack(USAGE_ALARM) 반복이 맡고, 여기서는 한 주기 파형만 만든다.
 * 소리 성격은 아래 상수로 현장에서 조정한다.
 * SAMPLE_RATE 는 정적 AudioTrack 버퍼를 작게 하려고 22.05 kHz 로 둔다 (내용은 3.9 kHz 이하).
 */
object SirenGenerator {

    const val SAMPLE_RATE = 22_050

    // 사이렌 wail: 저음에서 고음으로 올라갔다 내려오는 스윕
    const val WAIL_LOW_HZ = 650.0
    const val WAIL_HIGH_HZ = 1300.0
    const val WAIL_CYCLE_MS = 1800
    const val HARMONIC_MIX = 0.3
    const val AMPLITUDE = 0.9
    /** 확인 단계 비프의 최대 진폭. 사이렌보다 확실히 작게 둔다 (약 -5 dB). */
    const val BEEP_AMPLITUDE = 0.5

    // 확인 단계: 짧은 3연 비프 (사이렌과 확실히 다르게)
    const val BEEP_HZ = 880.0
    const val BEEP_ON_MS = 150
    const val BEEP_OFF_MS = 150
    const val BEEP_COUNT = 3
    const val BEEP_PAUSE_MS = 700
    const val BEEP_FADE_MS = 5

    private const val PEAK = 32767.0

    private val wail: ShortArray by lazy { buildWail() }
    private val beep: ShortArray by lazy { buildBeep() }

    /** 코사인 스윕: 주기 시작 LOW, 절반 지점 HIGH, 끝에서 다시 LOW. */
    fun wailFreqAt(tSec: Double): Double {
        val cycle = WAIL_CYCLE_MS / 1000.0
        val frac = (tSec % cycle) / cycle
        return WAIL_LOW_HZ + (WAIL_HIGH_HZ - WAIL_LOW_HZ) * (0.5 - 0.5 * cos(2 * PI * frac))
    }

    /** 두 버퍼를 백그라운드에서 미리 만든다. 첫 재생이 메인 스레드에서 계산으로 지연되지 않게 한다. */
    fun prewarm() {
        Thread({ wail.size; beep.size }, "siren-prewarm").apply { isDaemon = true }.start()
    }

    /** 반복 재생용 한 주기. 호출자는 배열을 수정하지 않는다. */
    fun wailCycle(): ShortArray = wail

    /** 확인 단계 비프 한 주기 (끝의 무음 포함). 호출자는 배열을 수정하지 않는다. */
    fun checkBeepCycle(): ShortArray = beep

    private fun buildWail(): ShortArray {
        val size = SAMPLE_RATE * WAIL_CYCLE_MS / 1000
        // 한 주기의 총 위상 회전 수를 정수로 맞춰야 반복 이음매에서 딸깍 소리가 없다
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

    /** 절대 피크가 amplitude 가 되게 키운 뒤 -32767..32767 로 자르고 Short 로 바꾼다 (F11). */
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
