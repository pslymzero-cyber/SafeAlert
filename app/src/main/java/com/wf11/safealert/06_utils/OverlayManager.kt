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
 * On-screen alert sidebar — an edge panel docked flush to the left/right screen edge.
 *
 * The window has only two states, and the visible child sets the window width.
 *   Collapsed = overlay_handle only → 20dp wide. The only element left on screen normally.
 *   Expanded  = overlay_panel only  → 236dp wide.
 *
 * State transitions
 *   1+ hazard devices (WARNING or above) → auto-expand (alert list). Never collapses during an alert —
 *                       the alert list must not be hideable by accident.
 *   0 hazard devices  → auto-collapse. Tapping the handle expands it manually (process-change entry);
 *                       "닫기" collapses it again.
 *
 * Key points: (a) flush with zero margin, and (b) collapsing shrinks the window width itself to 20dp.
 * A 236dp card left in place when collapsed would be a floating widget, not a sidebar.
 *
 * - Lists every hazard target (warning and danger). Tapping a row sends ACTION_MUTE_DEVICE (acknowledges only that device for 30 s).
 * - The handle and header are the drag points. The list takes touches first, so nothing is attached to the root.
 * - During an alert, pushing the header all the way off screen sends ACTION_MUTE_ALL (acknowledge all).
 *   The sidebar is not removed.
 * - All colors and dimensions come from res tokens (sa_*). No literal colors in code.
 */
object OverlayManager {

    private const val TAG                 = "OverlayManager"
    private const val DRAG_SLOP_PX        = 12f   // movement below this counts as a tap, not a drag
    private const val PANEL_WIDTH_DP      = 236   // must match overlay_panel's layout_width
    private const val HANDLE_WIDTH_DP     = 20    // must match overlay_handle's layout_width
    private const val HANDLE_HEIGHT_DP    = 96    // must match overlay_handle's layout_height
    private const val LIST_MAX_HEIGHT_DP  = 280   // a longer list scrolls inside the sidebar
    private const val DISMISS_RATIO       = 0.45f // pushing this fraction of the width off screen = drag all the way
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

    /** Collapsed state. null = never applied yet (forced on the first update). */
    private var collapsed: Boolean? = null

    /** Expanded by tapping the handle while there are 0 danger devices. Cleared automatically when an alert fires. */
    private var manualExpand = false

    /** true = docked to the right edge. Either side docks with no margin. */
    private var dockEnd = true

    /** Last applied content. Redrawn from this when a handle tap expands the panel. */
    private var lastHazards: List<HazardItem> = emptyList()
    private var lastRole: String = ""

    // Only the dragged vertical position is remembered; horizontal always comes from docking.
    private var savedY = Int.MIN_VALUE

    private var downX = 0
    private var downY = 0
    private var touchRawX = 0f
    private var touchRawY = 0f
    private var moved = false

    /**
     * On-screen alert fault reason. null = normal.
     * Read from the service thread too, since it surfaces in the notification that only sound and vibration remain.
     */
    @Volatile
    var overlayFaultReason: String? = null
        private set

    /** Callback for on-screen alert fault/recovery. A null argument means recovered. */
    var onOverlayFault: ((String?) -> Unit)? = null

    /**
     * Called when the header or the "공정 변경" badge is tapped while expanded with no hazards; entry point for process (role) change.
     */
    var onHeaderTap: (() -> Unit)? = null

    private fun setFault(reason: String?) {
        if (overlayFaultReason == reason) return
        overlayFaultReason = reason
        runCatching { onOverlayFault?.invoke(reason) }
    }

    /** Permission to draw over other apps. Without it the sidebar cannot be shown at all. */
    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * A danger device shown in one sidebar row.
     * dBm is shown instead when distText is empty (no UWB measurement).
     */
    data class HazardItem(
        val deviceId: String,
        val name: String,
        val rssi: Int,
        val danger: Boolean,
        val distText: String = ""
    )

    /**
     * Shows the sidebar permanently. Even with 0 hazard targets it stays, leaving only the handle.
     *   - Collapsed (normal): 20dp handle. Tap to expand and reach process change.
     *   - Expanded (alert): list of hazard devices (warning and danger). Expands automatically when an alert fires.
     * Why it stays: the process-change path must always be available, alert or not.
     * If already shown, only the content is updated, without removeView/addView → no flicker or position reset.
     */
    fun showSidebar(context: Context, hazards: List<HazardItem>, roleLabel: String = "") {
        if (!canDrawOverlays(context)) {
            Log.w(TAG, "오버레이 권한 없음")
            setFault("화면 경보 권한 꺼짐 — 소리·진동만 동작")
            return
        }
        if (rootView == null) createSidebar(context)
        if (rootView == null) return   // addView failed — createSidebar already recorded why via setFault
        updateContent(context, hazards, roleLabel)
    }

    private fun updateContent(context: Context, hazards: List<HazardItem>, roleLabel: String) {
        lastHazards = hazards
        lastRole    = roleLabel
        // An alert makes the manual-expand flag moot; clear it here so the panel collapses again when the alert ends.
        if (hazards.isNotEmpty()) manualExpand = false

        val nowCollapsed = hazards.isEmpty() && !manualExpand
        if (collapsed != nowCollapsed) {
            handleView?.visibility = if (nowCollapsed) View.VISIBLE else View.GONE
            panelView?.visibility  = if (nowCollapsed) View.GONE    else View.VISIBLE
            collapsed = nowCollapsed
            // Changing the visible child changes the window width; re-set x to stay flush with the edge.
            applyGeometry(context)
        }

        if (nowCollapsed) {
            stopPulse()
            return
        }

        // Expanded with 0 hazards = process-change view opened via the handle; hide list/hint, show only the entry badge.
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
            // A header blinking in normal times would itself be a misleading signal.
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
        list.itemAnimator  = null   // alert list swaps instantly, no animation (delay = danger)

        // Handle tap = expand. Header tap (0 hazards) = process change. Both can be dragged left/right and up/down.
        handle.setOnTouchListener(buildDragTouchListener { expandManually() })
        header.setOnTouchListener(buildDragTouchListener { requestRoleChange() })
        action.setOnClickListener   { requestRoleChange() }
        collapse.setOnClickListener { collapseManually() }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,   // implies NOT_TOUCH_MODAL: outside touches pass to the app behind
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Initial state is collapsed = handle width, docked to the edge with no margin.
            x = dockX(context, dpToPx(context, HANDLE_WIDTH_DP))
            y = if (savedY != Int.MIN_VALUE) savedY else defaultY(context)
        }

        try {
            wm.addView(root, lp)
            // Assign view references only after addView succeeds.
            //   If it failed but rootView stayed non-null, the next call would take the 'already shown' path
            //   and the on-screen alert would be lost until reboot.
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
            collapsed     = true      // match the layout default (only the handle VISIBLE)
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

    /** Handle tap = manual expand. Opens the process-change entry even with no danger. */
    private fun expandManually() {
        if (manualExpand) return
        manualExpand = true
        val ctx = rootView?.context ?: return
        updateContent(ctx, lastHazards, lastRole)
    }

    /** "닫기" tap = back to the handle. During an alert "닫기" itself is hidden. */
    private fun collapseManually() {
        if (!manualExpand) return
        manualExpand = false
        val ctx = rootView?.context ?: return
        updateContent(ctx, lastHazards, lastRole)
    }

    /**
     * Enters process change. Ignored while there are hazard targets (warning or danger) —
     * an accidental tap during an alert must never hide the alert list.
     */
    private fun requestRoleChange() {
        if (lastHazards.isNotEmpty()) return
        runCatching { onHeaderTap?.invoke() }
    }

    /** Docked x: 0 on the left, screen width − window width on the right. No margin = flush with the edge. */
    private fun dockX(context: Context, widthPx: Int): Int =
        if (dockEnd) (context.resources.displayMetrics.widthPixels - widthPx).coerceAtLeast(0) else 0

    /** Default vertical position = handle centered on screen. */
    private fun defaultY(context: Context): Int =
        ((context.resources.displayMetrics.heightPixels - dpToPx(context, HANDLE_HEIGHT_DP)) / 2)
            .coerceAtLeast(0)

    /**
     * Re-snaps to the edge after collapse/expand changes the window width.
     * With absolute coordinates (Gravity.TOP or START), a width change leaves the window
     * off the edge unless x is updated.
     */
    private fun applyGeometry(context: Context) {
        val root = rootView ?: return
        val p    = params   ?: return
        val w = dpToPx(context, if (collapsed == true) HANDLE_WIDTH_DP else PANEL_WIDTH_DP)
        p.x = dockX(context, w)
        p.y = p.y.coerceAtLeast(0)
        try { windowManager?.updateViewLayout(root, p) } catch (_: Exception) {}
        // Real height is known only after measure; if the expanded panel overflows the screen bottom, pull it up.
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

    /** Swaps in a background whose outer corners are square for the docking side (rounded inside only = edge panel). */
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

    /** Swapping the background overwrites view padding if the drawable has padding; restore the original values. */
    private fun setBackgroundKeepPadding(v: View, resId: Int) {
        val l = v.paddingLeft
        val t = v.paddingTop
        val r = v.paddingRight
        val b = v.paddingBottom
        v.setBackgroundResource(resId)
        v.setPadding(l, t, r, b)
    }

    /**
     * Cap on list height. With RecyclerView at wrap_content, more devices would make the sidebar
     * cover the whole screen. Beyond the cap the list scrolls inside the sidebar.
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
     * Drag listener (move · snap flush left/right · during an alert, push all the way to acknowledge all).
     * The list consumes touches first, so it is attached only to the handle and header, not the root.
     * onTap = action on release without moving (handle = expand, header = process change).
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
                        view.performClick()   // accessibility
                        onTap()
                    }
                    true
                }
                else -> false
            }
        }

    /**
     * Cleanup after the drag is released.
     *   - During an alert, pushed all the way off screen → acknowledge all (ACTION_MUTE_ALL). The sidebar stays.
     *   - Otherwise snaps flush to the nearer edge.
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

        // The collapsed handle is only 20dp wide, so the threshold would be just 9dp. A mere brush would acknowledge
        // all, so this action is allowed only when expanded with an alert showing.
        if (pushedOut && collapsed == false && lastHazards.isNotEmpty()) {
            dockEnd = p.x >= 0
            savedY  = p.y.coerceIn(0, maxY)
            Log.d(TAG, "사이드바 끝까지 드래그 → 전체 확인(ACTION_MUTE_ALL)")
            runCatching {
                context.startService(Intent(context, BleService::class.java).apply {
                    action = BleService.ACTION_MUTE_ALL
                })
            }.onFailure { Log.w(TAG, "전체 확인 전송 실패: ${it.message}") }
            // The sidebar is always shown. Don't remove it; return it to the edge it was pushed toward
            //   (BleService clears the alert, and the result comes back as a collapse-state update).
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

    /** Slides to the flush edge position. */
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

    /** Header blink: danger fast and deep, warning slow and shallow — tells levels apart where sound can't be heard. */
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

    /** Stops blinking and restores opacity when the alert ends. */
    private fun stopPulse() {
        if (currentDanger == null && pulseAnimator == null) return
        pulseAnimator?.cancel()
        pulseAnimator = null
        headerView?.alpha = 1.0f
        currentDanger = null
    }

    /**
     * Removes the sidebar from the screen completely.
     * Call only when monitoring ends (service stop).
     *   0 hazard targets means 'collapsed', not removal — removing it here would lose the process-change entry.
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

    /** Danger device list adapter. Row tap = 30 s acknowledge mute for that device only. */
    private class HazardAdapter(private val ctx: Context) : RecyclerView.Adapter<HazardAdapter.VH>() {

        private val items = ArrayList<HazardItem>()

        /** Updates the list and returns whether the row count changed (triggers sidebar height recalculation). */
        fun submit(list: List<HazardItem>): Boolean {
            val sizeChanged = items.size != list.size
            items.clear()
            items.addAll(list)
            // Same count → update in place; only distance/dBm change each frame, so there's no reason to drop view holders.
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
            // shape_overlay_row is an opaque plate; the danger/warning tint set here replaces its color (default SRC_IN).
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
