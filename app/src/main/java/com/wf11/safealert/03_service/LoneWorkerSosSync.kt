package com.wf11.safealert.service

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.SystemClock
import com.google.firebase.database.ServerValue
import com.wf11.safealert.firebase.FirebaseConfig
import com.wf11.safealert.firebase.SosRemote
import com.wf11.safealert.utils.DevSettings

/**
 * 단독 작업자 SOS 의 서버 쪽 일 전부 (v1.1.99): 내 SOS 영속 저장·전송·해제는 [SosLedger] 에 맡기고,
 * 여기서는 SharedPreferences·Firebase 를 그 인터페이스에 이어 붙이며 동료 수신 재연결을 맡는다.
 *
 * 내 SOS 는 [괜찮아요]로만 끝나므로(R3) 서비스 종료·역할 전환·재시작·프로세스 사망 뒤에도 남아야 한다.
 * 해제는 저장해 둔 경로에 쓴다(현재 사업장 코드로 경로를 다시 만들지 않는다).
 *
 * 생성자는 참조만 저장한다(SharedPreferences·원장은 첫 사용 때 만든다). 모든 진입점은 메인 스레드에서 불리고,
 * Firebase 콜백은 handler 로 메인에 다시 게시한 뒤 원장에 전달한다.
 */
class LoneWorkerSosSync(
    private val ctx: Context,
    private val handler: Handler,
    private val onPeer: (SosRemote.SosRecord) -> Unit,
    private val onChange: () -> Unit
) {
    companion object {
        private const val FILE = "lone_worker_sos"

        /** 저장된(해제 전) 내 SOS 가 있는가. 서비스가 비어 있는 프로세스에서 시작될 때 복원 여부를 가리는 데 쓴다. */
        fun hasStoredSos(ctx: Context): Boolean = runCatching {
            ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(SosLedger.K_TRIGGER, null) != null
        }.getOrDefault(false)
    }

    private val prefs: SharedPreferences by lazy { ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    private val kv = object : SosKv {
        // 이전 개발 빌드가 다른 형으로 남긴 값은 읽지 않는다
        override fun get(k: String): String? = runCatching { prefs.getString(k, null) }.getOrNull()
        override fun put(changes: Map<String, String?>) {
            val e = prefs.edit()
            for ((k, v) in changes) if (v == null) e.remove(k) else e.putString(k, v)
            e.commit()
        }
    }

    private val transport = object : SosTransport {
        override fun uid(): String? {
            SosRemote.currentUid()?.let { return it }
            FirebaseConfig.ensureSignedIn()
            return null
        }

        override fun sitePath(): String? {
            val site = DevSettings.siteCode
            return if (site.isEmpty()) null else SosRemote.nodePath(DevSettings.firebaseRoot, site)
        }

        override fun newKey(path: String): String = SosRemote.newKey(path)

        // 규칙(R2)이 허용하는 필드만 쓴다: 에피소드는 선택 필드 ep 로 싣고, 비콘 짧은 ID 는 서버 기록에 넣지 않는다
        override fun create(path: String, key: String, rec: SosLedger.Record, uid: String, done: (Boolean) -> Unit) {
            val payload = SosRemote.recordPayload(
                rec.bleId, rec.name, rec.role, rec.trigger, rec.beacon, rec.beaconRssi, uid, ServerValue.TIMESTAMP, rec.ep
            )
            SosRemote.create(path, key, payload) { ok -> handler.post { done(ok) } }
        }

        override fun resolve(path: String, key: String, done: (Boolean) -> Unit) {
            SosRemote.resolve(path, key) { ok -> handler.post { done(ok) } }
        }

        override fun read(path: String, key: String, done: (SosLedger.Remote) -> Unit) {
            SosRemote.read(path, key) { ok, rec ->
                val mine = SosRemote.currentUid()
                val r = when {
                    !ok -> SosLedger.Remote.ERROR
                    rec == null -> SosLedger.Remote.ABSENT
                    mine == null || rec.uid != mine -> SosLedger.Remote.OTHER
                    rec.active -> SosLedger.Remote.MINE_ACTIVE
                    else -> SosLedger.Remote.MINE_RESOLVED
                }
                handler.post { done(r) }
            }
        }
    }

    private val ledger by lazy { SosLedger(kv, transport, SystemClock::elapsedRealtime) { onChange() } }

    private var remover: (() -> Unit)? = null
    private var listenPath = ""
    private var generation = 0

    // ── 내 SOS 영속·전송 (원장에 위임) ─────────────────────────

    /** 시작 시 저장 상태를 읽는다. 해제되지 않은 SOS 가 있으면 그 트리거, 없으면 null. 대기 중인 해제는 다음 tick 이 보낸다. */
    fun restoredTrigger(): String? = ledger.restoredTrigger()

    /** SOS 진입. 이미 저장된 SOS(복원분)가 있으면 그대로 두어 두 번째 기록을 만들지 않는다. */
    fun begin(
        bleId: String, name: String, role: String, trigger: String,
        beacon: String?, beaconRssi: Int?, beaconSid: Int
    ) = ledger.begin(SosLedger.Record(bleId, name, role, trigger, beacon, beaconRssi, beaconSid))

    /** 서버 전송 상태 문구. 내 SOS 가 없으면 null. */
    fun statusText(): String? = ledger.statusText()

    /** 해제([괜찮아요]에서만 호출). 활성 칸은 바로 비고 해제는 확인될 때까지 재시도된다. */
    fun resolve() = ledger.resolve()

    /** 해제가 서버에 닿지 못해 다시 보내는 중인가. */
    fun resolveFailing(): Boolean = ledger.resolveFailing()

    /** 내 SOS 의 에피소드 번호(없으면 0)와 비콘 짧은 ID(없으면 0). BLE 광고 확장에 쓴다. */
    fun episode(): Int = ledger.episode()
    fun hint(): Int = ledger.hint()

    // ── 동료 수신 ─────────────────────────────────────────────

    /**
     * 10초마다 부른다. 사업장 코드·루트가 바뀌었으면 새 노드로 다시 붙이고,
     * 붙어 있지 않으면(취소되었거나 로그인 전) 붙인다. 전송·해제 재시도도 여기서 한다.
     */
    fun tick() {
        attachIfNeeded()
        ledger.tick()
    }

    private fun attachIfNeeded() {
        val site = DevSettings.siteCode
        val path = if (site.isEmpty()) "" else SosRemote.nodePath(DevSettings.firebaseRoot, site)
        if (remover != null && path != listenPath) detach()
        if (remover != null || path.isEmpty()) return
        if (SosRemote.currentUid() == null) {
            FirebaseConfig.ensureSignedIn()
            return
        }
        val gen = ++generation
        listenPath = path
        remover = SosRemote.listen(
            path,
            { rec ->
                handler.post {
                    // 내 기록(역할 전환 전 bleId 포함)은 건너뛴다
                    val mine = SosRemote.currentUid()
                    if (gen == generation && !(rec.uid.isNotEmpty() && rec.uid == mine)) onPeer(rec)
                }
            },
            // 취소돼도 서버 시각 리스너는 남아 있으므로 떼어 낸 뒤 비운다
            { handler.post { if (gen == generation) { remover?.invoke(); remover = null; listenPath = "" } } }
        )
    }

    private fun detach() {
        generation++
        remover?.invoke()
        remover = null
        listenPath = ""
    }

    /** 수신만 끊는다. 저장된 내 SOS 와 대기 중인 해제는 남긴다(R3). */
    fun stopListening() = detach()
}
