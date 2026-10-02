package com.wf11.safealert.service

/** 최근 가장 강한 비콘 힌트(LoneWorkerLogic 이 위임한다). 안드로이드 의존 없음. */
class BeaconHints {

    companion object {
        const val BEACON_HINT_MS = 60_000L
        private const val BEACON_SAMPLE_CAP = 256
    }

    private class BeaconSample(val label: String, val rssi: Int, val tMs: Long, val sid: Int)
    private val beaconSamples = ArrayDeque<BeaconSample>()

    fun noteBeacon(label: String, rssi: Int, nowMs: Long, sid: Int = 0) {
        beaconSamples.addLast(BeaconSample(label, rssi, nowMs, sid))
        while (beaconSamples.isNotEmpty() &&
            (beaconSamples.size > BEACON_SAMPLE_CAP || nowMs - beaconSamples.first().tMs > BEACON_HINT_MS)
        ) beaconSamples.removeFirst()
    }

    fun beaconHint(nowMs: Long): Pair<String, Int>? {
        var best: BeaconSample? = null
        for (s in beaconSamples) {
            if (nowMs - s.tMs > BEACON_HINT_MS) continue
            if (best == null || s.rssi > best.rssi) best = s
        }
        return best?.let { it.label to it.rssi }
    }

    /** 최근 60초 안 가장 강한 표본 중 짧은 ID(sid)가 0 이 아닌 것의 sid. 없으면 0. 광고 byte3-4 에 싣는다. */
    fun beaconSid(nowMs: Long): Int {
        var best: BeaconSample? = null
        for (s in beaconSamples) {
            if (s.sid == 0 || nowMs - s.tMs > BEACON_HINT_MS) continue
            if (best == null || s.rssi > best.rssi) best = s
        }
        return best?.sid ?: 0
    }
}

// ── LoneWorkerLogic 의 최근 가장 강한 비콘 힌트(BeaconHints) 위임 ──────────────────
/** 비콘 표본 기록 — LoneWorkerLogic 의 위임, 500줄 제한으로 여기 둔다. */
fun LoneWorkerLogic.noteBeacon(label: String, rssi: Int, nowMs: Long, sid: Int = 0) = beacons.noteBeacon(label, rssi, nowMs, sid)
/** 최근 가장 강한 비콘 힌트 — LoneWorkerLogic 의 위임, 500줄 제한으로 여기 둔다. */
fun LoneWorkerLogic.beaconHint(nowMs: Long): Pair<String, Int>? = beacons.beaconHint(nowMs)
/** 최근 가장 강한 비콘의 sid — LoneWorkerLogic 의 위임, 500줄 제한으로 여기 둔다. */
fun LoneWorkerLogic.beaconSid(nowMs: Long): Int = beacons.beaconSid(nowMs)
