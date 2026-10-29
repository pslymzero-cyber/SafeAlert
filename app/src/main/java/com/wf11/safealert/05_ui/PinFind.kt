package com.wf11.safealert.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.wf11.safealert.R
import com.wf11.safealert.ble.BleConstants
import com.wf11.safealert.databinding.DialogPinFindBinding
import com.wf11.safealert.firebase.SitePinStore
import com.wf11.safealert.firebase.SosRemote
import com.wf11.safealert.service.AdminLink
import com.wf11.safealert.utils.AdminPin
import com.wf11.safealert.utils.DevSettings
import com.wf11.safealert.utils.PinShare
import com.wf11.safealert.utils.SitePin
import java.security.KeyPair
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

// ── PIN find ────────────────────────────────────────────
//   Requester: finds admin phones nearby over Bluetooth (ext byte bit3), sends one request through the server (wf11/pinreq),
//   waits for a sealed answer (PinShare) and shows the PIN for 60 s on this phone only.
//   Admin: showPinApproval, opened from the "PIN 찾기 요청" notification (AdminLink) — the PIN is never shown there.

private const val SCAN_MS = 6_000L
private const val SHOWN_SEC = 60
private val ID_RE = Regex("^[A-Za-z0-9_-]{1,24}$")   // the rule's $aid pattern; a bad key would reject the whole write
private const val ERR_OFFLINE = "인터넷 연결이 필요합니다"
private const val ERR_EXPIRED = "이미 처리됐거나 시간이 지난 요청입니다"
private const val ERR_APPROVE_NOT_SENT = "승인을 보내지 못했습니다. 인터넷 연결을 확인하고 다시 시도하세요"

private fun Activity.dpPx(v: Int) = (v * resources.displayMetrics.density).toInt()
private fun Activity.col(id: Int) = resources.getColor(id, theme)

private fun Activity.pinFindDialog(b: DialogPinFindBinding): Dialog {
    val d = Dialog(this)
    d.requestWindowFeature(Window.FEATURE_NO_TITLE)
    d.setContentView(b.root)
    d.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setLayout((resources.displayMetrics.widthPixels * 0.92).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
    }
    return d
}

// Pill button: filled (sky) or ghost (outline)
private fun Activity.pill(t: TextView, text: String, filled: Boolean, onClick: () -> Unit) {
    t.text = text
    t.background = GradientDrawable().apply {
        cornerRadius = dpPx(24).toFloat()
        if (filled) setColor(col(R.color.sa_accent)) else { setColor(Color.TRANSPARENT); setStroke(dpPx(1), col(R.color.sa_stroke)) }
    }
    t.setTextColor(col(if (filled) R.color.sa_on_accent else R.color.sa_text_secondary))
    t.setOnClickListener { onClick() }
}

private fun Activity.notice(text: String) {
    if (isFinishing || isDestroyed) return
    AlertDialog.Builder(this).setTitle("PIN 찾기").setMessage(text).setPositiveButton("확인", null).show()
}

private fun Activity.hasScanPermission(): Boolean {
    val p = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION
    return ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
}

/** Requester side. [onEnterPin] runs when the person taps [PIN 입력하기] on the PIN window. */
fun Activity.showPinFind(site: String, onEnterPin: () -> Unit) {
    if (site.isBlank() || isFinishing || isDestroyed) return
    if (!hasScanPermission()) {
        notice("블루투스 권한이 없습니다. 감시를 한 번 시작해 권한을 허용한 뒤 다시 시도하세요")
        return
    }
    val scanner = runCatching {
        getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
    }.getOrNull()
    if (scanner == null) { notice("블루투스를 켜세요"); return }

    val b = DialogPinFindBinding.inflate(layoutInflater)
    val dialog = pinFindDialog(b)
    dialog.setCanceledOnTouchOutside(false)
    val handler = Handler(Looper.getMainLooper())
    val svcUuid = ParcelUuid(UUID.fromString(BleConstants.SERVICE_UUID))
    val myIds = setOfNotNull(
        getSharedPreferences("safealert_prefs", Context.MODE_PRIVATE).getString("device_id", null),
        DevSettings.workerCode.takeIf { it.isNotBlank() }
    )
    val code = DevSettings.workerCode.take(10)
    val loc = listOf(DevSettings.floor, DevSettings.proc, DevSettings.zone).filter { it.isNotBlank() }.joinToString("-").take(24)
    val found = LinkedHashSet<String>()
    var scanCb: ScanCallback? = null
    var keyPair: KeyPair? = null        // memory only
    var req: DatabaseReference? = null
    var ansL: ValueEventListener? = null
    var reqId = ""

    // Idempotent: runs on cancel, close, success, timeout and every end(). Deletes the request when one was written.
    fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        scanCb?.let { cb -> runCatching { scanner.stopScan(cb) } }
        scanCb = null
        ansL?.let { l -> runCatching { req?.child("ans")?.removeEventListener(l) } }
        ansL = null
        req?.let { r -> runCatching { r.removeValue() } }
        req = null
    }

    fun end(text: String) {
        cleanup()
        if (!dialog.isShowing) return
        b.pbSpin.visibility = View.GONE
        b.tvWaitSub.visibility = View.GONE
        b.tvText.visibility = View.GONE
        b.tvWaitMain.text = text
        pill(b.btnAction, "닫기", false) { dialog.dismiss() }
    }

    b.tvTitle.text = "PIN 찾기"
    b.tvSub.text = SpannableStringBuilder("${site} 센터").apply {
        setSpan(StyleSpan(Typeface.BOLD), 0, site.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(ForegroundColorSpan(col(R.color.sa_text_primary)), 0, site.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    b.tvWaitMain.text = "근처 관리자를 찾는 중…"
    b.tvText.text = "관리자가 승인하면 이 폰에만 PIN이 표시됩니다. 관리자에게 이 폰을 보여 주세요."
    val me = listOf(code, loc).filter { it.isNotBlank() }.joinToString(" · ")
    if (me.isBlank()) b.tvMe.visibility = View.GONE else b.tvMe.text = "이 폰: ${me}"
    pill(b.btnAction, "요청 취소", false) { dialog.dismiss() }
    dialog.setOnDismissListener { cleanup() }

    fun onAnswer(s: DataSnapshot) {
        val epk = s.child("epk").getValue(String::class.java)
        val iv = s.child("iv").getValue(String::class.java)
        val ct = s.child("ct").getValue(String::class.java)
        val by = s.child("by").getValue(String::class.java).orEmpty()
        val pair = keyPair
        val pin = if (epk == null || iv == null || ct == null || pair == null) null
        else PinShare.open(PinShare.Sealed(epk, iv, ct), pair.private, reqId)
        if (pin == null || pin.length != SitePin.LEN || !pin.all { it in '0'..'9' }) {
            end("받은 PIN을 풀지 못했습니다. 다시 요청하세요")
            return
        }
        // Any signed-in app may write the one answer, so the PIN is checked against this site's check value first:
        //   only a real PIN is shown and only a real PIN lifts this phone's PIN lock. No cached value → shown, lock kept.
        val pub = SitePinStore.cached(applicationContext, site)
        if (pub == null) { cleanup(); dialog.dismiss(); showPinShown(site, pin, by, onEnterPin); return }
        b.tvWaitSub.text = "받은 PIN 확인 중…"
        SitePinStore.judge(pin, SitePin.State.Has(pub)) { v ->
            if (!dialog.isShowing) return@judge
            if (v != SitePin.Verdict.Ok) { end("받은 PIN이 맞지 않습니다. 다시 요청하세요"); return@judge }
            applicationContext.clearPinLockout()   // the admin checked the person holding this phone, so a PIN lock ends here
            cleanup()
            dialog.dismiss()
            showPinShown(site, pin, by, onEnterPin)
        }
    }

    fun sendRequest() {
        val uid = SosRemote.currentUid()
        val r = runCatching {
            FirebaseDatabase.getInstance().reference.child(DevSettings.FIREBASE_ROOT).child("pinreq").child(site).push()
        }.getOrNull()
        val id = r?.key
        if (uid == null || r == null || id == null) { end(ERR_OFFLINE); return }
        val pair = PinShare.newKeyPair()
        keyPair = pair
        reqId = id
        val map = HashMap<String, Any>()
        map["uid"] = uid
        map["pub"] = PinShare.publicB64(pair.public)
        if (code.isNotBlank()) map["code"] = code
        if (loc.isNotBlank()) map["loc"] = loc
        map["to"] = found.associateWith { true }
        map["at"] = ServerValue.TIMESTAMP
        req = r
        b.tvWaitMain.text = "근처 관리자 ${found.size}명에게 보냄"
        b.tvWaitSub.text = "승인을 기다리는 중…"
        b.tvWaitSub.visibility = View.VISIBLE
        runCatching {
            r.setValue(map) { err, _ ->
                if (err != null) handler.post { if (req != null) end("요청을 보내지 못했습니다. 인터넷 연결을 확인하고 다시 시도하세요") }
            }
            val l = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) { if (snapshot.exists() && req != null) onAnswer(snapshot) }
                override fun onCancelled(error: DatabaseError) {
                    handler.post { if (req != null) end("응답을 받지 못했습니다. 다시 요청하세요") }
                }
            }
            ansL = l
            r.child("ans").addValueEventListener(l)
        }.onFailure { end(ERR_OFFLINE); return }
        handler.postDelayed({ end("응답이 없습니다. 다시 요청하거나 개발 담당에게 문의하세요") }, PinShare.REQ_TTL_MS)
    }

    fun startScan() {
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val rec = result.scanRecord ?: return
                val walker = rec.getManufacturerSpecificData(BleConstants.COMPANY_ID_WALKER) ?: return
                val ext = rec.getServiceData(svcUuid)?.getOrNull(1)?.toInt()?.and(0xFF) ?: 0
                if (!BleConstants.decodeAdmin(ext)) return
                val id = String(walker, Charsets.UTF_8)
                if (!ID_RE.matches(id) || id in myIds) return
                handler.post { if (scanCb != null) found.add(id) }
            }
            override fun onScanFailed(errorCode: Int) {
                handler.post { if (scanCb != null) end("블루투스 검색을 시작하지 못했습니다. 잠시 뒤 다시 시도하세요") }
            }
        }
        val filter = ScanFilter.Builder().setServiceUuid(svcUuid).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(listOf(filter), settings, cb)
            com.wf11.safealert.ble.BleScanner.noteScanStart()   // counts against the app's scan start budget
            scanCb = cb
        } catch (e: Exception) {
            end("블루투스 검색을 시작하지 못했습니다. 잠시 뒤 다시 시도하세요")
            return
        }
        handler.postDelayed({
            scanCb?.let { c -> runCatching { scanner.stopScan(c) } }
            scanCb = null
            if (found.isEmpty()) end("근처에 관리자가 없습니다 — 개발 담당에게 초기화를 요청하세요") else sendRequest()
        }, SCAN_MS)
    }

    dialog.show()
    SitePinStore.checkOnline { online ->
        if (!dialog.isShowing) return@checkOnline
        if (!online) end(ERR_OFFLINE) else startScan()
    }
}

// PIN window (mock 5): 3-4-3 groups, 60 s countdown, only on this phone (FLAG_SECURE: no screenshot / recents capture).
private fun Activity.showPinShown(site: String, pin: String, by: String, onEnterPin: () -> Unit) {
    if (isFinishing || isDestroyed) return
    val b = DialogPinFindBinding.inflate(layoutInflater)
    val dialog = pinFindDialog(b)
    dialog.setCanceledOnTouchOutside(false)
    dialog.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    b.tvTitle.text = "${site} 센터 PIN"
    b.tvSub.text = "관리자 ${by} 승인"
    b.boxWait.visibility = View.GONE
    b.tvBigPin.text = pin.substring(0, 3) + " " + pin.substring(3, 7) + " " + pin.substring(7)
    b.tvBigPin.visibility = View.VISIBLE
    b.tvLeft.visibility = View.VISIBLE
    b.tvText.text = "이 PIN은 이 폰에만 보입니다."
    b.tvText.gravity = android.view.Gravity.CENTER
    b.tvMe.visibility = View.GONE
    val h = Handler(Looper.getMainLooper())
    var left = SHOWN_SEC
    val tick = object : Runnable {
        override fun run() {
            if (left <= 0) { dialog.dismiss(); return }
            b.tvLeft.text = "${left}초 뒤 사라집니다"
            left--
            h.postDelayed(this, 1000)
        }
    }
    pill(b.btnAction, "PIN 입력하기", true) { dialog.dismiss(); onEnterPin() }
    dialog.setOnDismissListener { h.removeCallbacksAndMessages(null) }
    dialog.show()
    tick.run()
}

/** Admin side: the approval window for one PIN lookup request. Declining writes nothing; the PIN is never shown here. */
fun Activity.showPinApproval(site: String, reqId: String, by: String, code: String, loc: String) {
    if (isFinishing || isDestroyed) return
    val first = code.ifBlank { loc }.ifBlank { "이름 없는 폰" }
    val second = listOfNotNull(loc.takeIf { code.isNotBlank() && it.isNotBlank() }, "근처에 있음").joinToString(" · ")
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.shape_icon_bg_blue)
        setPadding(dpPx(14), dpPx(12), dpPx(14), dpPx(12))
        addView(TextView(context).apply {
            text = first; textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(col(R.color.sa_text_primary))
        })
        addView(TextView(context).apply { text = second; textSize = 12f; setTextColor(col(R.color.sa_accent_soft)) })
    }
    val body = TextView(this).apply {
        text = "요청한 사람이 앞에 있는지 확인하고 승인하세요. 승인하면 그 폰에만 PIN이 보이고, 이 폰에는 나오지 않습니다."
        textSize = 14f
        setTextColor(col(R.color.sa_text_secondary))
        setPadding(0, dpPx(14), 0, 0)
    }
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpPx(24), dpPx(8), dpPx(24), 0)
        addView(box)
        addView(body)
    }
    AlertDialog.Builder(this)
        .setTitle("PIN 찾기 요청")
        .setView(root)
        .setNegativeButton("거절", null)
        .setPositiveButton("승인") { _, _ -> approvePinRequest(site, reqId, by) }
        .show()
}

// Re-reads the request (unanswered, under 2 minutes), takes the admin's PIN, seals it to the request's public key, writes ans once.
private fun Activity.approvePinRequest(site: String, reqId: String, by: String) {
    val ref = runCatching {
        FirebaseDatabase.getInstance().reference.child(DevSettings.FIREBASE_ROOT).child("pinreq").child(site).child(reqId)
    }.getOrNull()
    if (ref == null) { Toast.makeText(this, ERR_APPROVE_NOT_SENT, Toast.LENGTH_LONG).show(); return }
    val h = Handler(Looper.getMainLooper())

    fun send(pin: String, pub: String) {
        val sealed = runCatching { PinShare.seal(pin, pub, reqId) }.getOrNull()
        if (sealed == null) { Toast.makeText(this, ERR_EXPIRED, Toast.LENGTH_LONG).show(); return }
        val done = AtomicBoolean(false)
        lateinit var timeout: Runnable
        timeout = Runnable {
            // ponytail: a queued offline write may still land; the rule keeps it to one answer
            if (done.compareAndSet(false, true)) Toast.makeText(this, ERR_APPROVE_NOT_SENT, Toast.LENGTH_LONG).show()
        }
        val ans = mapOf(
            "epk" to sealed.epk, "iv" to sealed.iv, "ct" to sealed.ct,
            "by" to by.ifBlank { SitePinStore.byName() }.take(40)
        )
        h.postDelayed(timeout, 10_000L)
        ref.child("ans").setValue(ans) { err, _ ->
            if (!done.compareAndSet(false, true)) return@setValue
            h.removeCallbacks(timeout)
            when {
                err == null -> Toast.makeText(this, "승인했습니다. 요청한 폰에만 PIN이 보입니다.", Toast.LENGTH_LONG).show()
                err.code == DatabaseError.PERMISSION_DENIED -> Toast.makeText(this, ERR_EXPIRED, Toast.LENGTH_LONG).show()
                else -> Toast.makeText(this, ERR_APPROVE_NOT_SENT, Toast.LENGTH_LONG).show()
            }
        }
    }

    val done = AtomicBoolean(false)
    lateinit var timeout: Runnable
    fun once(block: () -> Unit) { if (done.compareAndSet(false, true)) { h.removeCallbacks(timeout); block() } }
    timeout = Runnable { once { Toast.makeText(this, ERR_APPROVE_NOT_SENT, Toast.LENGTH_LONG).show() } }
    h.postDelayed(timeout, 6_000L)
    runCatching {
        ref.get()
            .addOnSuccessListener { s -> once {
                val at = (s.child("at").value as? Number)?.toLong() ?: 0L
                val pub = s.child("pub").getValue(String::class.java)
                if (!s.exists() || s.child("ans").exists() || pub == null || at < AdminLink.serverNow() - PinShare.REQ_TTL_MS) {
                    Toast.makeText(this, ERR_EXPIRED, Toast.LENGTH_LONG).show()
                } else {
                    val saved = AdminPin.load(this, site)
                    if (saved != null) send(saved, pub)
                    else showSitePinDialog("관리자 확인", site = site) { p -> AdminPin.save(this, site, p); send(p, pub) }
                }
            } }
            .addOnFailureListener { once { Toast.makeText(this, ERR_APPROVE_NOT_SENT, Toast.LENGTH_LONG).show() } }
    }.onFailure { once { Toast.makeText(this, ERR_APPROVE_NOT_SENT, Toast.LENGTH_LONG).show() } }
}
