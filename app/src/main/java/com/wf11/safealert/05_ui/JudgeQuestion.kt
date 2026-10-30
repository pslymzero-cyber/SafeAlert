package com.wf11.safealert.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.wf11.safealert.R
import com.wf11.safealert.firebase.JudgeLog
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Judge-learning questions (recording only, never touches a judgment). The fall question is a heads-up notification
 * with three buttons answered by [JudgeAnswerReceiver]; the distance question lives in the alert sidebar (OverlayManager),
 * not here. The impact question (postAct) is a notification that opens [JudgeActActivity], a card with seven answers;
 * the answer is recorded with JudgeLog.truth(act = ...). The notification shows the newest question; unanswered earlier
 * ones are kept and the next card opens after one is answered. Notification ids already used elsewhere: 1001, 1002, 4242,
 * 4300 and 4301..4350, 4401 (fall question), 4402 (impact question) - keep new ones away from them.
 */
object JudgeQuestion {

    const val CHANNEL_ID = "judge_ask"
    const val FALL_ID = 4401
    const val REQ_FALL = 4411
    const val TIMEOUT_MS = 30 * 60 * 1000L

    const val ACT_CHANNEL_ID = "judge_act"
    const val ACT_ID = 4402
    const val REQ_ACT = 4421
    const val ACT_WINDOW_MS = 5 * 60 * 1000L
    const val EXTRA_IMP_WALL = "imp_wall"
    const val EXTRA_POSTED = "posted"

    /** Impact question answers: label to record code, in card order. */
    val ACTS: List<Pair<String, String>> = listOf(
        "걷는 중" to "walk", "뛰는 중" to "run", "폰 내려놓음" to "put_down", "폰 떨어뜨림" to "drop",
        "충전기 꽂음" to "charge", "넘어짐" to "fall", "모르겠어요" to "unknown",
    )

    /** Answer code to the truth value the analysis expects. */
    fun truthOf(act: String): String = when (act) {
        "fall" -> "fall"
        "unknown" -> "unknown"
        else -> "no"
    }

    /** Both times are elapsedRealtime, so a wall-clock change cannot expire or revive a question. */
    fun actExpired(postedEl: Long, nowEl: Long): Boolean = nowEl - postedEl > ACT_WINDOW_MS

    /** Elapsed-realtime impact time to wall clock. */
    fun wallOf(elapsedT: Long): Long = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - elapsedT)

    const val EXTRA_ME = "me"
    const val EXTRA_ROLE = "role"
    const val EXTRA_IMP_T = "imp_t"
    const val EXTRA_CLOSE_T = "close_t"
    const val EXTRA_WHY = "why"
    const val EXTRA_TRUTH = "truth"

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

    private fun actChannel(ctx: Context) {
        runCatching {
            val ch = NotificationChannel(ACT_CHANNEL_ID, "충격 질문", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 120)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    /** One posted impact question. */
    data class ActAsk(val me: String, val role: String, val impT: Long, val impWall: Long, val posted: Long, val why: String)

    /** Unanswered impact questions, oldest first (at most [ASKED_MAX]); process memory only (an app restart clears it). */
    private val pending = ArrayList<ActAsk>()

    /** Keeps an asked question until it is answered or its 5 minutes pass; the same impact replaces the earlier entry. */
    @Synchronized
    internal fun addPending(a: ActAsk) {
        pending.removeAll { it.impT == a.impT }
        pending.add(a)
        if (pending.size > ASKED_MAX) pending.removeAt(0)
    }

    /** After [done] was answered: drops it and every expired question, returns the newest one left (null if none). */
    @Synchronized
    internal fun nextPending(done: Long, nowEl: Long): ActAsk? {
        pending.removeAll { it.impT == done || actExpired(it.posted, nowEl) }
        return pending.lastOrNull()
    }

    /** The card for [a] (the notification's intent, and the next card after an older one is answered). */
    fun actIntent(ctx: Context, a: ActAsk): Intent = Intent(ctx, JudgeActActivity::class.java)
        .putExtra(EXTRA_ME, a.me).putExtra(EXTRA_ROLE, a.role).putExtra(EXTRA_IMP_T, a.impT)
        .putExtra(EXTRA_IMP_WALL, a.impWall).putExtra(EXTRA_POSTED, a.posted).putExtra(EXTRA_WHY, a.why)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    /** Impact times (elapsed) already asked, newest last; process memory only (an app restart clears it). */
    private val asked = ArrayDeque<Long>()
    private const val ASKED_MAX = 16

    /** True the first time an impact is asked (and remembers it), false when it was already asked: one question per impact. */
    @Synchronized
    internal fun firstAsk(impT: Long): Boolean {
        if (impT in asked) return false
        asked.addLast(impT)
        if (asked.size > ASKED_MAX) asked.removeFirst()
        return true
    }

    /**
     * An impact happened (impT = elapsed time of the candidate): ask what it was. One notification id showing the newest
     * question; an unanswered earlier one is kept ([addPending]) and its card opens after an answer. Equipment phones
     * (forklift / EPJ, not carts) get it silently, others one short vibration.
     */
    fun postAct(ctx: Context, me: String, role: String, impT: Long, why: String) {
        if (!firstAsk(impT)) return
        runCatching {
            val ask = ActAsk(me, role, impT, wallOf(impT), SystemClock.elapsedRealtime(), why)
            addPending(ask)
            show(ctx, ask, quiet = false)
        }
    }

    /** Shows [a] in the one impact notification, for the rest of its 5 minutes; [quiet] = shown again after an answer, no new sound or vibration (even when the notification was dismissed or timed out). */
    fun show(ctx: Context, a: ActAsk, quiet: Boolean) {
        runCatching {
            val equipment = (a.role == "FORKLIFT" || a.role == "EPJ") &&
                !ctx.getSharedPreferences("safealert_prefs", Context.MODE_PRIVATE).getBoolean("running_cart", false)
            val chId = if (equipment) { channel(ctx); CHANNEL_ID } else { actChannel(ctx); ACT_CHANNEL_ID }
            val pi = PendingIntent.getActivity(ctx, REQ_ACT, actIntent(ctx, a), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val hms = SimpleDateFormat("HH:mm:ss", Locale.US).format(a.impWall)
            val b = NotificationCompat.Builder(ctx, chId)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle("방금 충격이 있었어요")
                .setContentText("${hms} · 무엇이었는지 눌러서 알려 주세요")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(pi)
                .addAction(0, "답하기", pi)
                .setSilent(quiet)
                .setTimeoutAfter((ACT_WINDOW_MS - (SystemClock.elapsedRealtime() - a.posted)).coerceAtLeast(1L))
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ACT_ID, b.build())
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
                force = true,   // the question was posted while recording was on
            )
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(JudgeQuestion.FALL_ID)
        }
    }
}
