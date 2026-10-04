package com.wf11.safealert.firebase

import android.os.SystemClock
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.Query
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import java.util.UUID

/**
 * RTDB transport for lone-worker SOS.
 *
 * Path: {firebaseRoot}/sos/{siteCode}/{pushKey}. The rules (database.rules.json) allow creating only records owned by the writer's uid,
 * and only the writer may change active→resolved. Delivery is through RTDB listeners only, without FCM or Functions.
 * Property initialization touches neither Firebase nor Android, so JVM unit tests can load this.
 */
object SosRemote {
    private const val TAG = "SosRemote"

    /** One SOS record. bleId is the advertised fullId as is, so it merges with the BLE receive key. */
    data class SosRecord(
        val key: String,
        val bleId: String,
        val name: String,
        val role: String,
        val trigger: String,      // "still" | "fall"
        val beacon: String,       // Label of the recent strongest beacon ("" if none)
        val beaconRssi: Int?,
        val createdAt: Long,
        val active: Boolean,
        val uid: String,          // Writer uid ("" if none); used to filter out my own records
        val ep: Int = 0,          // SOS episode 1..255 (0 = none); same value as the BLE advertised episode
        val resolvedAt: Long = 0L // Resolve server time (0 if none)
    )

    private const val SOS_STR_MAX = 64
    private const val SOS_ROLE_MAX = 32

    /**
     * Same as the beaconRssi range the rules (database.rules.json) allow. Out of
     * range would reject the whole record, so the field is left out.
     */
    const val BEACON_RSSI_MIN = -150
    const val BEACON_RSSI_MAX = 20

    /**
     * (server time, elapsedRealtime) at the moment the server time offset was read. listen() keeps it updated; null if unknown.
     */
    @Volatile private var serverAnchor: Pair<Long, Long>? = null

    /**
     * Estimated current server time: server time when the offset was read + time
     * elapsed since (unaffected by wall-clock changes). null if unknown.
     */
    fun serverNowMs(): Long? = serverAnchor?.let { (s, e) -> s + (SystemClock.elapsedRealtime() - e) }

    /** Receive replay window: records created up to one shift (12 h) before start are received, so a phone that
     *  starts or restarts later in the shift still gets a request nobody has resolved. */
    const val REPLAY_WINDOW_MS = 12 * 60 * 60_000L

    /** Snapshot value (Map) → SosRecord. Boundary input, so a wrong type or length gives null (ignored). */
    fun parseSosRecord(key: String, v: Any?): SosRecord? {
        val m = v as? Map<*, *> ?: return null
        val bleId = (m["bleId"] as? String)?.takeIf { it.isNotEmpty() && it.length <= SOS_STR_MAX } ?: return null
        val status = m["status"] as? String
        if (status != "active" && status != "resolved") return null
        val createdAt = (m["createdAt"] as? Number)?.toLong() ?: return null
        val trigger = (m["trigger"] as? String)?.takeIf { it == "still" || it == "fall" } ?: "still"
        return SosRecord(
            key = key,
            bleId = bleId,
            name = (m["name"] as? String).orEmpty().take(SOS_STR_MAX),
            role = (m["role"] as? String).orEmpty().take(SOS_STR_MAX),
            trigger = trigger,
            beacon = (m["beacon"] as? String).orEmpty().take(SOS_STR_MAX),
            beaconRssi = (m["beaconRssi"] as? Number)?.toInt(),
            createdAt = createdAt,
            active = status == "active",
            uid = (m["uid"] as? String).orEmpty().take(SOS_STR_MAX),
            ep = (m["ep"] as? Number)?.toInt()?.takeIf { it in 1..255 } ?: 0,
            resolvedAt = (m["resolvedAt"] as? Number)?.toLong() ?: 0L
        )
    }

    /** Record body. beacon is included only when non-empty, beaconRssi only when within the rule range. */
    fun recordPayload(
        bleId: String, name: String, role: String, trigger: String,
        beacon: String?, beaconRssi: Int?, uid: String, createdAt: Any, ep: Int = 0
    ): Map<String, Any> {
        val data = HashMap<String, Any>()
        data["bleId"] = bleId.take(SOS_STR_MAX)
        data["name"] = name.take(SOS_STR_MAX)
        data["role"] = role.take(SOS_ROLE_MAX)
        data["trigger"] = trigger
        data["createdAt"] = createdAt
        data["status"] = "active"
        data["uid"] = uid
        if (!beacon.isNullOrEmpty()) data["beacon"] = beacon.take(SOS_STR_MAX)
        if (beaconRssi != null && beaconRssi in BEACON_RSSI_MIN..BEACON_RSSI_MAX) data["beaconRssi"] = beaconRssi
        if (ep in 1..255) data["ep"] = ep
        return data
    }

    /** Receive query start: REPLAY_WINDOW_MS before the call time (converted to server time). */
    fun replayStartAt(t0WallMs: Long, serverOffsetMs: Long): Long = t0WallMs + serverOffsetMs - REPLAY_WINDOW_MS

    fun nodePath(root: String, site: String): String = "$root/sos/$site"

    /** Path of the lone-worker liveness session node. */
    fun hbPath(root: String, site: String): String = "$root/hb/$site"

    /** Partial update of the session node (updateChildren). Failure logs never include the path, key or uid. */
    fun update(path: String, key: String, fields: Map<String, Any>, onDone: (Boolean) -> Unit) {
        FirebaseDatabase.getInstance().reference.child(path).child(key).updateChildren(fields)
            .addOnCompleteListener {
                if (!it.isSuccessful) Log.w(TAG, "살아 있음 기록 실패")
                onDone(it.isSuccessful)
            }
    }

    fun currentUid(): String? = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()

    /** Creates a new push key under the given path locally (no server round trip). */
    fun newKey(path: String): String =
        FirebaseDatabase.getInstance().reference.child(path).push().key
            ?: UUID.randomUUID().toString().replace("-", "").take(20)

    /**
     * Creates the record. createdAt is server time. The result (success or not) goes to
     * onResult. While offline it stays pending until a response arrives.
     */
    fun create(path: String, key: String, payload: Map<String, Any>, onResult: (Boolean) -> Unit) {
        FirebaseDatabase.getInstance().reference.child(path).child(key).setValue(payload)
            .addOnCompleteListener {
                if (it.isSuccessful) Log.d(TAG, "구조 요청 기록: $key")
                else Log.e(TAG, "구조 요청 기록 실패: ${it.exception?.message}")
                onResult(it.isSuccessful)
            }
    }

    /**
     * Resolves the SOS — status=resolved + resolvedAt (server time). Writes to the
     * saved path and never rebuilds it from the current site code.
     */
    fun resolve(path: String, key: String, onDone: (Boolean) -> Unit) {
        FirebaseDatabase.getInstance().reference.child(path).child(key)
            .updateChildren(mapOf("status" to "resolved", "resolvedAt" to ServerValue.TIMESTAMP))
            .addOnCompleteListener {
                if (it.isSuccessful) Log.d(TAG, "구조 요청 해제 기록: $key")
                else Log.e(TAG, "구조 요청 해제 기록 실패: ${it.exception?.message}")
                onDone(it.isSuccessful)
            }
    }

    /**
     * Reads one record (server first, cache only when the server is unreachable). Used after
     * a failed write to check whether my record already exists on the server.
     * On success (true, the record or null if absent/unparseable); on read failure (false, null).
     */
    fun read(path: String, key: String, onResult: (Boolean, SosRecord?) -> Unit) {
        FirebaseDatabase.getInstance().reference.child(path).child(key).get()
            .addOnCompleteListener {
                if (it.isSuccessful) onResult(true, parseSosRecord(key, it.result?.value))
                else {
                    Log.e(TAG, "구조 요청 조회 실패: ${it.exception?.message}")
                    onResult(false, null)
                }
            }
    }

    /**
     * Real-time SOS reception. Captures the call time t0 and, after the server time offset is first read, queries records created after
     * (t0 + offset - REPLAY_WINDOW_MS). So active records from the shift are replayed and sound,
     * older records are not queried, and resolved records do not notify.
     * Deleted records are delivered as resolved. On cancel (onCancelled) the query is dropped and onCancel is called once.
     * Returns a detach function that is safe to call even before the offset read finishes.
     */
    fun listen(path: String, onRecord: (SosRecord) -> Unit, onCancel: () -> Unit): () -> Unit {
        val t0 = System.currentTimeMillis()
        val node = FirebaseDatabase.getInstance().reference.child(path)
        var stopped = false
        var attached: Pair<Query, ChildEventListener>? = null
        val handle = { s: DataSnapshot -> parseSosRecord(s.key.orEmpty(), s.value)?.let(onRecord) }
        val listener = object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, prev: String?) { handle(s) }
            override fun onChildChanged(s: DataSnapshot, prev: String?) { handle(s) }
            override fun onChildRemoved(s: DataSnapshot) {
                parseSosRecord(s.key.orEmpty(), s.value)?.let { onRecord(it.copy(active = false)) }
            }
            override fun onChildMoved(s: DataSnapshot, prev: String?) {}
            override fun onCancelled(e: DatabaseError) {
                Log.e(TAG, "구조 요청 수신 취소: ${e.message}")
                attached = null
                if (!stopped) onCancel()
            }
        }
        fun attach(offset: Long) {
            if (stopped) return
            val q = node.orderByChild("createdAt").startAt(replayStartAt(t0, offset).toDouble())
            q.addChildEventListener(listener)
            attached = q to listener
        }
        // Keep subscribing to the offset, updating only the server-time reference. The query is attached once, on the first callback.
        var first = true
        val offsetRef = FirebaseDatabase.getInstance().getReference(".info/serverTimeOffset")
        val offsetListener = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val off = (s.value as? Number)?.toLong()?.takeIf { it != 0L }
                serverAnchor = off?.let { System.currentTimeMillis() + it to SystemClock.elapsedRealtime() }
                if (first) { first = false; attach(off ?: 0L) }
            }
            override fun onCancelled(e: DatabaseError) {
                serverAnchor = null
                if (first) { first = false; attach(0L) }
            }
        }
        offsetRef.addValueEventListener(offsetListener)
        return {
            stopped = true
            offsetRef.removeEventListener(offsetListener)
            attached?.let { (q, l) -> q.removeEventListener(l) }
            attached = null
        }
    }
}
