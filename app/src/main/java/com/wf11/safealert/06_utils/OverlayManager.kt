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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wf11.safealert.R
import com.wf11.safealert.service.BleService
import kotlin.math.abs

/**
 * (v1.1.65) 화면 경보 사이드바 — 기존 이동형 플로팅 위젯(최우선 1대)을 대체한다.
 *
 * - 위험 대상을 '전부' 목록으로 표시한다. (기존: 위험도 최댓값 1대만 노출)
 * - 행을 탭하면 BleService 에 ACTION_MUTE_DEVICE 를 보내 그 기기를 30초간 Acknowledge 무음시킨다.
 * - 헤더(손잡이)를 드래그해 옮기고, 손을 떼면 가까운 좌/우 가장자리로 스냅 도킹한다.
 *   목록이 터치를 먼저 가져가므로 드래그 리스너는 루트가 아니라 헤더에만 건다.
 * - 헤더를 화면 밖으로 끝까지 밀면 ACTION_MUTE_ALL(전체 확인)을 보내고 사이드바를 접는다.
 * - 위험 대상이 0대가 되면 자동으로 접힌다 (showSidebar(빈 목록) = hideOverlay).
 * - 색·치수는 전부 res 토큰(SA.*)에서 읽는다. 코드에 리터럴 색을 두지 않는다.
 */
object OverlayManager {

    private const val TAG                 = "OverlayManager"
    private const val DRAG_SLOP_PX        = 12f   // 이 이하 이동은 드래그가 아닌 '탭'으로 간주
    private const val SIDEBAR_WIDTH_DP    = 236   // overlay_sidebar.xml 의 layout_width 와 반드시 일치
    private const val LIST_MAX_HEIGHT_DP  = 280   // 목록이 이보다 길면 사이드바 안에서 스크롤
    private const val EDGE_MARGIN_DP      = 12    // 스냅 도킹 시 화면 가장자리 여백
    private const val DISMISS_RATIO       = 0.45f // 폭의 이 비율만큼 화면 밖으로 밀면 '끝까지 드래그'
    private const val SNAP_DURATION_MS    = 160L

    private var windowManager: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null

    private var headerView: View? = null
    private var titleView: TextView? = null
    private var actionView: TextView? = null
    private var dividerView: View? = null
    private var listView: RecyclerView? = null
    private var hintView: TextView? = null
    private var adapter: HazardAdapter? = null

    private var pulseAnimator: ValueAnimator? = null
    private var snapAnimator: ValueAnimator? = null

    private var currentDanger: Boolean? = null

    // (v1.1.69) 접힘/펼침 상태. 위험 0대 = 접힘(헤더만), 1대 이상 = 펼침(목록 표시).
    //   사이드바 자체는 서비스가 도는 동안 늘 떠 있다 = 공정 변경 진입점이 상시 확보된다.
    private var collapsed: Boolean? = null

    // 사용자가 드래그로 옮긴 위치 기억 (재표시 시 그대로 유지)
    private var savedX = Int.MIN_VALUE
    private var savedY = Int.MIN_VALUE

    // 드래그 추적 상태
    private var downX = 0
    private var downY = 0
    private var touchRawX = 0f
    private var touchRawY = 0f
    private var moved = false

    // (v1.1.64 패치3-7) 화면 경보를 띄우지 못하게 된 사유. 정상이면 null.
    //   기존에는 권한 없음·addView 실패가 모두 로그로만 끝나 사용자는 "경보가 안 뜬다"는
    //   사실 자체를 알 수 없었다. BleService 가 이 값을 상시 알림으로 승격한다.
    @Volatile var overlayFaultReason: String? = null
        private set

    /** 화면 경보 이상/복구 통지 콜백. 인자가 null 이면 복구. */
    var onOverlayFault: ((String?) -> Unit)? = null

    /** (v1.1.69) 접힘 상태에서 헤더를 탭했을 때 통지. 공정(역할) 변경 진입점이다. */
    var onHeaderTap: (() -> Unit)? = null

    private fun setFault(reason: String?) {
        if (overlayFaultReason == reason) return
        overlayFaultReason = reason
        runCatching { onOverlayFault?.invoke(reason) }
    }

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** 사이드바 1행 = 위험 기기 1대. */
    data class HazardItem(
        val deviceId: String,
        val name: String,
        val rssi: Int,
        val danger: Boolean,
        val distText: String = ""
    )

    /**
     * (v1.1.69) 사이드바 상시 표시. 위험 대상이 0대여도 걷지 않는다.
     *   - 접힘(평상시): 헤더만. 현재 공정명 + [공정 변경] 배지. 헤더 탭 = 공정 변경 진입.
     *   - 펼침(경보 중): 위험 기기 목록까지. 경보가 뜨면 자동으로 펼쳐진다.
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
        updateContent(hazards, roleLabel)
    }

    private fun updateContent(hazards: List<HazardItem>, roleLabel: String) {
        val nowCollapsed = hazards.isEmpty()
        if (collapsed != nowCollapsed) {
            val bodyVis = if (nowCollapsed) View.GONE else View.VISIBLE
            dividerView?.visibility = bodyVis
            listView?.visibility    = bodyVis
            hintView?.visibility    = bodyVis
            actionView?.visibility  = if (nowCollapsed) View.VISIBLE else View.GONE
            collapsed = nowCollapsed
        }
        if (nowCollapsed) {
            titleView?.text = if (roleLabel.isNotEmpty()) roleLabel else "감시 중"
            adapter?.submit(hazards)
            // 평상시에 헤더가 깜빡이면 그 자체가 오인 신호가 된다. 펄스는 접힘 진입 시 정지.
            if (currentDanger != null) {
                pulseAnimator?.cancel(); pulseAnimator = null
                headerView?.alpha = 1.0f
                currentDanger = null
            }
            return
        }
        val danger = hazards.any { it.danger }
        titleView?.text = if (danger) "위험 ${hazards.size}대" else "경고 ${hazards.size}대"
        // 갱신은 ~120ms 마다 들어온다. 행 개수가 그대로면 높이를 다시 재지 않는다
        //   (매번 WRAP_CONTENT 로 되돌리면 목록이 상한을 넘을 때 프레임마다 늘었다 줄어 깜빡인다).
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
            .inflate(R.layout.overlay_sidebar, null) as LinearLayout

        val header  = root.findViewById<View>(R.id.overlay_header)
        val title   = root.findViewById<TextView>(R.id.overlay_title)
        val action  = root.findViewById<TextView>(R.id.overlay_action)
        val divider = root.findViewById<View>(R.id.overlay_divider)
        val list    = root.findViewById<RecyclerView>(R.id.overlay_list)
        val hint    = root.findViewById<TextView>(R.id.overlay_hint)
        val ad      = HazardAdapter(themed)

        list.layoutManager = LinearLayoutManager(themed)
        list.adapter       = ad
        list.itemAnimator  = null   // 경보 목록은 애니메이션 없이 즉시 교체 (지연 = 위험)
        header.setOnTouchListener(buildHeaderTouchListener())

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,   // NOT_TOUCH_MODAL 을 함축 = 바깥 터치는 뒤 앱으로 통과
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (savedX != Int.MIN_VALUE) savedX else defaultX(context)
            y = if (savedY != Int.MIN_VALUE) savedY else dpToPx(context, 140)
        }

        try {
            wm.addView(root, lp)
            // (v1.1.64 패치3-7) 뷰 참조 대입은 addView 성공 이후에만 한다.
            //   기존에는 addView 앞에서 대입해, 실패해도 참조가 non-null 로 남았다.
            //   그러면 다음 호출이 "이미 떠 있음" 분기로 빠져 다시는 addView 를 시도하지 않는다
            //   = 재부팅 전까지 화면 경보 영구 소실.
            windowManager = wm
            rootView      = root
            params        = lp
            headerView    = header
            titleView     = title
            actionView    = action
            dividerView   = divider
            listView      = list
            hintView      = hint
            adapter       = ad
            currentDanger = null
            collapsed     = null
            setFault(null)
            Log.d(TAG, "사이드바 표시")
        } catch (e: Exception) {
            Log.e(TAG, "사이드바 추가 실패: ${e.message}")
            windowManager = null
            rootView      = null
            params        = null
            headerView    = null
            titleView     = null
            actionView    = null
            dividerView   = null
            listView      = null
            hintView      = null
            adapter       = null
            currentDanger = null
            collapsed     = null
            setFault("화면 경보 표시 실패 — 소리·진동만 동작")
        }
    }

    /** 기본 위치 = 오른쪽 가장자리 도킹. 시야 중앙을 비운다. */
    private fun defaultX(context: Context): Int {
        val screenW = context.resources.displayMetrics.widthPixels
        return screenW - dpToPx(context, SIDEBAR_WIDTH_DP) - dpToPx(context, EDGE_MARGIN_DP)
    }

    /**
     * 목록 높이 상한. RecyclerView 에는 maxHeight 속성이 없으므로 측정 후 코드로 고정한다.
     * 상한을 넘으면 사이드바 안에서 스크롤되며, 목록 자체는 전체 대상을 계속 보유한다.
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
     * 헤더 드래그 리스너 (이동 · 스냅 도킹 · 끝까지 밀어 닫기).
     * 목록이 터치를 먼저 소비하므로 루트가 아니라 헤더에만 건다.
     */
    private fun buildHeaderTouchListener(): View.OnTouchListener =
        View.OnTouchListener { view, event ->
            val p    = params   ?: return@OnTouchListener false
            val root = rootView ?: return@OnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    downX     = p.x
                    downY     = p.y
                    touchRawX = event.rawX
                    touchRawY = event.rawY
                    moved     = false
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
                        // (v1.1.69) 접힘(평상시)에서만 공정 변경으로 넘어간다.
                        //   펼침 = 경보 중이므로, 오조작으로 경보 목록이 가려지지 않게 무동작을 유지한다.
                        if (collapsed == true) runCatching { onHeaderTap?.invoke() }
                    }
                    true
                }
                else -> false
            }
        }

    /**
     * 드래그를 놓은 뒤 처리.
     *  - 패널 폭의 DISMISS_RATIO 이상을 화면 밖으로 밀었으면 '끝까지 드래그'
     *    → BleService 에 ACTION_MUTE_ALL(전체 확인)을 보내고 접는다.
     *  - 그 외에는 가까운 좌/우 가장자리로 스냅 도킹하고 세로 위치는 화면 안으로 클램프한다.
     */
    private fun settleAfterDrag(context: Context, root: View, p: WindowManager.LayoutParams) {
        val dm      = context.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val w       = if (root.width  > 0) root.width  else dpToPx(context, SIDEBAR_WIDTH_DP)
        val h       = if (root.height > 0) root.height else 0
        val margin  = dpToPx(context, EDGE_MARGIN_DP)
        val maxY    = (screenH - h - margin).coerceAtLeast(margin)
        val slack   = (w * DISMISS_RATIO).toInt()

        if (p.x < -slack || p.x + w > screenW + slack) {
            // 밀어낸 쪽 가장자리 도킹 좌표로 되돌려 기억한다.
            savedX = if (p.x < 0) margin else screenW - w - margin
            savedY = p.y.coerceIn(margin, maxY)
            Log.d(TAG, "사이드바 끝까지 드래그 → 전체 확인(ACTION_MUTE_ALL)")
            runCatching {
                context.startService(Intent(context, BleService::class.java).apply {
                    action = BleService.ACTION_MUTE_ALL
                })
            }.onFailure { Log.w(TAG, "전체 확인 전송 실패: ${it.message}") }
            // (v1.1.69) 사이드바는 상시 노출이다. 걷지 않고 도킹 위치로 돌려놓는다
            //   (경보 해제는 BleService 가 처리하고, 그 결과가 접힘 상태 갱신으로 돌아온다).
            p.x = savedX
            p.y = savedY
            try { windowManager?.updateViewLayout(root, p) } catch (_: Exception) {}
            return
        }

        p.y    = p.y.coerceIn(margin, maxY)
        savedY = p.y
        animateSnapX(root, p, if (p.x + w / 2 < screenW / 2) margin else screenW - w - margin)
    }

    /** 가까운 가장자리로 미끄러져 붙는다. */
    private fun animateSnapX(root: View, p: WindowManager.LayoutParams, targetX: Int) {
        snapAnimator?.cancel()
        savedX = targetX
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

    /**
     * 긴급도 펄스. 패널 전체가 아니라 헤더 행에만 건다.
     * (기존 플로팅은 패널 전체 alpha 를 낮췄다 — 목록이 생긴 뒤에는 기기 이름·거리가 읽히지 않는다.)
     */
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

    /**
     * 사이드바를 화면에서 완전히 걷는다.
     * (v1.1.69) 감시 종료(서비스 정지) 때만 호출한다.
     *   위험 대상 0대는 '접힘'이지 철거가 아니다 — 여기서 걷으면 공정 변경 진입점이 사라진다.
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
        headerView    = null
        titleView     = null
        actionView    = null
        dividerView   = null
        listView      = null
        hintView      = null
        adapter       = null
        params        = null
        windowManager = null
        currentDanger = null
        collapsed     = null
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
