package com.wf11.safealert.ui

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.wf11.safealert.databinding.ActivityLoneWorkerBinding
import com.wf11.safealert.service.LoneWorkerLogic
import com.wf11.safealert.service.LoneWorkerMonitor

/**
 * 단독 작업자 확인·구조 요청 화면 (v1.1.99).
 *
 * 잠금 화면 위에 뜨고, 상태는 LoneWorkerMonitor.uiState() 만 읽어 그린다.
 * 유예·휴식 버튼과 뒤로가기 처리가 없다 — 확인 창은 [근무 중], 구조 요청은 본인 [괜찮음]으로만 닫힌다.
 */
class LoneWorkerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoneWorkerBinding

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
    }

    override fun onStart() {
        super.onStart()
        LoneWorkerMonitor.uiListener = { runOnUiThread { render() } }
        render()
    }

    override fun onStop() {
        LoneWorkerMonitor.uiListener = null
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        render()
    }

    private fun render() {
        val mon = LoneWorkerMonitor.current
        val st = mon?.uiState()
        if (mon == null || st == null) {
            finish()
            return
        }
        val b = binding
        b.btnLwPeer.visibility = View.GONE
        val own = st.mode != LoneWorkerLogic.Mode.WATCHING
        val bg: Int
        val fg: Int
        when {
            st.mode == LoneWorkerLogic.Mode.SOS -> {
                bg = Color.parseColor("#C62828"); fg = Color.WHITE
                b.tvLwTitle.text = "구조 요청 중"
                b.tvLwBody.text = (listOf("같은 사업장 휴대폰에 구조 요청이 나가고 있습니다") + st.peerLines).joinToString("\n\n")
                b.btnLwPrimary.text = "괜찮음"
                b.btnLwPrimary.setOnClickListener { mon.cancelSos() }
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
                b.btnLwPrimary.setOnClickListener { mon.silencePeers() }
            }
        }
        if (own && st.peerActive) {
            b.btnLwPeer.text = "확인(다른 작업자)"
            b.btnLwPeer.setOnClickListener { mon.silencePeers() }
            b.btnLwPeer.visibility = View.VISIBLE
        }
        b.lwRoot.setBackgroundColor(bg)
        b.tvLwTitle.setTextColor(fg)
        b.tvLwBody.setTextColor(fg)
    }
}
