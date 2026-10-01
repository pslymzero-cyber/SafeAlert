package com.wf11.safealert.service

/** 살아 있음 기록이 서버에 닿는 통로. 안드로이드 구현은 LoneWorkerSosSync 에 있다. */
interface HbRemote {
    /** 익명 로그인 uid. 로그인 전이면 null. */
    fun uid(): String?
    /** "{루트}/hb/{센터 코드}". 사업장 코드가 없으면 null. */
    fun path(): String?
    fun newKey(path: String): String
    /** 추정 서버 시각(모르면 벽시계). 일이 일어난 순간에 찍어 늦게 닿아도 시각이 바뀌지 않게 한다. */
    fun serverNow(): Long
    /** 세션 노드 일부 갱신. done 은 메인 스레드에서 서버 확인 결과로 불린다. */
    fun update(path: String, key: String, fields: Map<String, Any>, done: (Boolean) -> Unit)
}

/**
 * 단독 작업자 감시 중 '살아 있음' 세션 기록 (v1.2.2). 안드로이드 의존이 없는 순수 로직이며 모든 진입점은 메인 스레드다.
 * 감시가 켜져 있는 동안 세션 하나({uid, role, start, last})를 열고 5분마다 last 를 갱신하며, 정상 종료면 end 를 남긴다.
 * 서버 쓰기 성공 사이가 15분을 넘으면 끊김 구간 g/{n} = {from, to} 를 세션당 50개까지 남긴다.
 * 이름·기기 식별자는 남기지 않는다. 기록만 하고 메일·알림은 없다(경보·판정과 무관).
 * 시작·갱신이 거부되면 5분 뒤 새 세션을 열고, 끝·끊김 구간 쓰기 실패는 무시한다.
 */
class LoneWorkerHeartbeat(private val remote: HbRemote, private val clock: () -> Long) {
    companion object {
        const val INTERVAL_MS = 5 * 60_000L
        const val GAP_MS = 15 * 60_000L
        const val MAX_GAPS = 50
        private val ROLES = setOf("WALKER", "EPJ", "FORKLIFT", "UNKNOWN")
    }

    private var key: String? = null
    private var path = ""
    private var role = ""
    private var lastTry = 0L
    private var failedAt: Long? = null
    private var okAt = 0L       // 직전 성공(첫 기준은 세션 시작)의 clock
    private var okServer = 0L   // 그 순간의 추정 서버 시각
    private var gaps = 0
    private var gen = 0

    /** 감시 tick(10초)마다 부른다. on = 단독 작업자 감시가 켜져 있는가. */
    fun tick(on: Boolean, role: String) {
        if (!on) { end(); return }
        val r = if (role in ROLES) role else "UNKNOWN"
        val p = remote.path()
        if (key != null && (p != path || r != this.role)) end()
        val t = clock()
        val k = key
        if (k == null) { start(p, r, t); return }
        if (t - lastTry < INTERVAL_MS) return
        lastTry = t
        write(k, mapOf("last" to remote.serverNow()))
    }

    /** 정상 종료([중지]·감시 끄기·역할·사업장 변경). 결과는 기다리지 않는다. */
    fun end() {
        val k = key ?: return
        key = null
        gen++
        val s = remote.serverNow()
        val f = mutableMapOf<String, Any>("last" to s, "end" to s)
        // 음영 중 끝나도 서버가 못 들은 구간을 끝과 같은 쓰기로 남긴다
        if (clock() - okAt > GAP_MS && gaps < MAX_GAPS) f["g/$gaps"] = mapOf("from" to okServer, "to" to s)
        remote.update(path, k, f) {}
    }

    private fun start(p: String?, r: String, t: Long) {
        if (p == null) return
        failedAt?.let { if (t - it < INTERVAL_MS) return }
        val uid = remote.uid() ?: return
        val k = remote.newKey(p)
        val s = remote.serverNow()
        key = k; path = p; role = r
        lastTry = t; okAt = t; okServer = s; gaps = 0; failedAt = null
        remote.update(p, k, mapOf("uid" to uid, "role" to r, "start" to s, "last" to s), answer(++gen, k))
    }

    private fun write(k: String, fields: Map<String, Any>) = remote.update(path, k, fields, answer(gen, k))

    // 시작·갱신 결과. 지난 세대는 무시, 거부되면 5분 뒤 새 키로
    private fun answer(g: Int, k: String): (Boolean) -> Unit = { ok ->
        if (g == gen) { if (ok) onOk(k) else { key = null; gen++; failedAt = clock() } }
    }

    // 성공 사이가 15분을 넘으면 그 구간을 남긴다(오프라인에 쌓였다 늦게 닿은 쓰기도 잡힌다)
    private fun onOk(k: String) {
        val t = clock()
        val s = remote.serverNow()
        if (t - okAt > GAP_MS && gaps < MAX_GAPS) {
            remote.update(path, k, mapOf("g/$gaps" to mapOf("from" to okServer, "to" to s))) {}
            gaps++
        }
        okAt = t
        okServer = s
    }
}
