package com.wf11.safealert.service

import java.net.URLEncoder

/**
 * 단독 작업자 구조 요청 메일 대기열 (v1.2.2). 안드로이드 의존이 없는 순수 로직이며 모든 진입점은 메인 스레드다.
 *
 * 구조 요청·해제가 서버에 기록된 것이 확인된 순간(SosLedger.onSaved)에만 항목이 들어오고,
 * 메일 스크립트에 요청을 보내 성공·중복이면 지우고, 일시 실패면 10초부터 두 배씩(최대 5분) 늘려 다시 보낸다.
 * 서버 저장 확인 뒤(넣은 시각) 2시간이 넘거나 시계가 1분 넘게 거꾸로 가면 포기한다(스크립트의 신선도 기준과 같다).
 * 같은 기록의 sos 가 남아 있는 동안 resolved 는 보내지 않고, sos 가 끝나면 바로 이어 보낸다.
 * 받는 주소·무동작 분은 넣는 순간 고정한다. 해제 메일은 같은 기록의 구조 요청 메일이 들어갈 때의 주소로 간다
 * (기록별로 7일 보관). 이 단말에서 구조 요청 메일을 넣은 적 없는 기록이면 해제 메일도 넣지 않는다.
 * 구조 요청이 확정 거절되면 그 기록의 해제도 넣지 않는다. 주소 행은 7일 지나면 쓰지 않는다.
 * 메일은 보조 통로라 어떤 경보·판정도 이 클래스를 기다리지 않는다.
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
        const val K_ADDR = "m.to"
        const val KEEP_ADDR_MS = 7 * 86_400_000L

        private val SCRIPT_URL = Regex("https://script\\.google\\.com/macros/s/[A-Za-z0-9_-]+/exec")
        private val ADDRESS = Regex("[A-Za-z0-9%+_-]+(\\.[A-Za-z0-9%+_-]+)*@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+")
        private val CODE = Regex("\"code\"\\s*:\\s*\"([a-z_]+)\"")

        fun validAddress(s: String): Boolean = s.length in 1..254 && ADDRESS.matches(s)

        /** 웹 앱 배포 주소(https://script.google.com/macros/s/<id>/exec) 형식이면 정리한 값, 아니면 빈 값(메일 꺼짐). */
        fun scriptUrl(raw: String): String = raw.trim().let { if (SCRIPT_URL.matches(it)) it else "" }

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

    // 강제 종료로 예약 작업이 지워져도 다음 생성 때 남은 대기열로 다시 건다.
    init {
        if (load().isNotEmpty()) onQueue(true)
    }

    /** 서버 기록이 확인된 순간 부른다. path = "{루트}/sos/{사업장 코드}". */
    fun enqueue(event: String, path: String, key: String, stillMin: Int) {
        if (event != EVENT_SOS && event != EVENT_RESOLVED) return
        val i = path.lastIndexOf("/sos/")
        if (i <= 0) return
        val site = path.substring(0, i).trim('/')
        if (site.isEmpty()) return
        val sc = path.substring(i + 5)
        if (sc.isEmpty() || key.isEmpty()) return
        val t = now()
        val addr = loadAddr().filter { t - it[2].toLong() <= KEEP_ADDR_MS }
        // 해제는 설정을 다시 읽지 않고 같은 기록의 구조 요청 메일 주소를 쓴다(없거나 7일 지났으면 넣지 않음)
        val to = if (event == EVENT_SOS) addressFor(sc).trim()
            else addr.firstOrNull { it[0] == key }?.get(1) ?: return
        if (!validAddress(to)) return
        val list = load()
        if (list.any { it.event == event && it.id == key }) return
        val kept = addr.filter { it[0] != key }
        val rows = if (event == EVENT_SOS) kept + listOf(listOf(key, to, t.toString())) else kept
        save(list + Item(event, site, sc, key, to, stillMin.coerceIn(1, 30), t), mapOf(K_ADDR to addrText(rows)))
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
                val o = outcome(body)
                if (o == Outcome.RETRY) {
                    val n = (fails[tag] ?: 0) + 1
                    fails[tag] = n
                    next[tag] = now() + SosLedger.backoffMs(n)
                } else {
                    fails.remove(tag)
                    next.remove(tag)
                    val list = load()
                    if (o == Outcome.DROP && e.event == EVENT_SOS) {
                        // 구조 요청 메일이 안 나간 것이 확정이라 같은 기록의 해제와 주소 행도 지운다
                        val rest = list.filterNot { it.id == e.id }
                        val addr = loadAddr()
                        val rows = addr.filter { it[0] != e.id }
                        if (rest.size != list.size || rows.size != addr.size) {
                            save(rest, if (rows.size != addr.size) mapOf(K_ADDR to addrText(rows)) else emptyMap())
                        }
                    } else if (list.any { it.tag == tag }) save(list.filterNot { it.tag == tag })
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

    // 기록별 받는 주소: 한 줄에 '기록 키 \t 주소 \t 넣은 시각'. 칸 수·시각이 어긋난 줄은 버린다.
    private fun loadAddr(): List<List<String>> = (kv.get(K_ADDR) ?: "").split('\n')
        .map { it.split('\t') }.filter { it.size == 3 && it[2].toLongOrNull() != null }

    private fun addrText(rows: List<List<String>>): String? =
        if (rows.isEmpty()) null else rows.joinToString("\n") { it.joinToString("\t") }

    private fun save(list: List<Item>, extra: Map<String, String?> = emptyMap()) {
        kv.put(extra + (K_LIST to if (list.isEmpty()) null else list.joinToString("\n") {
            listOf(it.event, it.site, it.sc, it.id, it.to, it.stillMin, it.at).joinToString("\t")
        }))
        onQueue(list.isNotEmpty())
    }
}
