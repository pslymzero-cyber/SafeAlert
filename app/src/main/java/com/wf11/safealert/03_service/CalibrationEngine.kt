package com.wf11.safealert.service

import android.content.Context
import android.os.Build
import android.util.Log
import com.wf11.safealert.ble.BleConstants
import com.wf11.safealert.firebase.FirebaseManager
import com.wf11.safealert.utils.DevSettings

/**
 * [REFACTOR-03] 에코 RSSI 보정 계층 — BleService 에서 분리한 단일 소유자.
 *   본문은 BleService 원본 그대로(수신자만 조정). 판정 로직·상수 값 변경 없음.
 *   Context 의존은 init(context) 로 1회 고정 — DevSettings·BeaconRegistry·UwbCalibrator 관례 승계.
 */
object CalibrationEngine {

    private const val TAG = "CalibrationEngine"

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // [v1.1.54 에코편차 집계] 상호RSSI 에코(0xE0C0) 텔레메트리 — 수집 자체는 판정 결과 미사용.
    //   (v1.1.55 Level2 자동보정이 이 히스토그램을 '읽어' echoCal 을 산출·주입한다 — 아래 계층 참조.)
    //   diff = (내가 측정한 상대 avgRssi) − (상대가 측정한 나 peerEchoRssi) 를 5dB×16버킷
    //   (−40~+40dB) 히스토그램으로 기기별 누적. 중앙값=체계적 비대칭(TX전력·안테나 등 모델별
    //   오프셋의 실측 근거), 산포=채널 노이즈(보정 불가 성분) — 이 둘의 구분이 수집 목적.
    //   DevSettingsActivity 폴러(1200ms)가 직접 읽는다(detectedSnapshot 폴링 선례). 스캔콜백은
    //   v1.1.47 메인루퍼 마셜, 폴러·stopAll 도 메인스레드 → 별도 동기화 불요(메인스레드 전용 맵).
    //   수명: 첫 틱에 SharedPreferences 누적분 시드 → 라이브 누적 → 소실/중지/주기 저장(세션 간 누적).
    const val ECHO_BUCKET_COUNT = 16      // 5dB × 16버킷 = −40 ~ +40dB
    const val ECHO_BUCKET_DB    = 5
    const val ECHO_BUCKET_MIN   = -40
    const val ECHO_PREFS        = "echo_diff_stats"   // 전용 SharedPreferences(설정 프리퍼런스와 분리)
    const val ECHO_KEY          = "data"
    private const val ECHO_PERSIST_EVERY_TICKS = 500  // 판정 ~120ms 주기 기준 약 1분마다 주기 저장
    val echoDiffLive = mutableMapOf<String, EchoDiffStats>()

    // ── [v1.1.54] 직렬화 유틸 — 순수 함수(서비스·개발자설정 공용). 레코드 '\n', 필드 '|', 버킷 ','
    //   "기기ID|echoTicks|totalTicks|b0,…,b15". 형식 불일치 레코드는 건너뛴다(방어적 파싱).
    fun parseEchoBlob(blob: String): MutableMap<String, EchoDiffStats> {
        val out = mutableMapOf<String, EchoDiffStats>()
        for (line in blob.split('\n')) {
            val f = line.split('|')
            if (f.size != 4) continue
            val b = f[3].split(',')
            if (b.size != ECHO_BUCKET_COUNT) continue
            val s = EchoDiffStats()
            s.echoTicks  = f[1].toIntOrNull() ?: continue
            s.totalTicks = f[2].toIntOrNull() ?: continue
            for (i in 0 until ECHO_BUCKET_COUNT) s.buckets[i] = b[i].toIntOrNull() ?: 0
            out[f[0]] = s
        }
        return out
    }

    fun serializeEchoBlob(map: Map<String, EchoDiffStats>): String =
        map.entries.joinToString("\n") { (id, s) ->
            "$id|${s.echoTicks}|${s.totalTicks}|${s.buckets.joinToString(",")}"
        }

    // ── [v1.1.55 Level2 에코 자동보정] 위 히스토그램을 '읽어' 판정 오프셋(echoCal)을 산출하는 계층 ──
    //   echoCal = clamp(−중앙값/2, ±clampDb). 절반인 이유: 거울쌍(상대도 같은 편차를 반대 부호로
    //   관측)이 양쪽에서 각자 절반씩 물러나 대칭점에 수렴 — 한쪽 전량 보정이면 쌍이 서로 과보정.
    //   게이트: n(echoTicks) < echoCalMinTicks = 판단 불가(null → FB 프라이어 대체 시도),
    //          산포(±IQR/2) > echoCalMaxIqrDb = 중앙값 불신(0.0 = 보정 포기 확정, 프라이어 미대체).
    //   킬스위치(echoAutoCalibEnabled, 기본 OFF)는 주입부(totalOffset)에서 — 아래 함수들은 항상
    //   계산 가능해 개발자설정 '후보 표시'와 판정이 같은 코드를 공유한다.
    private const val ECHO_DECAY_TICKS = 30_000   // 초과 시 전 버킷 반감(망각) — 시정수 ~1.5만 틱, 고정 상수
    private const val ECHO_FB_MODELS_KEY  = "fb_models"     // 캐시: "기기ID(sanitize)|모델" 라인
    private const val ECHO_FB_PRIORS_KEY  = "fb_priors"     // 캐시: "상대모델|중앙값|Σn" 라인(내 모델 기준 fold)
    private const val ECHO_FB_FETCHED_AT  = "fb_fetched_at"
    private const val ECHO_FB_UPLOADED_AT = "fb_uploaded_at"
    private const val ECHO_FB_UPLOAD_INTERVAL_MS = 3_600_000L   // 업로드 1h 스로틀(persistEchoAll 편승)
    // Firebase 모델쌍 프라이어 — 기동 시 캐시 즉시 복원+비동기 갱신(loadEchoPriors), 판정·표시는
    //   메모리 맵만 읽는다(판정 시 네트워크 0). Firebase 콜백=메인 루퍼 → echoDiffLive 와 같은
    //   메인스레드 전용 맵(별도 동기화 불요).
    val echoFbPriorByModel = mutableMapOf<String, Pair<Double, Int>>()   // 상대모델 → (fold 중앙값 dB, Σn)
    val echoFbModelById    = mutableMapOf<String, String>()              // sanitize 기기ID → 모델명
    @Volatile var echoFbFetchedAt = 0L

    /** 버킷 히스토그램 분위수(dB) — 버킷 내 균등분포 가정 선형 보간(버킷 중심 근사보다 정밀).
     *  total = echoTicks(버킷 총합), q ∈ (0,1]. total≤0 이면 0.0. */
    fun echoQuantileDb(buckets: IntArray, total: Int, q: Double): Double {
        if (total <= 0) return 0.0
        val target = total * q
        var cum = 0
        for (i in buckets.indices) {
            val c = buckets[i]
            if (c > 0 && cum + c >= target) {
                val frac = ((target - cum) / c).coerceIn(0.0, 1.0)
                return ECHO_BUCKET_MIN + (i + frac) * ECHO_BUCKET_DB
            }
            cum += c
        }
        return (ECHO_BUCKET_MIN + ECHO_BUCKET_COUNT * ECHO_BUCKET_DB).toDouble()
    }

    /** 로컬 보정 후보(dB) — 킬스위치 무관 계산. n 미달=null(프라이어 대체 허용), 산포 초과=0.0
     *  (보정 포기 '확정' — 로컬 표본이 충분한데 노이즈가 크다는 뜻이라 프라이어로도 안 덮는다). */
    fun echoCalLocalDb(s: EchoDiffStats): Double? {
        if (s.echoTicks < DevSettings.echoCalMinTicks) return null
        val iqrHalf = (echoQuantileDb(s.buckets, s.echoTicks, 0.75) -
                       echoQuantileDb(s.buckets, s.echoTicks, 0.25)) / 2.0
        if (iqrHalf > DevSettings.echoCalMaxIqrDb) return 0.0
        val clamp = DevSettings.echoCalClampDb.toDouble()
        return (-echoQuantileDb(s.buckets, s.echoTicks, 0.50) / 2.0).coerceIn(-clamp, clamp)
    }

    /** Firebase 모델쌍 프라이어 보정(dB) — 상대 모델 미상·프라이어 부재·Σn 게이트 미달이면 null.
     *  Σn 게이트는 '판정 시점' 라이브 평가(echoCalMinTicks 변경 즉시 반영). per-sample 산포
     *  게이트는 fetch 시점 설정으로 이미 걸러져 있다(loadEchoPriors — 다음 fetch 에 반영되는 절충). */
    fun echoCalPriorDb(deviceId: String): Double? {
        val model = echoFbModelById[FirebaseManager.sanitizeKey(deviceId)] ?: return null
        val (m, n) = echoFbPriorByModel[model] ?: return null
        if (n < DevSettings.echoCalMinTicks) return null
        val clamp = DevSettings.echoCalClampDb.toDouble()
        return (-m / 2.0).coerceIn(-clamp, clamp)
    }

    /** 판정 주입값(정수 dB) — 로컬 우선, 로컬 n 미달 시 FB 프라이어, 둘 다 없으면 0.
     *  킬스위치는 호출부가 건다(OFF 면 이 함수를 부르지 않아 순수 v1.1.54 거동). */
    fun echoCalAppliedDb(deviceId: String): Int {
        val v = echoDiffLive[deviceId]?.let { echoCalLocalDb(it) }
            ?: echoCalPriorDb(deviceId) ?: 0.0
        return Math.round(v).toInt()
    }

    /** (v1.1.54) 기기별 에코편차 누적치 — buckets 의 i번째 = diff 가 −40+5i 이상 −35+5i 미만인 틱 수(범위 밖은 양끝 버킷에 클램프). */
    class EchoDiffStats {
        val buckets = IntArray(ECHO_BUCKET_COUNT)
        var echoTicks  = 0   // 에코 존재 틱 수(= 버킷 총합)
        var totalTicks = 0   // RSSI 판정 블록 도달 틱 수(에코 유무 무관 — Case A(UWB) 조기분기 틱은 제외)
    }

    // ── [v1.1.54 에코편차 집계] 수집 텔레메트리 — 기록 자체는 판정 결과 미사용(v1.1.55 Level2 가 누적을 읽어 보정 산출) ──
    //   기록 규칙: peerEchoRssi 존재 시 '항상' 기록 — 25dB 정합성 게이트(hasReciprocal)와 무관.
    //   게이트 밖 극단 비대칭이야말로 관찰 대상이라 검열하면 25dB 문턱의 적정성을 평가할 수 없다.
    //   킬스위치(reciprocalRssiEnabled)와도 무관 — myEchoHash 주입은 무조건이라 판정 OFF 중에도
    //   상대 에코는 계속 파싱된다(판정 끄고 관찰만 하는 운용 가능). 단 debugMode(시뮬 RSSI 대입)
    //   틱은 호출부에서 제외 — 가짜 RSSI 가 누적 히스토그램을 오염시키면 안 된다.

    private fun echoPrefs() = appContext.getSharedPreferences(ECHO_PREFS, Context.MODE_PRIVATE)

    /** 라이브 전체를 저장분과 병합 저장 — 라이브 항목은 첫 틱에 저장분을 시드한 총 누적치라 단순 덮어쓰기. */
    fun persistEchoAll(myId: String) {
        if (echoDiffLive.isEmpty()) return
        val merged = parseEchoBlob(echoPrefs().getString(ECHO_KEY, "") ?: "")
        merged.putAll(echoDiffLive)
        echoPrefs().edit().putString(ECHO_KEY, serializeEchoBlob(merged)).apply()
        maybeUploadEchoCalib(myId, merged)   // [v1.1.55] FB 프라이어 업로드(1h 스로틀) — 주기 저장에 편승
    }

    /** 단일 기기 저장(onDeviceLost 소실 경로) — 라이브에서 이미 remove 된 항목을 넘겨받는다. */
    fun persistEchoEntry(deviceId: String, stats: EchoDiffStats) {
        val merged = parseEchoBlob(echoPrefs().getString(ECHO_KEY, "") ?: "")
        merged[deviceId] = stats
        echoPrefs().edit().putString(ECHO_KEY, serializeEchoBlob(merged)).apply()
    }

    /** 매 RSSI 판정 틱 호출(협력 격상 블록 직전). 첫 틱에 저장 누적분 시드 → 라이브 = 총 누적. */
    fun recordEchoDiff(myId: String, deviceId: String, avgRssi: Int, peerEchoRssi: Int) {
        val stats = echoDiffLive.getOrPut(deviceId) {
            parseEchoBlob(echoPrefs().getString(ECHO_KEY, "") ?: "")[deviceId] ?: EchoDiffStats()
        }
        stats.totalTicks++
        if (peerEchoRssi != BleConstants.NO_ECHO_RSSI) {
            stats.echoTicks++
            val diff = avgRssi - peerEchoRssi
            stats.buckets[((diff - ECHO_BUCKET_MIN) / ECHO_BUCKET_DB).coerceIn(0, ECHO_BUCKET_COUNT - 1)]++
            // [v1.1.55] 망각 — 에코틱 3만 초과 시 전 버킷 반감. 환경 변화(케이스 장착·수리 교체 등)에
            //   보정이 고착되지 않고 ~1.5만 틱 시정수로 추종한다. echoTicks=버킷합 불변식 유지,
            //   totalTicks 도 함께 반감해 '에코 %' 의미 보존(주기 저장 모듈로 위상이 흔들리는 건 무해).
            if (stats.echoTicks > ECHO_DECAY_TICKS) {
                for (i in stats.buckets.indices) stats.buckets[i] /= 2
                stats.echoTicks = stats.buckets.sum()
                stats.totalTicks /= 2
            }
        }
        // 주기 저장 — 새 타이머 없이 틱 카운터로(프로세스 강제종료 시 유실 상한 ~1분치).
        if (stats.totalTicks % ECHO_PERSIST_EVERY_TICKS == 0) persistEchoAll(myId)
    }

    // ── [v1.1.55 Level2] Firebase 모델쌍 프라이어 — 업로드(집계 원본 공유)·다운로드(부트스트랩) ──
    //   목적: 신규 기기쌍이 로컬 n 게이트(기본 3,000틱)를 채우기 전에도 같은 '모델쌍'의 집계 중앙값으로
    //   보정을 시작한다(로컬 성립 즉시 로컬 우선). 업로드·다운로드는 킬스위치와 무관(수집·공유 상시 —
    //   v1.1.54 상시 기록과 같은 정신), '적용'만 echoAutoCalibEnabled 가 결정한다.

    /** 주기 저장 편승 업로드 — 1h 스로틀. 스탬프는 '시도 시점'에 선갱신: Firebase 오프라인 퍼시스턴스
     *  (setPersistenceEnabled)가 쓰기를 큐잉해 재전송하므로, 실패 즉시 재시도 반복보다 다음 시간창이 안전. */
    private fun maybeUploadEchoCalib(myId: String, merged: Map<String, EchoDiffStats>) {
        if (myId.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - echoPrefs().getLong(ECHO_FB_UPLOADED_AT, 0L) < ECHO_FB_UPLOAD_INTERVAL_MS) return
        val peers = mutableMapOf<String, Triple<Double, Int, Double>>()
        for ((id, s) in merged) {
            if (s.echoTicks <= 0) continue   // 에코 없는 기기(비콘·구버전)는 집계 대상 아님
            val med = echoQuantileDb(s.buckets, s.echoTicks, 0.50)
            val iqr = (echoQuantileDb(s.buckets, s.echoTicks, 0.75) -
                       echoQuantileDb(s.buckets, s.echoTicks, 0.25)) / 2.0
            peers[FirebaseManager.sanitizeKey(id)] = Triple(med, s.echoTicks, iqr)
        }
        if (peers.isEmpty()) return
        echoPrefs().edit().putLong(ECHO_FB_UPLOADED_AT, now).apply()
        FirebaseManager.uploadEchoCalib(myId, Build.MODEL, peers) { }
    }

    /** 기동 시 1회(onCreate) — 프리퍼런스 캐시 즉시 복원(오프라인 재기동 대비) 후 비동기 갱신.
     *  per-sample 산포 게이트는 fetch 시점 설정으로 집계에 반영(설정 변경은 다음 fetch 부터),
     *  Σn 유효성 게이트는 판정 시점 라이브(echoCalPriorDb). 실패·빈 응답이면 캐시 유지. */
    fun loadEchoPriors() {
        val p = echoPrefs()
        echoFbModelById.clear()
        for (line in (p.getString(ECHO_FB_MODELS_KEY, "") ?: "").split('\n')) {
            val f = line.split('|')
            if (f.size == 2) echoFbModelById[f[0]] = f[1]
        }
        echoFbPriorByModel.clear()
        for (line in (p.getString(ECHO_FB_PRIORS_KEY, "") ?: "").split('\n')) {
            val f = line.split('|')
            if (f.size == 3) {
                val m = f[1].toDoubleOrNull()
                val n = f[2].toIntOrNull()
                if (m != null && n != null) echoFbPriorByModel[f[0]] = m to n
            }
        }
        echoFbFetchedAt = p.getLong(ECHO_FB_FETCHED_AT, 0L)
        FirebaseManager.downloadEchoCalibAll { nodes ->
            if (nodes.isEmpty()) return@downloadEchoCalibAll
            val models = nodes.associate { it.id to it.model }
            val priors = FirebaseManager.aggregateEchoPriors(
                nodes, Build.MODEL, DevSettings.echoCalMaxIqrDb.toDouble())
            echoFbModelById.clear()
            echoFbModelById.putAll(models)
            echoFbPriorByModel.clear()
            echoFbPriorByModel.putAll(priors)
            echoFbFetchedAt = System.currentTimeMillis()
            p.edit()
                .putString(ECHO_FB_MODELS_KEY,
                    models.entries.joinToString("\n") { "${it.key}|${it.value}" })
                .putString(ECHO_FB_PRIORS_KEY,
                    priors.entries.joinToString("\n") { "${it.key}|${it.value.first}|${it.value.second}" })
                .putLong(ECHO_FB_FETCHED_AT, echoFbFetchedAt)
                .apply()
            Log.d(TAG, "에코 프라이어 갱신: 노드 ${nodes.size} · 내 모델(${Build.MODEL}) 기준 ${priors.size}종")
        }
    }

    // [v1.1.37 ③] UWB↔RSSI 보정 학습·조회 키 — 역할쌍(카테고리쌍) 세그먼트.
    //   내 카테고리와 상대(스캔 캐시) 카테고리를 토큰화해 순서 무관하게 정렬·결합("×").
    //   같은 역할쌍(예 FORKLIFT×WALKER)은 안테나 높이·차폐 특성이 유사하다는 물리 모델 →
    //   한 지게차와 UWB로 학습한 편차를, 아직 UWB로 못 만난 다른 지게차의 RSSI 역산·임계 넛지에
    //   즉시 적용(사용자: "역할에 따른 데이터를 따로 저장 / 그 역할에 따른 데이터로 보정").
    //   상대 카테고리 미상(스캔 캐시 없음)이면 가장 보수적인 보행자로 간주.
    fun uwbPairKeyFor(myCategory: Int, peerCategory: Int): String {
        val mine   = categoryToken(myCategory)
        val theirs = categoryToken(peerCategory)
        return listOf(mine, theirs).sorted().joinToString("×")   // "×"
    }

    private fun categoryToken(cat: Int): String = when (cat) {
        BleConstants.CAT_FORKLIFT -> "FORKLIFT"
        BleConstants.CAT_EPJ      -> "EPJ"
        else                      -> "WALKER"
    }
}
