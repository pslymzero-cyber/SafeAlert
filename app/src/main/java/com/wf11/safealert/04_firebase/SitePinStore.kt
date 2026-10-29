package com.wf11.safealert.firebase

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.wf11.safealert.utils.DevSettings
import com.wf11.safealert.utils.SitePin
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Site PIN check values: a local cache (prefs "site_pin", separate from dev_settings so a settings reset keeps it)
 * and the server copy at wf11/pin/<SC>. Every Firebase call is wrapped, so a missing Firebase (unit tests) counts as offline.
 * All callbacks arrive on the main thread; PBKDF2 never runs there.
 * ponytail: a write that times out offline stays in Firebase's write queue and may still land later; the next
 * fetch shows the server state, so nothing is lost or wrong.
 */
object SitePinStore {
    private const val PREFS = "site_pin"
    private const val CONNECT_MS = 4_000L
    private const val GET_MS = 6_000L
    private const val WRITE_MS = 10_000L

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    /** Runs PBKDF2 work off the main thread; tests may run it inline. */
    @VisibleForTesting
    var runner: (() -> Unit) -> Unit = { executor.execute(it) }

    private fun root(): DatabaseReference =
        FirebaseDatabase.getInstance().reference.child(DevSettings.FIREBASE_ROOT)

    // ---- cache ----

    fun cached(ctx: Context, sc: String): SitePin.Pub? = runCatching {
        val j = JSONObject(ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("pub_$sc", null) ?: return null)
        SitePin.Pub(j.getString("salt"), j.getString("hash"), j.getInt("iter"), j.getLong("ver"), j.getLong("at"), j.getString("by"))
    }.getOrNull()

    fun saveCache(ctx: Context, sc: String, pub: SitePin.Pub) {
        val j = JSONObject().put("salt", pub.salt).put("hash", pub.hash).put("iter", pub.iter)
            .put("ver", pub.ver).put("at", pub.at).put("by", pub.by)
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("pub_$sc", j.toString()).apply()
    }

    fun clearCache(ctx: Context, sc: String) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("pub_$sc").apply()
    }

    // ---- server ----

    /**
     * Waits up to 4 s for ".info/connected" to turn true; an initial false does not end the wait. Calls back once, on main.
     * Android RTDB drops an idle connection after 60 s and an .info listener does not reopen it, so a real read of the
     * public /version node is started first to wake the connection.
     */
    fun checkOnline(cb: (Boolean) -> Unit) {
        val done = AtomicBoolean(false)
        var ref: DatabaseReference? = null
        var listener: ValueEventListener? = null
        lateinit var timeout: Runnable
        fun finish(online: Boolean) {
            if (!done.compareAndSet(false, true)) return
            main.removeCallbacks(timeout)
            runCatching { listener?.let { ref?.removeEventListener(it) } }
            cb(online)
        }
        timeout = Runnable { finish(false) }
        val ok = runCatching {
            FirebaseDatabase.getInstance().getReference("version").get()
            ref = FirebaseDatabase.getInstance().getReference(".info/connected")
            listener = object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) { if (s.getValue(Boolean::class.java) == true) main.post { finish(true) } }
                override fun onCancelled(e: DatabaseError) { main.post { finish(false) } }
            }
            ref!!.addValueEventListener(listener!!)
        }.isSuccess
        if (ok) main.postDelayed(timeout, CONNECT_MS) else main.post { finish(false) }
    }

    /** The PIN state of a site: server when reachable (and cached), otherwise the cache. */
    fun fetch(ctx: Context, sc: String, cb: (SitePin.State) -> Unit) {
        val app = ctx.applicationContext
        fun offline() { cb(cached(app, sc)?.let { SitePin.State.Has(it) } ?: SitePin.State.Unknown) }
        checkOnline { online ->
            if (!online) return@checkOnline offline()
            val done = AtomicBoolean(false)
            lateinit var timeout: Runnable
            fun once(block: () -> Unit) { if (done.compareAndSet(false, true)) { main.removeCallbacks(timeout); block() } }
            timeout = Runnable { once { offline() } }
            val started = runCatching {
                root().child("pin").child(sc).child("pub").get()
                    .addOnSuccessListener { snap -> once {
                        if (!snap.exists()) { clearCache(app, sc); cb(SitePin.State.None) }
                        else {
                            val pub = parse(snap)
                            if (pub == null) offline() else { saveCache(app, sc, pub); cb(SitePin.State.Has(pub)) }
                        }
                    } }
                    .addOnFailureListener { once { offline() } }
            }.isSuccess
            if (started) main.postDelayed(timeout, GET_MS) else once { offline() }
        }
    }

    private fun parse(s: DataSnapshot): SitePin.Pub? = runCatching {
        SitePin.Pub(
            s.child("salt").getValue(String::class.java)!!, s.child("hash").getValue(String::class.java)!!,
            s.child("iter").getValue(Long::class.java)!!.toInt(), s.child("ver").getValue(Long::class.java)!!,
            s.child("at").getValue(Long::class.java)!!, s.child("by").getValue(String::class.java)!!
        )
    }.getOrNull()

    /** PBKDF2 decision off the main thread. */
    fun judge(input: String, state: SitePin.State, cb: (SitePin.Verdict) -> Unit) {
        // A malformed cached record must not leave the dialog waiting forever: drop it and ask for the server.
        runner { val v = runCatching { SitePin.check(input, state) }.getOrDefault(SitePin.Verdict.NeedOnline); main.post { cb(v) } }
    }

    /** Who made the change: the worker code if there is one, else the device model. */
    fun byName(): String =
        (runCatching { DevSettings.workerCode }.getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL?.takeIf { it.isNotBlank() } ?: "android").take(40)

    fun writeFirst(ctx: Context, sc: String, pin: String, by: String, cb: (Boolean) -> Unit) =
        write(ctx, sc, pin, by, null, null, cb)

    fun writeChange(ctx: Context, sc: String, curPin: String, curPub: SitePin.Pub, newPin: String, by: String, cb: (Boolean) -> Unit) =
        write(ctx, sc, newPin, by, curPin, curPub, cb)

    private fun write(ctx: Context, sc: String, newPin: String, by: String, curPin: String?, curPub: SitePin.Pub?, cb: (Boolean) -> Unit) {
        val app = ctx.applicationContext
        checkOnline { online ->
            if (!online) return@checkOnline cb(false)
            runner {
                val rec = SitePin.newRecord(newPin, (curPub?.ver ?: 0L) + 1, by, SecureRandom())
                val proof = if (curPin != null && curPub != null) SitePin.gateOf(curPin, curPub.salt, curPub.iter) else null
                main.post { send(app, sc, rec, proof, cb) }
            }
        }
    }

    private fun send(app: Context, sc: String, rec: SitePin.NewRecord, proof: String?, cb: (Boolean) -> Unit) {
        val uid = SosRemote.currentUid()
        if (uid == null) return cb(false)
        val done = AtomicBoolean(false)
        lateinit var timeout: Runnable
        fun once(ok: Boolean) {
            if (!done.compareAndSet(false, true)) return
            main.removeCallbacks(timeout)
            if (ok) saveCache(app, sc, rec.pub.copy(at = System.currentTimeMillis()))
            cb(ok)
        }
        timeout = Runnable { once(false) }
        val started = runCatching {
            val r = root()
            val p = rec.pub
            val node = hashMapOf<String, Any>(
                "pub" to mapOf("salt" to p.salt, "hash" to p.hash, "iter" to p.iter, "ver" to p.ver, "at" to ServerValue.TIMESTAMP, "by" to p.by),
                "sec" to mapOf("gate" to rec.gate)
            )
            if (proof != null) node["proof"] = proof
            val log = mapOf("ver" to p.ver, "at" to ServerValue.TIMESTAMP, "by" to p.by, "uid" to uid)
            val key = r.child("pinlog").child(sc).push().key ?: error("no key")
            r.updateChildren(mapOf("pin/$sc" to node, "pinlog/$sc/$key" to log)) { err, _ -> once(err == null) }
        }.isSuccess
        if (started) main.postDelayed(timeout, WRITE_MS) else once(false)
    }
}
