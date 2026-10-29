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
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.wf11.safealert.R
import com.wf11.safealert.firebase.SitePinStore
import com.wf11.safealert.ui.MainActivity
import com.wf11.safealert.utils.AdminPin
import com.wf11.safealert.utils.DevSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Server link of an admin phone, alive only while it monitors as an admin (BleService.applyMode starts / stops it).
 * Watches the site's PIN record and notifies "센터 PIN 변경" when its version goes up.
 */
object AdminLink {
    private const val CHANNEL_ID = "safealert_admin"
    private const val NOTIF_ID = 4300
    private const val REQ_OPEN = 50
    private const val RETRY_MS = 30_000L
    private const val PREFS = "site_pin"

    private var app: Context? = null
    private var site: String? = null
    private var ref: DatabaseReference? = null
    private var listener: ValueEventListener? = null
    private val main = Handler(Looper.getMainLooper())
    private val retry = Runnable { if (site != null && listener == null) attach() }

    fun start(ctx: Context, sc: String) {
        if (sc.isBlank()) { stop(); return }
        if (sc == site && listener != null) return   // applyMode runs again on a Bluetooth restart
        stop()
        app = ctx.applicationContext
        site = sc
        createChannel()
        attach()
    }

    fun stop() {
        main.removeCallbacks(retry)
        val r = ref
        val l = listener
        if (r != null && l != null) runCatching { r.removeEventListener(l) }
        ref = null; listener = null; site = null; app = null
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
