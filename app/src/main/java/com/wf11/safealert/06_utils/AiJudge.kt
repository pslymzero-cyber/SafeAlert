package com.wf11.safealert.utils

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import com.wf11.safealert.ble.BleConstants
import com.wf11.safealert.service.MotionAnalyzer
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.sqrt

/**
 * On-phone AI judgment (demo). Two small models ship in assets/ai (prox.json: distance, fall.json: fall probability);
 * a training script on a PC replaces the files. DISPLAY AND RECORDING ONLY - nothing here changes an alert, a sound,
 * a check window or an SOS. A missing / broken model file only turns its values off, and every public function
 * swallows its exceptions (null / empty result).
 */
object AiJudge {

    private const val TAG = "AiJudge"

    class Layer(val w: Array<DoubleArray>, val b: DoubleArray, val act: String)

    class Model(
        val kind: String, val made: String, val inputs: List<String>, val fill: DoubleArray, val mean: DoubleArray,
        val std: DoubleArray, val layers: List<Layer>, val clamp: Pair<Double, Double>?, val checks: List<Pair<DoubleArray, Double>>,
    )

    @Volatile var prox: Model? = null
    @Volatile var fall: Model? = null

    private val ACTS = setOf("relu", "tanh", "linear", "sigmoid")

    /** Model JSON -> [Model]; any shape problem (lengths, activation name, clamp) or exception gives null. */
    fun parse(json: String): Model? = try {
        val o = JSONObject(json)
        val inputs = o.getJSONArray("inputs").let { a -> List(a.length()) { a.getString(it) } }
        val n = inputs.size
        val fill = doubles(o.getJSONArray("fill"))
        val mean = doubles(o.getJSONArray("mean"))
        val std = doubles(o.getJSONArray("std"))
        require(n >= 1 && fill.size == n && mean.size == n && std.size == n) { "input lengths" }
        val la = o.getJSONArray("layers")
        require(la.length() >= 1) { "no layers" }
        var prev = n
        val layers = ArrayList<Layer>()
        for (li in 0 until la.length()) {
            val lo = la.getJSONObject(li)
            val b = doubles(lo.getJSONArray("b"))
            val wa = lo.getJSONArray("w")
            require(wa.length() == b.size) { "layer $li rows" }
            val w = Array(wa.length()) { doubles(wa.getJSONArray(it)).also { r -> require(r.size == prev) { "layer $li cols" } } }
            val act = lo.getString("act")
            require(act in ACTS) { "act $act" }
            layers += Layer(w, b, act)
            prev = b.size
        }
        val clamp = o.optJSONArray("clamp")?.let { c ->
            require(c.length() == 2 && c.getDouble(0) < c.getDouble(1)) { "clamp" }
            c.getDouble(0) to c.getDouble(1)
        }
        val checks = ArrayList<Pair<DoubleArray, Double>>()
        o.optJSONArray("checks")?.let { ca ->
            for (i in 0 until ca.length()) {
                val c = ca.getJSONObject(i)
                val x = doubles(c.getJSONArray("x"))
                require(x.size == n) { "check length" }
                checks += x to c.getDouble("y")
            }
        }
        Model(o.optString("kind"), o.optString("made"), inputs, fill, mean, std, layers, clamp, checks)
    } catch (e: Exception) {
        Log.w(TAG, "model parse failed: ${e.message}")
        null
    }

    private fun doubles(a: JSONArray) = DoubleArray(a.length()) { a.getDouble(it) }

    /** Raw input vector in the model's input order; a name missing from [f] is NaN (= use fill). */
    fun Model.vec(f: Map<String, Double>): DoubleArray = DoubleArray(inputs.size) { f[inputs[it]] ?: Double.NaN }

    /** Model output (first output of the last layer, after its activation) for the raw inputs [x]; NaN entries use fill. */
    fun Model.run(x: DoubleArray): Double {
        var h = DoubleArray(x.size) {
            val v = if (x[it].isNaN()) fill[it] else x[it]
            (v - mean[it]) / (if (std[it] == 0.0) 1.0 else std[it])
        }
        for (l in layers) {
            h = DoubleArray(l.b.size) { j ->
                var z = l.b[j]
                for (i in h.indices) z += l.w[j][i] * h[i]
                when (l.act) {
                    "relu" -> max(0.0, z)
                    "tanh" -> Math.tanh(z)
                    "sigmoid" -> 1.0 / (1.0 + Math.exp(-z))
                    else -> z
                }
            }
        }
        return h[0]
    }

    /** App start: reads assets ai/prox.json and ai/fall.json. A missing or mismatching file leaves that model null. */
    fun load(ctx: Context) {
        prox = readModel(ctx, "ai/prox.json", "prox")
        fall = readModel(ctx, "ai/fall.json", "fall")
    }

    private fun readModel(ctx: Context, path: String, kind: String): Model? = try {
        val m = parse(ctx.assets.open(path).bufferedReader().use { it.readText() })
        if (m != null && m.kind != kind) { Log.w(TAG, "$path kind=${m.kind}, expected $kind"); null } else m
    } catch (e: Exception) {
        Log.w(TAG, "$path load failed: ${e.message}")
        null
    }

    // ── Proximity ────────────────────────────────────────────────────────────────────────────

    /**
     * The prox model inputs from one peer's latest P1 frame and its ring (1 s summaries, oldest first), both roles
     * (names as BleConstants.categoryName; a cart is an EPJ, a walker sets neither flag: me_epj · me_fk · peer_epj ·
     * peer_fk) and this phone's model ("mdl:<model>" = 1.0 when [mdl] is not empty; a model column the shipped model
     * does not have is simply unused — [vec] leaves it out). Same names and values as scripts/judge-ml app_model.prox_f.
     */
    fun proxFeatures(
        rssi: Int, med: Int, pre: Int, kf: Int, ema: Int, avg1s: Int, kfVel: Double, ringEma: IntArray, ringVel: DoubleArray,
        myRole: String = "", peerRole: String = "", mdl: String = "",
    ): Map<String, Double> {
        val f = linkedMapOf(
            "rssi" to rssi.toDouble(), "med" to med.toDouble(), "pre" to pre.toDouble(), "kf" to kf.toDouble(),
            "ema" to ema.toDouble(), "avg1s" to avg1s.toDouble(), "kf_vel" to kfVel,
            "me_epj" to (if (myRole == "EPJ") 1.0 else 0.0), "me_fk" to (if (myRole == "FORKLIFT") 1.0 else 0.0),
            "peer_epj" to (if (peerRole == "EPJ") 1.0 else 0.0), "peer_fk" to (if (peerRole == "FORKLIFT") 1.0 else 0.0),
        )
        if (mdl.isNotEmpty()) f["mdl:$mdl"] = 1.0
        val n = ringEma.size
        if (n == 0) {
            val e = ema.toDouble()
            f["seq_mean"] = e; f["seq_std"] = 0.0; f["seq_min"] = e; f["seq_max"] = e; f["seq_slope"] = 0.0; f["seq_vel"] = kfVel
            return f
        }
        val mean = ringEma.sumOf { it.toDouble() } / n
        f["seq_mean"] = mean
        f["seq_std"] = sqrt(ringEma.sumOf { (it - mean) * (it - mean) } / n)
        f["seq_min"] = ringEma.min().toDouble()
        f["seq_max"] = ringEma.max().toDouble()
        f["seq_slope"] = if (n >= 2) (ringEma[n - 1] - ringEma[0]).toDouble() / (n - 1) else 0.0
        // Rounded like the recorded seq ("%.1f") so a training script sees exactly these numbers
        f["seq_vel"] = ringVel.sumOf { String.format(Locale.US, "%.1f", it).toDouble() } / n
        return f
    }

    /** Estimated distance in metres, null without a model or on any problem. */
    fun proxMeters(f: Map<String, Double>): Float? = runCatching {
        val m = prox ?: return null
        val y = m.run(m.vec(f))
        if (!y.isFinite()) return null
        val (lo, hi) = m.clamp ?: (0.3 to 30.0)
        Math.pow(10.0, y).coerceIn(lo, hi).toFloat()
    }.getOrNull()

    /** Same pair rule as JudgeLog.uwb: a forklift on either side uses the forklift distances. */
    fun level(m: Float, fk: Boolean): Int {
        val dang = if (fk) DevSettings.uwbForkliftDangerMeters else DevSettings.uwbPairDangerMeters
        val warn = if (fk) DevSettings.uwbForkliftWarnMeters else DevSettings.uwbPairWarnMeters
        return if (m <= dang) BleConstants.LEVEL_DANGER else if (m <= warn) BleConstants.LEVEL_WARNING else BleConstants.LEVEL_SAFE
    }

    fun proxText(m: Float, level: Int): String {
        val d = if (m < 10f) String.format(Locale.US, "%.1f", m) else Math.round(m).toString()
        val w = when (level) { BleConstants.LEVEL_DANGER -> "위험"; BleConstants.LEVEL_WARNING -> "경고"; else -> "안전" }
        return "약 ${d}m · $w"
    }

    // ── Fall ─────────────────────────────────────────────────────────────────────────────────

    /** [impT] wall-clock impact time, [rulePass] what the rule decided, [decidedAt] wall-clock time of that decision. */
    data class FallAi(val impT: Long, val p: Double, val rulePass: Boolean, val decidedAt: Long)

    @Volatile var lastFall: FallAi? = null

    /** Fall probability 0..1 for one decided impact candidate, null without a model or on any problem. */
    fun fallP(c: MotionAnalyzer.Candidate): Double? = runCatching {
        val m = fall ?: return null
        val f = HashMap<String, Double>()
        f["ff_ms"] = c.ffMs.toDouble(); f["peak_g"] = c.peakG; f["imp_g"] = c.impactG
        c.deg?.let { f["deg"] = it }
        f["post_n"] = c.postN.toDouble(); f["post_act"] = c.postActive.toDouble(); f["pre_ok"] = if (c.preValid) 1.0 else 0.0
        val p = m.run(m.vec(f))
        if (p.isFinite()) p.coerceIn(0.0, 1.0) else null
    }.getOrNull()

    private fun pct(p: Double) = Math.round(p * 100).toInt()

    /** Monitor status line for 60 s after the decision. */
    fun fallLine(now: Long): String? {
        val f = lastFall ?: return null
        if (now - f.decidedAt !in 0..60_000L) return null
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(f.impT))
        return "충격 $t · AI 넘어짐 ${pct(f.p)}% · 규칙: ${if (f.rulePass) "넘어짐" else "넘어짐 아님"}"
    }

    /** Fall check window line for 3 min after the decision. */
    fun checkLine(now: Long): String? {
        val f = lastFall ?: return null
        if (now - f.decidedAt !in 0..180_000L) return null
        return "AI 판단: 넘어짐일 확률 ${pct(f.p)}%"
    }

    // ── Text styling (UI only; no alarm colors) ──────────────────────────────────────────────

    const val SKY = 0xFF7DD3FC.toInt()
    const val REST = 0xFFC3CDDC.toInt()
    const val STRONG = 0xFFF1F5F9.toInt()

    private fun bold(s: Spannable, a: Int, b: Int, color: Int? = null) {
        s.setSpan(StyleSpan(Typeface.BOLD), a, b, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (color != null) s.setSpan(ForegroundColorSpan(color), a, b, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** "AI " + [text]: AI bold sky, the rest light grey, the level word after the last " · " bold white. */
    fun proxSpan(text: String): CharSequence {
        val s = SpannableString("AI $text")
        s.setSpan(ForegroundColorSpan(REST), 0, s.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        bold(s, 0, 2, SKY)
        val i = s.lastIndexOf(" · ")
        if (i >= 0) bold(s, i + 3, s.length, STRONG)
        return s
    }

    /** [line] with its last "AI" bold (and [aiColor] when given) and its last "NN%" bold. */
    fun mark(line: String, aiColor: Int?): CharSequence {
        val s = SpannableString(line)
        val ai = line.lastIndexOf("AI")
        if (ai >= 0) bold(s, ai, ai + 2, aiColor)
        Regex("\\d+%").findAll(line).lastOrNull()?.let { bold(s, it.range.first, it.range.last + 1) }
        return s
    }
}
