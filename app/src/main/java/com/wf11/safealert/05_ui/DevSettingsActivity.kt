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
        // 판정 파라미터 (고급) — 저장값 표시(미설정 시 기본값 = 기존 하드코딩값)
        //   소수 항목은 5단계 민감도 Spinner: 저장값과 가장 가까운 프리셋을 선택
        binding.spTtcThreshold.setSelection(presetIndex(ttcPresets, DevSettings.ttcThresholdSec))
        binding.spMinApproachVel.setSelection(presetIndex(approachVelPresets, DevSettings.minApproachVelDbm))
        binding.etTimegateMs.setText(DevSettings.timeGateMs.toString())
        binding.etTimegateCornering.setText(DevSettings.corneringTimeGateMs.toString())
        binding.spTimegateVel.setSelection(presetIndex(gateVelPresets, DevSettings.timeGateVelDbm))
        binding.etWarningCooldown.setText(DevSettings.warningCooldownMs.toString())
        binding.etDangerCooldown.setText(DevSettings.dangerCooldownMs.toString())
        binding.etHysteresis.setText(DevSettings.hysteresisDbm.toString())
        binding.etDepartingHysteresis.setText(DevSettings.departingHysteresisDbm.toString())
        binding.etRecedingClearMs.setText(DevSettings.recedingClearMs.toString())
        binding.etRecedingDrop.setText(DevSettings.recedingDbmDrop.toString())
        binding.spClosingFactor.setSelection(presetIndex(closingPresets, DevSettings.closingKmhToDbms))
        binding.spHeadonRatio.setSelection(presetIndex(headOnPresets, DevSettings.collisionHeadOnRatio))
        binding.spSideRatio.setSelection(presetIndex(sidePresets, DevSettings.collisionSideRatio))
        binding.spEmaRise.setSelection(presetIndex(emaRisePresets, DevSettings.emaAlphaRise))
        binding.spEmaFall.setSelection(presetIndex(emaFallPresets, DevSettings.emaAlphaFall))
        binding.spEmaDboost.setSelection(presetIndex(emaDBoostPresets, DevSettings.emaAlphaDBoost))
        binding.etPreserveBand.setText(DevSettings.filterPreserveBandDb.toString())
        binding.etWakeRssi.setText(DevSettings.wakeRssiDbm.toString())
        binding.etStaleMs.setText(DevSettings.signalStaleMs.toString())
        binding.etFbThrottle.setText(DevSettings.firebaseThrottleMs.toString())
        binding.etSpeedPush.setText(DevSettings.speedPushIntervalMs.toString())
        // [v1.1.7 #2] 후진(전진) 대비 감지 — Switch + 슬라이더(추천값 기본)
        binding.switchReversePrep.isChecked      = DevSettings.reversePrepEnabled
        binding.seekReverseRise.progress         = DevSettings.reverseRiseDbm - 2
        binding.tvReverseRiseVal.text            = "${DevSettings.reverseRiseDbm} dB"
        binding.seekReverseWindow.progress       = ((DevSettings.reverseWindowMs - 500L) / 100L).toInt()
        binding.tvReverseWindowVal.text          = "${DevSettings.reverseWindowMs} ms"
        binding.seekReverseStabletol.progress    = DevSettings.reverseStableTolDb
        binding.tvReverseStabletolVal.text       = "${DevSettings.reverseStableTolDb} dB"
        binding.seekReverseHold.progress         = ((DevSettings.reversePrepHoldMs - 1000L) / 1000L).toInt()
        binding.tvReverseHoldVal.text            = "${DevSettings.reversePrepHoldMs} ms"
        updateDebugBadge()
        updateSimRssiEnabled()
        updateReversePrepEnabled()
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
        // [v1.1.7 #2] 후진(전진) 대비 슬라이더 — progress↔실제값 변환 후 라벨 갱신
        binding.seekReverseRise.setOnSeekBarChangeListener(seekListener { v ->
            binding.tvReverseRiseVal.text = "${v + 2} dB"
        })
        binding.seekReverseWindow.setOnSeekBarChangeListener(seekListener { v ->
            binding.tvReverseWindowVal.text = "${v * 100 + 500} ms"
        })
        binding.seekReverseStabletol.setOnSeekBarChangeListener(seekListener { v ->
            binding.tvReverseStabletolVal.text = "$v dB"
        })
        binding.seekReverseHold.setOnSeekBarChangeListener(seekListener { v ->
            binding.tvReverseHoldVal.text = "${v * 1000 + 1000} ms"
        })
        binding.switchReversePrep.setOnCheckedChangeListener { _, _ -> updateReversePrepEnabled() }
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
        // 판정 파라미터 (고급) — Spinner 는 프리셋값 직저장, EditText 는 파싱 실패 시 기존값 유지.
        //   범위 제한(clamp)은 DevSettings setter 담당
        DevSettings.ttcThresholdSec        = ttcPresets[binding.spTtcThreshold.selectedItemPosition]
        DevSettings.minApproachVelDbm      = approachVelPresets[binding.spMinApproachVel.selectedItemPosition]
        DevSettings.timeGateMs             = binding.etTimegateMs.text.toString().toLongOrNull() ?: DevSettings.timeGateMs
        DevSettings.corneringTimeGateMs    = binding.etTimegateCornering.text.toString().toLongOrNull() ?: DevSettings.corneringTimeGateMs
        DevSettings.timeGateVelDbm         = gateVelPresets[binding.spTimegateVel.selectedItemPosition]
        DevSettings.warningCooldownMs      = binding.etWarningCooldown.text.toString().toLongOrNull() ?: DevSettings.warningCooldownMs
        DevSettings.dangerCooldownMs       = binding.etDangerCooldown.text.toString().toLongOrNull() ?: DevSettings.dangerCooldownMs
        DevSettings.hysteresisDbm          = binding.etHysteresis.text.toString().toIntOrNull() ?: DevSettings.hysteresisDbm
        DevSettings.departingHysteresisDbm = binding.etDepartingHysteresis.text.toString().toIntOrNull() ?: DevSettings.departingHysteresisDbm
        DevSettings.recedingClearMs        = binding.etRecedingClearMs.text.toString().toLongOrNull() ?: DevSettings.recedingClearMs
        DevSettings.recedingDbmDrop        = binding.etRecedingDrop.text.toString().toIntOrNull() ?: DevSettings.recedingDbmDrop
        DevSettings.closingKmhToDbms       = closingPresets[binding.spClosingFactor.selectedItemPosition]
        DevSettings.collisionHeadOnRatio   = headOnPresets[binding.spHeadonRatio.selectedItemPosition]
        DevSettings.collisionSideRatio     = sidePresets[binding.spSideRatio.selectedItemPosition]
        DevSettings.emaAlphaRise           = emaRisePresets[binding.spEmaRise.selectedItemPosition]
        DevSettings.emaAlphaFall           = emaFallPresets[binding.spEmaFall.selectedItemPosition]
        DevSettings.emaAlphaDBoost         = emaDBoostPresets[binding.spEmaDboost.selectedItemPosition]
        DevSettings.filterPreserveBandDb   = binding.etPreserveBand.text.toString().toIntOrNull() ?: DevSettings.filterPreserveBandDb
        DevSettings.wakeRssiDbm            = binding.etWakeRssi.text.toString().toIntOrNull() ?: DevSettings.wakeRssiDbm
        DevSettings.signalStaleMs          = binding.etStaleMs.text.toString().toLongOrNull() ?: DevSettings.signalStaleMs
        DevSettings.firebaseThrottleMs     = binding.etFbThrottle.text.toString().toLongOrNull() ?: DevSettings.firebaseThrottleMs
        DevSettings.speedPushIntervalMs    = binding.etSpeedPush.text.toString().toLongOrNull() ?: DevSettings.speedPushIntervalMs
        // [v1.1.7 #2] 후진(전진) 대비 — progress→실제값(setter 가 범위 clamp)
        DevSettings.reversePrepEnabled     = binding.switchReversePrep.isChecked
        DevSettings.reverseRiseDbm         = binding.seekReverseRise.progress + 2
        DevSettings.reverseWindowMs        = (binding.seekReverseWindow.progress * 100L + 500L)
        DevSettings.reverseStableTolDb     = binding.seekReverseStabletol.progress
        DevSettings.reversePrepHoldMs      = (binding.seekReverseHold.progress * 1000L + 1000L)
        loadValues()   // 저장 직후 재로드 — clamp 적용된 실제 저장값을 입력란에 반영

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

    // [v1.1.7 #2] 후진 대비 스위치 OFF → 하위 슬라이더 4종 비활성·흐리게
    private fun updateReversePrepEnabled() {
        val enabled = binding.switchReversePrep.isChecked
        val a = if (enabled) 1f else 0.4f
        for (sb in arrayOf(binding.seekReverseRise, binding.seekReverseWindow,
                           binding.seekReverseStabletol, binding.seekReverseHold)) {
            sb.isEnabled = enabled
            sb.alpha = a
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // Spinner 인덱스 헬퍼
    private val scanPeriodValues = longArrayOf(1000, 2000, 3000, 5000)
    private val advertiseValues  = intArrayOf(100, 200, 500, 1000)
    private val vibWarningValues = longArrayOf(300, 500, 1000)
    private val vibCountValues   = intArrayOf(1, 3, 5)

    // [v1.1.7 #3] coerceAtLeast 버그 수정: indexOfFirst 가 유효한 낮은 인덱스(0·1)를 반환해도
    //   기본 인덱스로 강제 승격돼, 빠른 스캔(1000ms·idx0)·짧은 진동 선택이 느린 값으로 되돌아가던
    //   문제. '못 찾음(-1)'일 때만 기본 인덱스로 폴백하도록 교정.
    private fun scanPeriodIndex(v: Long)  = scanPeriodValues.indexOfFirst { it == v }.let { if (it < 0) 0 else it }
    private fun advertiseIndex(v: Int)    = advertiseValues.indexOfFirst { it == v }.let { if (it < 0) 1 else it }
    private fun vibWarningIndex(v: Long)  = vibWarningValues.indexOfFirst { it == v }.let { if (it < 0) 1 else it }
    private fun vibCountIndex(v: Int)     = vibCountValues.indexOfFirst { it == v }.let { if (it < 0) 1 else it }

    // 판정 파라미터 소수 항목 — [v1.1.7 #4] 5단계→9단계 민감도 프리셋 (arrays.xml 라벨과 순서 일치).
    //   중심 [4]=보통=기본값. 각 배열 index4 는 기존 5단계의 기본값([2])과 동일 → 회귀 없음.
    private val ttcPresets         = doubleArrayOf(5.0, 4.5, 4.0, 3.5, 3.0, 2.5, 2.0, 1.5, 1.0)
    private val approachVelPresets = doubleArrayOf(0.2, 0.25, 0.3, 0.4, 0.5, 0.7, 1.0, 1.25, 1.5)
    private val gateVelPresets     = doubleArrayOf(0.2, 0.25, 0.3, 0.4, 0.5, 0.7, 1.0, 1.25, 1.5)
    private val closingPresets     = doubleArrayOf(0.2, 0.3, 0.35, 0.42, 0.5, 0.65, 0.8, 1.0, 1.2)
    private val headOnPresets      = doubleArrayOf(0.4, 0.45, 0.5, 0.55, 0.6, 0.65, 0.7, 0.75, 0.8)
    private val sidePresets        = doubleArrayOf(0.1, 0.15, 0.2, 0.25, 0.3, 0.35, 0.4, 0.45, 0.5)
    private val emaRisePresets     = doubleArrayOf(0.6, 0.52, 0.45, 0.37, 0.3, 0.25, 0.2, 0.15, 0.1)
    private val emaFallPresets     = doubleArrayOf(0.2, 0.15, 0.1, 0.07, 0.05, 0.04, 0.03, 0.02, 0.01)
    private val emaDBoostPresets   = doubleArrayOf(0.7, 0.62, 0.55, 0.47, 0.4, 0.32, 0.25, 0.17, 0.1)

    // 저장값과 가장 가까운 프리셋 단계 선택 (프리셋 외 값이 저장돼 있어도 안전). 폴백=중심 index4.
    private fun presetIndex(presets: DoubleArray, v: Double) =
        presets.indices.minByOrNull { kotlin.math.abs(presets[it] - v) } ?: 4

    private fun seekListener(onChange: (Int) -> Unit) = object : android.widget.SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: android.widget.SeekBar, v: Int, b: Boolean) = onChange(v)
        override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
        override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
    }
}
