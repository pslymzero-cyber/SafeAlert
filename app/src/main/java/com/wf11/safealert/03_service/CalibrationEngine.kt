package com.wf11.safealert.service

import android.content.Context
import android.os.Build
import android.util.Log
import com.wf11.safealert.ble.BleConstants
import com.wf11.safealert.firebase.FirebaseManager
import com.wf11.safealert.utils.DevSettings

/**
 * Echo RSSI calibration layer (single owner).
 *   Context dependency is fixed once via init(context) — following the DevSettings/BeaconRegistry/UwbCalibrator convention.
 */
object CalibrationEngine {

    private const val TAG = "CalibrationEngine"

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        migrateSiteEchoFile()   // SafeAlertApp calls this after DevSettings.init (the handover reads siteCode)
    }

    // Echo-deviation tally: mutual-RSSI echo (0xE0C0) telemetry — collecting it does not affect decisions.
    //   (Echo auto-calibration 'reads' this histogram to compute and inject echoCal — see the layer below.)
    //   diff = (peer avgRssi as I measure it) − (me as the peer measures it, peerEchoRssi), accumulated per device
    //   in a 5dB×16-bucket (−40~+40dB) histogram. Median = systematic asymmetry (measured basis for per-model
    //   offsets such as TX power and antenna); spread = channel noise (uncorrectable) — telling them apart is the
    //   point of collecting. Read directly by the BleSettingsActivity poller (1200ms), like detectedSnapshot
    //   polling. Scan callbacks are marshalled to the main looper, and the poller and stopAll also run on the main
    //   thread → no extra sync needed (main-thread-only map).
    //   Lifetime: first tick seeds from the SharedPreferences totals → live accumulation → saved on
    //   loss/stop/periodically (accumulates across sessions).
    const val ECHO_BUCKET_COUNT = 16      // 5dB × 16 buckets = −40 ~ +40dB
    const val ECHO_BUCKET_DB    = 5
    const val ECHO_BUCKET_MIN   = -40
    const val ECHO_PREFS        = "echo_diff_stats"   // dedicated SharedPreferences (separate from the settings prefs)
    const val ECHO_KEY          = "data"
    private const val ECHO_PERSIST_EVERY_TICKS = 500  // periodic save roughly every minute at the ~120ms decision cycle
    val echoDiffLive = mutableMapOf<String, EchoDiffStats>()

    // ── Serialization utils — pure functions (shared by the service and developer settings). Record '\n', field '|', bucket ','
    //   "deviceId|echoTicks|totalTicks|b0,…,b15". Malformed records are skipped (defensive parsing).
    fun parseEchoBlob(blob: String): MutableMap<String, EchoDiffStats> {
        val out = mutableMapOf<String, EchoDiffStats>()
        for (line in blob.split('\n')) {
            val f = line.split('|')
            if (f.size != 4) continue
            val b = f[3].split(',')
            if (b.size != ECHO_BUCKET_COUNT) continue
            val s = EchoDiffStats()
            s.echoTicks  = f[1].toIntOrNull() ?: continue
            s.totalTicks = f[2].toIntOrNull() ?: continue
            for (i in 0 until ECHO_BUCKET_COUNT) s.buckets[i] = b[i].toIntOrNull() ?: 0
            out[f[0]] = s
        }
        return out
    }

    fun serializeEchoBlob(map: Map<String, EchoDiffStats>): String =
        map.entries.joinToString("\n") { (id, s) ->
            "$id|${s.echoTicks}|${s.totalTicks}|${s.buckets.joinToString(",")}"
        }

    // ── Echo auto-calibration: the layer that 'reads' the histogram above to compute the decision offset (echoCal) ──
    //   echoCal = clamp(−median/2, ±clampDb). Why half: in a mirror pair (the peer sees the same deviation with the
    //   opposite sign) each side backs off by half and they converge on the midpoint — full correction on each side
    //   would make the pair overcorrect each other.
    //   Gates: n(echoTicks) < echoCalMinTicks = undecidable (null → try the FB prior instead),
    //          spread (±IQR/2) > echoCalMaxIqrDb = median not trusted (0.0 = correction definitively abandoned, no prior).
    //   The kill switch (echoAutoCalibEnabled, default ON) is applied at injection (totalOffset) — the functions
    //   below can always compute, so the developer settings 'candidate display' and decisions share the same code.
    private const val ECHO_DECAY_TICKS = 30_000   // above this, halve all buckets (forgetting) — ~15k-tick time constant, fixed
    private const val ECHO_FB_MODELS_KEY  = "fb_models"     // cache: "deviceId(sanitized)|model" lines
    private const val ECHO_FB_PRIORS_KEY  = "fb_priors"     // cache: "peerModel|median|Σn" lines (folded relative to my model)
    private const val ECHO_FB_UPLOADED_AT = "fb_uploaded_at"
    private const val ECHO_FB_UPLOAD_INTERVAL_MS = 3_600_000L   // upload throttled to 1h (piggybacks on persistEchoAll)
    // Firebase model-pair priors — at startup the cache is restored at once + refreshed asynchronously
    //   (loadEchoPriors); decisions and display read only the in-memory map (no network during decisions).
    //   Firebase callbacks run on the main looper → a main-thread-only map like echoDiffLive (no extra sync needed).
    val echoFbPriorByModel = mutableMapOf<String, Pair<Double, Int>>()   // peer model → (folded median dB, Σn)
    val echoFbModelById    = mutableMapOf<String, String>()              // sanitized deviceId → model name

    /**
     * Bucket histogram quantile (dB) — linear interpolation assuming a uniform distribution within each bucket
     *  (more precise than bucket centers). total = echoTicks (sum of buckets), q ∈ (0,1]. 0.0 if total≤0.
     */
    fun echoQuantileDb(buckets: IntArray, total: Int, q: Double): Double {
        if (total <= 0) return 0.0
        val target = total * q
        var cum = 0
        for (i in buckets.indices) {
            val c = buckets[i]
            if (c > 0 && cum + c >= target) {
                val frac = ((target - cum) / c).coerceIn(0.0, 1.0)
                return ECHO_BUCKET_MIN + (i + frac) * ECHO_BUCKET_DB
            }
            cum += c
        }
        return (ECHO_BUCKET_MIN + ECHO_BUCKET_COUNT * ECHO_BUCKET_DB).toDouble()
    }

    /**
     * Local calibration candidate (dB) — computed regardless of the kill switch. n too low = null (a prior may
     *  substitute); spread too high = 0.0 (correction 'definitively' abandoned — enough local samples but too
     *  noisy, so not even a prior overrides it).
     */
    fun echoCalLocalDb(s: EchoDiffStats): Double? {
        if (s.echoTicks < DevSettings.echoCalMinTicks) return null
        val iqrHalf = (echoQuantileDb(s.buckets, s.echoTicks, 0.75) -
                       echoQuantileDb(s.buckets, s.echoTicks, 0.25)) / 2.0
        if (iqrHalf > DevSettings.echoCalMaxIqrDb) return 0.0
        val clamp = DevSettings.echoCalClampDb.toDouble()
        return (-echoQuantileDb(s.buckets, s.echoTicks, 0.50) / 2.0).coerceIn(-clamp, clamp)
    }

    /**
     * Firebase model-pair prior correction (dB) — null if the peer model is unknown, there is no prior, or the Σn gate is not met.
     *  The Σn gate is evaluated live 'at decision time' (echoCalMinTicks changes apply at once). The per-sample spread
     *  gate was already applied with the settings at fetch time (loadEchoPriors — a trade-off: changes apply on the next fetch).
     */
    fun echoCalPriorDb(deviceId: String): Double? {
        val model = echoFbModelById[FirebaseManager.sanitizeKey(deviceId)] ?: return null
        val (m, n) = echoFbPriorByModel[model] ?: return null
        if (n < DevSettings.echoCalMinTicks) return null
        val clamp = DevSettings.echoCalClampDb.toDouble()
        return (-m / 2.0).coerceIn(-clamp, clamp)
    }

    /**
     * Value injected into decisions (integer dB) — local first, the FB prior if local n is too low, 0 if neither.
     *  The caller applies the kill switch (when OFF this is not called, so decisions get no echo correction).
     */
    fun echoCalAppliedDb(deviceId: String): Int {
        val v = echoDiffLive[deviceId]?.let { echoCalLocalDb(it) }
            ?: echoCalPriorDb(deviceId) ?: 0.0
        return Math.round(v).toInt()
    }

    /**
     * Per-device echo-deviation totals — the i-th entry of buckets = number of ticks with
     * −40+5i ≤ diff < −35+5i (out-of-range values clamp to the end buckets).
     */
    class EchoDiffStats {
        val buckets = IntArray(ECHO_BUCKET_COUNT)
        var echoTicks  = 0   // ticks with an echo (= sum of buckets)
        var totalTicks = 0   // ticks reaching the RSSI decision (echo or not; excl. Case A UWB early exits)
    }

    // ── Echo-deviation tally: collection telemetry — recording does not affect decisions (auto-calibration reads the totals) ──
    //   Rule: 'always' record when peerEchoRssi exists — independent of the 25dB consistency gate (hasReciprocal).
    //   Extreme asymmetry outside the gate is exactly what we want to observe; censoring it would make the 25dB
    //   threshold impossible to evaluate.
    //   Also independent of the kill switch (reciprocalRssiEnabled) — myEchoHash is always injected, so peer echoes
    //   keep being parsed even with that decision OFF (observe-only operation is possible).

    // Echo calibration is a device/model property (a two-way difference over the same path at the same moment,
    //   so path loss cancels out) — one global file regardless of site. Same principle as the global Firebase
    //   echo_calib path. The fb_* cache and upload stamp live in this file too.
    private fun echoPrefs() =
        appContext.getSharedPreferences(ECHO_PREFS, Context.MODE_PRIVATE)

    /**
     * At startup, hands the current site's echo file over to the global file once.
     * For the same device the site file's value wins (shared snapshot + later learning, so a superset); devices only
     * in the global file are kept. The fb_* cache and stamps are copied too. The site file is cleared only after the
     * global commit() succeeds — clearing it on failure would let stale site values overwrite the latest global
     * values on every startup. An empty site file (already handed over) is a no-op → repeat calls are idempotent.
     * ponytail: only the current site's file is handed over; files of sites visited earlier remain — those peers
     *   relearn on re-contact (n gate), and meanwhile the global Firebase per-model prior corrects them.
     */
    private fun migrateSiteEchoFile() {
        val site = DevSettings.siteCode
        if (site.isEmpty()) return
        val src = appContext.getSharedPreferences(ECHO_PREFS + "_" + site, Context.MODE_PRIVATE)
        if (src.all.isEmpty()) return
        val dst = echoPrefs()
        val merged = parseEchoBlob(dst.getString(ECHO_KEY, "") ?: "")
        merged.putAll(parseEchoBlob(src.getString(ECHO_KEY, "") ?: ""))
        val e = dst.edit().putString(ECHO_KEY, serializeEchoBlob(merged))
        src.all.forEach { (k, v) ->
            if (k == ECHO_KEY) return@forEach
            when (v) {
                is String -> e.putString(k, v)
                is Long   -> e.putLong(k, v)
            }
        }
        if (!e.commit()) {
            Log.w(TAG, "에코 사업장 인계 실패 — 전역 commit 실패, 사업장 파일 보존")
            return
        }
        val migratedCount = src.all.size
        src.edit().clear().apply()
        Log.i(TAG, "에코 사업장 인계 완료: ${migratedCount}건 -> 전역")
    }

    /**
     * Saves all live entries merged with the stored ones — each live entry is the full
     * total (seeded from storage on its first tick), so it simply overwrites.
     */
    fun persistEchoAll(myId: String) {
        if (echoDiffLive.isEmpty()) return
        val merged = parseEchoBlob(echoPrefs().getString(ECHO_KEY, "") ?: "")
        merged.putAll(echoDiffLive)
        echoPrefs().edit().putString(ECHO_KEY, serializeEchoBlob(merged)).apply()
        maybeUploadEchoCalib(myId, merged)   // FB prior upload (1h throttle) — piggybacks on the periodic save
    }

    /** Saves a single device (onDeviceLost path) — receives an entry already removed from the live map. */
    fun persistEchoEntry(deviceId: String, stats: EchoDiffStats) {
        val merged = parseEchoBlob(echoPrefs().getString(ECHO_KEY, "") ?: "")
        merged[deviceId] = stats
        echoPrefs().edit().putString(ECHO_KEY, serializeEchoBlob(merged)).apply()
    }

    /**
     * Called on every RSSI decision tick (just before the cooperative escalation block).
     * The first tick seeds from the stored totals → live = full total.
     */
    fun recordEchoDiff(myId: String, deviceId: String, avgRssi: Int, peerEchoRssi: Int) {
        val stats = echoDiffLive.getOrPut(deviceId) {
            parseEchoBlob(echoPrefs().getString(ECHO_KEY, "") ?: "")[deviceId] ?: EchoDiffStats()
        }
        stats.totalTicks++
        if (peerEchoRssi != BleConstants.NO_ECHO_RSSI) {
            stats.echoTicks++
            val diff = avgRssi - peerEchoRssi
            stats.buckets[((diff - ECHO_BUCKET_MIN) / ECHO_BUCKET_DB).coerceIn(0, ECHO_BUCKET_COUNT - 1)]++
            // Forgetting — above 30k echo ticks, halve all buckets. Keeps calibration from sticking when the environment
            //   changes (case fitted, repair/replacement, etc.) and tracks it with a ~15k-tick time constant. Keeps the
            //   echoTicks = sum of buckets invariant; totalTicks is halved too so 'echo %' keeps its meaning (the
            //   periodic-save modulo phase shifting is harmless).
            if (stats.echoTicks > ECHO_DECAY_TICKS) {
                for (i in stats.buckets.indices) stats.buckets[i] /= 2
                stats.echoTicks = stats.buckets.sum()
                stats.totalTicks /= 2
            }
        }
        // Periodic save — via the tick counter, no new timer (at most ~1 minute of data lost if the process is killed).
        if (stats.totalTicks % ECHO_PERSIST_EVERY_TICKS == 0) persistEchoAll(myId)
    }

    // ── Firebase model-pair priors — upload (share raw aggregates) / download (bootstrap) ──
    //   Purpose: a new device pair starts calibrating from the aggregated median of the same 'model pair' even
    //   before it fills the local n gate (default 3,000 ticks) (local wins as soon as it qualifies). Upload and
    //   download ignore the kill switch (always collect and share — same idea as always recording); only
    //   'applying' is decided by echoAutoCalibEnabled.

    /**
     * Upload piggybacked on the periodic save — 1h throttle. The stamp is updated up front 'at attempt time':
     *  Firebase offline persistence (setPersistenceEnabled) queues and resends writes, so waiting for the next
     *  window is safer than retrying right after a failure.
     */
    private fun maybeUploadEchoCalib(myId: String, merged: Map<String, EchoDiffStats>) {
        if (myId.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - echoPrefs().getLong(ECHO_FB_UPLOADED_AT, 0L) < ECHO_FB_UPLOAD_INTERVAL_MS) return
        val peers = mutableMapOf<String, Triple<Double, Int, Double>>()
        for ((id, s) in merged) {
            if (s.echoTicks <= 0) continue   // devices without echo (beacons, older versions) are not aggregated
            val med = echoQuantileDb(s.buckets, s.echoTicks, 0.50)
            val iqr = (echoQuantileDb(s.buckets, s.echoTicks, 0.75) -
                       echoQuantileDb(s.buckets, s.echoTicks, 0.25)) / 2.0
            // The uploaded n is capped at the forgetting limit — so totals that predate forgetting use the same scale
            peers[FirebaseManager.sanitizeKey(id)] = Triple(med, minOf(s.echoTicks, ECHO_DECAY_TICKS), iqr)
        }
        if (peers.isEmpty()) return
        echoPrefs().edit().putLong(ECHO_FB_UPLOADED_AT, now).apply()
        FirebaseManager.uploadEchoCalib(myId, Build.MODEL, peers) { }
    }

    /**
     * Once at startup (onCreate) — restores the preferences cache at once (for offline restarts), then refreshes
     *  asynchronously. The per-sample spread gate is applied to the aggregate with the settings at fetch time
     *  (setting changes apply from the next fetch); the Σn validity gate is live at decision time
     *  (echoCalPriorDb). On failure or an empty response the cache is kept.
     */
    fun loadEchoPriors() {
        val p = echoPrefs()
        echoFbModelById.clear()
        for (line in (p.getString(ECHO_FB_MODELS_KEY, "") ?: "").split('\n')) {
            val f = line.split('|')
            if (f.size == 2) echoFbModelById[f[0]] = f[1]
        }
        echoFbPriorByModel.clear()
        for (line in (p.getString(ECHO_FB_PRIORS_KEY, "") ?: "").split('\n')) {
            val f = line.split('|')
            if (f.size == 3) {
                val m = f[1].toDoubleOrNull()
                val n = f[2].toIntOrNull()
                if (m != null && n != null) echoFbPriorByModel[f[0]] = m to n
            }
        }
        FirebaseManager.downloadEchoCalibAll { nodes ->
            if (nodes.isEmpty()) return@downloadEchoCalibAll
            val models = nodes.associate { it.id to it.model }
            val priors = FirebaseManager.aggregateEchoPriors(
                nodes, Build.MODEL, DevSettings.echoCalMaxIqrDb.toDouble(), DevSettings.echoCalMinTicks)
            echoFbModelById.clear()
            echoFbModelById.putAll(models)
            echoFbPriorByModel.clear()
            echoFbPriorByModel.putAll(priors)
            p.edit()
                .putString(ECHO_FB_MODELS_KEY,
                    models.entries.joinToString("\n") { "${it.key}|${it.value}" })
                .putString(ECHO_FB_PRIORS_KEY,
                    priors.entries.joinToString("\n") { "${it.key}|${it.value.first}|${it.value.second}" })
                .apply()
            Log.d(TAG, "에코 프라이어 갱신: 노드 ${nodes.size} · 내 모델(${Build.MODEL}) 기준 ${priors.size}종")
        }
    }

    // Key for UWB↔RSSI calibration learning/lookup — role-pair (category-pair) segment.
    //   Tokenizes my category and the peer's (scan cache) category, sorts them order-independently and joins with "×".
    //   Physical model: the same role pair (e.g. FORKLIFT×WALKER) has similar antenna height and shielding →
    //   an offset learned over UWB with one forklift applies at once to the RSSI→distance estimate for other forklifts
    //   not yet met over UWB (on-screen distance only; not used for judgment).
    //   If the peer category is unknown (no scan cache), assume walker, the most conservative choice.
    fun uwbPairKeyFor(myCategory: Int, peerCategory: Int): String {
        val mine   = categoryToken(myCategory)
        val theirs = categoryToken(peerCategory)
        return listOf(mine, theirs).sorted().joinToString("×")   // "×"
    }

    private fun categoryToken(cat: Int): String = when (cat) {
        BleConstants.CAT_FORKLIFT -> "FORKLIFT"
        BleConstants.CAT_EPJ      -> "EPJ"
        else                      -> "WALKER"
    }
}
