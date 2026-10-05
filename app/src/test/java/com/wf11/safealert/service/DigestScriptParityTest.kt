package com.wf11.safealert.service

import com.wf11.safealert.ble.BleConstants
import com.wf11.safealert.utils.DevSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/** App values vs. the weekly digest scripts (the .py files in .github/scripts): they must agree. */
class DigestScriptParityTest {

    @Test fun digest_gap_matches_app_gap() {
        val py = repoFile(".github/scripts/hb_digest.py")
        val minutes = Regex("""(?m)^GAP_MS = (\d+) \* 60_000""").find(py)!!.groupValues[1].toLong()
        assertEquals(LoneWorkerHeartbeat.GAP_MS, minutes * 60_000L)
    }

    /** The weekly digest converts the app's default thresholds and role-pair offsets to meters; they must match. */
    @Test fun digest_thresholds_match_app_defaults() {
        val py = repoFile(".github/scripts/uwb_probe.py")
        fun nums(src: String, name: String) = Regex("""(?m)^$name = (.+)$""").find(src)!!.groupValues[1]
            .substringBefore("#").let { line -> Regex("""-?\d+""").findAll(line).map { it.value.toInt() }.toList() }
        assertEquals(listOf(DevSettings.DEFAULT_RSSI_WARNING_ABS, DevSettings.DEFAULT_RSSI_DANGER_ABS), nums(py, "THRESHOLDS"))
        assertEquals(listOf(DevSettings.DEFAULT_FORWARD_APPROACH_BIAS_DB), nums(py, "FORWARD_BIAS_DB"))

        // One entry per probe pairKey, spelled as the app builds it, with the offset computePayloadRiskOffset adds
        val block = py.substringAfter("PAIR_BIAS_DB = {").substringBefore("}")
        val actual = Regex(""""([^"]+)": (-?\d+)""").findAll(block).associate { it.groupValues[1] to it.groupValues[2].toInt() }
        fun key(a: Int, b: Int) = CalibrationEngine.uwbPairKeyFor(a, b)
        val w = BleConstants.CAT_WALKER; val f = BleConstants.CAT_FORKLIFT; val e = BleConstants.CAT_EPJ
        assertEquals(mapOf(
            key(w, w) to 0,
            key(w, e) to DevSettings.DEFAULT_WALKER_VS_EPJ_BIAS_DB,
            key(w, f) to DevSettings.DEFAULT_WALKER_VS_EQUIP_BIAS_DB,
            key(e, e) to DevSettings.DEFAULT_EPJ_VS_EPJ_BIAS_DB,
            key(e, f) to DevSettings.DEFAULT_EQUIP_VS_EQUIP_BIAS_DB,
            key(f, f) to DevSettings.DEFAULT_EQUIP_VS_EQUIP_BIAS_DB), actual)

        val digest = repoFile(".github/scripts/analyze_alerts.py")
        assertEquals(DevSettings.DEFAULT_FIREBASE_THROTTLE_MS, nums(digest, "THROTTLE_DEFAULT_S").single() * 1000L)
    }
}
