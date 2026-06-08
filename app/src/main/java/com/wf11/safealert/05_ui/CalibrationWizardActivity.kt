package com.wf11.safealert.ui

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.wf11.safealert.ble.BleConstants
import com.wf11.safealert.databinding.ActivityCalibrationWizardBinding
import com.wf11.safealert.utils.DevSettings
import java.util.UUID
import kotlin.math.log10

class CalibrationWizardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCalibrationWizardBinding

    // 1m, 5m, 10m, 15m — 4점 전체 회귀 (0m 제거: 밀착은 근거리 커플링으로 모델 불일치)
    private val DISTANCES   = floatArrayOf(1f, 5f, 10f, 15f)
    private val DIST_HINTS  = arrayOf(
        "두 폰을 정확히 1m 거리에 두세요\n(가슴 높이, 장애물 없는 직선)",
        "두 폰을 정확히 5m 거리에 두세요",
        "두 폰을 정확히 10m 거리에 두세요",
        "두 폰을 정확히 15m 거리에 두세요"
    )

    private val MEASURE_SEC    = 10
    private val TOP_PERCENT    = 0.05    // 상위 5% 추출 (직접경로 신호)
    private val MIN_SAMPLES    = 10
    private val MIN_RSSI_RANGE = 8.0f   // 1m~15m 최소 신호 차이

    private var step = 0
    private val calibPoints = mutableListOf<Pair<Float, Float>>()

    private val readings = mutableListOf<Int>()
    private var isMeasuring = false
    private val handler = Handler(Looper.getMainLooper())
    private var bleScanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var latestRssi: Int? = null

    private val uiUpdater = object : Runnable {
        override fun run() {
            updateLiveDisplay()
            handler.postDelayed(this, 150)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            latestRssi = result.rssi
            if (isMeasuring) synchronized(readings) { readings.add(result.rssi) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCalibrationWizardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.apply { title = "거리 교정 마법사"; setDisplayHomeAsUpEnabled(true) }

        startContinuousScan()
        handler.post(uiUpdater)
        showStep(0)

        binding.btnStartMeasure.setOnClickListener { startMeasuring() }
        binding.btnRemeasure.setOnClickListener    { resetCurrentStep() }
        binding.btnNext.setOnClickListener         { proceedToNext() }
    }

    private fun showStep(s: Int) {
        step = s
        binding.tvStep.text = "${s + 1} / ${DISTANCES.size} 단계  (${DISTANCES[s].toInt()}m)"
        binding.tvInstruction.text = DIST_HINTS[s]

        val progressWeight = (s + 1).toFloat() / DISTANCES.size
        val params = binding.vProgressBar.layoutParams as android.widget.LinearLayout.LayoutParams
        params.weight = progressWeight
        binding.vProgressBar.layoutParams = params

        synchronized(readings) { readings.clear() }
        binding.tvRssiCurrent.text = "-- dBm"
        binding.tvRssiMin.text = "--"
        binding.tvRssiMax.text = "--"
        binding.tvRssiAvg.text = "--"
        binding.tvRssiAvg.setTextColor(Color.parseColor("#059669"))
        binding.tvSampleCount.text = "상위 ${(TOP_PERCENT*100).toInt()}% 평균"
        binding.tvStatus.text = "상대방 폰에서 SafeAlert가 실행 중이어야 합니다.\n준비되면 '측정 시작'을 탭하세요."
        binding.progressMeasure.progress = 0
        binding.progressMeasure.visibility = View.INVISIBLE
        binding.btnStartMeasure.isEnabled = true
        binding.btnStartMeasure.text = "측정 시작"
        binding.btnRemeasure.visibility = View.GONE
        binding.btnNext.isEnabled = false
        binding.tvNoDeviceWarning.visibility = View.GONE
    }

    private fun startMeasuring() {
        if (latestRssi == null) {
            binding.tvNoDeviceWarning.visibility = View.VISIBLE
            binding.tvStatus.text = "신호 없음. 상대방 폰에서 SafeAlert를 실행하세요."
            return
        }
        synchronized(readings) { readings.clear() }
        isMeasuring = true
        binding.btnStartMeasure.isEnabled = false
        binding.btnStartMeasure.text = "측정 중..."
        binding.progressMeasure.visibility = View.VISIBLE
        binding.tvNoDeviceWarning.visibility = View.GONE

        val totalMs = MEASURE_SEC * 1000L
        val tickMs  = 100L
        var elapsed = 0L
        val ticker = object : Runnable {
            override fun run() {
                elapsed += tickMs
                binding.progressMeasure.progress = (elapsed * 100 / totalMs).toInt()
                val cnt = synchronized(readings) { readings.size }
                binding.tvStatus.text = "측정 중... ${(totalMs - elapsed) / 1000 + 1}초 남음  (${cnt}회)"
                if (elapsed < totalMs) handler.postDelayed(this, tickMs)
                else finishMeasuring()
            }
        }
        handler.postDelayed(ticker, tickMs)
    }

    private fun finishMeasuring() {
        isMeasuring = false
        val snap = synchronized(readings) { readings.toList() }
        if (snap.size < MIN_SAMPLES) {
            binding.tvStatus.text = "샘플 부족 (${snap.size}개). 다시 측정하세요."
            binding.btnStartMeasure.isEnabled = true
            binding.btnStartMeasure.text = "측정 시작"
            return
        }
        val topAvg = topPercentAverage(snap, TOP_PERCENT)
        binding.tvRssiMin.text = "${snap.min()}"
        binding.tvRssiMax.text = "${snap.max()}"
        binding.tvRssiAvg.text = "%.1f".format(topAvg)
        binding.tvSampleCount.text = "총 ${snap.size}회  상위 ${(TOP_PERCENT*100).toInt()}% 평균 = ${topAvg.toInt()} dBm"
        binding.tvStatus.text = "✓ 측정 완료  기준값: ${topAvg.toInt()} dBm"
        binding.progressMeasure.progress = 100
        binding.btnRemeasure.visibility = View.VISIBLE
        binding.btnNext.isEnabled = true
        binding.btnStartMeasure.isEnabled = false
    }

    private fun resetCurrentStep() = showStep(step)

    private fun proceedToNext() {
        val snap   = synchronized(readings) { readings.toList() }
        val topAvg = topPercentAverage(snap, TOP_PERCENT)
        calibPoints.add(Pair(DISTANCES[step], topAvg))
        if (step < DISTANCES.size - 1) showStep(step + 1)
        else finishCalibration()
    }

    // ── 교정 계산 (4점 전체 회귀) ─────────────────────────────
    private fun finishCalibration() {
        // 신호 감소 검증
        val rssiNear = calibPoints.minByOrNull { it.first }?.second ?: 0f
        val rssiiFar  = calibPoints.maxByOrNull { it.first }?.second ?: 0f
        val range    = rssiNear - rssiiFar

        if (range < MIN_RSSI_RANGE) {
            AlertDialog.Builder(this)
                .setTitle("⚠ 교정 불가 — 신호 변화 부족")
                .setMessage(
                    "1m~15m 신호 차이가 ${range.toInt()} dBm뿐입니다.\n" +
                    "최소 ${MIN_RSSI_RANGE.toInt()} dBm 차이가 필요합니다.\n\n" +
                    "【측정 환경 개선】\n" +
                    "· 금속 선반, 벽 반사 없는 직선 구간\n" +
                    "· 두 폰 동일 높이 (가슴 위치)\n" +
                    "· 사람, 장애물 없는 공간"
                )
                .setPositiveButton("다시 측정") { _, _ -> showStep(0); calibPoints.clear() }
                .setNegativeButton("기본값 사용") { _, _ ->
                    DevSettings.resetCalibration()
                    Toast.makeText(this, "기본값으로 설정됨 (n=2.73)", Toast.LENGTH_SHORT).show()
                    finish()
                }
                .show()
            return
        }

        // 4점 전체 최소제곱 회귀: rssi = A + B * log10(d)
        val cnt  = calibPoints.size.toFloat()
        val xs   = calibPoints.map { log10(it.first.toDouble()).toFloat() }
        val ys   = calibPoints.map { it.second }
        val sumX = xs.sum();  val sumY = ys.sum()
        val sumXY = xs.zip(ys).sumOf { (x, y) -> (x * y).toDouble() }.toFloat()
        val sumX2 = xs.sumOf { (it * it).toDouble() }.toFloat()
        val denom = cnt * sumX2 - sumX * sumX
        val B = (cnt * sumXY - sumX * sumY) / denom  // = -10n
        val A = (sumY - B * sumX) / cnt               // = txPower at 1m

        val rawN       = -B / 10.0f
        val learnedN   = rawN.coerceIn(1.5f, 4.5f)
        val learnedTxP = A.toInt().coerceIn(-90, 0)   // 상한 0 dBm (이전 -20 제한 제거)

        val warnings = mutableListOf<String>()
        if (rawN < 1.5f) warnings.add("• n=${rawN.format2()} → 너무 낮아 1.5로 보정")
        if (rawN > 4.5f) warnings.add("• n=${rawN.format2()} → 너무 높아 4.5로 보정")

        // 미리보기
        val warnDist = DevSettings.warningDistM
        val dangDist = DevSettings.dangerDistM
        val rssiWarn = learnedTxP - 10 * learnedN * log10(warnDist.toDouble())
        val rssiDang = learnedTxP - 10 * learnedN * log10(dangDist.toDouble())

        val msg = buildString {
            appendLine("📊 교정 결과 (4점 전체 회귀)")
            appendLine()
            appendLine("측정값 (상위 5% 평균):")
            calibPoints.forEach { (dist, rssi) ->
                val pred = learnedTxP - 10 * learnedN * log10(dist.toDouble())
                appendLine("  ${dist.toInt()}m → ${rssi.toInt()} dBm  (모델: ${pred.toInt()} dBm)")
            }
            appendLine()
            appendLine("학습 결과:")
            appendLine("  1m 기준 RSSI : $learnedTxP dBm")
            appendLine("  경로손실지수 n : ${learnedN.format2()}  (${environmentLabel(learnedN)})")
            appendLine()
            appendLine("저장 후 거리 임계값:")
            appendLine("  경고 ${warnDist.toInt()}m → ${rssiWarn.toInt()} dBm")
            appendLine("  위험 ${dangDist.toInt()}m → ${rssiDang.toInt()} dBm")
            if (warnings.isNotEmpty()) {
                appendLine()
                appendLine("⚠ 보정 적용:")
                warnings.forEach { appendLine(it) }
            }
        }

        AlertDialog.Builder(this)
            .setTitle(if (warnings.isEmpty()) "교정 완료" else "⚠ 보정값 적용됨")
            .setMessage(msg)
            .setPositiveButton("저장") { _, _ ->
                DevSettings.calibRssiAt1m = learnedTxP
                DevSettings.pathLossExp   = learnedN
                Toast.makeText(this,
                    "교정 저장: n=${learnedN.format2()}, 기준 $learnedTxP dBm",
                    Toast.LENGTH_LONG).show()
                finish()
            }
            .setNeutralButton("기본값 사용") { _, _ ->
                DevSettings.resetCalibration()
                Toast.makeText(this, "기본값으로 설정됨 (n=2.73)", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("다시 측정") { _, _ -> showStep(0); calibPoints.clear() }
            .show()
    }

    // ── 상위 N% 평균 (신호 강도 순, 직접경로 추출) ────────────
    private fun topPercentAverage(data: List<Int>, percent: Double): Float {
        if (data.isEmpty()) return 0f
        val sorted = data.sortedDescending()  // 강한 신호(덜 음수) 먼저
        val count  = (sorted.size * percent).toInt().coerceAtLeast(1)
        return sorted.take(count).average().toFloat()
    }

    private fun updateLiveDisplay() {
        val cur = latestRssi ?: return
        binding.tvRssiCurrent.text = "$cur dBm"
        val snap = synchronized(readings) { if (readings.isEmpty()) null else readings.toList() }
        snap?.let {
            binding.tvRssiMin.text = "${it.min()}"
            binding.tvRssiMax.text = "${it.max()}"
            if (isMeasuring) binding.tvRssiAvg.text = "%.1f".format(topPercentAverage(it, TOP_PERCENT))
        }
    }

    private fun startContinuousScan() {
        val bt = getSystemService(BluetoothManager::class.java) ?: return
        bleScanner = bt.adapter?.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(UUID.fromString(BleConstants.SERVICE_UUID)))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0).build()
        runCatching { bleScanner?.startScan(listOf(filter), settings, scanCallback) }
    }

    private fun Float.format2() = "%.2f".format(this)
    private fun environmentLabel(n: Float) = when {
        n < 2.2f -> "개활지"
        n < 2.8f -> "일반 실내"
        n < 3.5f -> "창고/금속"
        else     -> "복잡한 산업 환경"
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
    override fun onDestroy() {
        super.onDestroy()
        isMeasuring = false
        handler.removeCallbacksAndMessages(null)
        runCatching { bleScanner?.stopScan(scanCallback) }
    }
}
