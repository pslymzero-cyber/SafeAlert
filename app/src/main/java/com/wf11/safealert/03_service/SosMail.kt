package com.wf11.safealert.service

import java.net.URLEncoder

/**
 * 단독 작업자 구조 요청 메일 대기열 (v1.2.2). 안드로이드 의존이 없는 순수 로직이며 모든 진입점은 메인 스레드다.
 *
 * 구조 요청·해제가 서버에 기록된 것이 확인된 순간(SosLedger.onSaved)에만 항목이 들어오고,
 * 메일 스크립트에 요청을 보내 성공·중복이면 지우고, 일시 실패면 10초부터 두 배씩(최대 5분) 늘려 다시 보낸다.
 * 서버 저장 확인 뒤(넣은 시각) 2시간이 넘거나 시계가 1분 넘게 거꾸로 가면 포기한다(스크립트의 신선도 기준과 같다).
 * 같은 기록의 sos 가 남아 있는 동안 resolved 는 보내지 않고, sos 가 끝나면 바로 이어 보낸다.
 * 받는 주소·무동작 분은 넣는 순간 고정한다. 메일은 보조 통로라 어떤 경보·판정도 이 클래스를 기다리지 않는다.
 * 대기열이 찼는지 비었는지는 onQueue 로 알린다(감시가 멈춘 뒤에도 보내는 예약 작업이 이를 따른다).
 */
class SosMail(
    private val kv: SosKv,
    private val post: (form: String, done: (String?) -> Unit) -> Unit,
    private val now: () -> Long,
    private val onQueue: (pending: Boolean) -> Unit = {},
    private val addressFor: (siteCode: String) -> String
) {
    enum class Outcome { DONE, RETRY, DROP }

    companion object {
        const val EVENT_SOS = "sos"
        const val EVENT_RESOLVED = "resolved"
        const val DEFAULT_TO = "pslymzero@coupangfs.com"
        const val GIVE_UP_MS = 2 * 3_600_000L
        const val CLOCK_BACK_MS = 60_000L
        const val K_LIST = "m.list"

        private val ADDRESS = Regex("[A-Za-z0-9%+_-]+(\\.[A-Za-z0-9%+_-]+)*@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+")
        private val CODE = Regex("\"code\"\\s*:\\s*\"([a-z_]+)\"")

        fun validAddress(s: String): Boolean = s.length in 1..254 && ADDRESS.matches(s)

        /** 스크립트 응답의 code 값(비밀 아님, 로그에 남겨도 된다). 없으면 null. */
        fun code(body: String?): String? = body?.let { CODE.find(it)?.groupValues?.get(1) }

        /** 스크립트 응답 해석. 코드만 읽는다. 모르는 코드·응답 없음은 다시 시도한다. */
        fun outcome(body: String?): Outcome = when (code(body)) {
            "sent", "dup" -> Outcome.DONE
            "bad_request", "not_allowed", "stale" -> Outcome.DROP
            else -> Outcome.RETRY
        }

        fun form(site: String, sc: String, id: String, event: String, to: String, stillMin: Int): String =
            listOf("site" to site, "sc" to sc, "id" to id, "event" to event, "to" to to, "stillMin" to stillMin.toString())
                .joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
    }

    private class Item(
        val event: String, val site: String, val sc: String, val id: String,
        val to: String, val stillMin: Int, val at: Long
    ) {
        val tag get() = "$event/$id"
    }

    // 진행 중·실패 횟수·다음 허용 시각은 메모리에만 둔다(재시작하면 바로 다시 시도).
    private val busy = HashSet<String>()
    private val fails = HashMap<String, Int>()
    private val next = HashMap<String, Long>()
    private var drainDone: ((Boolean) -> Unit)? = null

    /** 서버 기록이 확인된 순간 부른다. path = "{루트}/sos/{사업장 코드}". */
    fun enqueue(event: String, path: String, key: String, stillMin: Int) {
        if (event != EVENT_SOS && event != EVENT_RESOLVED) return
        val i = path.lastIndexOf("/sos/")
        if (i <= 0) return
        val site = path.substring(0, i).trim('/')
        if (site.isEmpty()) return
        val sc = path.substring(i + 5)
        if (sc.isEmpty() || key.isEmpty()) return
        val to = addressFor(sc).trim()
        if (!validAddress(to)) return
        val list = load()
        if (list.any { it.event == event && it.id == key }) return
        save(list + Item(event, site, sc, key, to, stillMin.coerceIn(1, 30), now()))
        tick()
    }

    fun tick() {
        val t = now()
        val all = load()
        val live = all.filter { t - it.at <= GIVE_UP_MS && it.at - t <= CLOCK_BACK_MS }
        if (live.size != all.size) save(live)
        for (e in live) {
            val tag = e.tag
            if (e.event == EVENT_RESOLVED && live.any { it.event == EVENT_SOS && it.id == e.id }) continue
            if (tag in busy || (next[tag] ?: 0L) > t) continue
            busy.add(tag)
            post(form(e.site, e.sc, e.id, e.event, e.to, e.stillMin)) { body ->
                busy.remove(tag)
                if (outcome(body) == Outcome.RETRY) {
                    val n = (fails[tag] ?: 0) + 1
                    fails[tag] = n
                    next[tag] = now() + SosLedger.backoffMs(n)
                } else {
                    fails.remove(tag)
                    next.remove(tag)
                    val list = load()
                    if (list.any { it.tag == tag }) save(list.filterNot { it.tag == tag })
                    tick() // 기다리던 같은 기록의 해제를 바로 보낸다
                }
                checkDrain()
            }
        }
    }

    /** 지금 보낼 것을 보내고, 진행 중인 요청이 모두 돌아오면 한 번 done(남은 항목이 있는가)을 부른다. */
    fun drain(done: (Boolean) -> Unit) {
        drainDone = done
        tick()
        checkDrain()
    }

    private fun checkDrain() {
        if (busy.isNotEmpty()) return
        val d = drainDone ?: return
        drainDone = null
        d(load().isNotEmpty())
    }

    // 저장 형식: 항목마다 한 줄, 탭으로 7칸. 칸 수·숫자가 어긋난 줄은 건너뛴다.
    private fun load(): List<Item> {
        val raw = kv.get(K_LIST) ?: return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val c = line.split('\t')
            if (c.size != 7) return@mapNotNull null
            val still = c[5].toIntOrNull() ?: return@mapNotNull null
            val at = c[6].toLongOrNull() ?: return@mapNotNull null
            Item(c[0], c[1], c[2], c[3], c[4], still, at)
        }
    }

    private fun save(list: List<Item>) {
        kv.put(mapOf(K_LIST to if (list.isEmpty()) null else list.joinToString("\n") {
            listOf(it.event, it.site, it.sc, it.id, it.to, it.stillMin, it.at).joinToString("\t")
        }))
        onQueue(list.isNotEmpty())
    }
}
