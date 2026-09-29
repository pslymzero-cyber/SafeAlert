package com.wf11.safealert.service

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import com.google.firebase.database.ServerValue
import com.wf11.safealert.firebase.FirebaseConfig
import com.wf11.safealert.firebase.SosRemote
import com.wf11.safealert.utils.DevSettings

/**
 * 단독 작업자 SOS 의 서버 쪽 일 전부 (v1.1.99): 내 SOS 영속 저장, 기록 생성·재전송, 해제, 동료 수신 재연결.
 *
 * 내 SOS 는 [괜찮음]으로만 끝나므로(R3) 서비스 종료·역할 전환·재시작·프로세스 사망 뒤에도 남아야 한다.
 * 그래서 서버 키·생성 당시 경로·시작 시각·트리거·bleId 를 SharedPreferences 에 두고, 다음 시작 때 같은 키로
 * 되살린다. 해제는 저장해 둔 경로에 쓴다(현재 사업장 코드로 경로를 다시 만들지 않는다).
 *
 * 생성자는 참조만 저장한다(SharedPreferences 는 첫 사용 때 연다). 모든 진입점은 메인 스레드에서 불린다.
 */
class LoneWorkerSosSync(
    private val ctx: Context,
    private val handler: Handler,
    private val onPeer: (SosRemote.SosRecord) -> Unit,
    private val onChange: () -> Unit
) {
    companion object {
        private const val FILE = "lone_worker_sos"
        private const val K_ACTIVE = "active"
        private const val K_KEY = "key"
        private const val K_PATH = "path"
        private const val K_BLE = "bleId"
        private const val K_NAME = "name"
        private const val K_ROLE = "role"
        private const val K_TRIGGER = "trigger"
        private const val K_BEACON = "beacon"
        private const val K_RSSI = "beaconRssi"
        private const val K_START = "startWallMs"
        private const val K_SENT = "sent"
        private const val K_RESOLVING = "resolving"
    }

    private val prefs: SharedPreferences by lazy { ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    private var inFlightKey: String? = null
    private var failed = false

    private var remover: (() -> Unit)? = null
    private var listenPath = ""
    private var generation = 0

    // ── 내 SOS 영속·전송 ───────────────────────────────────────

    /**
     * 시작 시 저장 상태를 읽는다. 해제 중이던 것은 해제를 다시 보내고 null,
     * 해제되지 않은 SOS 가 있으면 그 트리거, 없으면 null.
     */
    fun restoredTrigger(): String? {
        if (!prefs.getBoolean(K_ACTIVE, false)) return null
        if (prefs.getBoolean(K_RESOLVING, false)) {
            sendResolve()
            return null
        }
        return prefs.getString(K_TRIGGER, null) ?: "still"
    }

    /** SOS 진입. 이미 저장된 SOS(복원분)가 있으면 그대로 두어 두 번째 기록을 만들지 않는다. */
    fun begin(bleId: String, name: String, role: String, trigger: String, beacon: String?, beaconRssi: Int?) {
        if (prefs.getBoolean(K_ACTIVE, false)) return
        val e = prefs.edit().clear()
            .putBoolean(K_ACTIVE, true)
            .putString(K_BLE, bleId).putString(K_NAME, name).putString(K_ROLE, role)
            .putString(K_TRIGGER, trigger)
            .putLong(K_START, System.currentTimeMillis())
        if (beacon != null) e.putString(K_BEACON, beacon)
        if (beaconRssi != null) e.putInt(K_RSSI, beaconRssi)
        e.commit()
        failed = false
        trySend()
    }

    /** 전송 시도. 키가 없으면 만들고, 실패한 기록은 같은 키·경로로 다시 쓴다(생성 전용 규칙과 충돌하지 않음). */
    private fun trySend() {
        val p = prefs
        if (!p.getBoolean(K_ACTIVE, false) || p.getBoolean(K_SENT, false) || p.getBoolean(K_RESOLVING, false)) return
        val uid = SosRemote.currentUid()
        var key = p.getString(K_KEY, null)
        var path = p.getString(K_PATH, null)
        if (key == null || path == null) {
            val site = DevSettings.siteCode
            if (site.isEmpty()) return
            if (uid == null) { FirebaseConfig.ensureSignedIn(); return }
            path = SosRemote.nodePath(DevSettings.firebaseRoot, site)
            key = SosRemote.newKey(path)
            p.edit().putString(K_PATH, path).putString(K_KEY, key).commit()
        }
        if (inFlightKey == key) return
        if (uid == null) { FirebaseConfig.ensureSignedIn(); return }
        val payload = SosRemote.recordPayload(
            p.getString(K_BLE, "").orEmpty(), p.getString(K_NAME, "").orEmpty(), p.getString(K_ROLE, "").orEmpty(),
            p.getString(K_TRIGGER, "still").orEmpty(), p.getString(K_BEACON, null),
            if (p.contains(K_RSSI)) p.getInt(K_RSSI, 0) else null, uid, ServerValue.TIMESTAMP
        )
        val sentKey = key
        inFlightKey = sentKey
        SosRemote.create(path, sentKey, payload) { ok ->
            handler.post {
                if (inFlightKey == sentKey) inFlightKey = null
                failed = !ok
                // 해제·교체로 저장 상태가 바뀐 뒤에 늦게 온 응답은 저장하지 않는다
                if (ok && prefs.getString(K_KEY, null) == sentKey) prefs.edit().putBoolean(K_SENT, true).commit()
                onChange()
            }
        }
        // ponytail: 서버 응답을 받고 sent 를 저장하기 전에 프로세스가 죽으면, 다시 쓰기는 생성 전용 규칙에 막혀
        // 동료에게는 기록이 있어도 실패로 보인다. 필요해지면 재시도 전에 기록 존재를 먼저 읽어 확인한다.
    }

    /** 서버 전송 상태 문구. 내 SOS 가 없으면 null. */
    fun statusText(): String? {
        if (!prefs.getBoolean(K_ACTIVE, false)) return null
        if (prefs.getBoolean(K_SENT, false)) return "서버 전송됨"
        if (inFlightKey != null && !failed) return "서버 전송 중"
        return "서버 전송 실패 — 재시도 중"
    }

    /** 해제([괜찮음]에서만 호출). 키를 받은 적 없으면 서버에 아무것도 없으므로 저장만 지운다. */
    fun resolve() {
        if (!prefs.getBoolean(K_ACTIVE, false)) return
        if (prefs.getString(K_KEY, null) == null) {
            clearStored()
            return
        }
        prefs.edit().putBoolean(K_RESOLVING, true).commit()
        sendResolve()
    }

    private fun sendResolve() {
        val key = prefs.getString(K_KEY, null)
        val path = prefs.getString(K_PATH, null)
        if (key == null || path == null) {
            clearStored()
            return
        }
        // 실패는 기록이 서버에 닿지 않았다는 뜻이다(오프라인이면 RTDB 가 생성 뒤에 순서대로 보낸다). 성공·실패 모두 저장을 지운다.
        SosRemote.resolve(path, key) {
            handler.post {
                if (prefs.getString(K_KEY, null) == key) clearStored()
                onChange()
            }
        }
    }

    private fun clearStored() {
        prefs.edit().clear().commit()
        failed = false
        inFlightKey = null
    }

    // ── 동료 수신 ─────────────────────────────────────────────

    /**
     * 10초마다 부른다. 사업장 코드·루트가 바뀌었으면 새 노드로 다시 붙이고,
     * 붙어 있지 않으면(취소되었거나 로그인 전) 붙인다. 전송 재시도도 여기서 한다.
     */
    fun tick() {
        attachIfNeeded()
        trySend()
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
            { handler.post { if (gen == generation) { remover = null; listenPath = "" } } }
        )
    }

    private fun detach() {
        generation++
        remover?.invoke()
        remover = null
        listenPath = ""
    }

    /** 수신만 끊는다. 저장된 내 SOS 는 남긴다(R3). */
    fun stopListening() = detach()
}
