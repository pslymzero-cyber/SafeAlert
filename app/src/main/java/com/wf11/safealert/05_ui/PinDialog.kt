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

// ── PIN 다이얼로그 ──────────────────────────────────────────
//   설정 진입·비콘 공유 전송/삭제 공용. PIN 일치 시 닫고 onSuccess 실행.
//   (v1.1.97) 연속 오류 잠금(PinLockout) — 잠금 중에는 입력 창 대신 남은 시간을 안내한다.
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
            // (v1.1.90) 설정 PIN — 값은 빌드 시 주입(BuildConfig + CI Secrets). 3자리 유지(장갑 입력)
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

// (v1.1.97) PIN 잠금 상태는 설정(dev_settings)과 분리된 파일에 둔다 — 설정 초기화와 무관하게 유지.
private const val PIN_LOCKOUT_PREFS = "pin_lockout"

//   commit(동기) — 입력 직후 앱을 닫아도 횟수가 남게. 값 4개짜리 파일이라 부담이 없다.
private class PrefsPinStore(private val p: SharedPreferences) : PinLockout.Store {
    override var fails: Int
        get() = p.getInt("fails", 0)
        set(v) { p.edit().putInt("fails", v).commit() }
    override var lockWallUntil: Long
        get() = p.getLong("lock_wall_until", 0L)
        set(v) { p.edit().putLong("lock_wall_until", v).commit() }
    override var lockElapsedUntil: Long
        get() = p.getLong("lock_elapsed_until", 0L)
        set(v) { p.edit().putLong("lock_elapsed_until", v).commit() }
    override var lockBoot: Int
        get() = p.getInt("lock_boot", 0)
        set(v) { p.edit().putInt("lock_boot", v).commit() }
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
