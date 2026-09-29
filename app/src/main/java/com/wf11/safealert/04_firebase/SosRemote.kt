package com.wf11.safealert.firebase

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.Query
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import java.util.UUID

/**
 * 단독 작업자 구조 요청(SOS)의 RTDB 전송부 (v1.1.99).
 *
 * 경로는 {firebaseRoot}/sos/{siteCode}/{pushKey}. 규칙(database.rules.json)이 작성자 uid 소유 기록만
 * 생성하고 작성자만 active→resolved 로 바꿀 수 있게 막는다. FCM·Functions 없이 RTDB 리스너로만 전달한다.
 * 프로퍼티 초기화에서 Firebase·안드로이드를 건드리지 않으므로 JVM 단위 테스트에서 불러올 수 있다.
 */
object SosRemote {
    private const val TAG = "SosRemote"

    /** 구조 요청 기록 한 건. bleId 는 광고 fullId 그대로라 BLE 수신 키와 병합된다. */
    data class SosRecord(
        val key: String,
        val bleId: String,
        val name: String,
        val role: String,
        val trigger: String,      // "still" | "fall"
        val beacon: String,       // 최근 최강 비콘 라벨(없으면 "")
        val beaconRssi: Int?,
        val createdAt: Long,
        val active: Boolean,
        val uid: String,          // 작성자 uid(없으면 ""). 내 기록 걸러내기에 쓴다
        val ep: Int = 0           // SOS 회차 1..255(0 = 없음). BLE 광고 회차와 같은 값이다
    )

    private const val SOS_STR_MAX = 64
    private const val SOS_ROLE_MAX = 32

    /** 규칙(database.rules.json)의 beaconRssi 허용 범위와 같다. 벗어나면 기록 전체가 거부되므로 필드를 뺀다. */
    const val BEACON_RSSI_MIN = -150
    const val BEACON_RSSI_MAX = 20

    /** 수신 재생 창: 시작 전 30분 이내에 만들어진 기록까지 받는다 (R1). */
    const val REPLAY_WINDOW_MS = 30 * 60_000L

    /** 스냅샷 값(Map) → SosRecord. 경계 입력이라 타입·길이가 어긋나면 null(무시). */
    fun parseSosRecord(key: String, v: Any?): SosRecord? {
        val m = v as? Map<*, *> ?: return null
        val bleId = (m["bleId"] as? String)?.takeIf { it.isNotEmpty() && it.length <= SOS_STR_MAX } ?: return null
        val status = m["status"] as? String
        if (status != "active" && status != "resolved") return null
        val createdAt = (m["createdAt"] as? Number)?.toLong() ?: return null
        val trigger = (m["trigger"] as? String)?.takeIf { it == "still" || it == "fall" } ?: "still"
        return SosRecord(
            key = key,
            bleId = bleId,
            name = (m["name"] as? String).orEmpty().take(SOS_STR_MAX),
            role = (m["role"] as? String).orEmpty().take(SOS_STR_MAX),
            trigger = trigger,
            beacon = (m["beacon"] as? String).orEmpty().take(SOS_STR_MAX),
            beaconRssi = (m["beaconRssi"] as? Number)?.toInt(),
            createdAt = createdAt,
            active = status == "active",
            uid = (m["uid"] as? String).orEmpty().take(SOS_STR_MAX),
            ep = (m["ep"] as? Number)?.toInt()?.takeIf { it in 1..255 } ?: 0
        )
    }

    /** 기록 본문. beacon 은 비어 있지 않을 때만, beaconRssi 는 규칙 범위 안일 때만 넣는다. */
    fun recordPayload(
        bleId: String, name: String, role: String, trigger: String,
        beacon: String?, beaconRssi: Int?, uid: String, createdAt: Any, ep: Int = 0
    ): Map<String, Any> {
        val data = HashMap<String, Any>()
        data["bleId"] = bleId.take(SOS_STR_MAX)
        data["name"] = name.take(SOS_STR_MAX)
        data["role"] = role.take(SOS_ROLE_MAX)
        data["trigger"] = trigger
        data["createdAt"] = createdAt
        data["status"] = "active"
        data["uid"] = uid
        if (!beacon.isNullOrEmpty()) data["beacon"] = beacon.take(SOS_STR_MAX)
        if (beaconRssi != null && beaconRssi in BEACON_RSSI_MIN..BEACON_RSSI_MAX) data["beaconRssi"] = beaconRssi
        if (ep in 1..255) data["ep"] = ep
        return data
    }

    /** 수신 조회 시작점: 호출 시각(서버 시각 환산) 30분 전. */
    fun replayStartAt(t0WallMs: Long, serverOffsetMs: Long): Long = t0WallMs + serverOffsetMs - REPLAY_WINDOW_MS

    fun nodePath(root: String, site: String): String = "$root/sos/$site"

    fun currentUid(): String? = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()

    /** 지정 경로 아래 새 푸시 키를 로컬에서 만든다(서버 왕복 없음). */
    fun newKey(path: String): String =
        FirebaseDatabase.getInstance().reference.child(path).push().key
            ?: UUID.randomUUID().toString().replace("-", "").take(20)

    /** 기록 생성. createdAt 은 서버 시각. 결과(성공 여부)는 onResult 로 알린다. 오프라인이면 응답이 올 때까지 미완료. */
    fun create(path: String, key: String, payload: Map<String, Any>, onResult: (Boolean) -> Unit) {
        FirebaseDatabase.getInstance().reference.child(path).child(key).setValue(payload)
            .addOnCompleteListener {
                if (it.isSuccessful) Log.d(TAG, "구조 요청 기록: $key")
                else Log.e(TAG, "구조 요청 기록 실패: ${it.exception?.message}")
                onResult(it.isSuccessful)
            }
    }

    /** 구조 요청 해제 — status=resolved + resolvedAt(서버 시각). 저장해 둔 경로에 쓰며 현재 사업장 코드로 다시 만들지 않는다. */
    fun resolve(path: String, key: String, onDone: (Boolean) -> Unit) {
        FirebaseDatabase.getInstance().reference.child(path).child(key)
            .updateChildren(mapOf("status" to "resolved", "resolvedAt" to ServerValue.TIMESTAMP))
            .addOnCompleteListener {
                if (it.isSuccessful) Log.d(TAG, "구조 요청 해제 기록: $key")
                else Log.e(TAG, "구조 요청 해제 기록 실패: ${it.exception?.message}")
                onDone(it.isSuccessful)
            }
    }

    /**
     * 기록 한 건 조회(서버 우선, 닿지 않을 때만 캐시). 쓰기가 실패한 뒤 서버에 이미 내 기록이 있는지 확인하는 데 쓴다.
     * 성공이면 (true, 기록 또는 없음/해석 불가 시 null), 조회 실패면 (false, null).
     */
    fun read(path: String, key: String, onResult: (Boolean, SosRecord?) -> Unit) {
        FirebaseDatabase.getInstance().reference.child(path).child(key).get()
            .addOnCompleteListener {
                if (it.isSuccessful) onResult(true, parseSosRecord(key, it.result?.value))
                else {
                    Log.e(TAG, "구조 요청 조회 실패: ${it.exception?.message}")
                    onResult(false, null)
                }
            }
    }

    /**
     * 구조 요청 실시간 수신 (R1). 이 함수를 부른 시각 t0 를 잡아 두고 서버 시각 오프셋을 한 번 읽은 뒤
     * (t0 + 오프셋 - 30분) 이후 생성분을 조회한다. 그래서 진행 중(active) 기록은 30분 이내면 재생되어 울리고,
     * 그보다 오래된 기록은 조회되지 않으며, 해제된 기록은 알림 대상이 아니다.
     * 삭제된 기록은 해제로 전달한다. 취소(onCancelled)되면 조회를 버리고 onCancel 을 한 번 부른다.
     * 반환값은 해제 함수이며 오프셋 읽기가 끝나기 전에 불러도 안전하다.
     */
    fun listen(path: String, onRecord: (SosRecord) -> Unit, onCancel: () -> Unit): () -> Unit {
        val t0 = System.currentTimeMillis()
        val node = FirebaseDatabase.getInstance().reference.child(path)
        var stopped = false
        var attached: Pair<Query, ChildEventListener>? = null
        val handle = { s: DataSnapshot -> parseSosRecord(s.key.orEmpty(), s.value)?.let(onRecord) }
        val listener = object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, prev: String?) { handle(s) }
            override fun onChildChanged(s: DataSnapshot, prev: String?) { handle(s) }
            override fun onChildRemoved(s: DataSnapshot) {
                parseSosRecord(s.key.orEmpty(), s.value)?.let { onRecord(it.copy(active = false)) }
            }
            override fun onChildMoved(s: DataSnapshot, prev: String?) {}
            override fun onCancelled(e: DatabaseError) {
                Log.e(TAG, "구조 요청 수신 취소: ${e.message}")
                attached = null
                if (!stopped) onCancel()
            }
        }
        fun attach(offset: Long) {
            if (stopped) return
            val q = node.orderByChild("createdAt").startAt(replayStartAt(t0, offset).toDouble())
            q.addChildEventListener(listener)
            attached = q to listener
        }
        FirebaseDatabase.getInstance().getReference(".info/serverTimeOffset")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) { attach((s.value as? Number)?.toLong() ?: 0L) }
                override fun onCancelled(e: DatabaseError) { attach(0L) }
            })
        return {
            stopped = true
            attached?.let { (q, l) -> q.removeEventListener(l) }
            attached = null
        }
    }
}
