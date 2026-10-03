package com.wf11.safealert.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.Window
import android.view.WindowManager
import com.wf11.safealert.BuildConfig
import com.wf11.safealert.R
import com.wf11.safealert.databinding.DialogPinBinding
import com.wf11.safealert.utils.PinLockout

// ── PIN dialog ──────────────────────────────────────────
//   Shared by developer settings entry, beacon management entry, beacon share send/delete and the
//   BLE settings beacon-gain/UWB unlock. On a PIN match, closes and runs onSuccess.
//   Lockout after consecutive errors (PinLockout) — while locked, shows the remaining time instead of the input.
fun Activity.showDevPinDialog(onSuccess: () -> Unit) {
    val lockout = PinLockout(PrefsPinStore(getSharedPreferences(PIN_LOCKOUT_PREFS, Context.MODE_PRIVATE)))
    val lockedMs = lockout.remainingLockMs(pinNow())
    if (lockedMs > 0L) return showPinLockedNotice(lockedMs)

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

    val dots  = listOf(pb.dot1, pb.dot2, pb.dot3)
    val input = StringBuilder()

    fun updateDots() {
        dots.forEachIndexed { i, dot ->
            dot.setBackgroundResource(
                if (i < input.length) R.drawable.shape_pin_dot_filled
                else R.drawable.shape_pin_dot_empty
            )
        }
    }

    fun onDigit(d: String) {
        if (input.length >= 3) return
        input.append(d)
        updateDots()
        pb.tvError.visibility = View.INVISIBLE
        if (input.length == 3) {
            // Settings PIN — injected at build time (BuildConfig + CI Secrets). Kept at 3 digits (gloved input)
            when (val r = lockout.submit(input.toString() == BuildConfig.DEV_PIN, pinNow())) {
                PinLockout.Result.Ok -> {
                    dialog.dismiss()
                    onSuccess()
                }
                is PinLockout.Result.Wrong -> {
                    pb.tvError.text = "PIN이 올바르지 않습니다 (남은 시도 ${r.triesLeft}회)"
                    pb.tvError.visibility = View.VISIBLE
                    input.clear()
                    updateDots()
                }
                is PinLockout.Result.Locked -> {
                    dialog.dismiss()
                    showPinLockedNotice(r.remainingMs)
                }
            }
        }
    }

    mapOf(pb.btn1 to "1", pb.btn2 to "2", pb.btn3 to "3",
          pb.btn4 to "4", pb.btn5 to "5", pb.btn6 to "6",
          pb.btn7 to "7", pb.btn8 to "8", pb.btn9 to "9",
          pb.btn0 to "0").forEach { (btn, digit) ->
        btn.setOnClickListener { onDigit(digit) }
    }
    pb.btnBack.setOnClickListener {
        if (input.isNotEmpty()) {
            input.deleteCharAt(input.length - 1)
            updateDots()
            pb.tvError.visibility = View.INVISIBLE
        }
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

private fun Activity.showPinLockedNotice(remainingMs: Long) {
    val sec = (remainingMs + 999L) / 1000L
    AlertDialog.Builder(this)
        .setTitle("PIN 입력 잠김")
        .setMessage("PIN을 ${PinLockout.MAX_FAILS}회 연속 틀려 입력이 잠겼습니다.\n" +
            "${sec / 60}분 ${sec % 60}초 후 다시 시도하세요.")
        .setPositiveButton("확인", null)
        .show()
}
