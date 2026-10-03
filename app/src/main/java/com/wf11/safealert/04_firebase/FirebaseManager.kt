package com.wf11.safealert.firebase

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import com.wf11.safealert.BuildConfig
import com.wf11.safealert.utils.BeaconRegistry
import com.wf11.safealert.utils.DevSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object FirebaseManager {

    private const val TAG = "FirebaseManager"
    private val db get() = FirebaseDatabase.getInstance().reference.child(DevSettings.firebaseRoot)

    /**
     * Per-site node — only the alert log (alerts) is split per site via siteNode.
     * Echo calibration (echo_calib) uses a flat global path with only a site label, and the local echo statistics
     * (CalibrationEngine) are global as well, independent of site.
     * With an empty code, the same path as older versions is used (keeps existing data reachable).
     */
    private fun siteNode(name: String) =
        DevSettings.siteCode.let { if (it.isEmpty()) db.child(name) else db.child(name).child(it) }

    // ── Writer uid — DB rules require uid (= auth.uid) on alert, beacon-share and echo-calibration writes ──
    private fun currentUid(): String? = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()

    private var pending: PendingAlerts? = null

    /**
     * Once at app start (SafeAlertApp) — opens the pending-record store and sends the
     * pending records once signed in (or right away if already signed in).
     */
    fun init(context: Context) {
        pending = PendingAlerts(context.getSharedPreferences("pending_alerts", Context.MODE_PRIVATE))
        try {
            FirebaseAuth.getInstance().addAuthStateListener { auth ->
                auth.currentUser?.uid?.let { flushPendingAlerts(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "로그인 상태 구독 실패: ${e.message}")
        }
    }

    private fun flushPendingAlerts(uid: String) {
        val items = pending?.drain().orEmpty()
        if (items.isEmpty()) return
        for ((url, data) in items) {
            runCatching {
                FirebaseDatabase.getInstance().getReferenceFromUrl(url).setValue(data + ("uid" to uid))
                    .addOnFailureListener { Log.e(TAG, "보류 경보 저장 실패: ${it.message}") }
            }.onFailure { Log.e(TAG, "보류 경보 전송 오류: ${it.message}") }
        }
        Log.d(TAG, "보류 경보 ${items.size}건 저장")
    }

    // Roles (myRole/peerRole) are converted to names in 03_service before being passed — 04_firebase does not
    //   depend on 02_ble (layer rule). Defaults keep existing calls compiling.
    fun saveAlert(deviceId: String, walkerId: String, rssi: Int, level: String,
                  myRole: String = "UNKNOWN", peerRole: String = "UNKNOWN") {
        val logId = BeaconRegistry.shortFullId(deviceId)   // Beacon UUID keys are stored as 8 chars only
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val alertId = UUID.randomUUID().toString()
        val data = mapOf(
            "timestamp" to System.currentTimeMillis(),
            "deviceId" to withSite(logId),   // CenterName-EquipmentID (e.g. WF11-CB-01)
            "walkerId" to withSite(walkerId),
            // Values outside the storage rule range (−150~20) become 0 = no value (the marker the UWB path uses when it has no RSSI)
            "rssi" to (rssi.takeIf { it in -150..20 } ?: 0),
            "alertLevel" to level,
            "myRole" to myRole,
            "peerRole" to peerRole,
            "site" to DevSettings.siteCode
        )
        val ref = siteNode("alerts").child(today).child(alertId)
        val uid = currentUid()
        if (uid == null) {
            // Not signed in yet — fix the date and site slots as of now, hold the record, and send it once signed in
            pending?.add(ref.toString(), data) ?: Log.w(TAG, "보류 저장소 없음 — 경보 기록 생략")
            Log.d(TAG, "경보 보류(로그인 전): $level ${withSite(logId)}")
            return
        }
        ref.setValue(data + ("uid" to uid))
            .addOnFailureListener { Log.e(TAG, "경보 저장 실패: ${it.message}") }
        Log.d(TAG, "경보 저장: $level ${withSite(logId)} rssi=$rssi")
    }

    // ── UWB measurement samples — physical-distance basis for the performance spec ─────────────
    //   Records the UWB-measured distance (m) and the BLE RSSI of the same frame as one entry. Both are needed to
    //   back-calculate "how many meters warning -78dBm / danger -65dBm really is". The learned value (Δ)
    //   stays on the device and is never exported, so aggregation needs these raw samples.
    //   Called only while the developer setting switch (DevSettings.uwbProbeUploadEnabled) is on —
    //   it is meant for real-device measurement sessions, not continuous collection, so it is off by default.
    fun saveUwbProbe(myId: String, model: String, site: String,
                     pairKey: String, distM: Float, rssi: Int) {
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val data = mapOf(
            "timestamp" to System.currentTimeMillis(),
            "walkerId"  to myId,
            "model"     to model,
            "site"      to site,
            "pairKey"   to pairKey,
            "distM"     to distM,
            "rssi"      to rssi
        )
        db.child("uwb_probe").child(today).child(UUID.randomUUID().toString()).setValue(data)
            .addOnFailureListener { Log.e(TAG, "UWB 표본 저장 실패: ${it.message}") }
    }

    // ── Device-to-device beacon sharing (named sets) ───────────────────────
    //   Uploads the selection to beacon_share/<siteCode>/<key> under the same root (firebaseRoot);
    //   devices at the same site pick a set from the list, download it and merge.
    //   With an empty site code it returns failure instead of falling back to a flat path (the rules allow writes only under $sc).

    /** My site's share node. null if the site code is not set */
    private fun beaconShareNode() =
        DevSettings.siteCode.takeIf { it.isNotEmpty() }?.let { db.child("beacon_share").child(it) }

    data class BeaconSetMeta(
        val key: String,        // Firebase key (normalized)
        val name: String,       // Display name (raw user input)
        val count: Int,
        val sender: String,
        val timestamp: Long
    )

    // Strips characters forbidden in Firebase keys ( . # $ [ ] / ) — public because echo-calibration upload keys use it too
    fun sanitizeKey(s: String): String =
        s.trim().replace(Regex("[.#$\\[\\]/]"), "_").ifEmpty { "set" }

    /**
     * Display name = PIT equipment ID: two tokens, `TypeCode-Number` — `CB-01`, `RT-07`.
     *
     * There is no free input, only selection (type dropdown + number dropdown), so there is structurally no path
     * for a person's name or nickname to get in. This check is a second line of defense that filters values
     * left by older versions and values coming from outside.
     *
     * Whether the type code is an actually registered equipment type is not checked here — 04_firebase does not depend
     * on 01_model (layer rule). PitType.parse in the selection UI validates the code.
     *
     * Fixed at 5 bytes, leaving 10 bytes of the 15-byte BLE broadcast limit. The center name is not included.
     */
    val PIT_ID_REGEX = Regex("^[A-Z]{2}-[0-9]{2}$")

    /** Input guidance text — the UI hint and the migration notice use the same sentence */
    const val PIT_ID_HINT = "장비 종류와 번호를 선택하세요 (예: CB-01)"

    /** Input normalization — same rule as the site code: trim, then uppercase */
    fun normalizeDeviceId(s: String): String = s.trim().uppercase(Locale.ROOT)

    /**
     * Auto-issued ID format — the MainActivity.newAutoId() rule ("SA-" + 8 uppercase UUID chars).
     * Used by devices without an equipment ID, such as walkers.
     */
    val AUTO_ID_REGEX = Regex("^SA-[0-9A-F]{8}$")

    /**
     * Whether the value may be used as the broadcast ID as is — an equipment ID or an auto-issued ID (empty is not allowed).
     * Used to filter out personal names that older versions wrote into device_id.
     */
    fun isUsableAdvertisedId(s: String): Boolean {
        val t = normalizeDeviceId(s)
        if (t.isEmpty()) return false
        return AUTO_ID_REGEX.matches(t) || PIT_ID_REGEX.matches(t)
    }

    /**
     * Full identifier for the alert log — `CenterName-EquipmentID`, stored as `WF11-CB-01`.
     *
     * The center name is not sent over BLE (byte budget, redundancy); it is attached at save time instead. A peer met
     * over BLE is physically inside the same center, so my center code applies as is — the path (`alerts/{site}/...`)
     * and the `site` field already rest on the same assumption.
     */
    fun withSite(id: String): String {
        val site = DevSettings.siteCode
        return if (site.isEmpty() || id.isEmpty()) id else "$site-$id"
    }

    /** Uploads the selected beacon profiles (JSON) as a named set */
    fun uploadBeaconSet(setName: String, profilesJson: String, count: Int, sender: String, onResult: (Boolean) -> Unit) {
        val node = beaconShareNode() ?: return onResult(false)
        // Not signed in yet: report failure and retry sign-in (the rules require uid)
        val uid = currentUid() ?: run { FirebaseConfig.ensureSignedIn(); return onResult(false) }
        val key = sanitizeKey(setName)
        val data = mapOf(
            "name"         to setName.trim().ifEmpty { key },
            "profilesJson" to profilesJson,
            "count"        to count,
            "sender"       to sender,
            "timestamp"    to System.currentTimeMillis(),
            "uid"          to uid
        )
        node.child(key).setValue(data)
            .addOnSuccessListener { Log.d(TAG, "비콘 세트 업로드: $key (${count}개)"); onResult(true) }
            .addOnFailureListener { Log.e(TAG, "비콘 세트 업로드 실패: ${it.message}"); onResult(false) }
    }

    /** Lists uploaded named sets (newest first) */
    fun listBeaconSets(onResult: (List<BeaconSetMeta>) -> Unit) {
        val node = beaconShareNode() ?: return onResult(emptyList())
        node.get()
            .addOnSuccessListener { snap ->
                val sets = snap.children.mapNotNull { c ->
                    val key = c.key ?: return@mapNotNull null
                    BeaconSetMeta(
                        key       = key,
                        name      = c.child("name").getValue(String::class.java) ?: key,
                        count     = (c.child("count").getValue(Long::class.java) ?: 0L).toInt(),
                        sender    = c.child("sender").getValue(String::class.java) ?: "",
                        timestamp = c.child("timestamp").getValue(Long::class.java) ?: 0L
                    )
                }.sortedByDescending { it.timestamp }
                onResult(sets)
            }
            .addOnFailureListener { Log.e(TAG, "비콘 세트 목록 조회 실패: ${it.message}"); onResult(emptyList()) }
    }

    /** Downloads one set's profile JSON (key = BeaconSetMeta.key) */
    fun downloadBeaconSet(key: String, onResult: (String?) -> Unit) {
        val node = beaconShareNode() ?: return onResult(null)
        node.child(key).child("profilesJson").get()
            .addOnSuccessListener { onResult(it.getValue(String::class.java)) }
            .addOnFailureListener { Log.e(TAG, "비콘 세트 다운로드 실패: ${it.message}"); onResult(null) }
    }

    /** Deletes an uploaded set from the cloud (admin use) */
    fun deleteBeaconSet(key: String, onResult: (Boolean) -> Unit) {
        val node = beaconShareNode() ?: return onResult(false)
        node.child(key).removeValue()
            .addOnSuccessListener { onResult(true) }
            .addOnFailureListener { Log.e(TAG, "비콘 세트 삭제 실패: ${it.message}"); onResult(false) }
    }

    // ── Echo-offset auto-calibration prior sharing ───────────────────────
    //   Each device uploads its histogram summary (median, n, spread per peer device) to echo_calib/<myID>,
    //   downloads all nodes and aggregates them per 'model pair' — so a new device, before it has filled local samples (n < gate),
    //   can bootstrap calibration from the aggregated median of the same model pair (local wins once established).

    data class EchoPeerStat(val m: Double, val n: Int, val iqr: Double)   // median dB · echo ticks · spread (±IQR/2)
    data class EchoCalibNode(val id: String, val model: String, val peers: Map<String, EchoPeerStat>)

    /** Uploads, overwriting my whole node — peers keys are sanitized peer device IDs, values = (median, n, spread). */
    fun uploadEchoCalib(myId: String, model: String, peers: Map<String, Triple<Double, Int, Double>>, onResult: (Boolean) -> Unit) {
        // Not signed in yet: skip this round (the rules require uid; the next cycle uploads again)
        val uid = currentUid() ?: return onResult(false)
        val data = mapOf(
            "model" to model,
            // App version — echo_calib is fully overwritten every hour, so this is always current.
            //   Lets the server spot devices still running old versions at a glance (verifying the rule-lock rollout).
            "ver"   to BuildConfig.VERSION_NAME,
            "ts"    to System.currentTimeMillis(),
            // The site code is kept only as a label, not in the path. saveAlert has a per-site path (siteNode)
            //   and also a label, but this echo upload has a flat path and keeps only the label.
            "site"  to DevSettings.siteCode,
            "peers" to peers.mapValues { (_, v) -> mapOf("m" to v.first, "n" to v.second, "iqr" to v.third) },
            "uid"   to uid
        )
        db.child("echo_calib").child(sanitizeKey(myId)).setValue(data)
            .addOnSuccessListener { Log.d(TAG, "에코보정 업로드: $myId (피어 ${peers.size})"); onResult(true) }
            .addOnFailureListener { Log.e(TAG, "에코보정 업로드 실패: ${it.message}"); onResult(false) }
    }

    /** Downloads all nodes — empty list on failure or absence (callers keep their cache). */
    fun downloadEchoCalibAll(onResult: (List<EchoCalibNode>) -> Unit) {
        db.child("echo_calib").get()
            .addOnSuccessListener { snap ->
                // A child without model is the site segment of echo_calib/<site>/<deviceID> written by older versions —
                //   go one level down and read the grandchildren as device nodes (absorbs the mixed rollout).
                val current = mutableListOf<EchoCalibNode>()
                val legacy = mutableListOf<EchoCalibNode>()
                for (c in snap.children) {
                    val node = parseEchoNode(c)
                    if (node != null) current += node else c.children.mapNotNullTo(legacy, ::parseEchoNode)
                }
                onResult(mergeEchoNodes(legacy, current))
            }
            .addOnFailureListener { Log.e(TAG, "에코보정 노드 조회 실패: ${it.message}"); onResult(emptyList()) }
    }

    /**
     * Absorbs mixed old/new paths: each device ID is kept once and current (the new path) wins.
     * The old path echo_calib/<site>/<deviceID> survives upgrades and is never deleted, so without filtering the same device's
     * samples are counted twice, doubling Σn (overstated confidence), and the old median is n-weighted with the current one,
     * keeping half the weight forever.
     */
    fun mergeEchoNodes(legacy: List<EchoCalibNode>, current: List<EchoCalibNode>): List<EchoCalibNode> =
        (legacy + current).associateBy { it.id }.values.toList()

    private fun parseEchoNode(c: DataSnapshot): EchoCalibNode? {
        val id = c.key ?: return null
        val model = c.child("model").getValue(String::class.java) ?: return null
        val peers = c.child("peers").children.mapNotNull { pc ->
            val k = pc.key ?: return@mapNotNull null
            val m = pc.child("m").getValue(Double::class.java) ?: return@mapNotNull null
            val n = (pc.child("n").getValue(Long::class.java) ?: 0L).toInt()
            val iqr = pc.child("iqr").getValue(Double::class.java) ?: 0.0
            k to EchoPeerStat(m, n, iqr)
        }.toMap()
        return EchoCalibNode(id, model, peers)
    }

    /**
     * Pure aggregation: directional model-pair (myModel→peerModel) prior — peerModel → (folded median dB, Σn).
     * Fold rule: samples where my model's node measured the peer model count as +m;
     * samples where the peer model's node measured my model count as −m
     * (the offset is antisymmetric: A−B seen by A = −(B−A seen by B)). For an identical model pair (M×M) the two directions
     * naturally cancel (±) and converge near 0 (the expectation for symmetric hardware). A per-sample spread gate drops
     * noisy samples (iqr > maxIqrDb), and peers of unknown model (no node of their own) are dropped. Callers judge Σn validity.
     * Weighted median with per-sample weight min(n, capN) — a single outlying sample cannot drag the result.
     * Σn is the sum of the same weights, so with capN equal to the Σn gate (echoCalMinTicks) the gate passes or fails
     * exactly as it would without the cap.
     */
    fun aggregateEchoPriors(nodes: List<EchoCalibNode>, myModel: String, maxIqrDb: Double, capN: Int): Map<String, Pair<Double, Int>> {
        val modelById = nodes.associate { it.id to it.model }
        val samples = mutableMapOf<String, MutableList<Pair<Double, Int>>>()   // peerModel → (folded value, weight)
        for (node in nodes) for ((peerId, st) in node.peers) {
            val peerModel = modelById[peerId] ?: continue
            if (st.n <= 0 || st.iqr > maxIqrDb) continue
            val w = minOf(st.n, capN)
            when {
                node.model == myModel ->     // Direct: median my model measured on the peer model (+)
                    samples.getOrPut(peerModel) { mutableListOf() } += st.m to w
                peerModel == myModel ->      // Reverse: median the peer model's node measured on my model (folded as −)
                    samples.getOrPut(node.model) { mutableListOf() } += -st.m to w
            }
        }
        return samples.mapValues { (_, s) -> weightedMedian(s) to s.sumOf { it.second } }
    }

    /**
     * Weighted median — the first value whose cumulative weight exceeds half. If the split falls
     * exactly at half, the mean of the two values on either side (2 samples = mean).
     */
    private fun weightedMedian(samples: List<Pair<Double, Int>>): Double {
        val sorted = samples.sortedBy { it.first }
        val total = sorted.sumOf { it.second.toLong() }
        var acc = 0L
        for ((i, s) in sorted.withIndex()) {
            acc += s.second
            if (acc * 2 > total) return s.first
            if (acc * 2 == total) return (s.first + sorted[i + 1].first) / 2
        }
        return sorted.last().first
    }
}
