package com.wf11.safealert.service

/** Most recent strongest beacon hint (LoneWorkerLogic delegates here). No Android dependency. */
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

    /**
     * sid of the strongest sample in the last 60s with a non-zero short ID (sid). 0 if none. Carried in advertising bytes 3-4.
     */
    fun beaconSid(nowMs: Long): Int {
        var best: BeaconSample? = null
        for (s in beaconSamples) {
            if (s.sid == 0 || nowMs - s.tMs > BEACON_HINT_MS) continue
            if (best == null || s.rssi > best.rssi) best = s
        }
        return best?.sid ?: 0
    }
}

// ── Delegation of LoneWorkerLogic's most recent strongest beacon hint (BeaconHints) ──────────────────
/** Records a beacon sample — delegated from LoneWorkerLogic, kept here because of the 500-line limit. */
fun LoneWorkerLogic.noteBeacon(label: String, rssi: Int, nowMs: Long, sid: Int = 0) = beacons.noteBeacon(label, rssi, nowMs, sid)
/** Most recent strongest beacon hint — delegated from LoneWorkerLogic, kept here because of the 500-line limit. */
fun LoneWorkerLogic.beaconHint(nowMs: Long): Pair<String, Int>? = beacons.beaconHint(nowMs)
/** sid of the most recent strongest beacon — delegated from LoneWorkerLogic, kept here because of the 500-line limit. */
fun LoneWorkerLogic.beaconSid(nowMs: Long): Int = beacons.beaconSid(nowMs)
