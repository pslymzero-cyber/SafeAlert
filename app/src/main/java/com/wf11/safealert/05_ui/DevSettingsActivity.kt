package com.wf11.safealert.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

    private fun updateCalibLabel(rssi: Int) {
        binding.tvCalibVal.text = "$rssi dBm"
        val hint = when {
            rssi >= -45 -> "최신 플래그십 (Galaxy S/iPhone Pro)"
            rssi >= -55 -> "최신 중급기"
            rssi >= -62 -> "일반 스마트폰"
            else        -> "구형/저가 폰"
        }
        binding.tvCalibHint.text = hint
        // 교정값 변경 시 거리 표시 즉시 갱신 (미터는 그대로, 실제 RSSI 참고용 표시)
        updateDistLabels()
    }

    // 미터 → "Xm (RSSI ~Y)" 표시
    private fun distLabel(distM: Float): String {
        val rssi = (DevSettings.calibRssiAt1m.toDouble() - 20.0 * Math.log10(distM.toDouble())).toInt()
        return "${distM.toInt()}m  (RSSI ≈ ${rssi} dBm)"
    }

    private fun updateDistLabels() {
        val warnM = (binding.seekRssiWarning.progress + 1).toFloat()
        val dangM = (binding.seekRssiDanger.progress  + 1).toFloat()
        binding.tvRssiWarningVal.text = distLabel(warnM)
        binding.tvRssiDangerVal.text  = distLabel(dangM)
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
        // 거리 교정 (seekCalib: 0=-90, 60=-30, default -65 → progress=25)
        val calibProgress = DevSettings.calibRssiAt1m + 90  // -90→0, -65→25, -30→60
        binding.seekCalib.progress  = calibProgress.coerceIn(0, 60)
        updateCalibLabel(DevSettings.calibRssiAt1m)
        // 경보 볼륨
        binding.seekAlarmVolume.progress = DevSettings.alarmVolume
        binding.tvAlarmVolumeVal.text    = "${DevSettings.alarmVolume}%"
        // 송수신 모드
        binding.switchWalkerDetectsWalker.isChecked = DevSettings.walkerDetectsWalker
        binding.switchDeviceTx.isChecked = DevSettings.deviceTx
        binding.switchDeviceRx.isChecked = DevSettings.deviceRx
        binding.switchWalkerTx.isChecked = DevSettings.walkerTx
        binding.switchWalkerRx.isChecked = DevSettings.walkerRx
        // BLE 거리 (미터 단위, progress = distM - 1)
        binding.seekRssiWarning.progress = (DevSettings.warningDistM.toInt() - 1).coerceIn(0, 49)
        binding.seekRssiDanger.progress  = (DevSettings.dangerDistM.toInt()  - 1).coerceIn(0, 49)
        updateDistLabels()
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
            // 교정값 바뀌면 경고/위험 거리 표시도 즉시 갱신
            binding.tvRssiWarningVal.text = rssiToDistance(binding.seekRssiWarning.progress - 100)
            binding.tvRssiDangerVal.text  = rssiToDistance(binding.seekRssiDanger.progress  - 100)
        })
        binding.seekAlarmVolume.setOnSeekBarChangeListener(seekListener { v ->
            binding.tvAlarmVolumeVal.text = "$v%"
        })
        binding.seekRssiWarning.setOnSeekBarChangeListener(seekListener { _ -> updateDistLabels() })
        binding.seekRssiDanger.setOnSeekBarChangeListener(seekListener  { _ -> updateDistLabels() })
        binding.seekSimRssi.setOnSeekBarChangeListener(seekListener { v ->
            binding.tvSimRssiVal.text = rssiToDistance(v - 100)
        })
        binding.switchDebug.setOnCheckedChangeListener { _, _ ->
            updateDebugBadge(); updateSimRssiEnabled()
        }
        binding.btnSave.setOnClickListener { saveValues() }
        binding.btnReset.setOnClickListener { resetValues() }
        // 비콘 관리는 메인 화면으로 이동됨
    }

    private fun saveValues() {
        DevSettings.calibRssiAt1m = binding.seekCalib.progress - 90

        // 미터 단위 저장 (progress + 1 = 거리 m)
        var warnM = (binding.seekRssiWarning.progress + 1).toFloat()
        var dangM = (binding.seekRssiDanger.progress  + 1).toFloat()

        // 경고가 위험보다 가까우면 자동 교정 (경고 = 더 멀어야 함)
        if (warnM < dangM) {
            val tmp = warnM; warnM = dangM + 1f; dangM = tmp
        }

        DevSettings.warningDistM = warnM
        DevSettings.dangerDistM  = dangM
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
