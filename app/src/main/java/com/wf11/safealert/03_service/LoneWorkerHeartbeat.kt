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
 * 시작·갱신이 거부되면 5분 뒤 새 세션을 열고 이어 받기는 지운다. 끝·끊김 구간 쓰기 실패는 무시한다.
 * 끝 표시 없이 프로세스가 죽은 뒤 같은 uid·같은 센터로, 마지막으로 보낸 시작·갱신 도장에서 15분 안에 다시 시작하면
 * 직전 성공 서버 시각을 이어 받는다. 이 기준은 새 세션의 첫 성공 응답(또는 끝)에서 놓아, 재시작을 사이에 둔 끊김도
 * 구간으로 남긴다(정상 종료하면 지운다).
 */
class LoneWorkerHeartbeat(private val remote: HbRemote, private val kv: SosKv, private val clock: () -> Long) {
    companion object {
        const val INTERVAL_MS = 5 * 60_000L
        const val GAP_MS = 15 * 60_000L
        const val MAX_GAPS = 50
        /**
         * 단말에 남기는 이어 받기 값: '경로 \t 기준 서버 시각(직전 성공, 첫 성공 전이면 세션 시작 또는 이어 받은 값)
         * \t 마지막으로 보낸 시작·갱신 서버 시각 \t uid'.
         */
        const val K_CARRY = "hb.carry"
        private val ROLES = setOf("WALKER", "EPJ", "FORKLIFT", "UNKNOWN")
    }

    private var key: String? = null
    private var path = ""
    private var role = ""
    private var uid = ""
    private var lastTry = 0L
    private var failedAt: Long? = null
    private var okAt = 0L       // 직전 성공의 clock(첫 기준은 세션 시작, 이어 받았으면 첫 성공·끝에서 다시 놓음)
    private var okServer = 0L   // 그 순간의 추정 서버 시각
    // 마지막으로 보낸 시작·갱신의 추정 서버 시각 — 서버 last 와 같은 값이라 집계의 재시작 짝짓기 기준과 맞다
    private var sentAt = 0L
    // 이어 받은 직전 성공 서버 시각. 서버 시각을 아는 첫 성공에서 기준으로 놓는다
    private var carried: Long? = null
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
        sentAt = remote.serverNow()
        keep()
        write(k, mapOf("last" to sentAt))
    }

    /** 정상 종료([중지]·감시 끄기·역할·사업장 변경). 결과는 기다리지 않는다. */
    fun end() {
        keep(null) // 갱신 거부로 세션이 없을 때 멈춰도 이어 받기를 지운다
        val k = key ?: return
        key = null
        gen++
        val s = remote.serverNow()
        val f = mutableMapOf<String, Any>("last" to s, "end" to s)
        // 음영 중 끝나도 서버가 못 들은 구간을 끝과 같은 쓰기로 남긴다
        gap(clock(), s)?.let { f += it }
        remote.update(path, k, f) {}
    }

    private fun start(p: String?, r: String, t: Long) {
        if (p == null) return
        failedAt?.let { if (t - it < INTERVAL_MS) return }
        val u = remote.uid() ?: return
        val k = remote.newKey(p)
        val s = remote.serverNow()
        key = k; path = p; role = r; uid = u
        lastTry = t; okAt = t; okServer = s; gaps = 0; failedAt = null; sentAt = s; carried = null
        // 끝 표시 없이 멈춘 직전 세션(같은 uid·같은 센터, 마지막으로 보낸 도장에서 15분 안)의 성공 서버 시각을
        // 이어 받는다. okServer 를 바로 바꿔 첫 성공 전에 또 죽어도 같은 기준을 잇고, 기준 시각은 gap() 에서 놓는다
        val c = kv.get(K_CARRY)?.split('\t')
        val ok = c?.getOrNull(1)?.toLongOrNull()
        val last = c?.getOrNull(2)?.toLongOrNull()
        if (c?.size == 4 && c[0] == p && c[3] == u && ok != null && ok > 0 && last != null && s - last <= GAP_MS) {
            okServer = ok
            carried = ok
        }
        keep()
        remote.update(p, k, mapOf("uid" to u, "role" to r, "start" to s, "last" to s), answer(++gen, k))
    }

    private fun write(k: String, fields: Map<String, Any>) = remote.update(path, k, fields, answer(gen, k))

    // 시작·갱신 결과. 지난 세대는 무시, 거부되면 5분 뒤 새 키로
    private fun answer(g: Int, k: String): (Boolean) -> Unit = { ok ->
        if (g == gen) {
            if (ok) onOk(k) else {
                key = null; gen++; failedAt = clock()
                keep(null) // 거부된 쓰기가 남긴 도장은 서버에 없는 값이라 이어 받지 않는다
            }
        }
    }

    // 성공 사이가 15분을 넘으면 그 구간을 남긴다(오프라인에 쌓였다 늦게 닿은 쓰기도 잡힌다)
    private fun onOk(k: String) {
        val t = clock()
        val s = remote.serverNow()
        gap(t, s)?.let {
            remote.update(path, k, mapOf(it)) {}
            gaps++
        }
        okAt = t
        okServer = s
        keep()
    }

    // 이어 받은 기준은 서버 시각을 아는 첫 성공(onOk) 또는 끝(end)에서 놓는다
    private fun gap(t: Long, s: Long): Pair<String, Any>? {
        carried?.let { okAt = t - (s - it); carried = null }
        return if (t - okAt > GAP_MS && gaps < MAX_GAPS) "g/$gaps" to mapOf("from" to okServer, "to" to s) else null
    }

    // 바뀐 때만 쓴다(apply). 응답 처리 전에 죽거나 이 쓰기를 잃으면 한 주기 이른 기준이 남아, 재시작 때 거짓 끊김이
    // 하나 생길 수 있다(서버가 받은 쓰기인지 단말에서 구분할 수 없다)
    private fun keep(v: String? = "$path\t$okServer\t$sentAt\t$uid") {
        if (kv.get(K_CARRY) != v) kv.put(mapOf(K_CARRY to v))
    }
}
