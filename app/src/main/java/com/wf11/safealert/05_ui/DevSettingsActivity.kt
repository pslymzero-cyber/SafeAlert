package com.wf11.safealert.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.wf11.safealert.BuildConfig
import com.wf11.safealert.utils.BeaconRegistry
import com.wf11.safealert.utils.DevSettings
import com.wf11.safealert.databinding.ActivityDevSettingsBinding

class DevSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDevSettingsBinding

    // RSSI → 거리 변환 (교정값 기반)
    // 교정값(calibRssiAt1m)이 내 폰의 실제 1m RSSI에 맞게 설정될수록 정확해짐
    private fun rssiToDistance(rssi: Int): String {
        val ref = DevSettings.calibRssiAt1m.toDouble()
        val dist = Math.pow(10.0, (ref - rssi) / 20.0)
        return when {
            dist < 0.5  -> "약 ${(dist * 100).toInt()}cm"
            dist < 1.0  -> "약 ${(dist * 10).toInt() * 10}cm"
            dist < 10.0 -> "약 %.1fm".format(dist)
            else        -> "약 ${dist.toInt()}m"
        }
    }

    private fun updateCalibSummary() {
        val txp = DevSettings.calibRssiAt1m
        val n   = DevSettings.pathLossExp
        binding.tvCalibSummary.text = "1m RSSI: ${txp} dBm  ·  n = %.2f".format(n)
    }

    private fun updatePathLossLabel() {
        val n = DevSettings.pathLossExp
        binding.tvPathLossVal.text = "%.2f".format(n)
        binding.tvPathLossHint.text = when {
            n < 2.2f -> "자유공간 수준 (개활지)"
            n < 2.8f -> "일반 실내"
            n < 3.5f -> "창고 / 금속 구조물"
            else     -> "복잡한 산업 환경"
        }
    }

    private fun showCalibrateWizard() {
        startActivity(android.content.Intent(this, CalibrationWizardActivity::class.java))
    }

    @Suppress("UNUSED")
    private fun showCalibrateWizardOld() {
        val distances = floatArrayOf(1f, 3f, 6f)
        val rssiMeasured = mutableListOf<Pair<Float, Int>>()  // (거리, rssi)
        var step = 0

        fun nextStep() {
            if (step >= distances.size) {
                // 최소제곱 회귀: rssi = A + B * log10(d)  →  A=txPower, B=-10n
                val n = distances.size.toFloat()
                val logDs = distances.map { Math.log10(it.toDouble()).toFloat() }
                val rssis  = rssiMeasured.map { it.second.toFloat() }
                val sumX  = logDs.sum();  val sumY  = rssis.sum()
                val sumXY = logDs.zip(rssis).sumOf { (x, y) -> (x * y).toDouble() }.toFloat()
                val sumX2 = logDs.sumOf { (it * it).toDouble() }.toFloat()
                val B = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX)  // = -10n
                val A = (sumY - B * sumX) / n                                    // = txPower

                val learnedN   = (-B / 10.0f).coerceIn(1.5f, 4.5f)
                val learnedTxP = A.toInt().coerceIn(-90, -30)
                DevSettings.pathLossExp  = learnedN
                DevSettings.calibRssiAt1m = learnedTxP
                updatePathLossLabel()
                updateCalibLabel(learnedTxP)
                updateCalibSummary()
                binding.seekCalib.progress = (learnedTxP + 90).coerceIn(0, 60)

                android.app.AlertDialog.Builder(this)
                    .setTitle("교정 완료")
                    .setMessage("1m RSSI: ${learnedTxP} dBm\n경로손실지수 n: %.2f\n\n실제 환경에 맞게 학습되었습니다.".format(learnedN))
                    .setPositiveButton("확인", null)
                    .show()
                return
            }
            val dist = distances[step]
            android.app.AlertDialog.Builder(this)
                .setTitle("교정 측정 ${step + 1}/3")
                .setMessage("상대방 폰을 정확히 ${dist.toInt()}m 거리에 두고\n'측정' 버튼을 탭하세요.")
                .setPositiveButton("측정") { _, _ ->
                    // 현재 상태바의 RSSI값이 없으므로 실시간 측정 안내
                    val input = android.widget.EditText(this).apply {
                        hint = "현재 RSSI 값 입력 (상태바에서 확인, 예: -65)"
                        inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                        setPadding(48, 20, 48, 8)
                    }
                    android.app.AlertDialog.Builder(this)
                        .setTitle("${dist.toInt()}m RSSI 입력")
                        .setMessage("상태바에 표시된 RSSI 값을 입력하세요.\n(장비를 가동 중이어야 합니다)")
                        .setView(input)
                        .setPositiveButton("확인") { _, _ ->
                            val rssi = input.text.toString().toIntOrNull()
                            if (rssi != null) {
                                rssiMeasured.add(Pair(dist, rssi))
                                step++
                                nextStep()
                            }
                        }
                        .setNegativeButton("취소", null)
                        .show()
                }
                .setNegativeButton("취소", null)
                .show()
        }
        nextStep()
    }

    private fun updateCalibLabel(rssi: Int) {
        binding.tvCalibVal.text = "$rssi dBm"
        val hint = when {
            rssi >= -45 -> "최신 플래그십 (Galaxy S/iPhone Pro)"
            rssi >= -55 -> "최신 중급기"
            rssi >= -62 -> "일반 스마트폰"
            else        -> "구형/저가 폰"
        }
        binding.tvCalibHint.text = hint
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDevSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.apply { title = "⚙ 개발자 설정"; setDisplayHomeAsUpEnabled(true) }
        loadValues()
        setupListeners()
    }

    private fun loadValues() {
        // 거리 교정
        val calibProgress = DevSettings.calibRssiAt1m + 90
        binding.seekCalib.progress  = calibProgress.coerceIn(0, 60)
        updateCalibLabel(DevSettings.calibRssiAt1m)
        updatePathLossLabel()
        // 경보 볼륨
        binding.seekAlarmVolume.progress = DevSettings.alarmVolume
        binding.tvAlarmVolumeVal.text    = "${DevSettings.alarmVolume}%"
        // 송수신 모드
        binding.switchWalkerDetectsWalker.isChecked = DevSettings.walkerDetectsWalker
        binding.switchDeviceTx.isChecked = DevSettings.deviceTx
        binding.switchDeviceRx.isChecked = DevSettings.deviceRx
        binding.switchWalkerTx.isChecked = DevSettings.walkerTx
        binding.switchWalkerRx.isChecked = DevSettings.walkerRx
        binding.spinnerScanPeriod.setSelection(scanPeriodIndex(DevSettings.scanPeriodMs))
        binding.spinnerAdvertise.setSelection(advertiseIndex(DevSettings.advertiseInterval))
        // 경보
        binding.switchVibration.isChecked = DevSettings.vibrationEnabled
        binding.spinnerVibWarning.setSelection(vibWarningIndex(DevSettings.vibrationWarningMs))
        binding.spinnerVibCount.setSelection(vibCountIndex(DevSettings.vibrationDangerCount))
        binding.switchSound.isChecked = DevSettings.soundEnabled
        // Firebase
        binding.etFirebaseRoot.setText(DevSettings.firebaseRoot)
        binding.switchAutoSave.isChecked = DevSettings.autoSaveAlerts
        // 디버그
        binding.switchDebug.isChecked = DevSettings.debugMode
        binding.seekSimRssi.progress = DevSettings.simulatedRssi + 100
        binding.tvSimRssiVal.text = rssiToDistance(DevSettings.simulatedRssi)
        binding.switchVerbose.isChecked = DevSettings.logVerbose
        updateDebugBadge()
        updateSimRssiEnabled()
    }

    private fun setupListeners() {
        binding.seekCalib.setOnSeekBarChangeListener(seekListener { v ->
            updateCalibLabel(v - 90)
        })
        binding.seekAlarmVolume.setOnSeekBarChangeListener(seekListener { v ->
            binding.tvAlarmVolumeVal.text = "$v%"
        })
        binding.seekSimRssi.setOnSeekBarChangeListener(seekListener { v ->
            binding.tvSimRssiVal.text = rssiToDistance(v - 100)
        })
        binding.switchDebug.setOnCheckedChangeListener { _, _ ->
            updateDebugBadge(); updateSimRssiEnabled()
        }
        binding.btnSave.setOnClickListener { saveValues() }
        binding.btnReset.setOnClickListener { resetValues() }
        binding.btnCalibrateWizard.setOnClickListener    { showCalibrateWizard() }
        binding.btnCalibrateWizardTop.setOnClickListener { showCalibrateWizard() }

        // 앱 정보 — 버전 표시 + 오픈소스 라이선스 이동
        binding.tvAppVersion.text = "SafeAlert v${BuildConfig.VERSION_NAME}"
        binding.btnOpenSourceLicenses.setOnClickListener {
            startActivity(Intent(this, OpenSourceLicensesActivity::class.java))
        }

        // 교정 초기화 (잘못된 교정값 복구용)
        binding.tvCalibSummary.setOnLongClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle("교정값 초기화")
                .setMessage("교정값을 기본값으로 되돌립니다.\n(n=2.5, 1m RSSI=-65 dBm)\n\n잘못된 교정으로 오경보가 발생할 때 사용하세요.")
                .setPositiveButton("초기화") { _, _ ->
                    DevSettings.resetCalibration()
                    updateCalibLabel(-65)
                    updatePathLossLabel()
                    updateCalibSummary()
                    binding.seekCalib.progress = 25  // -65+90=25
                    Toast.makeText(this, "교정값 초기화됨 (기본값)", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("취소", null)
                .show()
            true
        }
        updateCalibSummary()
        // 비콘 관리는 메인 화면으로 이동됨
    }

    private fun saveValues() {
        DevSettings.calibRssiAt1m = binding.seekCalib.progress - 90
        DevSettings.alarmVolume          = binding.seekAlarmVolume.progress
        DevSettings.walkerDetectsWalker  = binding.switchWalkerDetectsWalker.isChecked
        DevSettings.deviceTx             = binding.switchDeviceTx.isChecked
        DevSettings.deviceRx             = binding.switchDeviceRx.isChecked
        DevSettings.walkerTx             = binding.switchWalkerTx.isChecked
        DevSettings.walkerRx             = binding.switchWalkerRx.isChecked
        // rssiWarning / rssiDanger 는 computed property (거리+교정으로 자동 계산), 직접 저장 불필요
        DevSettings.scanPeriodMs         = scanPeriodValues[binding.spinnerScanPeriod.selectedItemPosition]
        DevSettings.advertiseInterval    = advertiseValues[binding.spinnerAdvertise.selectedItemPosition]
        DevSettings.vibrationEnabled     = binding.switchVibration.isChecked
        DevSettings.vibrationWarningMs   = vibWarningValues[binding.spinnerVibWarning.selectedItemPosition]
        DevSettings.vibrationDangerCount = vibCountValues[binding.spinnerVibCount.selectedItemPosition]
        DevSettings.soundEnabled         = binding.switchSound.isChecked
        DevSettings.firebaseRoot         = binding.etFirebaseRoot.text.toString().trim().ifEmpty { "wf11" }
        DevSettings.autoSaveAlerts       = binding.switchAutoSave.isChecked
        DevSettings.debugMode            = binding.switchDebug.isChecked
        DevSettings.simulatedRssi        = binding.seekSimRssi.progress - 100
        DevSettings.logVerbose           = binding.switchVerbose.isChecked

        Toast.makeText(this, "설정이 저장되었습니다\n변경 사항은 다음 스캔 사이클부터 적용됩니다",
            Toast.LENGTH_LONG).show()
    }

    private fun resetValues() {
        DevSettings.resetToDefault()
        loadValues()
        Toast.makeText(this, "기본값으로 초기화되었습니다", Toast.LENGTH_SHORT).show()
    }

    private fun updateDebugBadge() {
        supportActionBar?.subtitle = if (binding.switchDebug.isChecked) "[ DEBUG ]" else null
    }

    private fun updateSimRssiEnabled() {
        val enabled = binding.switchDebug.isChecked
        binding.seekSimRssi.isEnabled = enabled
        binding.seekSimRssi.alpha = if (enabled) 1f else 0.4f
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // Spinner 인덱스 헬퍼
    private val scanPeriodValues = longArrayOf(1000, 2000, 3000, 5000)
    private val advertiseValues  = intArrayOf(100, 200, 500, 1000)
    private val vibWarningValues = longArrayOf(300, 500, 1000)
    private val vibCountValues   = intArrayOf(1, 3, 5)

    private fun scanPeriodIndex(v: Long)  = scanPeriodValues.indexOfFirst { it == v }.coerceAtLeast(2)
    private fun advertiseIndex(v: Int)    = advertiseValues.indexOfFirst { it == v }.coerceAtLeast(1)
    private fun vibWarningIndex(v: Long)  = vibWarningValues.indexOfFirst { it == v }.coerceAtLeast(1)
    private fun vibCountIndex(v: Int)     = vibCountValues.indexOfFirst { it == v }.coerceAtLeast(1)

    private fun seekListener(onChange: (Int) -> Unit) = object : android.widget.SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: android.widget.SeekBar, v: Int, b: Boolean) = onChange(v)
        override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
        override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
    }
}
