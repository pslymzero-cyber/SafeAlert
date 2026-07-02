package com.wf11.safealert.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

// (v1.1.31) UWB 델타 보정 학습기 — UWB 실거리(d)와 같은 프레임의 BLE RSSI(median)를 짝지어
//   페어(deviceId)별 채널 편차 Δ = EMA(실측RSSI − 기대RSSI(d)) 를 학습한다.
//   · 기대RSSI(d) = A − 10n·log10(d)  (A = 1m 기준 −59dBm, n = 2.0 실내 자유공간 근사)
//   · Δ > 0 = 이 페어는 모델보다 세게 들림(안테나 이득 등) → 임계를 늦춰도 됨(−측, 최대 −3dB)
//   · Δ < 0 = 모델보다 약하게 들림(주머니/케이스 차폐) → 임계를 앞당김(+측, 최대 +10dB)
//   안전 불변식: 보정은 비대칭 클램프(−3 .. +10) — 조기경보 방향은 넓게, 지연 방향은 3dB 로 제한.
//   마지막 학습 후 24h 에 걸쳐 선형 감쇠로 0 수렴(환경이 바뀐 낡은 보정이 임계를 계속 흔들지 않게).
//   UWB 미지원/세션 없음/학습 부족(5샘플 미만)/킬스위치 OFF 면 항상 0 = 기존 거동과 완전 동일.
//   경보는 여전히 100% RSSI 구동 — UWB 는 임계를 '보정'만 하므로 UWB 가 끊겨도 이음새가 없다.
object UwbCalibrator {

    private const val TAG = "UwbCalibrator"
    private const val PREF_NAME = "uwb_calib"

    // 경로손실 모델 — BLE 1m 기준 수신강도(A)와 감쇠지수(n)
    private const val PATHLOSS_A = -59.0
    private const val PATHLOSS_N = 2.0

    // 품질 게이트 — 0.3m 미만(근접 NLOS 반사 오차)·15m 초과(RSSI 분산 과대)는 학습 제외
    private const val MIN_DIST_M = 0.3f
    private const val MAX_DIST_M = 15.0f
    private const val MIN_SAMPLES = 5              // 이 미만이면 보정 0(학습 중)
    private const val EMA_ALPHA = 0.2              // Δ 평활 계수
    private const val STALE_MS = 24L * 60 * 60 * 1000        // 24h 선형 감쇠 창
    private const val GC_MS = 7L * 24 * 60 * 60 * 1000       // 7일 지난 학습치는 로드 시 폐기
    private const val CLAMP_MIN_DB = -3.0          // 지연(경보 늦춤) 방향 한계 — 안전 불변식
    private const val CLAMP_MAX_DB = 10.0          // 조기(경보 앞당김) 방향 한계
    private const val PERSIST_THROTTLE_MS = 5000L  // 디스크 기록 스로틀(프로세스 사망 시 최대 5초 손실 허용)

    private data class Calib(val delta: Double, val updatedAt: Long, val samples: Int)

    private val map = ConcurrentHashMap<String, Calib>()
    private var prefs: SharedPreferences? = null
    @Volatile private var lastPersistMs = 0L
    @Volatile private var dirty = false

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs = p
        val now = System.currentTimeMillis()
        var loaded = 0
        p.all.forEach { (key, value) ->
            val f = (value as? String)?.split('|') ?: return@forEach
            if (f.size < 3) return@forEach
            val delta = f[0].toDoubleOrNull() ?: return@forEach
            val at = f[1].toLongOrNull() ?: return@forEach
            val n = f[2].toIntOrNull() ?: return@forEach
            if (now - at > GC_MS) return@forEach   // 폐기분은 다음 persist(전체 재작성)에서 디스크에서도 사라진다
            map[key] = Calib(delta, at, n)
            loaded++
        }
        if (loaded > 0) Log.d(TAG, "UWB 보정 로드: ${loaded}건")
    }

    // 학습 입력 — BleService.processAlert 가 '활성 UWB 세션 페어'에 한해 매 프레임 호출.
    //   rssi 는 medianValue(median-of-3): 스파이크 없고 위상지연≈0 이라 평활 잔상이 Δ 에 섞이지 않는다.
    fun onSample(deviceId: String, measuredRssi: Int, distM: Float) {
        if (distM < MIN_DIST_M || distM > MAX_DIST_M) return
        val residual = measuredRssi - expectedRssiAt(distM.toDouble())
        val now = System.currentTimeMillis()
        val prev = map[deviceId]
        val next = if (prev == null || now - prev.updatedAt > STALE_MS) {
            Calib(residual, now, 1)                // 첫 샘플(또는 24h 단절 후) = 시드로 재시작
        } else {
            Calib(prev.delta + EMA_ALPHA * (residual - prev.delta), now, (prev.samples + 1).coerceAtMost(1000))
        }
        map[deviceId] = next
        dirty = true
        maybePersist(now)
    }

    // 경보 임계 보정(dB) — BleService totalOffset 에 가산(+ = 더 먼 거리에서 조기 경보 = fail-safe 방향).
    fun offsetDbFor(deviceId: String): Int {
        if (!DevSettings.uwbCalibEnabled) return 0
        val c = map[deviceId] ?: return 0
        if (c.samples < MIN_SAMPLES) return 0
        val decay = (1.0 - (System.currentTimeMillis() - c.updatedAt).toDouble() / STALE_MS).coerceIn(0.0, 1.0)
        return ((-c.delta).coerceIn(CLAMP_MIN_DB, CLAMP_MAX_DB) * decay).roundToInt()
    }

    // 목록/플로팅용 거리 문자열. 빈 문자열 = 호출측이 기존 dBm 표기로 폴백.
    //   표시 모드 0 = dBm만 / 1 = UWB 실측 페어만 미터 / 2 = 전부 미터(비UWB 는 RSSI 역산 추정).
    //   UWB 실측은 소수 1자리 확정 표기, 역산은 '약' 접두로 추정임을 구분한다.
    fun distanceTextFor(deviceId: String, rssi: Int, uwbDistM: Float?): String {
        val mode = DevSettings.distanceDisplayMode
        if (mode <= 0) return ""
        if (uwbDistM != null) return "%.1fm".format(uwbDistM)
        if (mode < 2) return ""
        val d = estimateDistanceM(deviceId, rssi)
        return if (d < 9.95) "약 %.1fm".format(d) else "약 %.0fm".format(d)
    }

    // RSSI → 거리(m) 역산 — d = 10^((A − (rssi − Δeff)) / (10n)). Δeff = 감쇠 반영 학습 편차.
    private fun estimateDistanceM(deviceId: String, rssi: Int): Double {
        var deltaEff = 0.0
        val c = map[deviceId]
        if (c != null && c.samples >= MIN_SAMPLES) {
            val decay = (1.0 - (System.currentTimeMillis() - c.updatedAt).toDouble() / STALE_MS).coerceIn(0.0, 1.0)
            deltaEff = c.delta * decay
        }
        return 10.0.pow((PATHLOSS_A - (rssi - deltaEff)) / (10.0 * PATHLOSS_N)).coerceIn(0.1, 99.0)
    }

    private fun expectedRssiAt(distM: Double): Double = PATHLOSS_A - 10.0 * PATHLOSS_N * log10(distM)

    // 전체 재작성 persist — 항목 수가 페어 수(수 개)라 통째 기록이 가장 단순·안전하다.
    private fun maybePersist(now: Long) {
        if (!dirty || now - lastPersistMs < PERSIST_THROTTLE_MS) return
        val p = prefs ?: return
        lastPersistMs = now
        dirty = false
        val e = p.edit().clear()
        map.forEach { (id, c) -> e.putString(id, "${c.delta}|${c.updatedAt}|${c.samples}") }
        e.apply()
    }
}
