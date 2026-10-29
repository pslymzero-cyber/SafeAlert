package com.wf11.safealert.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import com.wf11.safealert.R
import com.wf11.safealert.databinding.DialogPinBinding
import com.wf11.safealert.firebase.SitePinStore
import com.wf11.safealert.utils.DevSettings
import com.wf11.safealert.utils.PinLockout
import com.wf11.safealert.utils.SitePin

// ── PIN dialog ──────────────────────────────────────────
//   One dialog, three modes, all on the site's 10-digit PIN (SitePin / SitePinStore):
//     verify  — settings, beacon management, beacon share send/delete, BLE settings unlock, site change.
//               If the server confirms the site has no PIN yet, 0000000000 continues into first-set.
//     first   — set the site's first PIN (new PIN, then once more) and save it to the server.
//     change  — current PIN, new PIN, once more; needs the server (current PIN is the proof).
//   Lockout after consecutive errors (PinLockout) — while locked, shows the remaining time instead of the input.
//   A missing server answer on a first look (no cache, offline) is not counted as a wrong try.
fun Activity.showSitePinDialog(
    title: String,
    site: String = DevSettings.siteCode,
    onCancel: (() -> Unit)? = null,
    onForgot: (() -> Unit)? = null,
    onSuccess: (pin: String) -> Unit
) = openSitePinDialog(title, site, false, onCancel, onForgot, null, onSuccess)

/** Settings "change PIN": current PIN, then the new PIN twice. [onClosed] runs on every dismiss. */
fun Activity.showChangeSitePinDialog(site: String = DevSettings.siteCode, onClosed: (() -> Unit)? = null) =
    openSitePinDialog("PIN 바꾸기", site, true, null, null, onClosed) { }

private const val MODE_VERIFY = 0
private const val MODE_FIRST = 1
private const val MODE_CHANGE = 2

private fun Activity.openSitePinDialog(
    title: String,
    site: String,
    change: Boolean,
    onCancel: (() -> Unit)?,
    onForgot: (() -> Unit)?,
    onClosed: (() -> Unit)?,
    onSuccess: (pin: String) -> Unit
) {
    if (site.isBlank()) {
        Toast.makeText(this, "첫 화면에서 센터를 먼저 넣으세요", Toast.LENGTH_SHORT).show()
        onCancel?.invoke()
        onClosed?.invoke()
        return
    }
    val lockout = PinLockout(PrefsPinStore(getSharedPreferences(PIN_LOCKOUT_PREFS, Context.MODE_PRIVATE)))
    val lockedMs = lockout.remainingLockMs(pinNow())
    if (lockedMs > 0L) {
        showPinLockedNotice(lockedMs, onForgot)
        onCancel?.invoke()
        onClosed?.invoke()
        return
    }

    val dialog = Dialog(this)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val pb = DialogPinBinding.inflate(layoutInflater)
    dialog.setContentView(pb.root)
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    val dots = listOf(pb.dot1, pb.dot2, pb.dot3, pb.dot4, pb.dot5, pb.dot6, pb.dot7, pb.dot8, pb.dot9, pb.dot10)
    val input = StringBuilder()
    val cPrimary = resources.getColor(R.color.sa_text_primary, theme)
    val cTertiary = resources.getColor(R.color.sa_text_tertiary, theme)
    val cSafe = resources.getColor(R.color.sa_safe, theme)
    val cDanger = resources.getColor(R.color.sa_danger, theme)

    var mode = if (change) MODE_CHANGE else MODE_VERIFY
    var step = 0                         // 0 = current PIN, 1 = new PIN, 2 = once more
    var state: SitePin.State? = null     // the site's PIN state once the first look is back
    var pending: String? = null          // 10 digits typed before the first look returned
    var busy = false
    var alive = true
    var succeeded = false
    var curPin = ""
    var curPub: SitePin.Pub? = null
    var newPin = ""

    fun updateDots() {
        dots.forEachIndexed { i, dot ->
            dot.setBackgroundResource(
                if (i < input.length) R.drawable.shape_pin_dot_filled
                else R.drawable.shape_pin_dot_empty
            )
        }
    }

    fun message(text: String?, color: Int) {
        if (text == null) { pb.tvError.visibility = View.INVISIBLE; return }
        pb.tvError.text = text
        pb.tvError.setTextColor(color)
        pb.tvError.visibility = View.VISIBLE
    }

    fun clearInput() { input.clear(); updateDots() }

    fun close(ok: Boolean) { succeeded = ok; dialog.dismiss() }

    fun render() {
        pb.tvTitle.text = if (mode == MODE_FIRST) "${site} 센터 PIN 정하기" else title
        pb.tvSub.text = when {
            step == 2 -> "새 PIN을 한 번 더 입력하세요"
            mode == MODE_FIRST -> "아직 PIN이 없는 센터입니다.\n새 PIN 10자리를 입력하세요"
            step == 1 -> "새 PIN 10자리를 입력하세요"
            else -> SpannableStringBuilder("${site} 센터 PIN 10자리를 입력하세요").apply {
                setSpan(StyleSpan(Typeface.BOLD), 0, site.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(cPrimary), 0, site.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        if (mode == MODE_VERIFY) { pb.tvSteps.visibility = View.GONE; return }
        val labels = if (mode == MODE_FIRST) listOf("PIN 없음 확인", "새 PIN", "한 번 더") else listOf("지금 PIN", "새 PIN", "한 번 더")
        val sb = SpannableStringBuilder()
        labels.forEachIndexed { i, l ->
            if (i > 0) sb.append("  ›  ")
            val from = sb.length
            sb.append(if (i < step) "$l ✓" else l)
            when {
                i < step -> sb.setSpan(ForegroundColorSpan(cSafe), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                i == step -> {
                    sb.setSpan(StyleSpan(Typeface.BOLD), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(cPrimary), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        pb.tvSteps.text = sb
        pb.tvSteps.visibility = View.VISIBLE
    }

    fun wrong(r: PinLockout.Result) {
        when (r) {
            is PinLockout.Result.Wrong -> { message("PIN이 올바르지 않습니다 (남은 시도 ${r.triesLeft}회)", cDanger); clearInput() }
            is PinLockout.Result.Locked -> { close(false); showPinLockedNotice(r.remainingMs, onForgot) }
            PinLockout.Result.Ok -> Unit
        }
    }

    fun judgeCurrent(pin: String, st: SitePin.State) {
        if (mode == MODE_CHANGE && st is SitePin.State.None) {
            Toast.makeText(this, "아직 PIN이 없습니다 — 0000000000으로 들어가 처음 정하세요", Toast.LENGTH_LONG).show()
            close(false)
            return
        }
        busy = true
        SitePinStore.judge(pin, st) { v ->
            if (!alive) return@judge
            busy = false
            when (v) {
                SitePin.Verdict.Ok -> {
                    lockout.submit(true, pinNow())
                    if (mode == MODE_CHANGE) {
                        curPin = pin
                        curPub = (st as? SitePin.State.Has)?.pub
                        step = 1; clearInput(); message(null, cDanger); render()
                    } else {
                        close(true)
                        onSuccess(pin)
                    }
                }
                SitePin.Verdict.FirstSet -> {
                    lockout.submit(true, pinNow())
                    mode = MODE_FIRST; step = 1; clearInput(); message(null, cDanger); render()
                }
                SitePin.Verdict.Wrong -> wrong(lockout.submit(false, pinNow()))
                SitePin.Verdict.NeedOnline -> {
                    message("처음 한 번은 인터넷 연결이 필요합니다", cDanger)
                    clearInput()
                }
            }
        }
    }

    fun saved(ok: Boolean, successToast: String) {
        if (!alive) return
        if (ok) {
            Toast.makeText(this, successToast, Toast.LENGTH_SHORT).show()
            close(true)
            if (mode == MODE_FIRST) onSuccess(newPin)
        } else {
            Toast.makeText(this, "저장하지 못했습니다. 인터넷 연결을 확인하고 다시 시도하세요", Toast.LENGTH_LONG).show()
            close(false)
        }
    }

    fun onFull() {
        val pin = input.toString()
        when (step) {
            0 -> {
                val st = state
                if (st == null) { pending = pin; busy = true; message("확인 중…", cTertiary) } else judgeCurrent(pin, st)
            }
            1 -> when {
                pin == SitePin.INITIAL || !SitePin.isValidNew(pin) -> { message("0000000000은 쓸 수 없습니다", cDanger); clearInput() }
                mode == MODE_CHANGE && pin == curPin -> { message("지금 PIN과 같습니다", cDanger); clearInput() }
                else -> { newPin = pin; step = 2; clearInput(); message(null, cDanger); render() }
            }
            else -> {
                if (pin != newPin) {
                    message("두 번 입력한 PIN이 다릅니다", cDanger)
                    step = 1; clearInput(); render()
                    return
                }
                busy = true
                message("저장 중…", cTertiary)
                val by = SitePinStore.byName()
                if (mode == MODE_FIRST) {
                    SitePinStore.writeFirst(this, site, newPin, by) { saved(it, "${site} 센터 PIN을 정했습니다") }
                } else {
                    val pub = curPub
                    SitePinStore.checkOnline { online ->
                        if (!alive) return@checkOnline
                        if (!online || pub == null) {
                            Toast.makeText(this, "인터넷에 연결되면 바꿀 수 있습니다", Toast.LENGTH_LONG).show()
                            close(false)
                        } else {
                            SitePinStore.writeChange(this, site, curPin, pub, newPin, by) { saved(it, "센터 PIN을 바꿨습니다") }
                        }
                    }
                }
            }
        }
    }

    fun onDigit(d: String) {
        if (busy || input.length >= SitePin.LEN) return
        input.append(d)
        updateDots()
        message(null, cDanger)
        if (input.length == SitePin.LEN) onFull()
    }

    mapOf(pb.btn1 to "1", pb.btn2 to "2", pb.btn3 to "3",
          pb.btn4 to "4", pb.btn5 to "5", pb.btn6 to "6",
          pb.btn7 to "7", pb.btn8 to "8", pb.btn9 to "9",
          pb.btn0 to "0").forEach { (btn, digit) ->
        btn.setOnClickListener { onDigit(digit) }
    }
    pb.btnBack.setOnClickListener {
        if (!busy && input.isNotEmpty()) {
            input.deleteCharAt(input.length - 1)
            updateDots()
            message(null, cDanger)
        }
    }
    if (onForgot != null) {
        pb.tvForgot.visibility = View.VISIBLE
        pb.tvForgot.setOnClickListener { close(false); onForgot() }
    }
    dialog.setOnDismissListener {
        alive = false
        if (!succeeded) onCancel?.invoke()
        onClosed?.invoke()
    }

    render()
    SitePinStore.fetch(this, site) { st ->
        if (!alive) return@fetch
        state = st
        pending?.let { pending = null; busy = false; message(null, cDanger); judgeCurrent(it, st) }
    }
    dialog.show()
}

// PIN lockout state lives in a file separate from settings (dev_settings) — kept regardless of a settings reset.
private const val PIN_LOCKOUT_PREFS = "pin_lockout"

// Written in one commit (synchronous) — the count survives closing the app right after input, and the fields never disagree.
private class PrefsPinStore(private val p: SharedPreferences) : PinLockout.Store {
    override fun load() = PinLockout.State(
        fails = p.getInt("fails", 0),
        lockWallUntil = p.getLong("lock_wall_until", 0L),
        lockElapsedUntil = p.getLong("lock_elapsed_until", 0L),
        lockBoot = p.getInt("lock_boot", 0)
    )

    override fun save(s: PinLockout.State) {
        p.edit()
            .putInt("fails", s.fails)
            .putLong("lock_wall_until", s.lockWallUntil)
            .putLong("lock_elapsed_until", s.lockElapsedUntil)
            .putInt("lock_boot", s.lockBoot)
            .commit()
    }
}

private fun Context.pinNow() = PinLockout.Now(
    wallMs = System.currentTimeMillis(),
    elapsedMs = SystemClock.elapsedRealtime(),
    bootCount = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, 0)
)

private fun Activity.showPinLockedNotice(remainingMs: Long, onForgot: (() -> Unit)? = null) {
    val sec = (remainingMs + 999L) / 1000L
    val b = AlertDialog.Builder(this)
        .setTitle("PIN 입력 잠김")
        .setMessage("PIN을 ${PinLockout.MAX_FAILS}회 연속 틀려 입력이 잠겼습니다.\n" +
            "${sec / 60}분 ${sec % 60}초 후 다시 시도하세요.")
        .setPositiveButton("확인", null)
    if (onForgot != null) b.setNeutralButton("PIN 찾기") { _, _ -> onForgot() }
    b.show()
}
