package com.wf11.safealert.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.wf11.safealert.databinding.ActivityLoneWorkerBinding
import com.wf11.safealert.service.LoneWorkerLogic
import com.wf11.safealert.service.LoneWorkerMonitor
import com.wf11.safealert.service.LoneWorkerNotifier
import com.wf11.safealert.service.PeerRow
import com.wf11.safealert.utils.DevSettings
import com.wf11.safealert.utils.SiteScope

/**
 * Lone-worker check and SOS screen.
 *
 * Shown over the lock screen; draws only what LoneWorkerMonitor.uiState() returns. Layout:
 * reason chip, large title, remaining-time ring (check window), large buttons;
 * one color set per state (check window yellow, SOS red, cleared green). The top
 * part scrolls and the buttons always stay at the bottom of the screen;
 * the ring is sized to the screen height so the buttons stay visible in landscape and on small screens. The
 * check window redraws each time the remaining seconds change (right after each second boundary).
 * There are no snooze/rest buttons, and Back does not close the screen during a check or SOS: the check window
 * closes only via "괜찮아요" or walking (the mounted no-motion window: rotation, shaking or "괜찮아요"),
 * and an SOS closes only via the user's own "괜찮아요". "괜찮아요" on an SOS always goes through the "정말 괜찮으신가요?" confirmation,
 * and "괜찮아요" on the lock-screen notification opens the same confirmation (EXTRA_CONFIRM_OK).
 * Taps right after a button's action changes (SETTLE_MS after the screen type or the active
 * peers change) are ignored, so a double tap doesn't hit the changed button.
 * Buttons go to the monitor current at tap time (LoneWorkerMonitor.current); a
 * peer's "확인"/"닫기" mutes only the entries this screen last drew.
 */
class LoneWorkerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoneWorkerBinding
    private var confirmDialog: AlertDialog? = null
    private var pendingConfirm = false
    private var waitUntil = 0L   // Service-restore wait deadline (elapsedRealtime); 0 = not waiting
    private var shownMode: LoneWorkerLogic.Mode? = null  // Last drawn screen (null = waiting for restore, buttons hidden)
    private var painted: Look? = null
    private val ackGate = PeerAckGate()
    private val retry = Runnable { render() }
    private val ticker = Runnable { render() }
    private val listener: () -> Unit = { runOnUiThread { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityLoneWorkerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        fitRing()
        binding.btnLwPrimary.setOnClickListener { onPrimary() }
        binding.btnLwPeer.setOnClickListener { ackPeers() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val st = LoneWorkerMonitor.current?.uiState()
                val hint = when (st?.mode) {
                    LoneWorkerLogic.Mode.CHECKING ->
                        if (st.closesByTurn) LoneWorkerNotifier.TURN_CLOSE_HINT else "[괜찮아요]를 누르거나 걸으면 닫혀요"
                    LoneWorkerLogic.Mode.SOS -> "[괜찮아요]를 눌러야 해제돼요"
                    else -> null
                }
                if (hint != null) Toast.makeText(this@LoneWorkerActivity, hint, Toast.LENGTH_SHORT).show() else finish()
            }
        })
        takeConfirmExtra(intent)
    }

    override fun onStart() {
        super.onStart()
        LoneWorkerMonitor.uiListener = listener
        render()
    }

    override fun onStop() {
        // Leave it alone if another screen already swapped in its own listener
        if (LoneWorkerMonitor.uiListener === listener) LoneWorkerMonitor.uiListener = null
        binding.root.removeCallbacks(retry)
        binding.root.removeCallbacks(ticker)
        confirmDialog?.dismiss()
        confirmDialog = null
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeConfirmExtra(intent)
        render()
    }

    /** Read the confirm request carried by the notification's "괜찮아요" only once. */
    private fun takeConfirmExtra(i: Intent?) {
        if (i?.getBooleanExtra(LoneWorkerNotifier.EXTRA_CONFIRM_OK, false) == true) {
            i.removeExtra(LoneWorkerNotifier.EXTRA_CONFIRM_OK)
            pendingConfirm = true
        }
    }

    /** Who my own SOS reaches (this phone's floor/process scope). */
    private fun audience() = SiteScope.audience(DevSettings.floor, DevSettings.proc)

    private fun showConfirm() {
        if (confirmDialog?.isShowing == true) return
        confirmDialog = AlertDialog.Builder(this)
            .setTitle("정말 괜찮으신가요?")
            .setMessage("해제하면 동료 휴대폰의 구조 요청 경보도 꺼져요")
            .setPositiveButton("괜찮아요") { _, _ -> LoneWorkerMonitor.current?.cancelSos() }
            .setNegativeButton("취소", null)
            .show()
    }

    /** Primary button: acts on the last drawn screen. Ignores a tap right after the button's action changed. */
    private fun onPrimary() {
        val mon = LoneWorkerMonitor.current ?: return
        if (!ackGate.settled(SystemClock.elapsedRealtime())) return
        when (shownMode) {
            LoneWorkerLogic.Mode.SOS -> showConfirm()
            LoneWorkerLogic.Mode.CHECKING -> mon.ack()
            LoneWorkerLogic.Mode.WATCHING -> ackPeers()
            null -> Unit
        }
    }

    /** Mutes only the peer entries last drawn. Ignores the tap right after the button's action changed. */
    private fun ackPeers() {
        val mon = LoneWorkerMonitor.current ?: return
        ackGate.onTap(SystemClock.elapsedRealtime())?.let { rows -> mon.silencePeers(rows.associate { it.id to it.epId }) }
    }

    private fun render() {
        val b = binding
        b.root.removeCallbacks(ticker)
        val mon = LoneWorkerMonitor.current
        if (mon == null) {
            // Without the service, revive the saved SOS and wait up to 10 s. Keep pendingConfirm while waiting.
            val t = SystemClock.elapsedRealtime()
            if (waitUntil == 0L && LoneWorkerUi.reviveIfStoredSos(this)) waitUntil = t + 10_000L
            if (t < waitUntil) {
                shownMode = null
                paint(ALERT)
                b.tvLwChip.visibility = View.GONE
                b.lwRingBox.visibility = View.GONE
                b.tvLwTitle.text = "구조 요청 복원 중"
                b.tvLwBody.text = ""
                b.btnLwPeer.visibility = View.GONE
                b.btnLwPrimary.visibility = View.INVISIBLE
                b.root.removeCallbacks(retry)
                b.root.postDelayed(retry, 500L)
                return
            }
            finish()
            return
        }
        waitUntil = 0L
        val st = mon.uiState()
        if (st == null) {
            finish()
            return
        }
        ackGate.onRender(st.peers, SystemClock.elapsedRealtime(), "${st.mode}:${st.peerActive}")
        shownMode = st.mode
        b.btnLwPrimary.visibility = View.VISIBLE
        b.btnLwPeer.visibility = View.GONE
        val own = st.mode != LoneWorkerLogic.Mode.WATCHING
        val peerLines = st.peers.map { it.line }
        val look = when (st.mode) {
            LoneWorkerLogic.Mode.SOS -> {
                chip("내 구조 요청")
                b.tvLwTitle.text = "구조 요청 중"
                b.tvLwBody.text = (listOf(listOfNotNull("${audience()} 휴대폰에 구조 요청이 나가고 있어요", st.serverStatus).joinToString("\n")) + peerLines)
                    .joinToString("\n\n")
                b.btnLwPrimary.text = "괜찮아요"
                ALERT
            }
            LoneWorkerLogic.Mode.CHECKING -> {
                chip(if (st.trigger == "fall") "넘어짐 감지" else "${st.stillMin}분 동안 움직임 없음")
                b.tvLwTitle.text = "괜찮으세요?"
                b.tvLwBody.text = (listOf("응답이 없으면 같은 사업장에 구조 요청이 나가요\n" +
                    (if (st.closesByTurn) LoneWorkerNotifier.TURN_CLOSE_HINT else walkHint(st.stepsAvailable))) + peerLines)
                    .joinToString("\n\n")
                b.btnLwPrimary.text = "괜찮아요"
                CHECK
            }
            else -> {
                chip("${audience()} 동료")
                b.tvLwTitle.text = if (st.peerActive) "동료 구조 요청" else if (st.peerAutoEnded) LoneWorkerNotifier.AUTO_ENDED else "해제됨"
                b.tvLwBody.text = peerLines.joinToString("\n\n")
                b.btnLwPrimary.text = if (st.peerActive) "확인" else "닫기"
                if (st.peerActive) ALERT else DONE
            }
        }
        ring(st)
        if (own && st.peerActive) {
            b.btnLwPeer.text = "확인(다른 작업자)"
            b.btnLwPeer.visibility = View.VISIBLE
        }
        st.alarmFault?.let { b.tvLwBody.text = "${b.tvLwBody.text}\n\n${it}" }
        if (st.mode == LoneWorkerLogic.Mode.SOS) {
            if (pendingConfirm) showConfirm()
        } else confirmDialog?.dismiss()
        pendingConfirm = false
        paint(look)
        // Redraw the check window when the remaining seconds change (right after the second boundary) so the number doesn't skip or jitter
        if (st.mode == LoneWorkerLogic.Mode.CHECKING) {
            val toNextSecond = st.responseLeftMs.mod(1000L).let { if (it == 0L) 1000L else it }
            b.root.postDelayed(ticker, toNextSecond + 20L)
        }
    }

    private fun chip(text: String) {
        binding.tvLwChip.text = text
        binding.tvLwChip.visibility = View.VISIBLE
    }

    /**
     * Walking hint for closing the check window — step count with a step sensor,
     * otherwise seconds of continued walking motion (logic constants as is).
     */
    private fun walkHint(steps: Boolean): String =
        if (steps) "${LoneWorkerLogic.DISTINCT_STEP_WINDOW_MS / 1000}초 안에 ${LoneWorkerLogic.DISTINCT_STEPS}걸음 걸으면 자동으로 닫혀요"
        else "${LoneWorkerLogic.STRONG_RUN_MS / 1000}초 넘게 계속 걸으면 자동으로 닫혀요"

    /**
     * Size the ring to the screen height (so the top isn't too tall in landscape/on
     * small screens). The number is sized in dp to fit inside the ring.
     */
    private fun fitRing() {
        val ringDp = (resources.configuration.screenHeightDp * 0.34f).coerceIn(112f, 216f)
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, ringDp, resources.displayMetrics).toInt()
        binding.lwRing.indicatorSize = px
        binding.lwRing.trackThickness = px / 12
        binding.tvLwCount.setTextSize(TypedValue.COMPLEX_UNIT_DIP, ringDp * 0.31f)
    }

    /**
     * In the check window, show the remaining seconds as a ring and a large number; otherwise hide
     * them. On first show, jump straight to that value (no filling up from an empty ring).
     */
    private fun ring(st: LoneWorkerMonitor.UiState) {
        val b = binding
        val show = st.mode == LoneWorkerLogic.Mode.CHECKING && st.responseTotalSec > 0
        val was = b.lwRingBox.visibility == View.VISIBLE
        b.lwRingBox.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        val left = st.responseLeftSec.coerceIn(0, st.responseTotalSec)
        b.lwRing.setProgressCompat(left * 1000 / st.responseTotalSec, was)
        b.tvLwCount.text = left.toString()
    }

    private fun paint(look: Look) {
        if (look === painted) return
        painted = look
        val b = binding
        b.lwRoot.setBackgroundColor(look.bg)
        b.tvLwTitle.setTextColor(look.title)
        b.tvLwBody.setTextColor(look.body)
        b.tvLwChip.setTextColor(look.body)
        b.tvLwChip.backgroundTintList = ColorStateList.valueOf(look.chip)
        b.btnLwPrimary.backgroundTintList = ColorStateList.valueOf(look.btnBg)
        b.btnLwPrimary.setTextColor(look.btnFg)
        b.btnLwPeer.backgroundTintList = ColorStateList.valueOf(look.chip)
        b.btnLwPeer.setTextColor(look.subFg)
    }

    /** One color set for one state. Chip text uses the body color; the secondary button background uses the chip color. */
    private class Look(val bg: Int, val title: Int, val body: Int, val chip: Int, val btnBg: Int, val btnFg: Int, val subFg: Int)

    private companion object {
        fun c(hex: String) = Color.parseColor(hex)
        val CHECK = Look(bg = c("#FAC775"), title = c("#412402"), body = c("#633806"), chip = c("#FAEEDA"), btnBg = c("#2C2C2A"), btnFg = Color.WHITE, subFg = c("#412402"))
        val ALERT = Look(bg = c("#A32D2D"), title = Color.WHITE, body = c("#FCEBEB"), chip = c("#791F1F"), btnBg = Color.WHITE, btnFg = c("#A32D2D"), subFg = c("#FCEBEB"))
        val DONE = Look(bg = c("#3B6D11"), title = Color.WHITE, body = c("#EAF3DE"), chip = c("#27500A"), btnBg = Color.WHITE, btnFg = c("#3B6D11"), subFg = c("#EAF3DE"))
    }
}

/**
 * Screen tap gate: ignores taps right after the button's action changed (within SETTLE_MS) — the
 * count restarts when the screen type (screen) or the set of active peer ids changes.
 * A peer acknowledgment passes only the rows last drawn.
 */
internal class PeerAckGate {
    companion object {
        const val SETTLE_MS = 700L
    }

    private var shown: List<PeerRow> = emptyList()
    private var active: Set<String> = emptySet()
    private var screen: Any? = null
    private var changedAt = Long.MIN_VALUE

    fun onRender(shown: List<PeerRow>, nowMs: Long, screen: Any? = null) {
        val activeIds = shown.filter { it.active }.mapTo(HashSet()) { it.id }
        if (activeIds != active || screen != this.screen) {
            active = activeIds
            this.screen = screen
            changedAt = nowMs
        }
        this.shown = shown
    }

    /** SETTLE_MS has passed since the button's action changed. */
    fun settled(nowMs: Long): Boolean = changedAt == Long.MIN_VALUE || nowMs - changedAt >= SETTLE_MS

    /** null = ignore this tap. An empty list mutes nothing. */
    fun onTap(nowMs: Long): List<PeerRow>? = if (settled(nowMs)) shown else null
}
