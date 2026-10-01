package com.wf11.safealert.service

/** 문자열 키-값 저장소. put 은 모든 변경을 한 번에 적용하고 null 값은 지운다. */
interface SosKv {
    fun get(k: String): String?
    fun put(changes: Map<String, String?>)
}

/**
 * 서버 전송 통로. 구현은 콜백을 메인 스레드로 돌려준다.
 * uid() 가 null 이면 아직 로그인 전, sitePath() 가 null 이면 사업장 코드 없음.
 */
interface SosTransport {
    fun uid(): String?
    fun sitePath(): String?
    fun newKey(path: String): String
    fun create(path: String, key: String, rec: SosLedger.Record, uid: String, done: (Boolean) -> Unit)
    fun resolve(path: String, key: String, done: (Boolean) -> Unit)
    fun read(path: String, key: String, done: (SosLedger.Remote) -> Unit)
}

/**
 * 내 SOS 의 저장·전송 상태기계 (v1.1.99). 안드로이드 의존이 없는 순수 로직이며 모든 진입점은 메인 스레드다.
 *
 * 활성 칸(a.*)과 해제 대기 목록(r.list)을 따로 둔다. [괜찮아요]를 누르면 활성 칸은 그 자리에서 비고
 * 해제는 대기 목록에서 확인될 때까지 재시도되므로, 오프라인에서 해제한 직후 새 SOS 가 나도 곧바로 기록되고
 * 옛 해제의 늦은 응답은 자기 항목만 지운다 (RR01).
 *
 * 쓰기가 실패하면 한 번 읽어 본다. 디스크에 남은 쓰기를 SDK 가 재시작 뒤 다시 보내면 서버에는 이미 기록이 있어
 * 같은 키로 다시 만드는 쓰기가 생성 전용 규칙에 막힌다. 내 uid 의 기록이 이미 있으면 전송된 것으로 본다 (RR11).
 * 그 밖의 실패는 10초부터 두 배씩(최대 5분) 늘려 다시 시도한다. 규칙이 허용하지 않는 필드는 절대 쓰지 않는다.
 * 실패한 해제도 기록이 살아 있으면 슬롯을 유지하고 계속 다시 보낸다 (RR15).
 * (v1.2.2) 서버 저장이 확인된 순간 onSaved 로 알린다 — 구조 요청 메일이 여기서 시작한다.
 */
class SosLedger(
    private val kv: SosKv,
    private val transport: SosTransport,
    private val clock: () -> Long,
    private val onSaved: (event: String, path: String, key: String) -> Unit = { _, _, _ -> },
    private val onChange: () -> Unit = {}
) {
    data class Record(
        val bleId: String, val name: String, val role: String, val trigger: String,
        val beacon: String?, val beaconRssi: Int?, val sid: Int, val ep: Int = 0
    )

    enum class Remote { ABSENT, MINE_ACTIVE, MINE_RESOLVED, OTHER, ERROR }

    companion object {
        /** 저장 키. 접두어를 붙여 이전 개발 빌드가 남긴 다른 형의 키를 읽지 않게 한다. 존재 = 활성. */
        const val K_TRIGGER = "a.trigger"
        private const val K_BLE = "a.ble"
        private const val K_NAME = "a.name"
        private const val K_ROLE = "a.role"
        private const val K_BEACON = "a.beacon"
        private const val K_RSSI = "a.rssi"
        private const val K_SID = "a.sid"
        private const val K_EP = "a.ep"
        private const val K_KEY = "a.key"
        private const val K_PATH = "a.path"
        private const val K_SENT = "a.sent"
        private const val K_PENDING = "r.list"
        private const val K_EP_LAST = "ep.last"
        private val ACTIVE_KEYS = listOf(
            K_TRIGGER, K_BLE, K_NAME, K_ROLE, K_BEACON, K_RSSI, K_SID, K_EP, K_KEY, K_PATH, K_SENT
        )

        const val STATUS_SENT = "서버 전송됨"
        const val STATUS_SENDING = "서버 전송 중"
        const val STATUS_FAILED = "서버 전송 실패 — 재시도 중"

        private const val BACKOFF_BASE_MS = 10_000L
        private const val BACKOFF_MAX_MS = 300_000L

        /** 실패 횟수(1부터)에 따른 재시도 대기: 10초, 20초, 40초 ... 최대 5분. */
        fun backoffMs(failures: Int): Long {
            if (failures <= 1) return BACKOFF_BASE_MS
            return minOf(BACKOFF_MAX_MS, BACKOFF_BASE_MS shl minOf(failures - 1, 20))
        }

        /** 다음 에피소드 번호: 1..255, 0 은 "없음"이라 쓰지 않고 255 다음은 1. */
        fun nextEpisode(prev: Int): Int = (prev.coerceAtLeast(0) % 255) + 1
    }

    private class Pending(val path: String, val key: String)

    // 진행 중·실패·다음 허용 시각은 메모리에만 둔다(재시작하면 바로 다시 시도). 생성과 해제는 따로 센다.
    private val createBusy = HashSet<String>()
    private val createFails = HashMap<String, Int>()
    private val createNext = HashMap<String, Long>()
    private val resolveBusy = HashSet<String>()
    private val resolveFails = HashMap<String, Int>()
    private val resolveNext = HashMap<String, Long>()

    fun hasActive(): Boolean = kv.get(K_TRIGGER) != null
    fun restoredTrigger(): String? = kv.get(K_TRIGGER)
    fun episode(): Int = kv.get(K_EP)?.toIntOrNull() ?: 0
    fun hint(): Int = kv.get(K_SID)?.toIntOrNull() ?: 0

    /** SOS 진입. 이미 저장된 SOS(복원분)가 있으면 그대로 두어 두 번째 기록을 만들지 않는다. */
    fun begin(rec: Record) {
        if (hasActive()) return
        val ep = nextEpisode(kv.get(K_EP_LAST)?.toIntOrNull() ?: 0)
        val ch = HashMap<String, String?>()
        for (k in ACTIVE_KEYS) ch[k] = null
        ch[K_TRIGGER] = rec.trigger
        ch[K_BLE] = rec.bleId
        ch[K_NAME] = rec.name
        ch[K_ROLE] = rec.role
        ch[K_BEACON] = rec.beacon
        ch[K_RSSI] = rec.beaconRssi?.toString()
        ch[K_SID] = rec.sid.toString()
        ch[K_EP] = ep.toString()
        ch[K_EP_LAST] = ep.toString()
        kv.put(ch)
        trySend()
        onChange()
    }

    fun tick() {
        trySend()
        sendResolves()
    }

    /** 서버 전송 상태 문구. 내 SOS 가 없으면 null. */
    fun statusText(): String? {
        if (!hasActive()) return null
        if (kv.get(K_SENT) != null) return STATUS_SENT
        val key = kv.get(K_KEY)
        if (key != null && key in createBusy && (createFails[key] ?: 0) == 0) return STATUS_SENDING
        return STATUS_FAILED
    }

    /** 해제([괜찮아요]에서만 호출). 활성 칸은 바로 비우고 해제는 대기 목록으로 옮긴다. */
    fun resolve() {
        if (!hasActive()) return
        val key = kv.get(K_KEY)
        val path = kv.get(K_PATH)
        val ch = HashMap<String, String?>()
        for (k in ACTIVE_KEYS) ch[k] = null
        if (key != null && path != null) {
            val list = pending()
            if (list.none { it.key == key && it.path == path }) {
                ch[K_PENDING] = encode(list + Pending(path, key))
            }
        }
        kv.put(ch)
        sendResolves()
        onChange()
    }

    /** 해제가 서버에 닿지 못해 다시 보내는 중인 것이 있는가. */
    fun resolveFailing(): Boolean = pending().any { (resolveFails[it.key] ?: 0) > 0 }

    // ── 생성 ──────────────────────────────────────────────────

    private fun trySend() {
        if (!hasActive() || kv.get(K_SENT) != null) return
        var key = kv.get(K_KEY)
        var path = kv.get(K_PATH)
        if (key == null || path == null) {
            val p = transport.sitePath() ?: return
            if (transport.uid() == null) return
            path = p
            key = transport.newKey(p)
            kv.put(mapOf(K_PATH to path, K_KEY to key))
        }
        val uid = transport.uid() ?: return
        if (key in createBusy || (createNext[key] ?: 0L) > clock()) return
        createBusy.add(key)
        val sentKey = key
        val sentPath = path
        transport.create(sentPath, sentKey, currentRecord(), uid) { ok ->
            if (ok) {
                createBusy.remove(sentKey)
                onCreated(sentPath, sentKey)
                onChange()
            } else {
                // 서버에 이미 내 기록이 있으면(재시작 전 쓰기가 뒤늦게 반영) 다시 쓰지 않고 전송된 것으로 본다
                transport.read(sentPath, sentKey) { r ->
                    createBusy.remove(sentKey)
                    if (r == Remote.MINE_ACTIVE || r == Remote.MINE_RESOLVED) {
                        onCreated(sentPath, sentKey)
                    } else {
                        val n = (createFails[sentKey] ?: 0) + 1
                        createFails[sentKey] = n
                        createNext[sentKey] = clock() + backoffMs(n)
                    }
                    onChange()
                }
            }
        }
    }

    private fun onCreated(path: String, key: String) {
        createFails.remove(key)
        createNext.remove(key)
        // 늦게 온 확인이라도 서버에는 기록이 생겼으므로 알린다.
        // 메일 대기열이 먼저 남아야 사이에 죽어도 재확인으로 다시 알린다
        onSaved(SosMail.EVENT_SOS, path, key)
        // 해제·교체로 활성 키가 바뀐 뒤에 늦게 온 응답은 활성 칸에 쓰지 않는다
        if (kv.get(K_KEY) == key && hasActive()) kv.put(mapOf(K_SENT to "1"))
    }

    private fun currentRecord() = Record(
        kv.get(K_BLE).orEmpty(), kv.get(K_NAME).orEmpty(), kv.get(K_ROLE).orEmpty(),
        kv.get(K_TRIGGER).orEmpty(), kv.get(K_BEACON), kv.get(K_RSSI)?.toIntOrNull(),
        kv.get(K_SID)?.toIntOrNull() ?: 0, kv.get(K_EP)?.toIntOrNull() ?: 0
    )

    // ── 해제 ──────────────────────────────────────────────────

    private fun sendResolves() {
        for (e in pending()) {
            val key = e.key
            if (key in resolveBusy || (resolveNext[key] ?: 0L) > clock()) continue
            resolveBusy.add(key)
            transport.resolve(e.path, key) { ok ->
                if (ok) {
                    resolveBusy.remove(key)
                    // 메일 대기열이 먼저 남아야 사이에 죽어도 재확인으로 다시 알린다
                    onSaved(SosMail.EVENT_RESOLVED, e.path, key)
                    drop(e)
                    onChange()
                } else {
                    transport.read(e.path, key) { r ->
                        resolveBusy.remove(key)
                        if (r == Remote.MINE_RESOLVED) {
                            onSaved(SosMail.EVENT_RESOLVED, e.path, key) // 대기열 먼저(위와 같은 이유)
                            drop(e)
                        } else if (r == Remote.ABSENT) {
                            drop(e)
                        } else {
                            val n = (resolveFails[key] ?: 0) + 1
                            resolveFails[key] = n
                            resolveNext[key] = clock() + backoffMs(n)
                        }
                        onChange()
                    }
                }
            }
        }
    }

    private fun drop(e: Pending) {
        val left = pending().filterNot { it.key == e.key && it.path == e.path }
        kv.put(mapOf(K_PENDING to if (left.isEmpty()) null else encode(left)))
        resolveFails.remove(e.key)
        resolveNext.remove(e.key)
    }

    // 목록 저장 형식: 줄마다 "경로 TAB 키". 깨진 줄은 건너뛴다.
    private fun pending(): List<Pending> {
        val raw = kv.get(K_PENDING) ?: return emptyList()
        val out = ArrayList<Pending>()
        for (line in raw.split('\n')) {
            val i = line.indexOf('\t')
            if (i <= 0 || i == line.length - 1) continue
            out.add(Pending(line.substring(0, i), line.substring(i + 1)))
        }
        return out
    }

    private fun encode(list: List<Pending>): String = list.joinToString("\n") { it.path + "\t" + it.key }
}
