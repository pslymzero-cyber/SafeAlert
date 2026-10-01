package com.wf11.safealert.ui

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.content.Intent
import android.content.res.ColorStateList
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
 * 색은 상태마다 한 벌(확인 창 노랑, 구조 요청 빨강, 해제됨 초록)이고 보이는 동안 1초마다 다시 그린다.
 * 유예·휴식 버튼이 없고 뒤로가기는 확인·구조 요청 중에 화면을 닫지 않는다 — 확인 창은 [괜찮아요]나 걸음,
 * 구조 요청은 본인 [괜찮아요]로만 닫힌다. 구조 요청의 [괜찮아요]는 언제나 "정말 괜찮으신가요?" 확인을 거치며,
 * 잠금 화면 알림의 [괜찮아요]도 같은 확인 창을 연다(EXTRA_CONFIRM_OK).
 * 동료 [확인]/[닫기]는 이 화면이 마지막으로 그린 항목만 묵음으로 만들며, 진행 중 항목이 막 바뀐 직후의 탭은 무시한다.
 */
class LoneWorkerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoneWorkerBinding
    private var confirmDialog: AlertDialog? = null
    private var pendingConfirm = false
    private var waitUntil = 0L   // 서비스 복원 대기 마감(elapsedRealtime), 0 = 대기 안 함
    private val ackGate = PeerAckGate()
    private val retry = Runnable { render() }
    private val ticker = object : Runnable {
        override fun run() {
            render()
            binding.root.postDelayed(this, TICK_MS)
        }
    }
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
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val m = LoneWorkerMonitor.current?.uiState()?.mode
                if (m == LoneWorkerLogic.Mode.CHECKING || m == LoneWorkerLogic.Mode.SOS)
                    Toast.makeText(this@LoneWorkerActivity, "[괜찮아요]를 눌러야 닫혀요", Toast.LENGTH_SHORT).show()
                else finish()
            }
        })
        takeConfirmExtra(intent)
    }

    override fun onStart() {
        super.onStart()
        LoneWorkerMonitor.uiListener = listener
        render()
        binding.root.postDelayed(ticker, TICK_MS)
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

    /** 알림의 [괜찮음]이 실어 보낸 확인 요청을 한 번만 읽는다. */
    private fun takeConfirmExtra(i: Intent?) {
        if (i?.getBooleanExtra(LoneWorkerNotifier.EXTRA_CONFIRM_OK, false) == true) {
            i.removeExtra(LoneWorkerNotifier.EXTRA_CONFIRM_OK)
            pendingConfirm = true
        }
    }

    private fun showConfirm(mon: LoneWorkerMonitor) {
        if (confirmDialog?.isShowing == true) return
        confirmDialog = AlertDialog.Builder(this)
            .setTitle("정말 괜찮으신가요?")
            .setMessage("해제하면 같은 사업장 휴대폰의 구조 요청 경보가 꺼져요")
            .setPositiveButton("괜찮아요") { _, _ -> mon.cancelSos() }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun render() {
        val b = binding
        val mon = LoneWorkerMonitor.current
        if (mon == null) {
            // 서비스가 없으면 저장된 구조 요청을 되살리고 최대 10초 기다린다. 대기 중에는 pendingConfirm 을 지우지 않는다.
            val t = SystemClock.elapsedRealtime()
            if (waitUntil == 0L && LoneWorkerUi.reviveIfStoredSos(this)) waitUntil = t + 10_000L
            if (t < waitUntil) {
                paint(ALERT)
                b.tvLwChip.visibility = View.GONE
                b.lwRingBox.visibility = View.GONE
                b.tvLwTitle.text = "구조 요청 복원 중"
                b.tvLwBody.text = ""
                b.btnLwPeer.visibility = View.GONE
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
        ackGate.onRender(st.peers, SystemClock.elapsedRealtime())
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
                b.btnLwPrimary.setOnClickListener { showConfirm(mon) }
                ALERT
            }
            LoneWorkerLogic.Mode.CHECKING -> {
                chip(if (st.trigger == "fall") "넘어짐 감지" else "${st.stillMin}분 동안 움직임 없음")
                b.tvLwTitle.text = "괜찮으세요?"
                b.tvLwBody.text = (listOf("응답이 없으면 같은 사업장에 구조 요청이 나가요\n걸으면(5걸음) 자동으로 닫혀요") + peerLines)
                    .joinToString("\n\n")
                b.btnLwPrimary.text = "괜찮아요"
                b.btnLwPrimary.setOnClickListener { mon.ack() }
                CHECK
            }
            else -> {
                chip("같은 사업장 동료")
                b.tvLwTitle.text = if (st.peerActive) "동료 구조 요청" else "해제됨"
                b.tvLwBody.text = peerLines.joinToString("\n\n")
                b.btnLwPrimary.text = if (st.peerActive) "확인" else "닫기"
                b.btnLwPrimary.setOnClickListener { ackPeers(mon) }
                if (st.peerActive) ALERT else DONE
            }
        }
        ring(st, look)
        if (own && st.peerActive) {
            b.btnLwPeer.text = "확인(다른 작업자)"
            b.btnLwPeer.setOnClickListener { ackPeers(mon) }
            b.btnLwPeer.visibility = View.VISIBLE
        }
        st.alarmFault?.let { b.tvLwBody.text = "${b.tvLwBody.text}\n\n${it}" }
        if (st.mode == LoneWorkerLogic.Mode.SOS) {
            if (pendingConfirm) showConfirm(mon)
        } else confirmDialog?.dismiss()
        pendingConfirm = false
        paint(look)
    }

    private fun chip(text: String) {
        binding.tvLwChip.text = text
        binding.tvLwChip.visibility = View.VISIBLE
    }

    /** 확인 창이면 남은 초를 링과 큰 숫자로 보여 주고, 아니면 숨긴다. */
    private fun ring(st: LoneWorkerMonitor.UiState, look: Look) {
        val b = binding
        val show = st.mode == LoneWorkerLogic.Mode.CHECKING && st.responseTotalSec > 0
        b.lwRingBox.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        val left = st.responseLeftSec.coerceIn(0, st.responseTotalSec)
        b.lwRing.trackColor = look.subBg
        b.lwRing.setIndicatorColor(look.title)
        b.lwRing.setProgressCompat(left * 1000 / st.responseTotalSec, true)
        b.tvLwCount.text = left.toString()
        b.tvLwCount.setTextColor(look.title)
        b.tvLwCountUnit.setTextColor(look.body)
    }

    private fun paint(look: Look) {
        val b = binding
        b.lwRoot.setBackgroundColor(look.bg)
        b.tvLwTitle.setTextColor(look.title)
        b.tvLwBody.setTextColor(look.body)
        b.tvLwChip.setTextColor(look.chipFg)
        b.tvLwChip.backgroundTintList = ColorStateList.valueOf(look.chipBg)
        b.btnLwPrimary.backgroundTintList = ColorStateList.valueOf(look.btnBg)
        b.btnLwPrimary.setTextColor(look.btnFg)
        b.btnLwPeer.backgroundTintList = ColorStateList.valueOf(look.subBg)
        b.btnLwPeer.setTextColor(look.subFg)
    }

    /** 상태 한 가지의 색 한 벌. 링 바탕은 subBg, 링과 숫자는 title 색이다. */
    private class Look(
        val bg: Int, val title: Int, val body: Int, val chipBg: Int, val chipFg: Int,
        val btnBg: Int, val btnFg: Int, val subBg: Int, val subFg: Int
    )

    private companion object {
        const val TICK_MS = 1_000L
        fun c(hex: String) = Color.parseColor(hex)
        val CHECK = Look(c("#FAC775"), c("#412402"), c("#633806"), c("#FAEEDA"), c("#633806"), c("#2C2C2A"), Color.WHITE, c("#FAEEDA"), c("#412402"))
        val ALERT = Look(c("#A32D2D"), Color.WHITE, c("#FCEBEB"), c("#791F1F"), c("#FCEBEB"), Color.WHITE, c("#A32D2D"), c("#791F1F"), c("#FCEBEB"))
        val DONE = Look(c("#3B6D11"), Color.WHITE, c("#EAF3DE"), c("#27500A"), c("#EAF3DE"), Color.WHITE, c("#3B6D11"), c("#27500A"), c("#EAF3DE"))
    }

    /** 마지막으로 그린 항목만 묵음으로 만든다. 진행 중 항목이 막 바뀐 직후면 탭을 무시한다. */
    private fun ackPeers(mon: LoneWorkerMonitor) {
        ackGate.onTap(SystemClock.elapsedRealtime())?.let { rows -> mon.silencePeers(rows.associate { it.id to it.epId }) }
    }
}

/** 화면 확인 대상: 마지막으로 그린 줄만 넘기고, 진행 중 항목 id 집합이 바뀐 뒤 SETTLE_MS 안의 탭은 무시(null)한다. */
internal class PeerAckGate {
    companion object {
        const val SETTLE_MS = 700L
    }

    private var shown: List<PeerRow> = emptyList()
    private var active: Set<String> = emptySet()
    private var changedAt = Long.MIN_VALUE

    fun onRender(shown: List<PeerRow>, nowMs: Long) {
        val activeIds = shown.filter { it.active }.mapTo(HashSet()) { it.id }
        if (activeIds != active) {
            active = activeIds
            changedAt = nowMs
        }
        this.shown = shown
    }

    /** null = 이 탭은 무시한다. 빈 목록은 아무것도 묵음으로 만들지 않는다. */
    fun onTap(nowMs: Long): List<PeerRow>? =
        if (changedAt != Long.MIN_VALUE && nowMs - changedAt < SETTLE_MS) null else shown
}
