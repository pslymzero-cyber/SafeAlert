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

/**
 * 단독 작업자 확인·구조 요청 화면 (v1.1.99).
 *
 * 잠금 화면 위에 뜨고, 상태는 LoneWorkerMonitor.uiState() 만 읽어 그린다. 모양은 이유 칩·큰 제목·남은 시간 링(확인 창)·큰 버튼,
 * 색은 상태마다 한 벌(확인 창 노랑, 구조 요청 빨강, 해제됨 초록)이다. 위쪽은 스크롤되고 버튼은 늘 화면 맨 아래에 있으며,
 * 링 크기는 화면 높이에 맞춘다(가로·작은 화면에서도 버튼이 보이게). 확인 창은 남은 초가 바뀌는 순간(초 경계 직후)마다 다시 그린다.
 * 유예·휴식 버튼이 없고 뒤로가기는 확인·구조 요청 중에 화면을 닫지 않는다 — 확인 창은 [괜찮아요]나 걸음(장비 거치 무동작 창은 회전·흔들기·[괜찮아요]),
 * 구조 요청은 본인 [괜찮아요]로만 닫힌다. 구조 요청의 [괜찮아요]는 언제나 "정말 괜찮으신가요?" 확인을 거치며,
 * 잠금 화면 알림의 [괜찮아요]도 같은 확인 창을 연다(EXTRA_CONFIRM_OK).
 * 버튼이 하는 일이 바뀐 직후(화면 종류나 진행 중 동료가 바뀐 뒤 SETTLE_MS)의 탭은 무시한다 — 두 번 누름이 바뀐 버튼을 누르지 않게.
 * 버튼은 누른 순간의 감시(LoneWorkerMonitor.current)에 보내고, 동료 [확인]/[닫기]는 이 화면이 마지막으로 그린 항목만 묵음으로 만든다.
 */
class LoneWorkerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoneWorkerBinding
    private var confirmDialog: AlertDialog? = null
    private var pendingConfirm = false
    private var waitUntil = 0L   // 서비스 복원 대기 마감(elapsedRealtime), 0 = 대기 안 함
    private var shownMode: LoneWorkerLogic.Mode? = null  // 마지막으로 그린 화면(null = 복원 대기, 버튼 숨김)
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
        // 다른 화면이 이미 자기 리스너로 바꿨다면 건드리지 않는다
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

    /** 알림의 [괜찮아요]가 실어 보낸 확인 요청을 한 번만 읽는다. */
    private fun takeConfirmExtra(i: Intent?) {
        if (i?.getBooleanExtra(LoneWorkerNotifier.EXTRA_CONFIRM_OK, false) == true) {
            i.removeExtra(LoneWorkerNotifier.EXTRA_CONFIRM_OK)
            pendingConfirm = true
        }
    }

    private fun showConfirm() {
        if (confirmDialog?.isShowing == true) return
        confirmDialog = AlertDialog.Builder(this)
            .setTitle("정말 괜찮으신가요?")
            .setMessage("해제하면 같은 사업장 휴대폰의 구조 요청 경보가 꺼져요")
            .setPositiveButton("괜찮아요") { _, _ -> LoneWorkerMonitor.current?.cancelSos() }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 주 버튼: 마지막으로 그린 화면대로 처리한다. 버튼이 하는 일이 막 바뀐 직후의 탭은 무시한다. */
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

    /** 마지막으로 그린 동료 항목만 묵음으로 만든다. 버튼이 하는 일이 막 바뀐 직후면 탭을 무시한다. */
    private fun ackPeers() {
        val mon = LoneWorkerMonitor.current ?: return
        ackGate.onTap(SystemClock.elapsedRealtime())?.let { rows -> mon.silencePeers(rows.associate { it.id to it.epId }) }
    }

    private fun render() {
        val b = binding
        b.root.removeCallbacks(ticker)
        val mon = LoneWorkerMonitor.current
        if (mon == null) {
            // 서비스가 없으면 저장된 구조 요청을 되살리고 최대 10초 기다린다. 대기 중에는 pendingConfirm 을 지우지 않는다.
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
                b.tvLwBody.text = (listOf(listOfNotNull("같은 사업장 휴대폰에 구조 요청이 나가고 있어요", st.serverStatus).joinToString("\n")) + peerLines)
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
                chip("같은 사업장 동료")
                b.tvLwTitle.text = if (st.peerActive) "동료 구조 요청" else "해제됨"
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
        // 확인 창은 남은 초가 바뀌는 순간(초 경계 바로 뒤)에 다시 그린다 — 숫자가 건너뛰거나 들쭉날쭉하지 않게
        if (st.mode == LoneWorkerLogic.Mode.CHECKING) {
            val toNextSecond = st.responseLeftMs.mod(1000L).let { if (it == 0L) 1000L else it }
            b.root.postDelayed(ticker, toNextSecond + 20L)
        }
    }

    private fun chip(text: String) {
        binding.tvLwChip.text = text
        binding.tvLwChip.visibility = View.VISIBLE
    }

    /** 확인 창을 닫는 걸음 안내 — 걸음 센서가 있으면 걸음 수, 없으면 걷는 모양이 이어지는 초(로직 상수 그대로). */
    private fun walkHint(steps: Boolean): String =
        if (steps) "${LoneWorkerLogic.DISTINCT_STEP_WINDOW_MS / 1000}초 안에 ${LoneWorkerLogic.DISTINCT_STEPS}걸음 걸으면 자동으로 닫혀요"
        else "${LoneWorkerLogic.STRONG_RUN_MS / 1000}초 넘게 계속 걸으면 자동으로 닫혀요"

    /** 링 크기를 화면 높이에 맞춘다(가로·작은 화면에서 위쪽이 너무 길지 않게). 숫자는 링에 들어가게 dp 로 맞춘다. */
    private fun fitRing() {
        val ringDp = (resources.configuration.screenHeightDp * 0.34f).coerceIn(112f, 216f)
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, ringDp, resources.displayMetrics).toInt()
        binding.lwRing.indicatorSize = px
        binding.lwRing.trackThickness = px / 12
        binding.tvLwCount.setTextSize(TypedValue.COMPLEX_UNIT_DIP, ringDp * 0.31f)
    }

    /** 확인 창이면 남은 초를 링과 큰 숫자로 보여 주고, 아니면 숨긴다. 처음 보일 때는 그 값으로 바로(빈 링에서 차오르지 않게). */
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

    /** 상태 한 가지의 색 한 벌. 칩 글자는 본문 색, 보조 버튼 바탕은 칩 색을 쓴다. */
    private class Look(val bg: Int, val title: Int, val body: Int, val chip: Int, val btnBg: Int, val btnFg: Int, val subFg: Int)

    private companion object {
        fun c(hex: String) = Color.parseColor(hex)
        val CHECK = Look(bg = c("#FAC775"), title = c("#412402"), body = c("#633806"), chip = c("#FAEEDA"), btnBg = c("#2C2C2A"), btnFg = Color.WHITE, subFg = c("#412402"))
        val ALERT = Look(bg = c("#A32D2D"), title = Color.WHITE, body = c("#FCEBEB"), chip = c("#791F1F"), btnBg = Color.WHITE, btnFg = c("#A32D2D"), subFg = c("#FCEBEB"))
        val DONE = Look(bg = c("#3B6D11"), title = Color.WHITE, body = c("#EAF3DE"), chip = c("#27500A"), btnBg = Color.WHITE, btnFg = c("#3B6D11"), subFg = c("#EAF3DE"))
    }
}

/**
 * 화면 탭 문: 버튼이 하는 일이 바뀐 직후(SETTLE_MS 안)의 탭은 무시한다 — 화면 종류(screen)나 진행 중 동료 id 집합이 바뀌면 다시 센다.
 * 동료 확인은 마지막으로 그린 줄만 넘긴다.
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

    /** 버튼이 하는 일이 바뀐 뒤 SETTLE_MS 가 지났다. */
    fun settled(nowMs: Long): Boolean = changedAt == Long.MIN_VALUE || nowMs - changedAt >= SETTLE_MS

    /** null = 이 탭은 무시한다. 빈 목록은 아무것도 묵음으로 만들지 않는다. */
    fun onTap(nowMs: Long): List<PeerRow>? = if (settled(nowMs)) shown else null
}
