package com.wf11.safealert.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import com.wf11.safealert.R
import com.wf11.safealert.databinding.DialogJudgeDistBinding
import com.wf11.safealert.firebase.JudgeLog
import com.wf11.safealert.service.JudgeAsk

/**
 * Judge-learning questions (recording only, never touches a judgment). The fall question is a heads-up notification
 * with three buttons answered by [JudgeAnswerReceiver]; the distance question is a notification that opens
 * [JudgeDistActivity] (a notification takes three buttons at most). Notification ids already used elsewhere: 1001, 1002, 4242,
 * 4300 and 4301..4350 - keep new ones away from them.
 */
object JudgeQuestion {

    const val CHANNEL_ID = "judge_ask"
    const val FALL_ID = 4401
    const val DIST_BASE = 4402
    const val REQ_FALL = 4411
    const val TIMEOUT_MS = 30 * 60 * 1000L

    const val EXTRA_ME = "me"
    const val EXTRA_ROLE = "role"
    const val EXTRA_IMP_T = "imp_t"
    const val EXTRA_CLOSE_T = "close_t"
    const val EXTRA_WHY = "why"
    const val EXTRA_TRUTH = "truth"
    const val EXTRA_PEER = "peer"
    const val EXTRA_LABEL = "label"
    const val EXTRA_ALERT_T = "alert_t"
    const val EXTRA_NID = "nid"

    private fun channel(ctx: Context) {
        runCatching {
            val ch = NotificationChannel(CHANNEL_ID, "판정 학습 질문", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    /** A fall check or fall SOS closed on this phone: ask whether the person really fell. */
    fun postFall(ctx: Context, me: String, role: String, impT: Long, closeT: Long, why: String) {
        runCatching {
            channel(ctx)
            val title = if (why == "rescuer") "작업자가 실제로 넘어졌나요?" else "방금 실제로 넘어지셨나요?"
            val text = if (why == "walk") "넘어짐 확인 창이 걸음으로 닫혔어요. 답하면 판정을 더 정확하게 만드는 데 쓰여요."
            else "판정을 더 정확하게 만드는 데만 쓰여요. 구조 요청과 경보에는 영향이 없어요."
            val b = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setTimeoutAfter(TIMEOUT_MS)
            listOf("넘어졌어요" to "fall", "아니요" to "no", "모르겠어요" to "unknown").forEachIndexed { i, (label, truth) ->
                val intent = Intent(ctx, JudgeAnswerReceiver::class.java)
                    .putExtra(EXTRA_ME, me).putExtra(EXTRA_ROLE, role).putExtra(EXTRA_IMP_T, impT)
                    .putExtra(EXTRA_CLOSE_T, closeT).putExtra(EXTRA_WHY, why).putExtra(EXTRA_TRUTH, truth)
                val pi = PendingIntent.getBroadcast(ctx, REQ_FALL + i, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                b.addAction(0, label, pi)
            }
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(FALL_ID, b.build())
        }
    }

    /** A proximity DANGER ended (equipment phones: once stopped): ask the distance at that moment. One tap opens the answer dialog. */
    fun postDist(ctx: Context, a: JudgeAsk.Ask, me: String, role: String) {
        runCatching {
            channel(ctx)
            val nid = DIST_BASE + a.slot
            val intent = Intent(ctx, JudgeDistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_ME, me).putExtra(EXTRA_ROLE, role).putExtra(EXTRA_PEER, a.peer)
                .putExtra(EXTRA_LABEL, a.label).putExtra(EXTRA_ALERT_T, a.alertT).putExtra(EXTRA_NID, nid)
            val pi = PendingIntent.getActivity(ctx, nid, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val text = "${a.label} 위험 경보가 끝났어요. 눌러서 답하면 판정을 더 정확하게 만드는 데 쓰여요."
            val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle("방금 경보, 상대와 거리가 얼마였나요?")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi)
                .addAction(0, "답하기", pi)
                .setAutoCancel(false)   // stays until answered or timed out
                .setTimeoutAfter(TIMEOUT_MS)
                .build()
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(nid, n)
        }
    }
}

/** Fall question buttons: records the answer only and removes the notification, never opens the app. */
class JudgeAnswerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching {
            val now = System.currentTimeMillis()
            val why = intent.getStringExtra(JudgeQuestion.EXTRA_WHY) ?: ""
            JudgeLog.truth(
                me = intent.getStringExtra(JudgeQuestion.EXTRA_ME) ?: "",
                role = intent.getStringExtra(JudgeQuestion.EXTRA_ROLE) ?: "",
                impT = intent.getLongExtra(JudgeQuestion.EXTRA_IMP_T, 0L),
                truth = intent.getStringExtra(JudgeQuestion.EXTRA_TRUTH) ?: "unknown",
                by = if (why == "rescuer") "rescuer" else "self",
                afterMs = now - intent.getLongExtra(JudgeQuestion.EXTRA_CLOSE_T, now),
                closeWhy = why,
                t = now,
            )
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(JudgeQuestion.FALL_ID)
        }
    }
}

/** Distance question dialog (judge learning): five choices, recorded by [JudgeLog.dist]. Shows no measured distance (it would bias the answer). */
class JudgeDistActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val me = intent.getStringExtra(JudgeQuestion.EXTRA_ME) ?: ""
        val role = intent.getStringExtra(JudgeQuestion.EXTRA_ROLE) ?: ""
        val peer = intent.getStringExtra(JudgeQuestion.EXTRA_PEER) ?: ""
        val label = intent.getStringExtra(JudgeQuestion.EXTRA_LABEL) ?: ""
        val alertT = intent.getLongExtra(JudgeQuestion.EXTRA_ALERT_T, 0L)
        val nid = intent.getIntExtra(JudgeQuestion.EXTRA_NID, 0)
        val b = DialogJudgeDistBinding.inflate(layoutInflater)
        val s = ((System.currentTimeMillis() - alertT) / 1000).coerceAtLeast(0)
        val ago = if (s < 60) "${s}초 전" else "${s / 60}분 전"
        b.tvJudgeDistSub.text = "${label} 위험 경보 · ${ago}"
        val dialog = android.app.AlertDialog.Builder(this)
            .setView(b.root)
            .setOnDismissListener { finish() }
            .create()
        fun answer(code: String) {
            runCatching {
                JudgeLog.dist(me, role, peer, alertT, code)
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(nid)
            }
            dialog.dismiss()
        }
        b.btnDistLt1.setOnClickListener { answer("lt1") }
        b.btnDist1to3.setOnClickListener { answer("1to3") }
        b.btnDist3to5.setOnClickListener { answer("3to5") }
        b.btnDistGt5.setOnClickListener { answer("gt5") }
        b.btnDistUnknown.setOnClickListener { answer("unknown") }
        dialog.show()
    }
}
