package com.wf11.safealert.ui

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.content.Intent
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

/**
 * 단독 작업자 확인·구조 요청 화면 (v1.1.99).
 *
 * 잠금 화면 위에 뜨고, 상태는 LoneWorkerMonitor.uiState() 만 읽어 그린다.
 * 유예·휴식 버튼이 없고 뒤로가기는 확인·구조 요청 중에 화면을 닫지 않는다 — 확인 창은 [근무 중],
 * 구조 요청은 본인 [괜찮음]으로만 닫힌다. [괜찮음]은 언제나 "정말 괜찮으신가요?" 확인을 거치며,
 * 잠금 화면 알림의 [괜찮음]도 같은 확인 창을 연다(EXTRA_CONFIRM_OK).
 * 동료 [확인]/[닫기]는 이 화면이 마지막으로 그린 항목만 묵음으로 만들며, 진행 중 항목이 막 바뀐 직후의 탭은 무시한다.
 */
class LoneWorkerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoneWorkerBinding
    private var confirmDialog: AlertDialog? = null
    private var pendingConfirm = false
    private var waitUntil = 0L   // 서비스 복원 대기 마감(elapsedRealtime), 0 = 대기 안 함
    private val ackGate = PeerAckGate()
    private val retry = Runnable { render() }
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
                    Toast.makeText(this@LoneWorkerActivity, "[근무 중] 또는 [괜찮음]을 눌러야 닫힙니다", Toast.LENGTH_SHORT).show()
                else finish()
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
            .setMessage("해제하면 같은 사업장 휴대폰의 구조 요청 경보가 꺼집니다")
            .setPositiveButton("괜찮음") { _, _ -> mon.cancelSos() }
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
        ackGate.onRender(st.peerIds, st.activePeerIds, SystemClock.elapsedRealtime())
        b.btnLwPeer.visibility = View.GONE
        val own = st.mode != LoneWorkerLogic.Mode.WATCHING
        val bg: Int
        val fg: Int
        when {
            st.mode == LoneWorkerLogic.Mode.SOS -> {
                bg = Color.parseColor("#C62828"); fg = Color.WHITE
                b.tvLwTitle.text = "구조 요청 중"
                b.tvLwBody.text = (listOf(listOfNotNull("같은 사업장 휴대폰에 구조 요청이 나가고 있습니다", st.serverStatus).joinToString("\n")) + st.peerLines)
                    .joinToString("\n\n")
                b.btnLwPrimary.text = "괜찮음"
                b.btnLwPrimary.setOnClickListener { showConfirm(mon) }
            }
            st.mode == LoneWorkerLogic.Mode.CHECKING -> {
                bg = Color.parseColor("#FFC107"); fg = Color.BLACK
                b.tvLwTitle.text = "근무 중이신가요?"
                b.tvLwBody.text = (listOf("${st.responseLeftSec}초 안에 누르지 않으면 같은 사업장에 구조 요청이 나갑니다") + st.peerLines)
                    .joinToString("\n\n")
                b.btnLwPrimary.text = "근무 중"
                b.btnLwPrimary.setOnClickListener { mon.ack() }
            }
            else -> {
                bg = Color.parseColor("#C62828"); fg = Color.WHITE
                b.tvLwTitle.text = if (st.peerActive) "구조 요청" else "해제됨"
                b.tvLwBody.text = st.peerLines.joinToString("\n\n")
                b.btnLwPrimary.text = if (st.peerActive) "확인" else "닫기"
                b.btnLwPrimary.setOnClickListener { ackPeers(mon) }
            }
        }
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
        b.lwRoot.setBackgroundColor(bg)
        b.tvLwTitle.setTextColor(fg)
        b.tvLwBody.setTextColor(fg)
    }

    /** 마지막으로 그린 항목만 묵음으로 만든다. 진행 중 항목이 막 바뀐 직후면 탭을 무시한다. */
    private fun ackPeers(mon: LoneWorkerMonitor) {
        ackGate.onTap(SystemClock.elapsedRealtime())?.let { mon.silencePeers(it) }
    }
}

/** 화면 확인 대상: 마지막으로 그린 회차 ID 만 넘기고, 진행 중 ID 집합이 바뀐 뒤 SETTLE_MS 안의 탭은 무시(null)한다. */
internal class PeerAckGate {
    companion object {
        const val SETTLE_MS = 700L
    }

    private var shown: List<String> = emptyList()
    private var active: Set<String> = emptySet()
    private var changedAt = Long.MIN_VALUE

    fun onRender(shown: List<String>, activeIds: Set<String>, nowMs: Long) {
        if (activeIds != active) {
            active = activeIds
            changedAt = nowMs
        }
        this.shown = shown
    }

    /** null = 이 탭은 무시한다. 빈 목록은 아무것도 묵음으로 만들지 않는다. */
    fun onTap(nowMs: Long): List<String>? =
        if (changedAt != Long.MIN_VALUE && nowMs - changedAt < SETTLE_MS) null else shown
}
