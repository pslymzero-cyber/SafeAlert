package com.wf11.safealert.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

// (v1.1.31) UWB 델타 보정 학습기 — UWB 실거리(d)와 같은 프레임의 BLE RSSI(median)를 짝지어
//   역할쌍(pairKey)별 채널 편차 Δ = EMA(실측RSSI − 기대RSSI(d)) 를 학습한다.
//   · 기대RSSI(d) = A − 10n·log10(d)  (A = 1m 기준 −59dBm, n = 2.0 실내 자유공간 근사)
//   · Δ > 0 = 이 페어는 모델보다 세게 들림(안테나 이득 등) → 임계를 늦춰도 됨(−측, 최대 −3dB)
//   · Δ < 0 = 모델보다 약하게 들림(주머니/케이스 차폐) → 임계를 앞당김(+측, 최대 +10dB)
//   안전 불변식: 보정은 비대칭 클램프(−3 .. +10) — 조기경보 방향은 넓게, 지연 방향은 3dB 로 제한.
//   마지막 학습 후 24h 에 걸쳐 선형 감쇠로 0 수렴(환경이 바뀐 낡은 보정이 임계를 계속 흔들지 않게).
//   UWB 미지원/세션 없음/학습 부족(5샘플 미만)/킬스위치 OFF 면 항상 0 = 기존 거동과 완전 동일.
//   경보는 여전히 100% RSSI 구동 — UWB 는 임계를 '보정'만 하므로 UWB 가 끊겨도 이음새가 없다.
//
// (v1.1.34) 사업장 보정 프로파일 — 학습 저장소를 사업장 코드(DevSettings.uwbSiteCode)로
//   네임스페이스(SharedPreferences uwb_calib_<사업장>)해 사업장별로 영속·전환한다.
//   · 사업장 미지정(빈 값) = 종전 파일(uwb_calib)·종전 공식 그대로 — 100% 현행 동일.
//   · 사업장 지정 시 2성분 모델: 기준선 baseline(느린 EMA, 감쇠·GC 없음 = 사업장 장기 특성)
//     + 당일 미세조정 delta(기존 빠른 EMA). 24h 감쇠는 delta→baseline 수렴으로만 작동해
//     '감쇠는 당일 미세조정만' — 주말이 지나도 기준선은 남고, 24h 단절 후에도 기준선에서
//     이어간다(samples 누적 유지 → 재방문 첫 샘플부터 보정 활성). 안전 클램프(−3..+10)는 동일.
//   · 사업장 전환(applySite) = 현재 프로파일 저장 → 새 프로파일 로드(각 사업장 학습 보존).
//
// (v1.1.37) 역할쌍 세분 — 학습 키를 개별 기기(deviceId)에서 역할쌍(pairKey)으로 바꾼다.
//   pairKey = 양끝 카테고리(WALKER/EPJ/FORKLIFT)를 정렬해 순서무관 결합(예 "FORKLIFT×WALKER").
//   같은 역할쌍은 안테나 높이·차폐 특성이 유사하다는 물리 모델 → 한 지게차와 UWB로 학습한 편차를
//   아직 UWB로 만난 적 없는 다른 지게차의 RSSI 거리 역산·임계 넛지에 즉시 적용한다
//   (사용자: "역할에 따른 데이터를 따로 저장 / 그 역할에 따른 데이터로 보정"). pairKey 산출은
//   카테고리·myMode 판정이 있는 BleService 가 맡고 여기선 불투명 문자열 키로만 쓴다.
//   경보 자체는 v1.1.36 이후 UWB 실측이 主권위 — 보정은 UWB 없는 페어의 RSSI 에만 관여한다.
object UwbCalibrator {

    private const val TAG = "UwbCalibrator"
    private const val PREF_NAME = "uwb_calib"

    // [v1.1.46→v1.1.49] 프로파일 스키마 — 학습 조건이 바뀌면 구버전 저장값을 1회 폐기하고 재학습한다.
    //   v3(v1.1.49): 판정 분리(offsetDbFor 를 totalOffset 에서 제거) + RSSI -80dBm 시작 게이트 재도입으로
    //   학습 모집단 자체가 바뀜 — 게이트 상시 페어(v1.1.45) 시절 누적된 NLOS 오염 Δ 를 여기서 1회 초기화한다.
    private const val KEY_SCHEMA = "_schema"
    private const val SCHEMA_VER = 3

    // 경로손실 모델 — BLE 1m 기준 수신강도(A)와 감쇠지수(n)
    private const val PATHLOSS_A = -59.0
    private const val PATHLOSS_N = 2.0

    // 품질 게이트 — 0.3m 미만(근접 NLOS 반사 오차)·8m 초과는 학습 제외. [v1.1.46] 15→8m:
    //   상시 페어(v1.1.45) 이후 5~15m NLOS(랙·파렛트 차폐) 잔차가 대량 유입돼 Δ 가 +10dB 클램프에
    //   고착(임계 영구 선행 = 즉시 DANGER 증상의 한 축) — 근거리 LOS 우세 구간만 학습한다.
    private const val MIN_DIST_M = 0.3f
    private const val MAX_DIST_M = 8.0f
    private const val MIN_SAMPLES = 5              // 이 미만이면 보정 0(학습 중)
    private const val EMA_ALPHA = 0.2              // Δ 평활 계수(당일 미세조정)
    private const val BASE_ALPHA = 0.05            // (v1.1.34) 사업장 기준선(장기 EMA) 계수 — delta 의 1/4 속도
    private const val STALE_MS = 24L * 60 * 60 * 1000        // 24h 선형 감쇠 창
    private const val GC_MS = 7L * 24 * 60 * 60 * 1000       // 7일 지난 학습치는 로드 시 폐기(공용 프로파일만)
    private const val CLAMP_MIN_DB = -3.0          // 지연(경보 늦춤) 방향 한계 — 안전 불변식
    private const val CLAMP_MAX_DB = 10.0          // 조기(경보 앞당김) 방향 한계
    private const val PERSIST_THROTTLE_MS = 5000L  // 디스크 기록 스로틀(프로세스 사망 시 최대 5초 손실 허용)

    // baseline = 사업장 장기 기준선(감쇠 없음). 공용 프로파일에서는 계산에 안 쓰고 저장만 동행.
    private data class Calib(val delta: Double, val updatedAt: Long, val samples: Int, val baseline: Double)

    private val map = ConcurrentHashMap<String, Calib>()
    private var appCtx: Context? = null
    private var prefs: SharedPreferences? = null
    @Volatile private var activeSite: String = ""
    @Volatile private var lastPersistMs = 0L
    @Volatile private var dirty = false

    fun init(context: Context) {
        appCtx = context.applicationContext
        activeSite = DevSettings.uwbSiteCode   // SafeAlertApp 이 DevSettings.init 이후에 부른다
        loadProfile(activeSite)
    }

    // (v1.1.34) 사업장 코드 변경 반영 — 설정 라이브 반영 경로에서 무조건 호출(무변경 = no-op).
    //   떠나는 프로파일을 먼저 저장하므로 사업장을 오가도 각 사업장의 학습이 보존된다.
    @Synchronized
    fun applySite() {
        val newSite = DevSettings.uwbSiteCode
        if (newSite == activeSite) return
        persistNow(System.currentTimeMillis())   // dirty 아니면 no-op — 타이핑 중간값 파일이 안 생긴다
        activeSite = newSite
        loadProfile(newSite)
    }

    // 사업장 코드 → SharedPreferences 파일명(파일시스템 예약문자·공백은 _ 치환)
    private fun prefNameFor(site: String): String =
        if (site.isEmpty()) PREF_NAME
        else PREF_NAME + "_" + site.replace(Regex("[\\\\/:*?\"<>|\\s]"), "_")

    @Synchronized
    private fun loadProfile(site: String) {
        val ctx = appCtx ?: return
        map.clear()
        dirty = false
        lastPersistMs = 0L
        val p = ctx.getSharedPreferences(prefNameFor(site), Context.MODE_PRIVATE)
        prefs = p
        // [v1.1.46] 구스키마(마커 없음=1) 프로파일 = 오염 가능 저장값 1회 폐기 — 마커만 남기고 비운다.
        if (p.getInt(KEY_SCHEMA, 1) < SCHEMA_VER) {
            p.edit().clear().putInt(KEY_SCHEMA, SCHEMA_VER).apply()
            Log.d(TAG, "UWB 보정 스키마 승격(${if (site.isEmpty()) "공용" else site}): v${SCHEMA_VER} — 구 학습값 폐기")
            return
        }
        val now = System.currentTimeMillis()
        var loaded = 0
        p.all.forEach { (key, value) ->
            val f = (value as? String)?.split('|') ?: return@forEach
            if (f.size < 3) return@forEach
            val delta = f[0].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return@forEach   // [v1.1.37 ①] 디스크 NaN 오염 자가치유(로드 시 폐기)
            val at = f[1].toLongOrNull() ?: return@forEach
            val n = f[2].toIntOrNull() ?: return@forEach
            // 공용 프로파일만 7일 GC(현행 유지) — 사업장 프로파일은 장기 보존이 목적이라 GC 없음.
            //   폐기분은 다음 persist(전체 재작성)에서 디스크에서도 사라진다.
            if (site.isEmpty() && now - at > GC_MS) return@forEach
            val baseline = if (f.size >= 4) f[3].toDoubleOrNull()?.takeIf { it.isFinite() } ?: delta else delta   // 구(3필드) 포맷 호환 + [v1.1.37 ①] NaN 자가치유
            map[key] = Calib(delta, at, n, baseline)
            loaded++
        }
        if (loaded > 0) Log.d(TAG, "UWB 보정 로드(${if (site.isEmpty()) "공용" else site}): ${loaded}건")
    }

    // 학습 입력 — BleService.processAlert 가 '신선한 UWB 실측이 있는 페어'(freshUwbDistM)에 한해 매 프레임 호출.
    //   rssi 는 medianValue(median-of-3): 스파이크 없고 위상지연≈0 이라 평활 잔상이 Δ 에 섞이지 않는다.
    @Synchronized
    fun onSample(pairKey: String, measuredRssi: Int, distM: Float) {
        if (!distM.isFinite() || distM < MIN_DIST_M || distM > MAX_DIST_M) return   // [v1.1.37 ①] NaN 방어(비교 false 통과 차단)
        val residual = measuredRssi - expectedRssiAt(distM.toDouble())
        val now = System.currentTimeMillis()
        val prev = map[pairKey]
        val next = when {
            prev == null ->
                Calib(residual, now, 1, residual)   // 첫 샘플 = 시드
            now - prev.updatedAt > STALE_MS && activeSite.isEmpty() ->
                Calib(residual, now, 1, residual)   // 공용: 24h 단절 후 시드 재시작(현행 동일)
            now - prev.updatedAt > STALE_MS ->
                // 사업장: 단절 후에도 기준선에서 이어간다(주말 리셋 없음) — delta 는 기준선 기점
                //   재수렴, samples 는 누적 유지 → 재방문 첫 샘플부터 보정 활성(MIN_SAMPLES 기충족).
                Calib(prev.baseline + EMA_ALPHA * (residual - prev.baseline), now,
                    (prev.samples + 1).coerceAtMost(1000),
                    prev.baseline + BASE_ALPHA * (residual - prev.baseline))
            else ->
                Calib(prev.delta + EMA_ALPHA * (residual - prev.delta), now,
                    (prev.samples + 1).coerceAtMost(1000),
                    prev.baseline + BASE_ALPHA * (residual - prev.baseline))
        }
        map[pairKey] = next
        dirty = true
        maybePersist(now)
    }

    // 경보 임계 보정(dB) — BleService totalOffset 에 가산(+ = 더 먼 거리에서 조기 경보 = fail-safe 방향).
    fun offsetDbFor(pairKey: String): Int {
        if (!DevSettings.uwbCalibEnabled) return 0
        val c = map[pairKey] ?: return 0
        if (c.samples < MIN_SAMPLES) return 0
        if (!c.delta.isFinite() || !c.baseline.isFinite()) return 0   // [v1.1.37 ①] NaN 오염 시 무보정 — coerceIn(NaN).roundToInt() 예외 방지
        val decay = (1.0 - (System.currentTimeMillis() - c.updatedAt).toDouble() / STALE_MS).coerceIn(0.0, 1.0)
        return if (activeSite.isEmpty()) {
            // 공용: 종전 공식 그대로(24h 후 0 수렴)
            ((-c.delta).coerceIn(CLAMP_MIN_DB, CLAMP_MAX_DB) * decay).roundToInt()
        } else {
            // 사업장: 감쇠는 당일 미세조정(delta)만 — 0 이 아니라 사업장 기준선으로 수렴
            (-(c.baseline + (c.delta - c.baseline) * decay)).coerceIn(CLAMP_MIN_DB, CLAMP_MAX_DB).roundToInt()
        }
    }

    // 목록/플로팅용 거리 문자열. 빈 문자열 = 호출측이 기존 dBm 표기로 폴백.
    //   표시 모드 0 = dBm만 / 1 = UWB 실측 페어만 미터 / 2 = 전부 미터(비UWB 는 RSSI 역산 추정).
    //   [v1.1.37 ③] 거리 소스를 명시 태그로 노출 — UWB 실측 페어는 '·UWB', RSSI 역산은 '·RSSI'.
    //   기존엔 UWB="3.2m" vs RSSI="약 3.2m" 로 '약' 접두만으로 구분해 사용자가 소스를 인지하지 못했다
    //   (사용자: "왜 rssi로 거리 측정을 하고 있지?"). 이제 어느 페어가 UWB로 측정 중인지 한눈에 보인다.
    fun distanceTextFor(pairKey: String, rssi: Int, uwbDistM: Float?): String {
        val mode = DevSettings.distanceDisplayMode
        if (mode <= 0) return ""
        if (uwbDistM != null) return "%.1fm·UWB".format(uwbDistM)
        if (mode < 2) return ""
        val d = estimateDistanceM(pairKey, rssi)
        return if (d < 9.95) "약 %.1fm·RSSI".format(d) else "약 %.0fm·RSSI".format(d)
    }

    // RSSI → 거리(m) 역산 — d = 10^((A − (rssi − Δeff)) / (10n)). Δeff = 감쇠 반영 학습 편차.
    private fun estimateDistanceM(pairKey: String, rssi: Int): Double {
        var deltaEff = 0.0
        val c = map[pairKey]
        if (c != null && c.samples >= MIN_SAMPLES) {
            val decay = (1.0 - (System.currentTimeMillis() - c.updatedAt).toDouble() / STALE_MS).coerceIn(0.0, 1.0)
            deltaEff = if (activeSite.isEmpty()) c.delta * decay
                       else c.baseline + (c.delta - c.baseline) * decay
        }
        return 10.0.pow((PATHLOSS_A - (rssi - deltaEff)) / (10.0 * PATHLOSS_N)).coerceIn(0.1, 99.0)
    }

    private fun expectedRssiAt(distM: Double): Double = PATHLOSS_A - 10.0 * PATHLOSS_N * log10(distM)

    // 전체 재작성 persist — 항목 수가 페어 수(수 개)라 통째 기록이 가장 단순·안전하다.
    //   포맷 v1.1.34 = "delta|updatedAt|samples|baseline" (4필드 — 구 3필드는 로드 시 baseline=delta 로 승격)
    private fun maybePersist(now: Long) {
        if (now - lastPersistMs < PERSIST_THROTTLE_MS) return
        persistNow(now)
    }

    @Synchronized
    private fun persistNow(now: Long) {
        if (!dirty) return
        val p = prefs ?: return
        lastPersistMs = now
        dirty = false
        val e = p.edit().clear()
        e.putInt(KEY_SCHEMA, SCHEMA_VER)   // [v1.1.46] clear() 가 마커도 지우므로 매 persist 에 동봉 — 누락 시 재기동마다 재리셋=학습 소실
        map.forEach { (id, c) -> e.putString(id, "${c.delta}|${c.updatedAt}|${c.samples}|${c.baseline}") }
        e.apply()
    }
}
