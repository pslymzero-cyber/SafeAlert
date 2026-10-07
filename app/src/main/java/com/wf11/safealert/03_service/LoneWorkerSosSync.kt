package com.wf11.safealert.service

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.wf11.safealert.BuildConfig
import com.google.firebase.database.ServerValue
import com.wf11.safealert.firebase.FirebaseConfig
import com.wf11.safealert.firebase.SosRemote
import com.wf11.safealert.utils.DevSettings
import com.wf11.safealert.utils.SiteScope
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * All server-side work for the lone-worker SOS: persisting, uploading and clearing my SOS is delegated to [SosLedger];
 * this class wires SharedPreferences and Firebase into that interface and reconnects peer reception.
 *
 * My SOS ends only with "괜찮아요", so it must survive service stop, role switch, restart and process death.
 * The clear is written to the saved path (the path is not rebuilt from the current site code).
 *
 * The constructor only stores references (SharedPreferences and the ledger are created on first use). All entry points are
 * called on the main thread; Firebase callbacks are re-posted to main via handler before reaching the ledger.
 *
 * Once the ledger confirms the server save, the SOS is queued in [SosMail] and sent to the mail script on a background thread.
 */
class LoneWorkerSosSync(
    private val ctx: Context,
    private val handler: Handler,
    private val onPeer: (SosRemote.SosRecord) -> Unit,
    private val onChange: () -> Unit
) {
    companion object {
        private const val FILE = "lone_worker_sos"

        /**
         * Whether a saved (not yet cleared) own SOS exists. Used to decide on restore when the service starts in an empty process.
         */
        fun hasStoredSos(ctx: Context): Boolean = runCatching {
            ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(SosLedger.K_TRIGGER, null) != null
        }.getOrDefault(false)

        private const val TAG = "SosMail"
        private const val K_TO = "to"

        /**
         * Mail script URL (injected at build time). Empty, meaning mail off, unless it is a web app deployment URL (…/macros/s/<id>/exec).
         */
        val mailUrl: String = SosMail.scriptUrl(BuildConfig.SOS_MAIL_URL)
        val mailEnabled: Boolean get() = mailUrl.isNotEmpty()

        // Per-site-code file (same naming rule as DevSettings.sitePrefName). Missing key = default URL, empty value = don't send.
        private fun mailPrefs(ctx: Context, sc: String): SharedPreferences =
            ctx.getSharedPreferences(if (sc.isEmpty()) "sos_mail" else "sos_mail_" + sc, Context.MODE_PRIVATE)

        fun mailTo(ctx: Context, sc: String): String =
            runCatching { mailPrefs(ctx, sc).getString(K_TO, null) }.getOrNull() ?: SosMail.DEFAULT_TO

        fun setMailTo(ctx: Context, sc: String, v: String) {
            mailPrefs(ctx, sc).edit().putString(K_TO, v.trim()).apply()
        }

        private val mailExec: ExecutorService by lazy { Executors.newSingleThreadExecutor() }
        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
        /** A peer's one-hour release is written this long after it, past the server-time margin of the rules. */
        const val PEER_RELEASE_DELAY_MS = 60_000L

        /**
         * Store backed by the ledger file. Writes use commit (synchronous), so they survive dying
         * right after the write. sync = false uses apply (values that may be lost).
         */
        private fun kvOf(ctx: Context, sync: Boolean = true): SosKv = object : SosKv {
            private val prefs: SharedPreferences by lazy { ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

            // Ignore values an earlier dev build stored with a different type
            override fun get(k: String): String? = runCatching { prefs.getString(k, null) }.getOrNull()
            override fun put(changes: Map<String, String?>) {
                val e = prefs.edit()
                for ((k, v) in changes) if (v == null) e.remove(k) else e.putString(k, v)
                if (sync) e.commit() else e.apply()
            }
        }

        // Mail is a secondary channel: failures are swallowed as null and the result goes back to the queue on main. Log the response code only.
        private val postMail: (String, (String?) -> Unit) -> Unit = { form, done ->
            val url = mailUrl
            mailExec.execute {
                val body = runCatching { httpPost(url, form) }.getOrNull()
                Log.i(TAG, "mail request: " + (SosMail.code(body) ?: "no response"))
                mainHandler.post { done(body) }
            }
        }

        private var mailQueue: SosMail? = null

        /**
         * The process-wide single mail queue. Call on the main thread only.
         * The monitoring tick and the scheduled job (SosMailJob) share this instance, so the same item is never sent twice.
         */
        fun mail(ctx: Context): SosMail = mailQueue ?: ctx.applicationContext.let { app ->
            SosMail(kvOf(app), postMail, System::currentTimeMillis, { SosMailJob.sync(app, it) }) { sc -> mailTo(app, sc) }
        }.also { mailQueue = it }

        /** On a 302 after POST, follows Location (https only) once with GET to fetch the result body. Otherwise null. */
        private fun httpPost(url: String, form: String): String? {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 10_000
                c.readTimeout = 30_000
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                c.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                if (code == 200) return c.inputStream.bufferedReader().use { it.readText() }
                if (code !in 300..399) return null
                val loc = c.getHeaderField("Location") ?: return null
                if (!loc.startsWith("https://")) return null
                val g = URL(loc).openConnection() as HttpURLConnection
                try {
                    g.instanceFollowRedirects = true
                    g.connectTimeout = 10_000
                    g.readTimeout = 30_000
                    return if (g.responseCode == 200) g.inputStream.bufferedReader().use { it.readText() } else null
                } finally {
                    g.disconnect()
                }
            } finally {
                c.disconnect()
            }
        }
    }

    private val kv = kvOf(ctx)

    private val transport = object : SosTransport {
        override fun uid(): String? {
            SosRemote.currentUid()?.let { return it }
            FirebaseConfig.ensureSignedIn()
            return null
        }

        override fun sitePath(): String? {
            val site = DevSettings.siteCode
            return if (site.isEmpty()) null else SosRemote.nodePath(DevSettings.FIREBASE_ROOT, site)
        }

        override fun newKey(path: String): String = SosRemote.newKey(path)

        // Write only the fields the database rules allow: the episode goes in the
        // optional field ep; the beacon short ID stays out of the server record
        override fun create(path: String, key: String, rec: SosLedger.Record, uid: String, done: (Boolean) -> Unit) {
            val payload = SosRemote.recordPayload(
                rec.bleId, rec.name, rec.role, rec.trigger, rec.beacon, rec.beaconRssi, uid, ServerValue.TIMESTAMP, rec.ep,
                rec.floor, rec.proc
            )
            SosRemote.create(path, key, payload) { ok -> handler.post { done(ok) } }
        }

        override fun resolve(path: String, key: String, auto: Boolean, done: (Boolean) -> Unit) {
            SosRemote.resolve(path, key, auto) { ok -> handler.post { done(ok) } }
        }

        override fun read(path: String, key: String, done: (SosLedger.Remote) -> Unit) {
            SosRemote.read(path, key) { ok, rec ->
                val mine = SosRemote.currentUid()
                val r = when {
                    !ok -> SosLedger.Remote.ERROR
                    rec == null -> SosLedger.Remote.ABSENT
                    mine == null || rec.uid != mine -> SosLedger.Remote.OTHER
                    rec.active -> SosLedger.Remote.MINE_ACTIVE
                    else -> SosLedger.Remote.MINE_RESOLVED
                }
                handler.post { done(r) }
            }
        }
    }

    private val ledger by lazy {
        SosLedger(kv, transport, SystemClock::elapsedRealtime, { event, path, key ->
            if (mailEnabled) mail(ctx).enqueue(event, path, key, DevSettings.lwStillMin)
        }, onChange = { onChange() })
    }

    // Liveness record while monitoring — record only; failures are ignored, unrelated to alerts and judgment
    private val hb by lazy {
        LoneWorkerHeartbeat(object : HbRemote {
            override fun uid(): String? = transport.uid()
            override fun path(): String? {
                val site = DevSettings.siteCode
                return if (site.isEmpty()) null else SosRemote.hbPath(DevSettings.FIREBASE_ROOT, site)
            }
            override fun newKey(path: String): String = SosRemote.newKey(path)
            override fun serverNow(): Long = SosRemote.serverNowMs() ?: System.currentTimeMillis()
            override fun scope(): Map<String, Any> = SiteScope.fields(DevSettings.floor, DevSettings.proc)
            override fun update(path: String, key: String, fields: Map<String, Any>, done: (Boolean) -> Unit) {
                runCatching { SosRemote.update(path, key, fields) { ok -> handler.post { done(ok) } } }
                    .onFailure { done(false) }
            }
        }, kvOf(ctx, sync = false), SystemClock::elapsedRealtime)
    }

    /**
     * Called every monitoring tick (10 s). on = whether lone-worker monitoring is enabled, role = role category (WALKER etc.).
     */
    fun heartbeat(on: Boolean, role: String) = hb.tick(on, role)

    /** Records the session end when monitoring stops normally. */
    fun endHeartbeat() = hb.end()

    private var remover: (() -> Unit)? = null
    /** Node plus the reception scope in effect; a change re-attaches so the replayed snapshot is filtered anew. */
    private var listenKey = ""
    /** Node each peer record was received from (server key -> path), for its one-hour release. */
    private val peerPaths = HashMap<String, String>()
    private var generation = 0

    // ── Own SOS persistence / upload (delegated to the ledger) ───

    /**
     * Reads the saved state at start. Returns the trigger of an uncleared SOS, or null. A pending clear is sent by the next tick.
     */
    fun restoredTrigger(): String? = ledger.restoredTrigger()

    /** SOS entry. If an SOS is already saved (restored), it is kept as is so no second record is created. */
    fun begin(
        bleId: String, name: String, role: String, trigger: String,
        beacon: String?, beaconRssi: Int?, beaconSid: Int
    ) = ledger.begin(
        SosLedger.Record(
            bleId, name, role, trigger, beacon, beaconRssi, beaconSid,
            floor = DevSettings.floor, proc = DevSettings.proc
        )
    )

    /** Server upload status text; null without an own SOS. */
    fun statusText(): String? = ledger.statusText()

    /**
     * Clear: "괜찮아요", or auto = the one-hour limit. The active slot empties at once and the clear is retried until
     * confirmed.
     */
    fun resolve(auto: Boolean = false) = ledger.resolve(auto)

    /** How long my SOS's server record has been up, or null without one or before the server confirmed it. */
    fun sosActiveForMs(): Long? = ledger.activeForMs()

    /**
     * Records the one-hour release of another phone's SOS on the server, on the node the record came from: its writer may
     * be gone, and while the record stays active every phone that starts replays it. Written PEER_RELEASE_DELAY_MS later so
     * the rules' hour (server time) has surely passed. Best effort — a record already resolved simply refuses the write.
     */
    fun autoResolvePeer(key: String) {
        val path = peerPaths[key] ?: return
        handler.postDelayed({ SosRemote.resolve(path, key, auto = true) { } }, PEER_RELEASE_DELAY_MS)
    }

    /** Whether the clear has not reached the server yet and is being resent. */
    fun resolveFailing(): Boolean = ledger.resolveFailing()

    /** Own SOS episode number (0 if none) and beacon short ID (0 if none). Used for the BLE advertisement extension. */
    fun episode(): Int = ledger.episode()
    fun hint(): Int = ledger.hint()

    // ── Peer reception ────────────────────────────────────────

    /**
     * Called every 10 s. Re-attaches to the new node if the site code or root changed,
     * and attaches if not attached (cancelled, or not signed in yet). Upload and clear retries also run here.
     */
    fun tick() {
        attachIfNeeded()
        ledger.tick()
        if (mailEnabled) mail(ctx).tick()
    }

    private fun attachIfNeeded() {
        val site = DevSettings.siteCode
        val path = if (site.isEmpty()) "" else SosRemote.nodePath(DevSettings.FIREBASE_ROOT, site)
        val all = DevSettings.sosAllSite
        val f = DevSettings.floor
        val p = DevSettings.proc
        val key = if (path.isEmpty()) "" else "$path|$all|$f|$p"
        if (remover != null && key != listenKey) detach()
        if (remover != null || key.isEmpty()) return
        if (SosRemote.currentUid() == null) {
            FirebaseConfig.ensureSignedIn()
            return
        }
        val gen = ++generation
        listenKey = key
        remover = SosRemote.listen(
            path,
            { rec ->
                handler.post {
                    // Skip my own records (including those under my bleId from before a role switch)
                    val mine = SosRemote.currentUid()
                    // The scope filter is for the server path only (BLE-heard SOS bypasses it); resolved records
                    // always pass, so a clear is never stranded after the scope narrows.
                    if (gen == generation && !(rec.uid.isNotEmpty() && rec.uid == mine) &&
                        SiteScope.receives(rec.active, rec.floor, rec.proc, f, p, all)
                    ) {
                        peerPaths[rec.key] = path
                        onPeer(rec)
                    }
                }
            },
            // The server time listener survives a cancel, so detach it before clearing
            { handler.post { if (gen == generation) { remover?.invoke(); remover = null; listenKey = "" } } }
        )
    }

    private fun detach() {
        generation++
        remover?.invoke()
        remover = null
        listenKey = ""
    }

    /** Stops reception only. The saved own SOS and any pending clear are kept. */
    fun stopListening() = detach()
}
