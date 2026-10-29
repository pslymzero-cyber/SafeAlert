package com.wf11.safealert.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.Query
import com.google.firebase.database.ValueEventListener
import com.wf11.safealert.R
import com.wf11.safealert.firebase.SitePinStore
import com.wf11.safealert.firebase.SosRemote
import com.wf11.safealert.ui.MainActivity
import com.wf11.safealert.utils.AdminPin
import com.wf11.safealert.utils.DevSettings
import com.wf11.safealert.utils.PinShare
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Server link of an admin phone, alive only while it monitors as an admin (BleService.applyMode starts / stops it).
 * Watches the site's PIN record and notifies "센터 PIN 변경" when its version goes up.
 * Also watches the site's PIN lookup requests (wf11/pinreq) and notifies "PIN 찾기 요청" for each fresh, unanswered
 * request that lists this phone's ID; tapping it opens the approval window in MainActivity.
 */
object AdminLink {
    private const val CHANNEL_ID = "safealert_admin"
    private const val NOTIF_ID = 4300
    private const val REQ_OPEN = 50
    private const val RETRY_MS = 30_000L
    private const val PREFS = "site_pin"
    private const val REQ_NOTIF_BASE = 4301   // + 0..49
    private const val REQ_CODE_BASE = 51      // + 0..49

    const val EXTRA_REQ_SITE = "pinreq_site"
    const val EXTRA_REQ_ID = "pinreq_id"
    const val EXTRA_REQ_BY = "pinreq_by"
    const val EXTRA_REQ_CODE = "pinreq_code"
    const val EXTRA_REQ_LOC = "pinreq_loc"

    private var app: Context? = null
    private var site: String? = null
    private var ref: DatabaseReference? = null
    private var listener: ValueEventListener? = null
    private val main = Handler(Looper.getMainLooper())
    private var me = ""                       // this phone's advertised ID: the requests' to/<ID>
    @Volatile private var offsetMs = 0L       // server time minus device time
    private var offRef: DatabaseReference? = null
    private var offListener: ValueEventListener? = null
    private var reqQuery: Query? = null
    private var reqListener: ChildEventListener? = null
    private val notified = HashMap<String, Int>()   // request ID -> notification ID
    private val retry = Runnable {
        val sc = site ?: return@Runnable
        if (listener == null) attach()
        if (offListener == null) attachReq() else if (reqQuery == null) attachQuery(sc)
    }

    /** Server time; the device clock when no link is running. */
    fun serverNow(): Long = System.currentTimeMillis() + offsetMs

    fun start(ctx: Context, sc: String, myId: String) {
        if (sc.isBlank()) { stop(); return }
        if (sc == site && myId == me && listener != null && offListener != null) return   // applyMode runs again on a Bluetooth restart
        stop()
        app = ctx.applicationContext
        site = sc
        me = myId
        createChannel()
        attach()
        attachReq()
    }

    fun stop() {
        main.removeCallbacks(retry)
        val r = ref
        val l = listener
        if (r != null && l != null) runCatching { r.removeEventListener(l) }
        val oR = offRef
        val oL = offListener
        if (oR != null && oL != null) runCatching { oR.removeEventListener(oL) }
        val q = reqQuery
        val ql = reqListener
        if (q != null && ql != null) runCatching { q.removeEventListener(ql) }
        app?.let { ctx ->
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notified.values.forEach { runCatching { nm.cancel(it) } }
        }
        notified.clear()
        ref = null; listener = null; site = null; app = null
        offRef = null; offListener = null; reqQuery = null; reqListener = null
        me = ""; offsetMs = 0L
    }

    private fun attach() {
        val sc = site ?: return
        // Firebase unavailable (tests, offline init) is swallowed; the retry is armed only from onCancelled
        runCatching {
            val r = FirebaseDatabase.getInstance().reference.child(DevSettings.FIREBASE_ROOT).child("pin").child(sc).child("pub")
            val l = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) { app?.let { onPub(it, sc, snapshot) } }
                override fun onCancelled(error: DatabaseError) {
                    // Typically permission denied before the anonymous sign-in finished
                    ref = null; listener = null
                    main.removeCallbacks(retry)
                    // ponytail: fixed 30 s retry; switch to an auth-state listener if logins prove slow
                    main.postDelayed(retry, RETRY_MS)
                }
            }
            ref = r; listener = l
            r.addValueEventListener(l)
        }
    }

    // The server clock first (a phone clock can be off), then the request query from "now - 2 minutes" on.
    private fun attachReq() {
        val sc = site ?: return
        runCatching {
            val r = FirebaseDatabase.getInstance().getReference(".info/serverTimeOffset")
            val l = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    offsetMs = (snapshot.value as? Number)?.toLong() ?: 0L
                    attachQuery(sc)
                }
                override fun onCancelled(error: DatabaseError) { attachQuery(sc) }
            }
            offRef = r; offListener = l
            r.addValueEventListener(l)
        }
    }

    private fun attachQuery(sc: String) {
        if (reqQuery != null || site != sc) return
        runCatching {
            val q = FirebaseDatabase.getInstance().reference.child(DevSettings.FIREBASE_ROOT).child("pinreq").child(sc)
                .orderByChild("at").startAt((serverNow() - PinShare.REQ_TTL_MS).toDouble())
            val l = object : ChildEventListener {
                override fun onChildAdded(s: DataSnapshot, prev: String?) { onReq(sc, s) }
                override fun onChildChanged(s: DataSnapshot, prev: String?) { onReq(sc, s) }
                override fun onChildRemoved(s: DataSnapshot) { s.key?.let { cancelReq(it) } }
                override fun onChildMoved(s: DataSnapshot, prev: String?) {}
                override fun onCancelled(error: DatabaseError) {
                    // Typically permission denied before the anonymous sign-in finished; Firebase already dropped the query
                    reqQuery = null; reqListener = null
                    main.removeCallbacks(retry)
                    main.postDelayed(retry, RETRY_MS)
                }
            }
            reqQuery = q; reqListener = l
            q.addChildEventListener(l)
        }
    }

    private fun onReq(sc: String, s: DataSnapshot) {
        val ctx = app ?: return
        val id = s.key ?: return
        if (s.child("ans").exists()) { cancelReq(id); return }
        if (me.isBlank() || notified.containsKey(id)) return
        val listed = runCatching { s.child("to").child(me).getValue(Boolean::class.java) == true }.getOrDefault(false)
        if (!listed) return
        if (s.child("uid").getValue(String::class.java) == SosRemote.currentUid()) return
        val at = (s.child("at").value as? Number)?.toLong() ?: return
        if (at < serverNow() - PinShare.REQ_TTL_MS) return
        notifyReq(ctx, sc, id, s.child("code").getValue(String::class.java).orEmpty(), s.child("loc").getValue(String::class.java).orEmpty())
    }

    private fun cancelReq(id: String) {
        val nid = notified.remove(id) ?: return
        val ctx = app ?: return
        runCatching { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(nid) }
    }

    private fun notifyReq(app: Context, sc: String, id: String, code: String, loc: String) {
        val n = Math.floorMod(id.hashCode(), 50)
        val who = listOf(code, loc).filter { it.isNotBlank() }.joinToString(" · ")
        val ask = "${sc} 센터 PIN을 요청했습니다. 눌러서 확인하세요."
        val text = if (who.isBlank()) ask else "${who} — ${ask}"
        val open = PendingIntent.getActivity(
            app, REQ_CODE_BASE + n,
            Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_REQ_SITE, sc).putExtra(EXTRA_REQ_ID, id).putExtra(EXTRA_REQ_BY, me)
                .putExtra(EXTRA_REQ_CODE, code).putExtra(EXTRA_REQ_LOC, loc),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("PIN 찾기 요청")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(PinShare.REQ_TTL_MS)
            .setContentIntent(open)
            .build()
        notified[id] = REQ_NOTIF_BASE + n
        runCatching { (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(REQ_NOTIF_BASE + n, notif) }
    }

    private fun onPub(app: Context, sc: String, snapshot: DataSnapshot) {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "admin_seen_ver_$sc"
        if (!snapshot.exists()) {
            // PIN record removed (developer reset): the next PIN starts again at ver 1 and must still notify
            prefs.edit().putLong(key, 0L).apply()
            return
        }
        val pub = SitePinStore.parse(snapshot) ?: return
        val seen = prefs.getLong(key, -1L)
        if (seen < 0) { prefs.edit().putLong(key, pub.ver).apply(); return }   // first value ever seen: record only
        if (pub.ver <= seen) return
        prefs.edit().putLong(key, pub.ver).apply()
        SitePinStore.saveCache(app, sc, pub)
        if (AdminPin.savedVer(app, sc) != pub.ver) AdminPin.clear(app, sc)   // the saved PIN belongs to an older version
        notifyChanged(app, sc, pub.at, pub.by)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ctx = app ?: return
        runCatching {
            val ch = NotificationChannel(CHANNEL_ID, "관리자 알림", NotificationManager.IMPORTANCE_HIGH)
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun notifyChanged(app: Context, sc: String, at: Long, by: String) {
        val hhmm = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(at))
        val text = "${sc} 센터 PIN이 바뀌었습니다 · ${hhmm} · 바꾼 폰 ${by}"
        val open = PendingIntent.getActivity(
            app, REQ_OPEN,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("센터 PIN 변경")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, n) }
    }
}
