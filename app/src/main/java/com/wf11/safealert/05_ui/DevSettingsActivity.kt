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

    // [v1.0.42] 거리 교정(calibRssiAt1m/pathLossExp)·거리 교정 마법사 전면 제거.
    //   거리 추정은 칼만 필터(RSSI)만으로 수행 — RSSI→거리 변환·교정 UI/핸들러 모두 폐지.
    //   (rssiToDistance / updateCalibSummary / updatePathLossLabel / showCalibrateWizard /
    //    updateCalibLabel 및 seekCalib·교정 마법사 버튼 핸들러 삭제됨.)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDevSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.apply { title = "⚙ 개발자 설정"; setDisplayHomeAsUpEnabled(true) }
        loadValues()
        setupListeners()
    }

    private fun loadValues() {
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
        binding.tvSimRssiVal.text = "${DevSettings.simulatedRssi} dBm"
        binding.switchVerbose.isChecked = DevSettings.logVerbose
        updateDebugBadge()
        updateSimRssiEnabled()
    }

    private fun setupListeners() {
        binding.seekAlarmVolume.setOnSeekBarChangeListener(seekListener { v ->
            binding.tvAlarmVolumeVal.text = "$v%"
        })
        binding.seekSimRssi.setOnSeekBarChangeListener(seekListener { v ->
            binding.tvSimRssiVal.text = "${v - 100} dBm"
        })
        binding.switchDebug.setOnCheckedChangeListener { _, _ ->
            updateDebugBadge(); updateSimRssiEnabled()
        }
        binding.btnSave.setOnClickListener { saveValues() }
        binding.btnReset.setOnClickListener { resetValues() }

        // 앱 정보 — 버전 표시 + 오픈소스 라이선스 이동
        binding.tvAppVersion.text = "SafeAlert v${BuildConfig.VERSION_NAME}"
        binding.btnOpenSourceLicenses.setOnClickListener {
            startActivity(Intent(this, OpenSourceLicensesActivity::class.java))
        }
        // 비콘 관리는 메인 화면으로 이동됨
    }

    private fun saveValues() {
        DevSettings.alarmVolume          = binding.seekAlarmVolume.progress
        DevSettings.walkerDetectsWalker  = binding.switchWalkerDetectsWalker.isChecked
        DevSettings.deviceTx             = binding.switchDeviceTx.isChecked
        DevSettings.deviceRx             = binding.switchDeviceRx.isChecked
        DevSettings.walkerTx             = binding.switchWalkerTx.isChecked
        DevSettings.walkerRx             = binding.switchWalkerRx.isChecked
        // rssiWarning / rssiDanger 는 BLE 설정 화면의 dBm 슬라이더에서 직접 저장 (여기선 건드리지 않음)
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
