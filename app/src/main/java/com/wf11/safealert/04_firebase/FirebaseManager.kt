package com.wf11.safealert.firebase

import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import com.wf11.safealert.utils.DevSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object FirebaseManager {

    private const val TAG = "FirebaseManager"
    private val db get() = FirebaseDatabase.getInstance().reference.child(DevSettings.firebaseRoot)

    fun saveAlert(deviceId: String, walkerId: String, rssi: Int, level: String) {
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val alertId = UUID.randomUUID().toString()
        val data = mapOf(
            "timestamp" to System.currentTimeMillis(),
            "deviceId" to deviceId,
            "walkerId" to walkerId,
            "rssi" to rssi,
            "alertLevel" to level
        )
        db.child("alerts").child(today).child(alertId).setValue(data)
            .addOnFailureListener { Log.e(TAG, "경보 저장 실패: ${it.message}") }
        Log.d(TAG, "경보 저장: $level $deviceId rssi=$rssi")
    }

    // ── 기기 간 비콘 공유 (이름붙은 세트) ───────────────────────
    //   같은 root(firebaseRoot) 아래 beacon_share/<key> 에 선택분을 업로드,
    //   다른 기기가 목록에서 골라 내려받아 병합한다. (v1.1.17)

    data class BeaconSetMeta(
        val key: String,        // Firebase 키(정규화됨)
        val name: String,       // 표시 이름(사용자 입력 원본)
        val count: Int,
        val sender: String,
        val timestamp: Long
    )

    // Firebase 키 금지문자 제거 ( . # $ [ ] / ) — (v1.1.55) 에코보정 업로드 키 생성에도 쓰여 public 전환
    fun sanitizeKey(s: String): String =
        s.trim().replace(Regex("[.#$\\[\\]/]"), "_").ifEmpty { "set" }

    /** 선택한 비콘 프로파일(JSON)을 이름붙은 세트로 업로드 */
    fun uploadBeaconSet(setName: String, profilesJson: String, count: Int, sender: String, onResult: (Boolean) -> Unit) {
        val key = sanitizeKey(setName)
        val data = mapOf(
            "name"         to setName.trim().ifEmpty { key },
            "profilesJson" to profilesJson,
            "count"        to count,
            "sender"       to sender,
            "timestamp"    to System.currentTimeMillis()
        )
        db.child("beacon_share").child(key).setValue(data)
            .addOnSuccessListener { Log.d(TAG, "비콘 세트 업로드: $key (${count}개)"); onResult(true) }
            .addOnFailureListener { Log.e(TAG, "비콘 세트 업로드 실패: ${it.message}"); onResult(false) }
    }

    /** 업로드된 이름붙은 세트 목록 조회 (최신순) */
    fun listBeaconSets(onResult: (List<BeaconSetMeta>) -> Unit) {
        db.child("beacon_share").get()
            .addOnSuccessListener { snap ->
                val sets = snap.children.mapNotNull { c ->
                    val key = c.key ?: return@mapNotNull null
                    BeaconSetMeta(
                        key       = key,
                        name      = c.child("name").getValue(String::class.java) ?: key,
                        count     = (c.child("count").getValue(Long::class.java) ?: 0L).toInt(),
                        sender    = c.child("sender").getValue(String::class.java) ?: "",
                        timestamp = c.child("timestamp").getValue(Long::class.java) ?: 0L
                    )
                }.sortedByDescending { it.timestamp }
                onResult(sets)
            }
            .addOnFailureListener { Log.e(TAG, "비콘 세트 목록 조회 실패: ${it.message}"); onResult(emptyList()) }
    }

    /** 특정 세트의 프로파일 JSON 다운로드 (key = BeaconSetMeta.key) */
    fun downloadBeaconSet(key: String, onResult: (String?) -> Unit) {
        db.child("beacon_share").child(key).child("profilesJson").get()
            .addOnSuccessListener { onResult(it.getValue(String::class.java)) }
            .addOnFailureListener { Log.e(TAG, "비콘 세트 다운로드 실패: ${it.message}"); onResult(null) }
    }

    /** 업로드된 세트를 클라우드에서 삭제 (관리용) */
    fun deleteBeaconSet(key: String, onResult: (Boolean) -> Unit) {
        db.child("beacon_share").child(key).removeValue()
            .addOnSuccessListener { onResult(true) }
            .addOnFailureListener { Log.e(TAG, "비콘 세트 삭제 실패: ${it.message}"); onResult(false) }
    }

    // ── (v1.1.55) 에코편차 자동보정 프라이어 공유 ───────────────────────
    //   각 기기가 자기 히스토그램 요약(상대기기별 중앙값·n·산포)을 echo_calib/<내ID> 에 업로드하고,
    //   전 노드를 내려받아 '모델쌍' 단위로 집계한다 — 신규 기기가 로컬 표본을 채우기 전(n<게이트)
    //   같은 모델쌍의 집계 중앙값으로 보정을 부트스트랩하기 위한 것(로컬 성립 시 로컬 우선).

    data class EchoPeerStat(val m: Double, val n: Int, val iqr: Double)   // 중앙값dB · 에코틱 · 산포(±IQR/2)
    data class EchoCalibNode(val id: String, val model: String, val peers: Map<String, EchoPeerStat>)

    /** 내 노드 전체 덮어쓰기 업로드 — peers 키는 sanitize 된 상대 기기ID, 값=(중앙값, n, 산포). */
    fun uploadEchoCalib(myId: String, model: String, peers: Map<String, Triple<Double, Int, Double>>, onResult: (Boolean) -> Unit) {
        val data = mapOf(
            "model" to model,
            "ts"    to System.currentTimeMillis(),
            "peers" to peers.mapValues { (_, v) -> mapOf("m" to v.first, "n" to v.second, "iqr" to v.third) }
        )
        db.child("echo_calib").child(sanitizeKey(myId)).setValue(data)
            .addOnSuccessListener { Log.d(TAG, "에코보정 업로드: $myId (피어 ${peers.size})"); onResult(true) }
            .addOnFailureListener { Log.e(TAG, "에코보정 업로드 실패: ${it.message}"); onResult(false) }
    }

    /** 전 노드 다운로드 — 실패·부재 시 빈 리스트(호출부는 캐시 유지). */
    fun downloadEchoCalibAll(onResult: (List<EchoCalibNode>) -> Unit) {
        db.child("echo_calib").get()
            .addOnSuccessListener { snap ->
                val nodes = snap.children.mapNotNull { c ->
                    val id = c.key ?: return@mapNotNull null
                    val model = c.child("model").getValue(String::class.java) ?: return@mapNotNull null
                    val peers = c.child("peers").children.mapNotNull { pc ->
                        val k = pc.key ?: return@mapNotNull null
                        val m = pc.child("m").getValue(Double::class.java) ?: return@mapNotNull null
                        val n = (pc.child("n").getValue(Long::class.java) ?: 0L).toInt()
                        val iqr = pc.child("iqr").getValue(Double::class.java) ?: 0.0
                        k to EchoPeerStat(m, n, iqr)
                    }.toMap()
                    EchoCalibNode(id, model, peers)
                }
                onResult(nodes)
            }
            .addOnFailureListener { Log.e(TAG, "에코보정 노드 조회 실패: ${it.message}"); onResult(emptyList()) }
    }

    /** 순수 집계: 방향성 모델쌍(내모델→상대모델) 프라이어 — 상대모델 → (fold 중앙값 dB, Σn).
     *  fold 규칙: 내 모델 노드가 상대모델을 잰 표본은 +m, 상대모델 노드가 내 모델을 잰 표본은 −m
     *  (편차는 반대칭: A가 본 A−B = −(B가 본 B−A)). n 가중 평균. 동일 모델쌍(M×M)은 양방향이
     *  자연히 ±상쇄돼 0 근방으로 수렴한다(대칭 하드웨어의 기대값). per-sample 산포 게이트로
     *  노이즈 표본(iqr>maxIqrDb) 제외, 모델 미상 피어(자기 노드 없음) 제외. Σn 유효성은 호출부가 판단. */
    fun aggregateEchoPriors(nodes: List<EchoCalibNode>, myModel: String, maxIqrDb: Double): Map<String, Pair<Double, Int>> {
        val modelById = nodes.associate { it.id to it.model }
        val sum = mutableMapOf<String, Double>()
        val cnt = mutableMapOf<String, Int>()
        for (node in nodes) for ((peerId, st) in node.peers) {
            val peerModel = modelById[peerId] ?: continue
            if (st.n <= 0 || st.iqr > maxIqrDb) continue
            when {
                node.model == myModel -> {   // 직접: 내 모델이 상대모델을 잰 중앙값(+)
                    sum[peerModel] = (sum[peerModel] ?: 0.0) + st.m * st.n
                    cnt[peerModel] = (cnt[peerModel] ?: 0) + st.n
                }
                peerModel == myModel -> {    // 역방향: 상대모델 노드가 내 모델을 잰 중앙값(−로 fold)
                    sum[node.model] = (sum[node.model] ?: 0.0) - st.m * st.n
                    cnt[node.model] = (cnt[node.model] ?: 0) + st.n
                }
            }
        }
        return cnt.mapValues { (k, n) -> Pair((sum[k] ?: 0.0) / n, n) }
    }

}
