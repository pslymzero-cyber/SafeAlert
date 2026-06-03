package com.wf11.safealert.utils

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.util.Log
import com.wf11.safealert.service.BleService

object OverlayManager {

    private const val TAG = "OverlayManager"
    private var rootView: FrameLayout? = null
    private var pulseAnimator: ValueAnimator? = null
    private var windowManager: WindowManager? = null

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun showDangerOverlay(context: Context, danger: Boolean) {
        if (!canDrawOverlays(context)) { Log.w(TAG, "오버레이 권한 없음"); return }
        hideOverlay()

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        // 색상 팔레트
        val coreColor  = if (danger) Color.argb(220, 220, 20,  20)  else Color.argb(200, 220, 150, 0)
        val midColor   = if (danger) Color.argb(120, 200, 0,   0)   else Color.argb(100, 200, 120, 0)
        val edgeColor  = if (danger) Color.argb(60,  180, 0,   0)   else Color.argb(50,  180, 100, 0)
        val transparent = Color.TRANSPARENT

        val root = FrameLayout(context)

        // ── 4방향 그라데이션 레이어 ──────────────────────────────
        val glowSize = dpToPx(context, 90)  // 가장자리 글로우 두께

        // 상단 글로우
        root.addView(makeGradView(context,
            intArrayOf(coreColor, midColor, edgeColor, transparent),
            GradientDrawable.Orientation.TOP_BOTTOM,
            width = ViewGroup.LayoutParams.MATCH_PARENT, height = glowSize,
            gravity = Gravity.TOP))

        // 하단 글로우
        root.addView(makeGradView(context,
            intArrayOf(coreColor, midColor, edgeColor, transparent),
            GradientDrawable.Orientation.BOTTOM_TOP,
            width = ViewGroup.LayoutParams.MATCH_PARENT, height = glowSize,
            gravity = Gravity.BOTTOM))

        // 좌측 글로우
        root.addView(makeGradView(context,
            intArrayOf(coreColor, midColor, edgeColor, transparent),
            GradientDrawable.Orientation.LEFT_RIGHT,
            width = glowSize, height = ViewGroup.LayoutParams.MATCH_PARENT,
            gravity = Gravity.START))

        // 우측 글로우
        root.addView(makeGradView(context,
            intArrayOf(coreColor, midColor, edgeColor, transparent),
            GradientDrawable.Orientation.RIGHT_LEFT,
            width = glowSize, height = ViewGroup.LayoutParams.MATCH_PARENT,
            gravity = Gravity.END))

        // ── 중앙 힌트 텍스트 ──────────────────────────────────────
        val hint = TextView(context).apply {
            text = if (danger) "⚠  위험  ⚠\n화면을 터치하면 10초 일시정지" else "주의\n화면을 터치하면 10초 일시정지"
            textSize = if (danger) 15f else 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(if (danger) 160 else 120, 0, 0, 0))
            setPadding(32, 16, 32, 16)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dpToPx(context, 100) }
        }
        root.addView(hint)

        // ── 터치 → 무음 ────────────────────────────────────────────
        root.setOnClickListener {
            context.startService(Intent(context, BleService::class.java).apply {
                action = BleService.ACTION_MUTE_TEMP
            })
        }

        // ── WindowManager 파라미터 ───────────────────────────────
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        try {
            wm.addView(root, params)
            rootView = root

            // ── 펄스 애니메이션 (alpha 반복) ──────────────────────
            val minAlpha = if (danger) 0.35f else 0.25f
            val maxAlpha = 1.0f
            val speed    = if (danger) 400L  else 650L

            pulseAnimator = ValueAnimator.ofFloat(minAlpha, maxAlpha).apply {
                duration     = speed
                repeatMode   = ValueAnimator.REVERSE
                repeatCount  = ValueAnimator.INFINITE
                addUpdateListener { anim ->
                    root.alpha = anim.animatedValue as Float
                }
                start()
            }
            Log.d(TAG, "그라데이션 오버레이 표시: ${if (danger) "위험" else "경고"}")
        } catch (e: Exception) {
            Log.e(TAG, "오버레이 추가 실패: ${e.message}")
        }
    }

    fun hideOverlay() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        rootView?.let { view ->
            try { windowManager?.removeView(view) }
            catch (e: Exception) { Log.w(TAG, "오버레이 제거 실패: ${e.message}") }
        }
        rootView = null
        windowManager = null
        Log.d(TAG, "오버레이 숨김")
    }

    private fun makeGradView(
        context: Context,
        colors: IntArray,
        orientation: GradientDrawable.Orientation,
        width: Int, height: Int,
        gravity: Int
    ): View {
        val grad = GradientDrawable(orientation, colors).apply {
            gradientType = GradientDrawable.LINEAR_GRADIENT
        }
        return View(context).apply {
            background = grad
            layoutParams = FrameLayout.LayoutParams(width, height, gravity)
        }
    }

    private fun dpToPx(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()
}
