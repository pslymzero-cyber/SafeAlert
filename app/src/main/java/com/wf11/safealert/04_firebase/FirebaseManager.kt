package com.wf11.safealert.firebase

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import com.wf11.safealert.BuildConfig
import com.wf11.safealert.utils.BeaconRegistry
import com.wf11.safealert.utils.DevSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object FirebaseManager {

    private const val TAG = "FirebaseManager"
    private val db get() = FirebaseDatabase.getInstance().reference.child(DevSettings.firebaseRoot)

    /**
     * (v1.1.77) 사업장별 노드 — siteNode 로 경로를 사업장 단위로 가르는 것은 경보 로그(alerts)뿐.
     * 에코보정(echo_calib)은 v1.1.85 부터 평면 전역 경로에 site 라벨만 남기고, 에코 로컬 통계
     * (CalibrationEngine)도 사업장과 무관한 전역이다.
     * 코드가 비면 구버전과 같은 경로를 그대로 쓴다(기존 데이터 접근 유지).
     */
    private fun siteNode(name: String) =
        DevSettings.siteCode.let { if (it.isEmpty()) db.child(name) else db.child(name).child(it) }

    // ── (v1.1.98) 작성 단말 uid — DB 규칙이 경보 기록·비콘 공유·에코 보정 쓰기에 uid(=auth.uid)를 요구한다 ──
    private fun currentUid(): String? = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()

    private var pending: PendingAlerts? = null

    /** 앱 시작 시 1회(SafeAlertApp) — 보류 기록 저장소를 열고, 로그인되면(이미 돼 있어도) 보류분을 보낸다. */
    fun init(context: Context) {
        pending = PendingAlerts(context.getSharedPreferences("pending_alerts", Context.MODE_PRIVATE))
        try {
            FirebaseAuth.getInstance().addAuthStateListener { auth ->
                auth.currentUser?.uid?.let { flushPendingAlerts(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "로그인 상태 구독 실패: ${e.message}")
        }
    }

    private fun flushPendingAlerts(uid: String) {
        val items = pending?.drain().orEmpty()
        if (items.isEmpty()) return
        val database = FirebaseDatabase.getInstance()
        for ((url, data) in items) {
            runCatching { database.getReferenceFromUrl(url) }
                .onSuccess { ref ->
                    ref.setValue(data + ("uid" to uid))
                        .addOnFailureListener { Log.e(TAG, "보류 경보 저장 실패: ${it.message}") }
                }
                .onFailure { Log.e(TAG, "보류 경보 주소 오류: ${it.message}") }
        }
        Log.d(TAG, "보류 경보 ${items.size}건 저장")
    }

    // 역할(myRole/peerRole)은 03_service 에서 이름으로 변환해 넘긴다 — 04_firebase 는
    //   02_ble 에 의존하지 않는다(레이어 규칙). 기본값이 있어 기존 호출은 그대로 컴파일된다.
    fun saveAlert(deviceId: String, walkerId: String, rssi: Int, level: String,
                  myRole: String = "UNKNOWN", peerRole: String = "UNKNOWN") {
        val logId = BeaconRegistry.shortFullId(deviceId)   // (v1.1.91) 비콘 UUID 키는 8자만 저장(v1.1.90 과 동일 항목)
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val alertId = UUID.randomUUID().toString()
        val data = mapOf(
            "timestamp" to System.currentTimeMillis(),
            "deviceId" to withSite(logId),   // (v1.1.90 SA-1) 센터명-장비ID (예: WF11-CB-01)
            "walkerId" to withSite(walkerId),
            // (v1.1.97) 저장 규칙 범위(−150~20) 밖의 값은 0 = 값 없음(UWB 경로가 RSSI 없을 때 쓰는 표기)
            "rssi" to (rssi.takeIf { it in -150..20 } ?: 0),
            "alertLevel" to level,
            "myRole" to myRole,
            "peerRole" to peerRole,
            "site" to DevSettings.siteCode
        )
        val ref = siteNode("alerts").child(today).child(alertId)
        val uid = currentUid()
        if (uid == null) {
            // (v1.1.98) 로그인 전 — 날짜·사업장 자리를 지금 기준으로 정해 보류했다가 로그인되면 보낸다
            pending?.add(ref.toString(), data) ?: Log.w(TAG, "보류 저장소 없음 — 경보 기록 생략")
            Log.d(TAG, "경보 보류(로그인 전): $level ${withSite(logId)}")
            return
        }
        ref.setValue(data + ("uid" to uid))
            .addOnFailureListener { Log.e(TAG, "경보 저장 실패: ${it.message}") }
        Log.d(TAG, "경보 저장: $level ${withSite(logId)} rssi=$rssi")
    }

    // ── (v1.1.76) UWB 실측 표본 — 성능 사양의 물리 거리 근거 ─────────────
    //   UWB 가 잰 실거리(m)와 같은 프레임의 BLE RSSI 를 한 건으로 남긴다. 이 둘이 있어야
    //   "경고 -78dBm / 위험 -65dBm 이 실제로 몇 m 인가" 를 역산할 수 있다. 학습값(Δ)은
    //   기기 안에만 있어 반출되지 않으므로, 집계용으로는 이 원표본이 필요하다.
    //   개발자 설정 스위치(DevSettings.uwbProbeUploadEnabled)가 켜진 동안에만 호출된다 —
    //   상시 수집이 아니라 실기 측정 세션용이라 기본은 꺼져 있다.
    fun saveUwbProbe(myId: String, model: String, site: String,
                     pairKey: String, distM: Float, rssi: Int) {
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val data = mapOf(
            "timestamp" to System.currentTimeMillis(),
            "walkerId"  to myId,
            "model"     to model,
            "site"      to site,
            "pairKey"   to pairKey,
            "distM"     to distM,
            "rssi"      to rssi
        )
        db.child("uwb_probe").child(today).child(UUID.randomUUID().toString()).setValue(data)
            .addOnFailureListener { Log.e(TAG, "UWB 표본 저장 실패: ${it.message}") }
    }

    // ── 기기 간 비콘 공유 (이름붙은 세트) ───────────────────────
    //   같은 root(firebaseRoot) 아래 beacon_share/<siteCode>/<key> 에 선택분을 업로드,
    //   같은 사업장 기기가 목록에서 골라 내려받아 병합한다. (v1.1.17)
    //   사업장 코드가 비면 평면 경로로 폴백하지 않고 실패로 반환한다(규칙이 $sc 하위 쓰기만 허용).

    /** 내 사업장 공유 노드. 사업장 코드 미설정이면 null */
    private fun beaconShareNode() =
        DevSettings.siteCode.takeIf { it.isNotEmpty() }?.let { db.child("beacon_share").child(it) }

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

    // (v1.1.87) 표시 이름 = BLE 송출 ID 상한. UTF-8 15바이트 = 한글 5자·영문 15자 (BleAdvertiser 절단 폭과 동일)
    const val DEVICE_ID_MAX_BYTES = 15

    /**
     * (v1.1.90 SA-1) 표시 이름 = PIT 장비 ID. `종류코드-번호` 두 토큰이다 — `CB-01`, `RT-07`.
     *
     * 자유 입력을 없애고 선택식(종류 드롭다운 + 번호 드롭다운)으로 바꿨으므로,
     * 사람 이름·닉네임이 들어올 경로가 구조적으로 존재하지 않는다. 이 검증은 구버전이
     * 남긴 값과 외부에서 들어온 값을 거르는 2차 방어선이다.
     *
     * 종류코드가 실제 등록된 장비인지는 여기서 보지 않는다 — 04_firebase 는 01_model 에
     * 의존하지 않는다(레이어 규칙). 코드 유효성은 선택 UI 의 PitType.parse 가 판정한다.
     *
     * 5바이트 고정이라 BLE 송출 상한(15바이트) 대비 10바이트가 남는다. 센터명은 싣지 않는다.
     */
    val PIT_ID_REGEX = Regex("^[A-Z]{2}-[0-9]{2}$")

    /** (v1.1.90) 입력 안내 문구 — UI 힌트·마이그레이션 안내가 같은 문장을 쓴다 */
    const val PIT_ID_HINT = "장비 종류와 번호를 선택하세요 (예: CB-01)"

    /** (v1.1.90) 입력 정규화 — 사업장 코드와 같은 규칙: 앞뒤 공백 제거 후 대문자화 */
    fun normalizeDeviceId(s: String): String = s.trim().uppercase(Locale.ROOT)

    /** (v1.1.90 SA-1) 표시 이름 검증 — 빈 값은 허용(자동 ID 사용), 그 외는 장비 ID 형식만 허용. */
    fun isValidDeviceId(s: String): Boolean {
        val t = normalizeDeviceId(s)
        if (t.isEmpty()) return true
        return PIT_ID_REGEX.matches(t)
    }

    /**
     * (v1.1.90) 자동 발급 ID 형식 — MainActivity.newAutoId() 생성규칙("SA-" + UUID 8자 대문자).
     * 보행자처럼 장비 ID 가 없는 기기가 쓴다.
     */
    val AUTO_ID_REGEX = Regex("^SA-[0-9A-F]{8}$")

    /**
     * (v1.1.90 SA-1) 송출 ID 로 그대로 써도 되는 값인가 — 장비 ID 또는 자동 발급 ID(빈 값은 불가).
     * 구버전이 device_id 에 써 넣은 사람 이름을 걸러내는 데 쓴다.
     */
    fun isUsableAdvertisedId(s: String): Boolean {
        val t = normalizeDeviceId(s)
        if (t.isEmpty()) return false
        return AUTO_ID_REGEX.matches(t) || PIT_ID_REGEX.matches(t)
    }

    /**
     * (v1.1.90 SA-1) 경보 로그용 전체 식별자 — `센터명-장비ID`. `WF11-CB-01` 로 남는다.
     *
     * BLE 에는 센터명을 싣지 않는다(예산·중복). 대신 저장 시점에 붙인다. BLE 로 만난
     * 상대는 물리적으로 같은 센터 안에 있으므로 내 센터 코드를 그대로 적용한다 —
     * 경로(`alerts/{site}/...`)와 `site` 필드가 이미 같은 전제 위에 서 있다.
     */
    fun withSite(id: String): String {
        val site = DevSettings.siteCode
        return if (site.isEmpty() || id.isEmpty()) id else "$site-$id"
    }

    /** (v1.1.87) s 의 앞에서부터 UTF-8 maxBytes 안에 드는 문자 수(서로게이트 쌍은 쪼개지 않음). 입력 필터용 */
    fun utf8PrefixLen(s: CharSequence, maxBytes: Int): Int {
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val cp = Character.codePointAt(s, i)
            val n = when { cp < 0x80 -> 1; cp < 0x800 -> 2; cp < 0x10000 -> 3; else -> 4 }
            if (bytes + n > maxBytes) break
            bytes += n
            i += Character.charCount(cp)
        }
        return i
    }

    /** 선택한 비콘 프로파일(JSON)을 이름붙은 세트로 업로드 */
    fun uploadBeaconSet(setName: String, profilesJson: String, count: Int, sender: String, onResult: (Boolean) -> Unit) {
        val node = beaconShareNode() ?: return onResult(false)
        // (v1.1.98) 로그인 전이면 실패로 알리고 로그인을 다시 시도한다(규칙이 uid 를 요구)
        val uid = currentUid() ?: run { FirebaseConfig.ensureSignedIn(); return onResult(false) }
        val key = sanitizeKey(setName)
        val data = mapOf(
            "name"         to setName.trim().ifEmpty { key },
            "profilesJson" to profilesJson,
            "count"        to count,
            "sender"       to sender,
            "timestamp"    to System.currentTimeMillis(),
            "uid"          to uid
        )
        node.child(key).setValue(data)
            .addOnSuccessListener { Log.d(TAG, "비콘 세트 업로드: $key (${count}개)"); onResult(true) }
            .addOnFailureListener { Log.e(TAG, "비콘 세트 업로드 실패: ${it.message}"); onResult(false) }
    }

    /** 업로드된 이름붙은 세트 목록 조회 (최신순) */
    fun listBeaconSets(onResult: (List<BeaconSetMeta>) -> Unit) {
        val node = beaconShareNode() ?: return onResult(emptyList())
        node.get()
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
        val node = beaconShareNode() ?: return onResult(null)
        node.child(key).child("profilesJson").get()
            .addOnSuccessListener { onResult(it.getValue(String::class.java)) }
            .addOnFailureListener { Log.e(TAG, "비콘 세트 다운로드 실패: ${it.message}"); onResult(null) }
    }

    /** 업로드된 세트를 클라우드에서 삭제 (관리용) */
    fun deleteBeaconSet(key: String, onResult: (Boolean) -> Unit) {
        val node = beaconShareNode() ?: return onResult(false)
        node.child(key).removeValue()
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
        // (v1.1.98) 로그인 전이면 이번 회차는 건너뛴다(규칙이 uid 를 요구, 다음 주기에 다시 올린다)
        val uid = currentUid() ?: return onResult(false)
        val data = mapOf(
            "model" to model,
            // (v1.1.84) 앱 버전 — echo_calib 은 1시간마다 전체 덮어쓰기라 항상 현재값이다.
            //   서버에서 구버전 잔존 기기를 한눈에 식별하는 용도(규칙 잠금 롤아웃 검증).
            "ver"   to BuildConfig.VERSION_NAME,
            "ts"    to System.currentTimeMillis(),
            // (v1.1.85) 사업장 코드는 경로가 아니라 라벨로만 남긴다. saveAlert 는 경로(siteNode)도
            //   사업장별이고 라벨도 남기지만, 이 에코 업로드는 경로가 평면이고 라벨만 남는다.
            "site"  to DevSettings.siteCode,
            "peers" to peers.mapValues { (_, v) -> mapOf("m" to v.first, "n" to v.second, "iqr" to v.third) },
            "uid"   to uid
        )
        db.child("echo_calib").child(sanitizeKey(myId)).setValue(data)
            .addOnSuccessListener { Log.d(TAG, "에코보정 업로드: $myId (피어 ${peers.size})"); onResult(true) }
            .addOnFailureListener { Log.e(TAG, "에코보정 업로드 실패: ${it.message}"); onResult(false) }
    }

    /** 전 노드 다운로드 — 실패·부재 시 빈 리스트(호출부는 캐시 유지). */
    fun downloadEchoCalibAll(onResult: (List<EchoCalibNode>) -> Unit) {
        db.child("echo_calib").get()
            .addOnSuccessListener { snap ->
                // (v1.1.85) model 이 없는 자식은 구버전이 쓴 echo_calib/<사업장>/<기기ID> 의
                //   사업장 세그먼트다 — 한 단계 내려가 손자를 기기 노드로 읽는다(롤아웃 중 흡수).
                val current = mutableListOf<EchoCalibNode>()
                val legacy = mutableListOf<EchoCalibNode>()
                for (c in snap.children) {
                    val node = parseEchoNode(c)
                    if (node != null) current += node else c.children.mapNotNullTo(legacy, ::parseEchoNode)
                }
                onResult(mergeEchoNodes(legacy, current))
            }
            .addOnFailureListener { Log.e(TAG, "에코보정 노드 조회 실패: ${it.message}"); onResult(emptyList()) }
    }

    /** (v1.1.86) 구·신 경로 혼재 흡수: 같은 기기ID 는 한 번만 남기고 current(신 경로)가 이긴다.
     *  구 경로 echo_calib/<사업장>/<기기ID> 는 업그레이드해도 삭제되지 않고 잔존하므로, 걸러내지
     *  않으면 같은 기기 표본이 두 번 세어져 Σn 이 배가 되고(신뢰도 과대), 옛 중앙값이 현재값과
     *  n 가중 평균돼 영구히 절반 지분을 갖는다. */
    fun mergeEchoNodes(legacy: List<EchoCalibNode>, current: List<EchoCalibNode>): List<EchoCalibNode> =
        (legacy + current).associateBy { it.id }.values.toList()

    private fun parseEchoNode(c: DataSnapshot): EchoCalibNode? {
        val id = c.key ?: return null
        val model = c.child("model").getValue(String::class.java) ?: return null
        val peers = c.child("peers").children.mapNotNull { pc ->
            val k = pc.key ?: return@mapNotNull null
            val m = pc.child("m").getValue(Double::class.java) ?: return@mapNotNull null
            val n = (pc.child("n").getValue(Long::class.java) ?: 0L).toInt()
            val iqr = pc.child("iqr").getValue(Double::class.java) ?: 0.0
            k to EchoPeerStat(m, n, iqr)
        }.toMap()
        return EchoCalibNode(id, model, peers)
    }

    /** 순수 집계: 방향성 모델쌍(내모델→상대모델) 프라이어 — 상대모델 → (fold 중앙값 dB, Σn).
     *  fold 규칙: 내 모델 노드가 상대모델을 잰 표본은 +m, 상대모델 노드가 내 모델을 잰 표본은 −m
     *  (편차는 반대칭: A가 본 A−B = −(B가 본 B−A)). 동일 모델쌍(M×M)은 양방향이
     *  자연히 ±상쇄돼 0 근방으로 수렴한다(대칭 하드웨어의 기대값). per-sample 산포 게이트로
     *  노이즈 표본(iqr>maxIqrDb) 제외, 모델 미상 피어(자기 노드 없음) 제외. Σn 유효성은 호출부가 판단.
     *  (v1.1.97) 표본 비중 min(n, capN) 의 가중 중앙값 — 동떨어진 표본 하나가 결과를 끌지 못한다.
     *  Σn 은 같은 비중의 합이라 capN 을 Σn 게이트(echoCalMinTicks)와 같게 주면 게이트 통과 여부는
     *  상한이 없을 때와 같다. */
    fun aggregateEchoPriors(nodes: List<EchoCalibNode>, myModel: String, maxIqrDb: Double, capN: Int): Map<String, Pair<Double, Int>> {
        val modelById = nodes.associate { it.id to it.model }
        val samples = mutableMapOf<String, MutableList<Pair<Double, Int>>>()   // 상대모델 → (fold 값, 비중)
        for (node in nodes) for ((peerId, st) in node.peers) {
            val peerModel = modelById[peerId] ?: continue
            if (st.n <= 0 || st.iqr > maxIqrDb) continue
            val w = minOf(st.n, capN)
            when {
                node.model == myModel ->     // 직접: 내 모델이 상대모델을 잰 중앙값(+)
                    samples.getOrPut(peerModel) { mutableListOf() } += st.m to w
                peerModel == myModel ->      // 역방향: 상대모델 노드가 내 모델을 잰 중앙값(−로 fold)
                    samples.getOrPut(node.model) { mutableListOf() } += -st.m to w
            }
        }
        return samples.mapValues { (_, s) -> weightedMedian(s) to s.sumOf { it.second } }
    }

    /** 가중 중앙값 — 누적 비중이 절반을 넘는 첫 값. 정확히 절반에서 끊기면 양쪽 값의 평균(표본 2개 = 평균). */
    private fun weightedMedian(samples: List<Pair<Double, Int>>): Double {
        val sorted = samples.sortedBy { it.first }
        val total = sorted.sumOf { it.second.toLong() }
        var acc = 0L
        for ((i, s) in sorted.withIndex()) {
            acc += s.second
            if (acc * 2 > total) return s.first
            if (acc * 2 == total) return (s.first + sorted[i + 1].first) / 2
        }
        return sorted.last().first
    }

}
