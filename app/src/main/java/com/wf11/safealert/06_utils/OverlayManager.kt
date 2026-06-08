package com.wf11.safealert.utils

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.text.SpannableStringBuilder
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
    private var hintTextView: TextView? = null
    private var gradientViews: List<View>? = null
    private var pulseAnimator: ValueAnimator? = null
    private var windowManager: WindowManager? = null
    private var currentDanger: Boolean? = null  // 현재 표시 중인 danger 상태

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * 경보 오버레이 표시 (깜빡임 없는 rootView 재사용 버전, v1.0.21)
     *
     * - rootView가 이미 표시 중이면 removeView/addView 없이 내용만 갱신 → 깜빡임 방지
     * - danger 상태가 바뀔 때만 그라데이션 색상 및 펄스 속도를 재생성
     * - 기기 목록(deviceList)이 바뀔 때마다 TextView 텍스트만 갱신
     *
     * @param danger      true=위험(빨강글로우), false=경고(주황글로우)
     * @param deviceList  Spannable 다중 기기 목록 (비어있으면 기본 문구 표시)
     */
    fun showDangerOverlay(context: Context, danger: Boolean, deviceList: CharSequence = "") {
        if (!canDrawOverlays(context)) { Log.w(TAG, "오버레이 권한 없음"); return }

        val existingRoot = rootView
        if (existingRoot != null) {
            // ── rootView 재사용 경로 ─────────────────────────────────────
            if (currentDanger != danger) {
                // 위험↔경고 전환: 그라데이션 색상만 갱신 (removeView/addView 없음)
                rebuildGradients(context, existingRoot, danger)
                hintTextView?.apply {
                    textSize = if (danger) 16f else 14f
                    setBackgroundColor(Color.argb(if (danger) 180 else 140, 0, 0, 0))
                }
                pulseAnimator?.cancel()
                val minAlpha = if (danger) 0.35f else 0.25f
                val speed    = if (danger) 400L  else 650L
                pulseAnimator = ValueAnimator.ofFloat(minAlpha, 1.0f).apply {
                    duration     = speed
                    repeatMode   = ValueAnimator.REVERSE
                    repeatCount  = ValueAnimator.INFINITE
                    addUpdateListener { anim -> existingRoot.alpha = anim.animatedValue as Float }
                    start()
                }
                currentDanger = danger
            }
            // 항상 텍스트 갱신 (기기 목록 변경 즉시 반영)
            hintTextView?.text = buildHintContent(danger, deviceList)
            return
        }

        // ── 신규 생성 경로 (rootView == null) ───────────────────────────
        currentDanger = danger
        createOverlay(context, danger, deviceList)
    }

    // ── 내부 헬퍼 ────────────────────────────────────────────────────────

    /**
     * 헤더 + 기기 목록 + 힌트 문자열 조합.
     * deviceList 가 비어있으면 이름 없는 기본 문구 반환.
     */
    private fun buildHintContent(danger: Boolean, deviceList: CharSequence): CharSequence {
        val header = if (danger) "🚨  위험 거리 접근!" else "⚠  경고 거리 접근"
        val footer = "\n화면을 터치하면 10초 일시정지"
        if (deviceList.isEmpty()) return "$header$footer"
        return SpannableStringBuilder().apply {
            append("$header\n")
            append(deviceList)
            append(footer)
        }
    }

    /**
     * 기존 그라데이션 뷰 4개를 제거하고 새 색상으로 재생성 (danger 상태 전환 시).
     * hintTextView 는 제거하지 않고 bringToFront 로 최상위 유지.
     */
    private fun rebuildGradients(context: Context, root: FrameLayout, danger: Boolean) {
        gradientViews?.forEach { root.removeView(it) }
        gradientViews = addGradientLayers(context, root, danger)
        hintTextView?.bringToFront()
    }

    /**
     * 4방향 글로우 그라데이션 뷰 생성 후 root 에 추가, 뷰 목록 반환.
     */
    private fun addGradientLayers(context: Context, root: FrameLayout, danger: Boolean): List<View> {
        val coreColor  = if (danger) Color.argb(220, 220, 20,  20) else Color.argb(200, 220, 150,  0)
        val midColor   = if (danger) Color.argb(120, 200,  0,   0) else Color.argb(100, 200, 120,  0)
        val edgeColor  = if (danger) Color.argb( 60, 180,  0,   0) else Color.argb( 50, 180, 100,  0)
        val colors     = intArrayOf(coreColor, midColor, edgeColor, Color.TRANSPARENT)
        val glowSize   = dpToPx(context, 90)

        val top    = makeGradView(context, colors,
            GradientDrawable.Orientation.TOP_BOTTOM,
            ViewGroup.LayoutParams.MATCH_PARENT, glowSize, Gravity.TOP)
        val bottom = makeGradView(context, colors,
            GradientDrawable.Orientation.BOTTOM_TOP,
            ViewGroup.LayoutParams.MATCH_PARENT, glowSize, Gravity.BOTTOM)
        val left   = makeGradView(context, colors,
            GradientDrawable.Orientation.LEFT_RIGHT,
            glowSize, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START)
        val right  = makeGradView(context, colors,
            GradientDrawable.Orientation.RIGHT_LEFT,
            glowSize, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END)

        listOf(top, bottom, left, right).forEach { root.addView(it) }
        return listOf(top, bottom, left, right)
    }

    /**
     * 오버레이 최초 생성 (rootView == null 일 때).
     */
    private fun createOverlay(context: Context, danger: Boolean, deviceList: CharSequence) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val root = FrameLayout(context)
        gradientViews = addGradientLayers(context, root, danger)

        // ── 텍스트 박스 (기기 목록 + 힌트) ────────────────────────────
        val hint = TextView(context).apply {
            text     = buildHintContent(danger, deviceList)
            textSize = if (danger) 16f else 14f
            setTextColor(Color.WHITE)
            gravity  = Gravity.CENTER
            setBackgroundColor(Color.argb(if (danger) 180 else 140, 0, 0, 0))
            setPadding(40, 20, 40, 20)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dpToPx(context, 120) }
        }
        root.addView(hint)
        hintTextView = hint

        // ── 터치 → 10초 무음 ────────────────────────────────────────
        root.setOnClickListener {
            context.startService(Intent(context, BleService::class.java).apply {
                action = BleService.ACTION_MUTE_TEMP
            })
        }

        // ── WindowManager 파라미터 ───────────────────────────────────
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
            val speed    = if (danger) 400L  else 650L
            pulseAnimator = ValueAnimator.ofFloat(minAlpha, 1.0f).apply {
                duration     = speed
                repeatMode   = ValueAnimator.REVERSE
                repeatCount  = ValueAnimator.INFINITE
                addUpdateListener { anim -> root.alpha = anim.animatedValue as Float }
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
        rootView      = null
        hintTextView  = null
        gradientViews = null
        currentDanger = null
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
            background   = grad
            layoutParams = FrameLayout.LayoutParams(width, height, gravity)
        }
    }

    private fun dpToPx(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()
}
