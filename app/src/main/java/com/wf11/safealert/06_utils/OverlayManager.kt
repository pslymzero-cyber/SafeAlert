package com.wf11.safealert.utils

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wf11.safealert.R
import com.wf11.safealert.service.BleService
import kotlin.math.abs

/**
 * (v1.1.70) 화면 경보 사이드바 — 화면 좌/우 가장자리에 밀착하는 엣지 패널이다.
 *
 * 창은 두 상태뿐이고, 보이는 자식이 곧 창의 폭이 된다.
 *   접힘 = overlay_handle 만  → 폭 20dp. 평상시 화면에 남는 유일한 요소.
 *   펼침 = overlay_panel 만   → 폭 236dp.
 *
 * 상태 전이
 *   위험 1대 이상 → 자동 펼침(경보 목록). 경보 중에는 접히지 않는다 —
 *                   위험 목록을 실수로 감출 수 없어야 한다.
 *   위험 0대      → 자동 접힘. 손잡이를 탭하면 수동 펼침(공정 변경 진입)이 되고 [닫기] 로 되돌린다.
 *
 * v1.1.69 까지는 가장자리에서 12dp 떨어진 236dp 카드가 접힌 뒤에도 그대로 남았다. 그것은
 * 사이드바가 아니라 플로팅 위젯이다. 이 판의 요점은 (a) 여백 0 밀착과 (b) 접을 때 폭 자체가
 * 20dp 로 줄어드는 것이다.
 *
 * - 위험 대상을 전부 목록으로 표시한다. 행을 탭하면 ACTION_MUTE_DEVICE(그 기기만 30초 확인).
 * - 손잡이와 헤더가 드래그 지점이다. 목록이 터치를 먼저 가져가므로 루트에는 걸지 않는다.
 * - 경보 중 헤더를 화면 밖으로 끝까지 밀면 ACTION_MUTE_ALL(전체 확인). 사이드바는 걷지 않는다.
 * - 색·치수는 전부 res 토큰(sa_*)에서 읽는다. 코드에 리터럴 색을 두지 않는다.
 */
object OverlayManager {

    private const val TAG                 = "OverlayManager"
    private const val DRAG_SLOP_PX        = 12f   // 이 이하 이동은 드래그가 아닌 '탭'으로 간주
    private const val PANEL_WIDTH_DP      = 236   // overlay_panel  의 layout_width 와 반드시 일치
    private const val HANDLE_WIDTH_DP     = 20    // overlay_handle 의 layout_width 와 반드시 일치
    private const val HANDLE_HEIGHT_DP    = 96    // overlay_handle 의 layout_height 와 반드시 일치
    private const val LIST_MAX_HEIGHT_DP  = 280   // 목록이 이보다 길면 사이드바 안에서 스크롤
    private const val DISMISS_RATIO       = 0.45f // 폭의 이 비율만큼 화면 밖으로 밀면 '끝까지 드래그'
    private const val SNAP_DURATION_MS    = 160L

    private var windowManager: WindowManager? = null
    private var rootView: ViewGroup? = null
    private var params: WindowManager.LayoutParams? = null

    private var handleView: View? = null
    private var panelView: View? = null
    private var headerView: View? = null
    private var titleView: TextView? = null
    private var actionView: TextView? = null
    private var dividerView: View? = null
    private var listView: RecyclerView? = null
    private var hintView: TextView? = null
    private var collapseView: TextView? = null
    private var adapter: HazardAdapter? = null

    private var pulseAnimator: ValueAnimator? = null
    private var snapAnimator: ValueAnimator? = null

    private var currentDanger: Boolean? = null

    /** 접힘 여부. null = 아직 한 번도 반영하지 않음(최초 갱신에서 강제 적용). */
    private var collapsed: Boolean? = null

    /** 위험 0대인데 손잡이를 눌러 펼쳐 둔 상태. 경보가 뜨면 자동으로 풀린다. */
    private var manualExpand = false

    /** true = 오른쪽 가장자리 도킹. 좌/우 어느 쪽이든 여백 없이 붙는다. */
    private var dockEnd = true

    /** 마지막으로 반영한 내용. 손잡이 탭으로 펼칠 때 이 값으로 다시 그린다. */
    private var lastHazards: List<HazardItem> = emptyList()
    private var lastRole: String = ""

    // 드래그로 옮긴 세로 위치만 기억한다. 가로는 항상 도킹 계산 결과라 기억할 이유가 없다.
    private var savedY = Int.MIN_VALUE

    private var downX = 0
    private var downY = 0
    private var touchRawX = 0f
    private var touchRawY = 0f
    private var moved = false

    /**
     * 화면 경보 이상 사유. null 이면 정상.
     * 소리·진동만 남은 상태를 알림에 드러내기 위한 값이라 서비스 스레드에서도 읽는다.
     */
    @Volatile
    var overlayFaultReason: String? = null
        private set

    /** 화면 경보 이상/복구 통지 콜백. 인자가 null 이면 복구. */
    var onOverlayFault: ((String?) -> Unit)? = null

    /** 접힘/수동 펼침 상태에서 헤더를 탭했을 때 통지. 공정(역할) 변경 진입점이다. */
    var onHeaderTap: (() -> Unit)? = null

    private fun setFault(reason: String?) {
        if (overlayFaultReason == reason) return
        overlayFaultReason = reason
        runCatching { onOverlayFault?.invoke(reason) }
    }

    /** 다른 앱 위에 표시 권한. 없으면 사이드바 자체를 띄울 수 없다. */
    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * 사이드바 한 행에 표시할 위험 기기.
     * distText 가 비어 있으면 dBm 을 대신 보여준다(UWB 미측정 구간).
     */
    data class HazardItem(
        val deviceId: String,
        val name: String,
        val rssi: Int,
        val danger: Boolean,
        val distText: String = ""
    )

    /**
     * 사이드바 상시 표시. 위험 대상이 0대여도 걷지 않고 손잡이만 남긴다.
     *   - 접힘(평상시): 20dp 손잡이. 탭하면 펼쳐져 공정 변경으로 갈 수 있다.
     *   - 펼침(경보 중): 위험 기기 목록. 경보가 뜨면 자동으로 펼쳐진다.
     * 걷지 않는 이유 = 공정 변경 경로가 경보 유무와 무관하게 늘 살아 있어야 하기 때문이다.
     * 이미 떠 있으면 removeView/addView 없이 내용만 갱신 → 깜빡임/위치 리셋 방지.
     */
    fun showSidebar(context: Context, hazards: List<HazardItem>, roleLabel: String = "") {
        if (!canDrawOverlays(context)) {
            Log.w(TAG, "오버레이 권한 없음")
            setFault("화면 경보 권한 꺼짐 — 소리·진동만 동작")
            return
        }
        if (rootView == null) createSidebar(context)
        if (rootView == null) return   // addView 실패 — 사유는 createSidebar 가 이미 setFault 로 기록
        updateContent(context, hazards, roleLabel)
    }

    private fun updateContent(context: Context, hazards: List<HazardItem>, roleLabel: String) {
        lastHazards = hazards
        lastRole    = roleLabel
        // 경보가 뜨면 수동 펼침 플래그는 의미를 잃는다. 여기서 풀어야 경보가 끝날 때 다시 접힌다.
        if (hazards.isNotEmpty()) manualExpand = false

        val nowCollapsed = hazards.isEmpty() && !manualExpand
        if (collapsed != nowCollapsed) {
            handleView?.visibility = if (nowCollapsed) View.VISIBLE else View.GONE
            panelView?.visibility  = if (nowCollapsed) View.GONE    else View.VISIBLE
            collapsed = nowCollapsed
            // 보이는 자식이 바뀌면 창 폭도 바뀐다. 가장자리 밀착을 유지하려면 x 를 다시 잡아야 한다.
            applyGeometry(context)
        }

        if (nowCollapsed) {
            stopPulse()
            return
        }

        // 펼침이지만 위험 0대 = 손잡이를 눌러 연 공정 변경 화면. 목록·힌트는 감추고 진입 배지만 띄운다.
        val idle    = hazards.isEmpty()
        val bodyVis = if (idle) View.GONE else View.VISIBLE
        dividerView?.visibility  = bodyVis
        listView?.visibility     = bodyVis
        hintView?.visibility     = bodyVis
        actionView?.visibility   = if (idle) View.VISIBLE else View.GONE
        collapseView?.visibility = if (idle) View.VISIBLE else View.GONE

        if (idle) {
            titleView?.text = if (roleLabel.isNotEmpty()) roleLabel else "감시 중"
            adapter?.submit(hazards)
            // 평상시에 헤더가 깜빡이면 그 자체가 오인 신호가 된다.
            stopPulse()
            return
        }

        val danger = hazards.any { it.danger }
        titleView?.text = if (danger) "위험 ${hazards.size}대" else "경고 ${hazards.size}대"
        val sizeChanged = adapter?.submit(hazards) ?: false
        if (sizeChanged) listView?.let { capListHeight(it) }
        if (currentDanger != danger) {
            startPulse(danger)
            currentDanger = danger
        }
    }

    private fun createSidebar(context: Context) {
        val wm     = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val themed = ContextThemeWrapper(context, R.style.Theme_SafeAlert)
        val root   = LayoutInflater.from(themed)
            .inflate(R.layout.overlay_sidebar, null) as ViewGroup

        val handle   = root.findViewById<View>(R.id.overlay_handle)
        val panel    = root.findViewById<View>(R.id.overlay_panel)
        val header   = root.findViewById<View>(R.id.overlay_header)
        val title    = root.findViewById<TextView>(R.id.overlay_title)
        val action   = root.findViewById<TextView>(R.id.overlay_action)
        val divider  = root.findViewById<View>(R.id.overlay_divider)
        val list     = root.findViewById<RecyclerView>(R.id.overlay_list)
        val hint     = root.findViewById<TextView>(R.id.overlay_hint)
        val collapse = root.findViewById<TextView>(R.id.overlay_collapse)
        val ad       = HazardAdapter(themed)

        list.layoutManager = LinearLayoutManager(themed)
        list.adapter       = ad
        list.itemAnimator  = null   // 경보 목록은 애니메이션 없이 즉시 교체 (지연 = 위험)

        // 손잡이 탭 = 펼침. 헤더 탭(위험 0대) = 공정 변경. 둘 다 드래그로 좌/우·위아래 이동이 된다.
        handle.setOnTouchListener(buildDragTouchListener { expandManually() })
        header.setOnTouchListener(buildDragTouchListener { requestRoleChange() })
        action.setOnClickListener   { requestRoleChange() }
        collapse.setOnClickListener { collapseManually() }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,   // NOT_TOUCH_MODAL 을 함축 = 바깥 터치는 뒤 앱으로 통과
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 최초 상태는 접힘 = 손잡이 폭. 가장자리에 여백 없이 붙인다.
            x = dockX(context, dpToPx(context, HANDLE_WIDTH_DP))
            y = if (savedY != Int.MIN_VALUE) savedY else defaultY(context)
        }

        try {
            wm.addView(root, lp)
            // (v1.1.64 패치3-7) 뷰 참조 대입은 addView 성공 이후에만 한다.
            //   실패했는데 rootView 가 non-null 로 남으면 다음 호출이 '이미 떠 있음' 으로 새어
            //   재부팅 전까지 화면 경보가 영구 소실된다.
            windowManager = wm
            rootView      = root
            params        = lp
            handleView    = handle
            panelView     = panel
            headerView    = header
            titleView     = title
            actionView    = action
            dividerView   = divider
            listView      = list
            hintView      = hint
            collapseView  = collapse
            adapter       = ad
            currentDanger = null
            collapsed     = true      // 레이아웃 기본값(손잡이만 VISIBLE)과 일치시킨다
            manualExpand  = false
            applyDockBackground()
            setFault(null)
            Log.d(TAG, "사이드바 표시 (도킹=" + (if (dockEnd) "오른쪽" else "왼쪽") + ")")
        } catch (e: Exception) {
            Log.e(TAG, "사이드바 추가 실패: ${e.message}")
            windowManager = null
            rootView      = null
            params        = null
            handleView    = null
            panelView     = null
            headerView    = null
            titleView     = null
            actionView    = null
            dividerView   = null
            listView      = null
            hintView      = null
            collapseView  = null
            adapter       = null
            currentDanger = null
            collapsed     = null
            setFault("화면 경보 표시 실패 — 소리·진동만 동작")
        }
    }

    /** 손잡이 탭 = 수동 펼침. 위험이 없어도 공정 변경 진입점이 열린다. */
    private fun expandManually() {
        if (manualExpand) return
        manualExpand = true
        val ctx = rootView?.context ?: return
        updateContent(ctx, lastHazards, lastRole)
    }

    /** [닫기] 탭 = 손잡이로 되돌린다. 경보 중에는 [닫기] 자체가 숨겨져 있다. */
    private fun collapseManually() {
        if (!manualExpand) return
        manualExpand = false
        val ctx = rootView?.context ?: return
        updateContent(ctx, lastHazards, lastRole)
    }

    /**
     * 공정 변경 진입. 위험 대상이 있으면 무시한다 —
     * 경보 중 오조작으로 위험 목록이 가려지는 일이 없어야 한다.
     */
    private fun requestRoleChange() {
        if (lastHazards.isNotEmpty()) return
        runCatching { onHeaderTap?.invoke() }
    }

    /** 도킹 x. 왼쪽이면 0, 오른쪽이면 화면폭 - 창폭. 여백은 두지 않는다 = 가장자리 밀착. */
    private fun dockX(context: Context, widthPx: Int): Int =
        if (dockEnd) (context.resources.displayMetrics.widthPixels - widthPx).coerceAtLeast(0) else 0

    /** 기본 세로 위치 = 손잡이가 화면 중앙에 오도록. */
    private fun defaultY(context: Context): Int =
        ((context.resources.displayMetrics.heightPixels - dpToPx(context, HANDLE_HEIGHT_DP)) / 2)
            .coerceAtLeast(0)

    /**
     * 접힘/펼침으로 창 폭이 바뀐 뒤 밀착 좌표를 다시 잡는다.
     * 절대 좌표(Gravity.TOP or START) 방식이라 폭이 바뀌면 x 를 손대지 않는 한 가장자리에서 떨어진다.
     */
    private fun applyGeometry(context: Context) {
        val root = rootView ?: return
        val p    = params   ?: return
        val w = dpToPx(context, if (collapsed == true) HANDLE_WIDTH_DP else PANEL_WIDTH_DP)
        p.x = dockX(context, w)
        p.y = p.y.coerceAtLeast(0)
        try { windowManager?.updateViewLayout(root, p) } catch (_: Exception) {}
        // 실제 높이는 측정 뒤에야 안다. 펼치며 커진 판이 화면 아래로 넘치면 끌어올린다.
        root.post {
            val pp   = params ?: return@post
            val maxY = (context.resources.displayMetrics.heightPixels - root.height).coerceAtLeast(0)
            if (pp.y > maxY) {
                pp.y   = maxY
                savedY = maxY
                try { windowManager?.updateViewLayout(root, pp) } catch (_: Exception) {}
            }
        }
    }

    /** 도킹 방향에 맞춰 바깥 모서리가 각진 배경으로 교체한다(안쪽만 둥근 판 = 엣지 패널). */
    private fun applyDockBackground() {
        handleView?.let {
            setBackgroundKeepPadding(
                it,
                if (dockEnd) R.drawable.shape_overlay_handle_end
                else         R.drawable.shape_overlay_handle_start
            )
        }
        panelView?.let {
            setBackgroundKeepPadding(
                it,
                if (dockEnd) R.drawable.shape_overlay_panel_end
                else         R.drawable.shape_overlay_panel_start
            )
        }
    }

    /** 배경 교체는 drawable 이 패딩을 갖고 있으면 뷰 패딩을 덮어쓴다. 원래 값을 되돌려 둔다. */
    private fun setBackgroundKeepPadding(v: View, resId: Int) {
        val l = v.paddingLeft
        val t = v.paddingTop
        val r = v.paddingRight
        val b = v.paddingBottom
        v.setBackgroundResource(resId)
        v.setPadding(l, t, r, b)
    }

    /**
     * 목록 높이 상한. RecyclerView 를 wrap_content 로 두면 기기가 늘어날수록 사이드바가
     * 화면을 다 덮는다. 상한을 넘으면 그때부터 사이드바 안에서 스크롤시킨다.
     */
    private fun capListHeight(list: RecyclerView) {
        val maxPx = dpToPx(list.context, LIST_MAX_HEIGHT_DP)
        val lp    = list.layoutParams ?: return
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        list.layoutParams = lp
        list.post {
            val p = list.layoutParams ?: return@post
            if (list.height > maxPx && p.height != maxPx) {
                p.height = maxPx
                list.layoutParams = p
            }
        }
    }

    private fun dpToPx(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()

    /**
     * 드래그 리스너(이동 · 좌/우 밀착 스냅 · 경보 중 끝까지 밀어 전체 확인).
     * 목록이 터치를 먼저 소비하므로 루트가 아니라 손잡이와 헤더에만 건다.
     * onTap = 이동 없이 뗐을 때의 동작(손잡이 = 펼침, 헤더 = 공정 변경).
     */
    private fun buildDragTouchListener(onTap: () -> Unit): View.OnTouchListener =
        View.OnTouchListener { view, event ->
            val p    = params   ?: return@OnTouchListener false
            val root = rootView ?: return@OnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    downX = p.x
                    downY = p.y
                    touchRawX = event.rawX
                    touchRawY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchRawX
                    val dy = event.rawY - touchRawY
                    if (abs(dx) > DRAG_SLOP_PX || abs(dy) > DRAG_SLOP_PX) moved = true
                    p.x = downX + dx.toInt()
                    p.y = downY + dy.toInt()
                    try { windowManager?.updateViewLayout(root, p) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved) settleAfterDrag(view.context, root, p)
                    else {
                        view.performClick()   // 접근성 대응
                        onTap()
                    }
                    true
                }
                else -> false
            }
        }

    /**
     * 드래그를 뗀 뒤 정리.
     *   - 경보 중 화면 밖으로 끝까지 밀면 전체 확인(ACTION_MUTE_ALL). 사이드바는 걷지 않는다.
     *   - 그 외에는 가까운 쪽 가장자리에 여백 없이 붙인다.
     */
    private fun settleAfterDrag(context: Context, root: View, p: WindowManager.LayoutParams) {
        val dm      = context.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val w       = if (root.width > 0) root.width
                      else dpToPx(context, if (collapsed == true) HANDLE_WIDTH_DP else PANEL_WIDTH_DP)
        val h       = if (root.height > 0) root.height else 0
        val maxY    = (screenH - h).coerceAtLeast(0)
        val slack   = (w * DISMISS_RATIO).toInt()
        val pushedOut = p.x < -slack || p.x + w > screenW + slack

        // 접힘 상태의 손잡이는 폭이 20dp 라 임계가 9dp 밖에 안 된다. 스치기만 해도 전체 확인이
        // 나가므로 경보가 떠 있는 펼침에서만 이 동작을 허용한다.
        if (pushedOut && collapsed == false && lastHazards.isNotEmpty()) {
            dockEnd = p.x >= 0
            savedY  = p.y.coerceIn(0, maxY)
            Log.d(TAG, "사이드바 끝까지 드래그 → 전체 확인(ACTION_MUTE_ALL)")
            runCatching {
                context.startService(Intent(context, BleService::class.java).apply {
                    action = BleService.ACTION_MUTE_ALL
                })
            }.onFailure { Log.w(TAG, "전체 확인 전송 실패: ${it.message}") }
            // 사이드바는 상시 노출이다. 걷지 않고 밀어낸 쪽 가장자리로 되돌린다
            //   (경보 해제는 BleService 가 처리하고, 그 결과가 접힘 상태 갱신으로 돌아온다).
            applyDockBackground()
            p.x = dockX(context, w)
            p.y = savedY
            try { windowManager?.updateViewLayout(root, p) } catch (_: Exception) {}
            return
        }

        dockEnd = p.x + w / 2 >= screenW / 2
        p.y     = p.y.coerceIn(0, maxY)
        savedY  = p.y
        applyDockBackground()
        animateSnapX(root, p, dockX(context, w))
    }

    /** 가장자리 밀착 위치로 미끄러뜨린다. */
    private fun animateSnapX(root: View, p: WindowManager.LayoutParams, targetX: Int) {
        snapAnimator?.cancel()
        if (p.x == targetX) {
            try { windowManager?.updateViewLayout(root, p) } catch (_: Exception) {}
            return
        }
        snapAnimator = ValueAnimator.ofInt(p.x, targetX).apply {
            duration = SNAP_DURATION_MS
            addUpdateListener { anim ->
                p.x = anim.animatedValue as Int
                try { windowManager?.updateViewLayout(root, p) } catch (_: Exception) {}
            }
            start()
        }
    }

    /** 헤더 점멸. 위험은 빠르고 깊게, 경고는 느리고 얕게 — 소리를 못 듣는 현장에서 등급을 구분한다. */
    private fun startPulse(danger: Boolean) {
        pulseAnimator?.cancel()
        val header = headerView ?: return
        header.alpha = 1.0f
        pulseAnimator = ValueAnimator.ofFloat(1.0f, if (danger) 0.5f else 0.7f).apply {
            duration    = if (danger) 450L else 750L
            repeatMode  = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim -> header.alpha = anim.animatedValue as Float }
            start()
        }
    }

    /** 경보가 끝났을 때 점멸을 멈추고 불투명도를 되돌린다. */
    private fun stopPulse() {
        if (currentDanger == null && pulseAnimator == null) return
        pulseAnimator?.cancel()
        pulseAnimator = null
        headerView?.alpha = 1.0f
        currentDanger = null
    }

    /**
     * 사이드바를 화면에서 완전히 걷는다.
     * 감시 종료(서비스 정지) 때만 호출한다.
     *   위험 대상 0대는 '접힘' 이지 철거가 아니다 — 여기서 걷으면 공정 변경 진입점이 사라진다.
     */
    fun hideOverlay() {
        pulseAnimator?.cancel(); pulseAnimator = null
        snapAnimator?.cancel();  snapAnimator  = null
        headerView?.alpha = 1.0f
        rootView?.let { v ->
            try { windowManager?.removeView(v) }
            catch (e: Exception) { Log.w(TAG, "사이드바 제거 실패: ${e.message}") }
        }
        rootView      = null
        handleView    = null
        panelView     = null
        headerView    = null
        titleView     = null
        actionView    = null
        dividerView   = null
        listView      = null
        hintView      = null
        collapseView  = null
        adapter       = null
        params        = null
        windowManager = null
        currentDanger = null
        collapsed     = null
        manualExpand  = false
        lastHazards   = emptyList()
        lastRole      = ""
    }

    /** 위험 기기 목록 어댑터. 행 탭 = 그 기기만 30초 Acknowledge 무음. */
    private class HazardAdapter(private val ctx: Context) : RecyclerView.Adapter<HazardAdapter.VH>() {

        private val items = ArrayList<HazardItem>()

        /** 목록을 갱신하고 '행 개수가 바뀌었는지'를 돌려준다(사이드바 높이 재계산 트리거). */
        fun submit(list: List<HazardItem>): Boolean {
            val sizeChanged = items.size != list.size
            items.clear()
            items.addAll(list)
            // 개수가 같으면 제자리 갱신 — 거리·dBm 만 매 프레임 바뀌므로 뷰홀더를 버릴 이유가 없다.
            if (sizeChanged) notifyDataSetChanged() else notifyItemRangeChanged(0, items.size)
            return sizeChanged
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(ctx).inflate(R.layout.item_overlay_hazard, parent, false)
            (v.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin =
                ctx.resources.getDimensionPixelSize(R.dimen.sa_space_xs)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val lvColor = ContextCompat.getColor(
                ctx, if (item.danger) R.color.sa_danger else R.color.sa_warning
            )
            holder.icon.text = if (item.danger) "위험" else "경고"
            holder.icon.setTextColor(lvColor)
            holder.name.text = item.name
            holder.name.setTextColor(lvColor)
            val meas = if (item.distText.isNotEmpty()) item.distText else "${item.rssi}dBm"
            holder.meas.text = "${meas} · 탭하면 30초 확인"
            // shape_overlay_row 는 흰색 판이며, 여기서 위험/경고 틴트를 곱해 색을 결정한다.
            holder.itemView.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(ctx, if (item.danger) R.color.sa_tint_rose else R.color.sa_tint_amber)
            )
            holder.itemView.setOnClickListener { v ->
                runCatching {
                    v.context.startService(Intent(v.context, BleService::class.java).apply {
                        action = BleService.ACTION_MUTE_DEVICE
                        putExtra(BleService.EXTRA_ID, item.deviceId)
                    })
                }.onFailure { Log.w(TAG, "기기 확인 전송 실패: ${it.message}") }
                Log.d(TAG, "행 탭 → 기기 확인(30초 무음): ${item.deviceId}")
            }
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon: TextView = view.findViewById(R.id.row_icon)
            val name: TextView = view.findViewById(R.id.row_name)
            val meas: TextView = view.findViewById(R.id.row_meas)
        }
    }
}
