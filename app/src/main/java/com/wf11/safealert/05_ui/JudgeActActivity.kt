package com.wf11.safealert.ui

import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import com.wf11.safealert.R
import com.wf11.safealert.databinding.ActivityJudgeActBinding
import com.wf11.safealert.firebase.JudgeLog
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Impact question card: opened from the notification posted by JudgeQuestion.postAct. Seven equal answers; the answer is
 * recorded with JudgeLog.truth(act = ...) and nothing else changes (recording only); after an answer the next unanswered
 * question (JudgeQuestion.nextPending) opens, if any. Tapping outside or back closes without an answer; the notification
 * stays so it can be reopened within the 5 minute window.
 */
class JudgeActActivity : AppCompatActivity() {

    private lateinit var binding: ActivityJudgeActBinding
    private var me = ""
    private var role = ""
    private var impT = 0L
    private var impWall = 0L
    private var posted = 0L
    private var why = ""

    private val tick = object : Runnable {
        override fun run() {
            updateTime()
            binding.root.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityJudgeActBinding.inflate(layoutInflater)
        setContentView(binding.root)
        me = intent.getStringExtra(JudgeQuestion.EXTRA_ME) ?: ""
        role = intent.getStringExtra(JudgeQuestion.EXTRA_ROLE) ?: ""
        impT = intent.getLongExtra(JudgeQuestion.EXTRA_IMP_T, 0L)
        impWall = intent.getLongExtra(JudgeQuestion.EXTRA_IMP_WALL, System.currentTimeMillis())
        posted = intent.getLongExtra(JudgeQuestion.EXTRA_POSTED, SystemClock.elapsedRealtime())
        why = intent.getStringExtra(JudgeQuestion.EXTRA_WHY) ?: ""
        binding.judgeActRoot.setOnClickListener { finish() }
        buildAnswers()
        binding.root.post(tick)
    }

    override fun onDestroy() {
        binding.root.removeCallbacks(tick)
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun answerButton(label: String, code: String, heightDp: Int, weight: Float): TextView =
        TextView(this).apply {
            text = label
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.sa_text_primary))
            gravity = Gravity.CENTER
            maxLines = 2
            isClickable = true
            isFocusable = true
            setBackgroundResource(R.drawable.shape_judge_act_btn)
            layoutParams = LinearLayout.LayoutParams(0, dp(heightDp), weight)
            setOnClickListener { answer(label, code) }
        }

    private fun buildAnswers() {
        val acts = JudgeQuestion.ACTS
        val box = binding.judgeActAnswers
        acts.dropLast(1).chunked(3).forEachIndexed { r, row ->
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .also { if (r > 0) it.topMargin = dp(8) }
            }
            row.forEachIndexed { i, (label, code) ->
                line.addView(answerButton(label, code, 52, 1f).also {
                    (it.layoutParams as LinearLayout.LayoutParams).marginStart = if (i > 0) dp(8) else 0
                })
            }
            box.addView(line)
        }
        val (label, code) = acts.last()
        val last = answerButton(label, code, 48, 1f)
        (last.layoutParams as LinearLayout.LayoutParams).apply {
            width = LinearLayout.LayoutParams.MATCH_PARENT
            topMargin = dp(8)
        }
        box.addView(last)
    }

    private fun updateTime() {
        val hms = SimpleDateFormat("HH:mm:ss", Locale.US).format(impWall)
        val sec = ((System.currentTimeMillis() - impWall) / 1000L).coerceAtLeast(0L)
        val ago = if (sec < 60L) "${sec}초 지남" else "${sec / 60L}분 지남"
        val accent = String.format("#%06X", 0xFFFFFF and ContextCompat.getColor(this, R.color.sa_text_primary))
        binding.judgeActTime.text = HtmlCompat.fromHtml(
            "충격 ${hms} · <font color=\"${accent}\"><b>${ago}</b></font>", HtmlCompat.FROM_HTML_MODE_LEGACY
        )
    }

    private fun answer(label: String, code: String) {
        val nowEl = SystemClock.elapsedRealtime()
        if (JudgeQuestion.actExpired(posted, nowEl)) {
            Toast.makeText(applicationContext, "시간이 지나 기록하지 않았어요", Toast.LENGTH_SHORT).show()
        } else {
            val now = System.currentTimeMillis()
            // force: the question was posted while recording was on, so the answer is kept even if it was turned off since
            JudgeLog.truth(me, role, impT, JudgeQuestion.truthOf(code), "self", now - impWall, why, t = now, act = code, force = true)
            Toast.makeText(applicationContext, "기록했어요 · ${label}", Toast.LENGTH_SHORT).show()
        }
        // Another impact is still unanswered: show it in the notification again and open its card right away; none left: remove the notification
        val next = JudgeQuestion.nextPending(impT, nowEl)
        if (next != null) {
            JudgeQuestion.show(applicationContext, next, quiet = true)
            startActivity(JudgeQuestion.actIntent(this, next))
        } else {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(JudgeQuestion.ACT_ID)
        }
        finish()
    }
}
