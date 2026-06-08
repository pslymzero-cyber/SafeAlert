package com.wf11.safealert.utils

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.util.Log
import com.wf11.safealert.service.BleService
import kotlin.math.abs

/**
 * [v1.0.25 Req4] 이동형 플로팅 위젯 (시야를 가리는 전체화면 테두리 오버레이 완전 폐기)
 *
 * - WRAP_CONTENT 작은 창: 가장 위험한 단 1대의 최우선 기기 정보(상태·이름·RSSI)만 표시.
 * - 드래그(ACTION_MOVE)로 자유롭게 위치 이동 — 운전 시야를 가리지 않음. 옮긴 위치는 기억.
 * - 위젯을 '탭'(이동 없는 터치)하면 BleService에 ACTION_MUTE_DEVICE를 보내
 *   해당 기기의 알림(사이렌·플로팅 노출)을 10초간 Acknowledge 무음시킨다.
 */
object OverlayManager {

    private const val TAG          = "OverlayManager"
    private const val DRAG_SLOP_PX = 12f   // 이 이하 이동은 드래그가 아닌 '탭'으로 간주

    private var windowManager: WindowManager? = null
    private var floatingView: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null

    private var iconText: TextView? = null
    private var nameText: TextView? = null
    private var rssiText: TextView? = null
    private var background: GradientDrawable? = null
    private var pulseAnimator: ValueAnimator? = null

    private var currentDeviceId: String? = null
    private var currentDanger: Boolean? = null

    // 사용자가 드래그로 옮긴 위치 기억 (재표시 시 그대로 유지)
    private var savedX = Int.MIN_VALUE
    private var savedY = Int.MIN_VALUE

    // 드래그 추적 상태
    private var downX = 0
    private var downY = 0
    private var touchRawX = 0f
    private var touchRawY = 0f
    private var moved = false

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * 최우선 기기 1대를 플로팅 위젯에 표시.
     * 이미 떠 있으면 removeView/addView 없이 내용·색상만 갱신 → 깜빡임/위치 리셋 방지.
     */
    fun showFloating(context: Context, deviceId: String, name: String, rssi: Int, danger: Boolean) {
        if (!canDrawOverlays(context)) { Log.w(TAG, "오버레이 권한 없음"); return }
        currentDeviceId = deviceId
        if (floatingView != null) {
            updateContent(name, rssi, danger)
        } else {
            createFloating(context, name, rssi, danger)
        }
    }

    // ── 내부 헬퍼 ────────────────────────────────────────────────────────

    private fun updateContent(name: String, rssi: Int, danger: Boolean) {
        iconText?.text = if (danger) "🚨" else "⚠"
        nameText?.text = name
        rssiText?.text = "${rssi}dBm · 탭하면 10초 확인"
        if (currentDanger != danger) {
            background?.setColor(bgColor(danger))
            startPulse(danger)
            currentDanger = danger
        }
    }

    private fun bgColor(danger: Boolean): Int =
        if (danger) Color.argb(238, 205, 30, 30) else Color.argb(238, 205, 135, 0)

    private fun createFloating(context: Context, name: String, rssi: Int, danger: Boolean) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        fun dp(v: Int) = dpToPx(context, v)

        val bg = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(bgColor(danger))
            setStroke(dp(2), Color.argb(230, 255, 255, 255))
        }
        background = bg

        val icon = TextView(context).apply {
            text = if (danger) "🚨" else "⚠"
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(0, 0, dp(10), 0)
        }
        iconText = icon

        val nameTv = TextView(context).apply {
            text = name
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
        }
        nameText = nameTv

        val rssiTv = TextView(context).apply {
            text = "${rssi}dBm · 탭하면 10초 확인"
            textSize = 11f
            setTextColor(Color.argb(225, 255, 255, 255))
            maxLines = 1
        }
        rssiText = rssiTv

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(nameTv)
            addView(rssiTv)
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(18), dp(10))
            background = bg
            addView(icon)
            addView(textCol)
        }
        floatingView = container
        container.setOnTouchListener(buildTouchListener())

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (savedX != Int.MIN_VALUE) savedX else dp(16)
            y = if (savedY != Int.MIN_VALUE) savedY else dp(140)
        }
        params = lp

        try {
            wm.addView(container, lp)
            currentDanger = danger
            startPulse(danger)
            Log.d(TAG, "플로팅 위젯 표시: $name (${if (danger) "위험" else "경고"})")
        } catch (e: Exception) {
            Log.e(TAG, "플로팅 추가 실패: ${e.message}")
        }
    }

    /** 드래그(이동) vs 탭(음소거) 판별 터치 리스너. */
    private fun buildTouchListener(): View.OnTouchListener =
        View.OnTouchListener { view, event ->
            val p = params ?: return@OnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = p.x; downY = p.y
                    touchRawX = event.rawX; touchRawY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchRawX
                    val dy = event.rawY - touchRawY
                    if (abs(dx) > DRAG_SLOP_PX || abs(dy) > DRAG_SLOP_PX) moved = true
                    p.x = downX + dx.toInt()
                    p.y = downY + dy.toInt()
                    savedX = p.x; savedY = p.y
                    try { windowManager?.updateViewLayout(view, p) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        view.performClick()       // 접근성 대응
                        onTap(view.context)       // 탭 = 해당 기기 10초 Acknowledge 무음
                    }
                    true
                }
                else -> false
            }
        }

    private fun onTap(context: Context) {
        val id = currentDeviceId ?: return
        context.startService(Intent(context, BleService::class.java).apply {
            action = BleService.ACTION_MUTE_DEVICE
            putExtra(BleService.EXTRA_ID, id)
        })
        Log.d(TAG, "플로팅 탭 → 기기 음소거(Acknowledge) 요청: $id")
    }

    private fun startPulse(danger: Boolean) {
        pulseAnimator?.cancel()
        val view = floatingView ?: return
        val minAlpha = if (danger) 0.5f else 0.7f
        val speed    = if (danger) 450L else 750L
        pulseAnimator = ValueAnimator.ofFloat(1.0f, minAlpha).apply {
            duration    = speed
            repeatMode  = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim -> view.alpha = anim.animatedValue as Float }
            start()
        }
    }

    fun hideOverlay() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        floatingView?.let { v ->
            v.alpha = 1.0f
            try { windowManager?.removeView(v) }
            catch (e: Exception) { Log.w(TAG, "플로팅 제거 실패: ${e.message}") }
        }
        floatingView    = null
        iconText        = null
        nameText        = null
        rssiText        = null
        background      = null
        params          = null
        windowManager   = null
        currentDeviceId = null
        currentDanger   = null
        Log.d(TAG, "플로팅 위젯 숨김")
    }

    private fun dpToPx(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()
}
