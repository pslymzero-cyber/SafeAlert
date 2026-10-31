package com.wf11.safealert.firebase

import android.os.Build
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.wf11.safealert.BuildConfig
import com.wf11.safealert.utils.AiJudge
import com.wf11.safealert.utils.BeaconRegistry
import com.wf11.safealert.utils.DevSettings
import com.wf11.safealert.utils.SiteScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Judge-data records — fall and proximity measurements written to `wf11/judge/<fall|prox>/<SC>/<yyyyMMdd>/<push id>`
 * for offline learning. Recording only: it never changes a judgment, every public function swallows its exceptions,
 * and it does nothing until [sync] switches it on (app start / developer settings). Roles arrive as names, so this
 * layer does not depend on the ble or service packages.
 */
object JudgeLog {

    private const val TAG = "JudgeLog"
    const val V = 1
    const val ACC_MAX = 20_000
    const val SC_PATTERN = "^[A-Z0-9_-]{1,12}\$"
    const val NOSITE = "NOSITE"
    private const val PEERS_MAX = 256
    private const val LEVEL_GAP_MS = 2_000L
    private const val UWB_GAP_MS = 1_000L
    private const val SIG_GAP_MS = 1_000L
    private const val UWB_FRESH_MS = 1_000L
    private const val RING = 10

    /** Recording switch, raw-acceleration switch and experiment session name. Off by default. */
    @Volatile var on = false
    @Volatile var accOn = false
    @Volatile var session = "field"
    /** AI display switch (DevSettings.aiShow): keeps the P1 memory running for the on-phone AI distance even when recording is off. */
    @Volatile var aiShow = false

    /** The Android-side snapshot one record needs. */
    data class Env(
        val site: String, val scope: Map<String, String>, val app: String, val uid: String,
        val fkWarn: Double, val fkDang: Double, val pairWarn: Double, val pairDang: Double,
        val mdl: String = "",
    )

    /** This phone's model ("samsung SM-S918N", at most 40 chars); empty when unknown (JVM tests). */
    private val model: String by lazy {
        runCatching { "${Build.MANUFACTURER.orEmpty()} ${Build.MODEL.orEmpty()}".trim().take(40) }.getOrDefault("")
    }

    /** Null while not signed in: such a record is dropped (the rules require the uid). */
    internal var env: () -> Env? = {
        val uid = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()
        if (uid == null) null else Env(
            DevSettings.siteCode, SiteScope.fields(DevSettings.floor, DevSettings.proc, DevSettings.zone),
            BuildConfig.VERSION_NAME, uid,
            DevSettings.uwbForkliftWarnMeters.toDouble(), DevSettings.uwbForkliftDangerMeters.toDouble(),
            DevSettings.uwbPairWarnMeters.toDouble(), DevSettings.uwbPairDangerMeters.toDouble(), model,
        )
    }

    /** Where a finished record goes. A failed write is only logged. */
    internal var sink: (kind: String, rec: Map<String, Any>) -> Unit = { kind, rec ->
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date((rec["t"] as? Long) ?: System.currentTimeMillis()))
        FirebaseDatabase.getInstance().reference.child(DevSettings.FIREBASE_ROOT).child("judge").child(kind)
            .child(scOf(rec["site"] as? String ?: "")).child(day).push().setValue(rec)
            .addOnFailureListener { Log.w(TAG, "판정 기록 실패: ${it.message}") }
    }

    /** Applies the developer settings (app start and every change of the three rows). */
    fun sync() {
        runCatching {
            on = DevSettings.judgeLogEnabled
            accOn = DevSettings.judgeLogAcc
            session = DevSettings.judgeLogSession
            aiShow = DevSettings.aiShow
        }
    }

    private val SC_RE = Regex(SC_PATTERN)

    fun scOf(site: String): String = if (SC_RE.matches(site)) site else NOSITE

    /** One record as a flat map (pure — the test target). Null body values are left out. */
    internal fun rec(k: String, t: Long, me: String, role: String, e: Env, body: Map<String, Any?>): Map<String, Any> {
        val m = LinkedHashMap<String, Any>()
        m["v"] = V
        m["k"] = k
        m["t"] = t
        m["dev"] = FirebaseManager.withSite(me, e.site)
        m["role"] = role
        m["site"] = e.site
        m.putAll(e.scope)
        m["app"] = e.app
        m["sess"] = session
        m["uid"] = e.uid
        if (e.mdl.isNotEmpty()) m["mdl"] = e.mdl
        for ((key, v) in body) if (v != null) m[key] = v
        return m
    }

    private fun emit(kind: String, k: String, t: Long, me: String, role: String, body: (Env) -> Map<String, Any?>) {
        val e = env() ?: return
        sink(kind, rec(k, t, me, role, e, body(e)))
    }

    private fun r2(d: Double) = Math.round(d * 100.0) / 100.0

    // ── P1: last frame per peer (memory only) ────────────────────────────────────────────────

    private class Sec(var sec: Long, var ema: Int, var vel: Double)

    private class Peer {
        var myRole = ""; var peerRole = ""
        var rssi = 0; var med = 0; var pre = 0; var kf = 0; var kfVel = 0.0; var ema = 0; var avg1s = 0
        var offPl = 0; var offBc = 0; var offGg = 0; var echo = 0; var effW = 0; var effD = 0
        var levelAt = Long.MIN_VALUE
        var uwbAt = Long.MIN_VALUE
        var uwbM = 0f; var uwbMAt = Long.MIN_VALUE
        var frameAt = Long.MIN_VALUE   // time of the last P1 frame — a record's age against it tells how stale the frame values are
        var uwbUse = ""                // use of the last uwb sample (judge | measure)
        var sigAt = Long.MIN_VALUE     // time of the last P5 sig record
        var aiM = 0f; var aiAt = Long.MIN_VALUE   // on-phone AI distance and when it was computed
        val ring = ArrayDeque<Sec>()
    }

    // Least recently seen peer goes first; one lock for frame / level / uwb (UWB samples and scan frames may differ in thread)
    private val peers = object : LinkedHashMap<String, Peer>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Peer>?) = size > PEERS_MAX
    }

    /**
     * P1 — called on every processAlert frame: keeps the latest values and a 10 s ring of 1 s summaries. Emits only
     * P5 sig (one per peer per second) while the experiment session starts with `test:`; in the field session it never emits.
     */
    fun frame(
        me: String, peer: String, myRole: String, peerRole: String, t: Long, rssi: Int, med: Int, pre: Int, kf: Int, kfVel: Double,
        ema: Int, avg1s: Int, offPl: Int, offBc: Int, offGg: Int, echo: Int, effW: Int, effD: Int,
    ) {
        if (!on && !aiShow) return
        runCatching {
            val sig = synchronized(this) {
                val p = peers.getOrPut(peer) { Peer() }
                p.myRole = myRole; p.peerRole = peerRole; p.frameAt = t
                p.rssi = rssi; p.med = med; p.pre = pre; p.kf = kf; p.kfVel = kfVel; p.ema = ema; p.avg1s = avg1s
                p.offPl = offPl; p.offBc = offBc; p.offGg = offGg; p.echo = echo; p.effW = effW; p.effD = effD
                val sec = t / 1000
                val last = p.ring.lastOrNull()
                if (last != null && last.sec == sec) {
                    last.ema = ema; last.vel = kfVel
                } else if (p.ring.size >= RING) {
                    // Reuse the oldest entry: no allocation per frame in steady state (runs on the scan callback)
                    p.ring.removeFirst().also { it.sec = sec; it.ema = ema; it.vel = kfVel }.let { p.ring.addLast(it) }
                } else {
                    p.ring.addLast(Sec(sec, ema, kfVel))
                }
                // AI distance at most once per peer per second (scan callback thread); !in also covers the first call and a clock step back
                if (AiJudge.prox != null && t - p.aiAt !in 0 until 1_000L) {
                    val n = p.ring.size
                    val ringEma = IntArray(n); val ringVel = DoubleArray(n)
                    p.ring.forEachIndexed { i, s -> ringEma[i] = s.ema; ringVel[i] = s.vel }
                    AiJudge.proxMeters(AiJudge.proxFeatures(rssi, med, pre, kf, ema, avg1s, kfVel, ringEma, ringVel, mdl = model))?.let { p.aiM = it; p.aiAt = t }
                }
                // P5 sig: experiment sessions only, at most once per peer per second (no allocation otherwise)
                if (!on || !session.startsWith("test:") || (p.sigAt != Long.MIN_VALUE && t - p.sigAt < SIG_GAP_MS)) return@synchronized null
                p.sigAt = t
                val fk = p.myRole == "FORKLIFT" || p.peerRole == "FORKLIFT"
                Triple(p.myRole, fk, proxBody(p, t).apply {
                    put("frame_age_ms", 0L)
                    if (p.uwbMAt != Long.MIN_VALUE && t - p.uwbMAt in 0..UWB_FRESH_MS) { put("uwb_m", r2(p.uwbM.toDouble())); put("uwb_use", p.uwbUse) }
                })
            }
            // warn_m / dang_m are always attached (also without a UWB sample): the analysis grades bundle-truth rows with them
            if (sig != null) emitProx("sig", t, me, sig.first, peer, sig.third) { e ->
                mapOf("warn_m" to r2(if (sig.second) e.fkWarn else e.pairWarn), "dang_m" to r2(if (sig.second) e.fkDang else e.pairDang))
            }
        }
    }

    /** The AI distance line ("약 2.6m · 위험") for one peer, empty when there is no AI value at most 2 s old. */
    fun aiText(peer: String, now: Long): String = runCatching {
        synchronized(this) {
            val p = peers[peer] ?: return@synchronized ""
            if (now - p.aiAt !in 0..2_000L) return@synchronized ""
            AiJudge.proxText(p.aiM, AiJudge.level(p.aiM, p.myRole == "FORKLIFT" || p.peerRole == "FORKLIFT"))
        }
    }.getOrDefault("")

    private fun proxBody(p: Peer, t: Long): LinkedHashMap<String, Any?> = linkedMapOf<String, Any?>(
        "peer_role" to p.peerRole,
        "rssi" to p.rssi, "med" to p.med, "pre" to p.pre, "kf" to p.kf, "kf_vel" to r2(p.kfVel),
        "ema" to p.ema, "avg1s" to p.avg1s, "off_pl" to p.offPl, "off_bc" to p.offBc, "off_gg" to p.offGg,
        "echo" to p.echo, "eff_w" to p.effW, "eff_d" to p.effD,
        "seq" to p.ring.joinToString(";") { "${it.ema},${String.format(Locale.US, "%.1f", it.vel)}" },
    ).apply {
        // On-phone AI distance (display model) when one at most 2 s old exists — recorded next to the rule values
        if (t - p.aiAt in 0..2_000L) {
            put("ai_m", r2(p.aiM.toDouble()))
            put("ai_lv", AiJudge.level(p.aiM, p.myRole == "FORKLIFT" || p.peerRole == "FORKLIFT"))
            put("ai_ver", AiJudge.prox?.made)
        }
    }

    /** Sends one proximity record. Runs OUTSIDE the lock: the peer id needs the site, which comes from [env]. */
    private fun emitProx(
        k: String, t: Long, me: String, role: String, peer: String, body: Map<String, Any?>,
        extra: (Env) -> Map<String, Any?> = { emptyMap() },
    ) {
        val e = env() ?: return
        val peerId = linkedMapOf<String, Any?>("peer" to FirebaseManager.withSite(BeaconRegistry.shortFullId(peer), e.site))
        sink("prox", rec(k, t, me, role, e, peerId + body + extra(e)))
    }

    /** P2 — a proximity level changed (prev → level). At most one per peer per 2 s; needs the peer's frame memory. */
    fun level(me: String, peer: String, prev: Int, level: Int, t: Long) {
        if (!on) return
        runCatching {
            val (role, body) = synchronized(this) {
                val p = peers[peer] ?: return
                if (p.levelAt != Long.MIN_VALUE && t - p.levelAt < LEVEL_GAP_MS) return
                p.levelAt = t
                p.myRole to proxBody(p, t).apply { put("prev", prev); put("level", level) }
            }
            emitProx("level", t, me, role, peer, body)
        }
    }

    /**
     * P3 — one UWB distance sample with this pair's warn / danger distances and the level after judgment. At most 1 per peer per 1 s.
     * use = "judge" (the sample fed the decision) or "measure" (record-only, kept below the start gate; level is then just the current one).
     * frame_age_ms = how long ago the last P1 frame (the rssi / ema values on the record) was taken.
     */
    fun uwb(me: String, peer: String, uwbM: Float, level: Int, t: Long, use: String = "judge") {
        if (!on) return
        runCatching {
            val (role, fk, body) = synchronized(this) {
                val p = peers[peer] ?: return
                // Every sample, before the gap check: markDanger and dist read the latest one
                p.uwbM = uwbM; p.uwbMAt = t; p.uwbUse = use
                if (p.uwbAt != Long.MIN_VALUE && t - p.uwbAt < UWB_GAP_MS) return
                p.uwbAt = t
                // Same pair rule as AlertStateMachine.judgeUwbOnly
                val fk = p.myRole == "FORKLIFT" || p.peerRole == "FORKLIFT"
                Triple(p.myRole, fk, proxBody(p, t).apply { put("uwb_m", r2(uwbM.toDouble())); put("level", level); put("use", use); put("frame_age_ms", t - p.frameAt) })
            }
            emitProx("uwb", t, me, role, peer, body) { e ->
                mapOf("warn_m" to r2(if (fk) e.fkWarn else e.pairWarn), "dang_m" to r2(if (fk) e.fkDang else e.pairDang))
            }
        }
    }

    /**
     * The values of a DANGER start for the distance question: start_rssi, start_ema, start_frame_age_ms and, when a UWB
     * distance at most 1 s old exists, start_uwb_m. Null when off or the peer has no frame memory. Never emits or keeps:
     * the one question that gets asked holds this one map until its answer ([dist]).
     * start_frame_age_ms = age of that frame at the danger start (a Case A danger can start on a UWB sample while the frame is seconds old).
     */
    fun markDanger(peer: String, t: Long): Map<String, Any?>? {
        if (!on) return null
        return runCatching {
            synchronized(this) {
                val p = peers[peer] ?: return@synchronized null
                val m = linkedMapOf<String, Any?>("start_rssi" to p.rssi, "start_ema" to p.ema, "start_frame_age_ms" to t - p.frameAt)
                if (p.uwbMAt != Long.MIN_VALUE && t - p.uwbMAt in 0..UWB_FRESH_MS) m["start_uwb_m"] = r2(p.uwbM.toDouble())
                m
            }
        }.getOrNull()
    }

    /**
     * P4 - the distance answer (lt1 | 1to3 | 3to5 | 5to8 | 8to12 | 12to15 | gt15 | unknown; gt5 = before 1.2.27, kept only for reading old records) given on the alert sidebar block, as one record:
     * the values of the tap moment (that peer's P1 memory incl. the 10 s seq, frame_age_ms, and uwb_m / uwb_use when a
     * sample at most 1 s old exists), the times (alert_t, shown_ms = first shown - alert_t, after_ms = tap - alert_t)
     * and the DANGER-start values ([markDanger]). Built under the lock, sent outside it.
     */
    fun dist(
        me: String, role: String, peer: String, alertT: Long, shownAt: Long, ans: String, start: Map<String, Any?>?,
        t: Long = System.currentTimeMillis(),
    ) {
        if (!on) return
        runCatching {
            val now = synchronized(this) {
                val p = peers[peer] ?: return@synchronized emptyMap<String, Any?>()
                proxBody(p, t).apply {
                    put("frame_age_ms", t - p.frameAt)
                    if (p.uwbMAt != Long.MIN_VALUE && t - p.uwbMAt in 0..UWB_FRESH_MS) { put("uwb_m", r2(p.uwbM.toDouble())); put("uwb_use", p.uwbUse) }
                }
            }
            emitProx(
                "dist", t, me, role, peer,
                linkedMapOf<String, Any?>("alert_t" to alertT, "shown_ms" to shownAt - alertT, "after_ms" to t - alertT, "ans" to ans) + now + (start ?: emptyMap()),
            )
        }
    }

    // ── Fall records ─────────────────────────────────────────────────────────────────────────

    /** F1 — the 12 s decision of one impact candidate (pass or drop). acc / accT0 / accDt only when raw acceleration was kept. */
    fun fallCand(
        me: String, role: String, impT: Long, ffMs: Long, peakG: Double, impG: Double, deg: Double?, postN: Int,
        postAct: Int, preOk: Boolean, pass: Boolean, why: String, acc: String?, accT0: Long?, accDt: Double?,
        aiP: Double? = null, t: Long = System.currentTimeMillis(),
    ) {
        if (!on) return
        runCatching {
            emit("fall", "cand", t, me, role) {
                linkedMapOf(
                    "imp_t" to impT, "ff_ms" to ffMs, "peak_g" to r2(peakG), "imp_g" to r2(impG), "deg" to deg?.let(::r2),
                    "post_n" to postN, "post_act" to postAct, "pre_ok" to preOk, "pass" to pass, "why" to why,
                    "acc" to acc, "acc_t0" to accT0, "acc_dt" to accDt?.let(::r2),
                    "ai_p" to aiP?.let(::r2), "ai_ver" to (if (aiP != null) AiJudge.fall?.made else null),
                )
            }
        }
    }

    /** F2 — what onAccident did with one FALL or not-a-fall impact: watch (suspicion opened) | shut | dropped. pass = the fall rule's result. */
    fun fallTake(
        me: String, role: String, impT: Long, take: String, zoneIn: Boolean, charging: Boolean,
        plugAgoMs: Long?, unplugAgoMs: Long?, pass: Boolean = true, t: Long = System.currentTimeMillis(),
    ) {
        if (!on) return
        runCatching {
            emit("fall", "fall", t, me, role) {
                linkedMapOf(
                    "imp_t" to impT, "take" to take, "pass" to pass, "zone_in" to zoneIn, "charging" to charging,
                    "plug_ago_ms" to plugAgoMs, "unplug_ago_ms" to unplugAgoMs,
                )
            }
        }
    }

    /** F3 — a monitoring mode change. why only when closing (or restore on a restored SOS). The answer is a separate [truth] record. */
    fun mode(
        me: String, role: String, from: String, to: String, trig: String, why: String, sinceMs: Long, impT: Long?,
        pass: Boolean? = null, t: Long = System.currentTimeMillis(),
    ) {
        if (!on) return
        runCatching {
            emit("fall", "mode", t, me, role) {
                linkedMapOf(
                    "from" to from, "to" to to, "trig" to trig, "pass" to pass, "why" to why.ifEmpty { null },
                    "since_ms" to sinceMs, "imp_t" to impT,
                )
            }
        }
    }

    /**
     * F4 — the fall question / impact question answer (fall | no | unknown) for one impact or closed fall episode.
     * act = what the impact was (walk | run | put_down | drop | charge | fall | unknown); null for the rescuer 3-button answer.
     * force = an answer to a question that was posted while recording was on: recorded even if recording was turned off
     * in between, so "기록했어요" is always true (user decision 2026-10-30).
     */
    fun truth(
        me: String, role: String, impT: Long, truth: String, by: String, afterMs: Long, closeWhy: String,
        t: Long = System.currentTimeMillis(), act: String? = null, force: Boolean = false,
    ) {
        if (!on && !force) return
        runCatching {
            emit("fall", "truth", t, me, role) {
                linkedMapOf(
                    "imp_t" to impT, "truth" to truth, "by" to by, "after_ms" to afterMs, "close_why" to closeWhy.ifEmpty { null },
                    "act" to act,
                )
            }
        }
    }
}
