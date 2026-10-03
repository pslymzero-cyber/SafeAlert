package com.wf11.safealert.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.log10
import kotlin.math.pow

// UWB delta calibration learner — pairs UWB true distance (d) with the same frame's BLE RSSI (median) and
//   learns the per-role-pair (pairKey) channel deviation Δ = EMA(measuredRSSI − expectedRSSI(d)).
//   · expectedRSSI(d) = A − 10n·log10(d)  (A = −59dBm at 1m, n = 2.0 indoor free-space approximation)
//   The learned Δ is not used for alert decisions (totalOffset), only for distance display (distanceTextFor →
//   estimateDistanceM). Linear 24h decay after the last update (common profile → 0, site profile → baseline).
//   Without UWB support or with too few samples (<5), Δ=0 and distance is pure RSSI inversion.
//
// Site calibration profiles — the learning store is namespaced by site code (DevSettings.siteCode) as
//   SharedPreferences uwb_calib_<site>, persisted and switched per site.
//   · No site (empty) = the common file (uwb_calib) with the delta-only formula.
//   · With a site, a two-part model: baseline (slow EMA, no decay or GC = long-term site traits)
//     + same-day fine-tune delta (fast EMA). The 24h decay only pulls delta toward baseline, so decay touches
//     only the same-day fine-tune — the baseline survives weekends and learning resumes from it after a 24h
//     gap (samples keep accumulating → calibration is active from the first sample on a revisit).
//   · Site switch (applySite) = save the current profile → load the new one (each site's learning is kept).
//
// Role-pair keys — learning is keyed by role pair (pairKey), not by device (deviceId).
//   pairKey = both ends' categories (WALKER/EPJ/FORKLIFT) sorted into an order-independent key
//   (e.g. "FORKLIFT×WALKER"). Physical model: the same role pair has similar antenna height and shadowing,
//   so a deviation learned over UWB with one forklift applies at once to RSSI distance estimates for another
//   forklift never met over UWB. BleService builds pairKey (CalibrationEngine.uwbPairKeyFor from both categories); here it
//   is only an opaque string key.
//   Alerts: pairs with a fresh UWB measurement are judged by UWB, the rest by RSSI. The learned Δ only
//   changes the RSSI distance shown for pairs without a fresh measurement.
object UwbCalibrator {

    private const val TAG = "UwbCalibrator"
    private const val PREF_NAME = "uwb_calib"

    // Profile schema version — when learning conditions change, values stored under an older schema are dropped
    //   once and relearned. Schema 3: learning output is display-only (out of totalOffset) and the -80dBm RSSI
    //   start gate changed the learning population, so Δ accumulated without the gate (NLOS-polluted) is
    //   reset once here.
    private const val KEY_SCHEMA = "_schema"
    private const val SCHEMA_VER = 3

    // Path-loss model — BLE received strength at 1m (A) and attenuation exponent (n)
    private const val PATHLOSS_A = -59.0
    private const val PATHLOSS_N = 2.0

    // Quality gate — samples under 0.3m (close-range NLOS reflection error) or over 8m are not learned.
    //   Beyond 8m, 5~15m NLOS residuals (rack/pallet shadowing) flood in and bias Δ, skewing the displayed
    //   RSSI distance (Δ is display-only; it does not move alert thresholds).
    //   Learn only the short-range, LOS-dominant band.
    private const val MIN_DIST_M = 0.3f
    private const val MAX_DIST_M = 8.0f
    private const val MIN_SAMPLES = 5              // below this, calibration is 0 (still learning)
    private const val EMA_ALPHA = 0.2              // Δ smoothing factor (same-day fine-tune)
    private const val BASE_ALPHA = 0.05            // site baseline (long-term EMA) factor — 1/4 the speed of delta
    private const val STALE_MS = 24L * 60 * 60 * 1000        // 24h linear decay window
    private const val GC_MS = 7L * 24 * 60 * 60 * 1000       // values older than 7 days are dropped on load (common profile only)
    private const val PERSIST_THROTTLE_MS = 5000L  // disk write throttle (up to 5 s may be lost if the process dies)

    // baseline = long-term site baseline (no decay). The common profile stores it but doesn't use it.
    private data class Calib(val delta: Double, val updatedAt: Long, val samples: Int, val baseline: Double)

    private val map = ConcurrentHashMap<String, Calib>()
    private var appCtx: Context? = null
    private var prefs: SharedPreferences? = null
    @Volatile private var activeSite: String = ""
    @Volatile private var lastPersistMs = 0L
    @Volatile private var dirty = false

    fun init(context: Context) {
        appCtx = context.applicationContext
        activeSite = DevSettings.siteCode   // SafeAlertApp calls this after DevSettings.init
        loadProfile(activeSite)
    }

    // Applies a site code change — always called from the live settings-apply path (no change = no-op).
    //   The outgoing profile is saved first, so each site's learning survives switching back and forth.
    @Synchronized
    fun applySite() {
        val newSite = DevSettings.siteCode
        if (newSite == activeSite) return
        persistNow(System.currentTimeMillis())   // no-op unless dirty — half-typed codes don't create files
        activeSite = newSite
        loadProfile(newSite)
    }

    // Site code → SharedPreferences file name (file-system reserved characters and spaces become _)
    private fun prefNameFor(site: String): String =
        if (site.isEmpty()) PREF_NAME
        else PREF_NAME + "_" + site.replace(Regex("[\\\\/:*?\"<>|\\s]"), "_")

    @Synchronized
    private fun loadProfile(site: String) {
        val ctx = appCtx ?: return
        map.clear()
        dirty = false
        lastPersistMs = 0L
        val p = ctx.getSharedPreferences(prefNameFor(site), Context.MODE_PRIVATE)
        prefs = p
        // Old-schema profile (no marker = 1) may hold polluted values: wipe once, keeping only the marker.
        if (p.getInt(KEY_SCHEMA, 1) < SCHEMA_VER) {
            p.edit().clear().putInt(KEY_SCHEMA, SCHEMA_VER).apply()
            Log.d(TAG, "UWB 보정 스키마 승격(${if (site.isEmpty()) "공용" else site}): v${SCHEMA_VER} — 구 학습값 폐기")
            return
        }
        val now = System.currentTimeMillis()
        var loaded = 0
        p.all.forEach { (key, value) ->
            val f = (value as? String)?.split('|') ?: return@forEach
            if (f.size < 3) return@forEach
            val delta = f[0].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return@forEach   // self-heal NaN on disk (dropped on load)
            val at = f[1].toLongOrNull() ?: return@forEach
            val n = f[2].toIntOrNull() ?: return@forEach
            // 7-day GC for the common profile only — site profiles exist for long-term retention, so no GC.
            //   Dropped entries also disappear from disk on the next persist (full rewrite).
            if (site.isEmpty() && now - at > GC_MS) return@forEach
            val baseline = if (f.size >= 4) f[3].toDoubleOrNull()?.takeIf { it.isFinite() } ?: delta else delta   // old 3-field format compatible + NaN self-heal
            map[key] = Calib(delta, at, n, baseline)
            loaded++
        }
        if (loaded > 0) Log.d(TAG, "UWB 보정 로드(${if (site.isEmpty()) "공용" else site}): ${loaded}건")
    }

    // Learning input — AlertStateMachine.processAlert calls this every frame, only for pairs with a fresh UWB
    //   measurement (freshUwbDistM).
    //   rssi is medianValue (median-of-3): spike-free with ≈0 phase lag, so smoothing lag doesn't leak into Δ.
    @Synchronized
    fun onSample(pairKey: String, measuredRssi: Int, distM: Float) {
        if (!distM.isFinite() || distM < MIN_DIST_M || distM > MAX_DIST_M) return   // NaN guard (NaN comparisons are false and would slip through)
        val residual = measuredRssi - expectedRssiAt(distM.toDouble())
        val now = System.currentTimeMillis()
        val prev = map[pairKey]
        val next = when {
            prev == null ->
                Calib(residual, now, 1, residual)   // first sample = seed
            now - prev.updatedAt > STALE_MS && activeSite.isEmpty() ->
                Calib(residual, now, 1, residual)   // common: reseed after a 24h gap
            now - prev.updatedAt > STALE_MS ->
                // Site: continue from the baseline even after a gap (no weekend reset) — delta re-converges from the
                //   baseline, samples keep accumulating → calibration active from the first sample on a revisit
                //   (MIN_SAMPLES already met).
                Calib(prev.baseline + EMA_ALPHA * (residual - prev.baseline), now,
                    (prev.samples + 1).coerceAtMost(1000),
                    prev.baseline + BASE_ALPHA * (residual - prev.baseline))
            else ->
                Calib(prev.delta + EMA_ALPHA * (residual - prev.delta), now,
                    (prev.samples + 1).coerceAtMost(1000),
                    prev.baseline + BASE_ALPHA * (residual - prev.baseline))
        }
        map[pairKey] = next
        dirty = true
        maybePersist(now)
    }

    // Distance string for the list/floating widget. Empty string = the caller falls back to the dBm label.
    //   Display mode 0 = dBm only / 1 = meters for UWB-measured pairs only /
    //   2 = meters for all (non-UWB = RSSI estimate).
    //   The source is tagged explicitly — "·UWB" for UWB-measured pairs, "·RSSI" for RSSI estimates — so it's
    //   clear at a glance which pairs are measured by UWB.
    fun distanceTextFor(pairKey: String, rssi: Int, uwbDistM: Float?): String {
        val mode = DevSettings.distanceDisplayMode
        if (mode <= 0) return ""
        if (uwbDistM != null) return "%.1fm·UWB".format(uwbDistM)
        if (mode < 2) return ""
        val d = estimateDistanceM(pairKey, rssi)
        return if (d < 9.95) "약 %.1fm·RSSI".format(d) else "약 %.0fm·RSSI".format(d)
    }

    // RSSI → distance (m) — d = 10^((A − (rssi − Δeff)) / (10n)). Δeff = learned deviation after decay.
    private fun estimateDistanceM(pairKey: String, rssi: Int): Double {
        var deltaEff = 0.0
        val c = map[pairKey]
        if (c != null && c.samples >= MIN_SAMPLES) {
            val decay = (1.0 - (System.currentTimeMillis() - c.updatedAt).toDouble() / STALE_MS).coerceIn(0.0, 1.0)
            deltaEff = if (activeSite.isEmpty()) c.delta * decay
                       else c.baseline + (c.delta - c.baseline) * decay
        }
        return 10.0.pow((PATHLOSS_A - (rssi - deltaEff)) / (10.0 * PATHLOSS_N)).coerceIn(0.1, 99.0)
    }

    private fun expectedRssiAt(distM: Double): Double = PATHLOSS_A - 10.0 * PATHLOSS_N * log10(distM)

    // Full-rewrite persist — entries = number of pairs (a handful), so writing everything is simplest and safest.
    //   Format = "delta|updatedAt|samples|baseline" (4 fields — the old 3-field format loads with baseline=delta)
    private fun maybePersist(now: Long) {
        if (now - lastPersistMs < PERSIST_THROTTLE_MS) return
        persistNow(now)
    }

    @Synchronized
    private fun persistNow(now: Long) {
        if (!dirty) return
        val p = prefs ?: return
        lastPersistMs = now
        dirty = false
        val e = p.edit().clear()
        e.putInt(KEY_SCHEMA, SCHEMA_VER)   // clear() drops the marker; write it every persist or each restart wipes learning
        map.forEach { (id, c) -> e.putString(id, "${c.delta}|${c.updatedAt}|${c.samples}|${c.baseline}") }
        e.apply()
    }
}
