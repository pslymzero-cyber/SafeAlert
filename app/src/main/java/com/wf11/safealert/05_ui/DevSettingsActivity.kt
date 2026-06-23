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
    // [v1.1.15] 저장 버튼 제거에 따른 EditText 지연 확정용 — 포커스 아웃/onPause 시 일괄 commit
    private val editCommitters = mutableListOf<() -> Unit>()

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
        // [v1.1.15] 저장 버튼 제거 — 모든 위젯 변경을 즉시 DevSettings 에 기록(라이브 반영).
        //   BleService 가 SharedPreferences 변경을 구독(registerOnChange→applyLiveSettings)하므로,
        //   슬라이더/스위치/스피너는 만지는 즉시, EditText 는 포커스가 빠질 때(또는 화면 이탈 시
        //   onPause 안전망) 반영된다. 값 변환식은 이전 saveValues 와 동일. 화면 종료는 기기 뒤로가기.

        // ── SeekBar : 라벨 갱신 + 즉시 기록 ──────────────────────────────
        binding.seekAlarmVolume.setOnSeekBarChangeListener(seekListener { v ->
            binding.tvAlarmVolumeVal.text = "$v%"
            DevSettings.alarmVolume = v
        })
        binding.seekSimRssi.setOnSeekBarChangeListener(seekListener { v ->
            binding.tvSimRssiVal.text = "${v - 100} dBm"
            DevSettings.simulatedRssi = v - 100
        })
        // [v1.1.7 #2] 후진(전진) 대비 슬라이더 — progress↔실제값 변환 후 라벨 갱신 + 기록
        binding.seekReverseRise.setOnSeekBarChangeListener(seekListener { v ->
            binding.tvReverseRiseVal.text = "${v + 2} dB"
            DevSettings.reverseRiseDbm = v + 2
        })
        binding.seekReverseWindow.setOnSeekBarChangeListener(seekListener { v ->
            binding.tvReverseWindowVal.text = "${v * 100 + 500} ms"
            DevSettings.reverseWindowMs = v * 100L + 500L
        })
        binding.seekReverseStabletol.setOnSeekBarChangeListener(seekListener { v ->
            binding.tvReverseStabletolVal.text = "$v dB"
            DevSettings.reverseStableTolDb = v
        })
        binding.seekReverseHold.setOnSeekBarChangeListener(seekListener { v ->
            binding.tvReverseHoldVal.text = "${v * 1000 + 1000} ms"
            DevSettings.reversePrepHoldMs = v * 1000L + 1000L
        })

        // ── Switch : 즉시 기록 (+ 의존 UI 갱신) ─────────────────────────
        binding.switchWalkerDetectsWalker.setOnCheckedChangeListener { _, c -> DevSettings.walkerDetectsWalker = c }
        binding.switchDeviceTx.setOnCheckedChangeListener { _, c -> DevSettings.deviceTx = c }
        binding.switchDeviceRx.setOnCheckedChangeListener { _, c -> DevSettings.deviceRx = c }
        binding.switchWalkerTx.setOnCheckedChangeListener { _, c -> DevSettings.walkerTx = c }
        binding.switchWalkerRx.setOnCheckedChangeListener { _, c -> DevSettings.walkerRx = c }
        binding.switchVibration.setOnCheckedChangeListener { _, c -> DevSettings.vibrationEnabled = c }
        binding.switchSound.setOnCheckedChangeListener { _, c -> DevSettings.soundEnabled = c }
        binding.switchAutoSave.setOnCheckedChangeListener { _, c -> DevSettings.autoSaveAlerts = c }
        binding.switchVerbose.setOnCheckedChangeListener { _, c -> DevSettings.logVerbose = c }
        binding.switchDebug.setOnCheckedChangeListener { _, c ->
            DevSettings.debugMode = c; updateDebugBadge(); updateSimRssiEnabled()
        }
        binding.switchReversePrep.setOnCheckedChangeListener { _, c ->
            DevSettings.reversePrepEnabled = c; updateReversePrepEnabled()
        }

        // ── Spinner : 선택 즉시 기록 ────────────────────────────────────
        //   scanPeriod·advertise 는 적용 시 스캐너/광고 재구성을 유발 → 값이 실제로 바뀔 때만 기록
        //   (화면 진입 시 복원 선택으로 인한 불필요한 스캔/광고 재시작 방지). 그 외는 라이브 read 라 무해.
        bindSpinner(binding.spinnerScanPeriod) { val nv = scanPeriodValues[it]; if (DevSettings.scanPeriodMs != nv) DevSettings.scanPeriodMs = nv }
        bindSpinner(binding.spinnerAdvertise)  { val nv = advertiseValues[it];  if (DevSettings.advertiseInterval != nv) DevSettings.advertiseInterval = nv }
        bindSpinner(binding.spinnerVibWarning) { DevSettings.vibrationWarningMs = vibWarningValues[it] }
        bindSpinner(binding.spinnerVibCount)   { DevSettings.vibrationDangerCount = vibCountValues[it] }
        bindSpinner(binding.spTtcThreshold)    { DevSettings.ttcThresholdSec = ttcPresets[it] }
        bindSpinner(binding.spMinApproachVel)  { DevSettings.minApproachVelDbm = approachVelPresets[it] }
        bindSpinner(binding.spTimegateVel)     { DevSettings.timeGateVelDbm = gateVelPresets[it] }
        bindSpinner(binding.spClosingFactor)   { DevSettings.closingKmhToDbms = closingPresets[it] }
        bindSpinner(binding.spHeadonRatio)     { DevSettings.collisionHeadOnRatio = headOnPresets[it] }
        bindSpinner(binding.spSideRatio)       { DevSettings.collisionSideRatio = sidePresets[it] }
        bindSpinner(binding.spEmaRise)         { DevSettings.emaAlphaRise = emaRisePresets[it] }
        bindSpinner(binding.spEmaFall)         { DevSettings.emaAlphaFall = emaFallPresets[it] }
        bindSpinner(binding.spEmaDboost)       { DevSettings.emaAlphaDBoost = emaDBoostPresets[it] }

        // ── EditText : 포커스 아웃 시 확정(+clamp 반영) · onPause 안전망(editCommitters) ──
        run {
            val et = binding.etFirebaseRoot
            val commit = { DevSettings.firebaseRoot = et.text.toString().trim().ifEmpty { "wf11" } }
            editCommitters += commit
            et.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) { commit(); et.setText(DevSettings.firebaseRoot) } }
        }
        bindLongField(binding.etTimegateMs,          { DevSettings.timeGateMs },             { DevSettings.timeGateMs = it })
        bindLongField(binding.etTimegateCornering,   { DevSettings.corneringTimeGateMs },    { DevSettings.corneringTimeGateMs = it })
        bindLongField(binding.etWarningCooldown,     { DevSettings.warningCooldownMs },      { DevSettings.warningCooldownMs = it })
        bindLongField(binding.etDangerCooldown,      { DevSettings.dangerCooldownMs },       { DevSettings.dangerCooldownMs = it })
        bindIntField (binding.etHysteresis,          { DevSettings.hysteresisDbm },          { DevSettings.hysteresisDbm = it })
        bindIntField (binding.etDepartingHysteresis, { DevSettings.departingHysteresisDbm }, { DevSettings.departingHysteresisDbm = it })
        bindLongField(binding.etRecedingClearMs,     { DevSettings.recedingClearMs },        { DevSettings.recedingClearMs = it })
        bindIntField (binding.etRecedingDrop,        { DevSettings.recedingDbmDrop },        { DevSettings.recedingDbmDrop = it })
        bindIntField (binding.etPreserveBand,        { DevSettings.filterPreserveBandDb },   { DevSettings.filterPreserveBandDb = it })
        bindIntField (binding.etWakeRssi,            { DevSettings.wakeRssiDbm },            { DevSettings.wakeRssiDbm = it })
        bindLongField(binding.etStaleMs,             { DevSettings.signalStaleMs },          { DevSettings.signalStaleMs = it })
        bindLongField(binding.etFbThrottle,          { DevSettings.firebaseThrottleMs },     { DevSettings.firebaseThrottleMs = it })
        bindLongField(binding.etSpeedPush,           { DevSettings.speedPushIntervalMs },    { DevSettings.speedPushIntervalMs = it })

        binding.btnReset.setOnClickListener { resetValues() }

        // 앱 정보 — 버전 표시 + 오픈소스 라이선스 이동
        binding.tvAppVersion.text = "SafeAlert v${BuildConfig.VERSION_NAME}"
        binding.btnOpenSourceLicenses.setOnClickListener {
            startActivity(Intent(this, OpenSourceLicensesActivity::class.java))
        }
        // 비콘 관리는 메인 화면으로 이동됨
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

    // [v1.1.15] 포커스 아웃을 거치지 않고 화면을 떠나는 경우의 안전망 — 입력란 전체 확정
    override fun onPause() {
        super.onPause()
        editCommitters.forEach { it() }
    }

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

    // [v1.1.15] 즉시 반영 바인딩 헬퍼 — 새 import 없이 정규화된 위젯 타입 사용
    private fun bindSpinner(sp: android.widget.Spinner, onSelect: (Int) -> Unit) {
        sp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) = onSelect(position)
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    // EditText 는 입력 도중 매 글자 반영 시 빈칸/부분입력 오염이 생기므로 포커스 아웃 시 확정.
    //   파싱 실패(빈칸 등) → 기존값 유지 후 setter 가 clamp 한 실제값을 다시 표시. onPause 안전망 등록.
    private fun bindLongField(et: android.widget.EditText, getter: () -> Long, setter: (Long) -> Unit) {
        val commit = { setter(et.text.toString().toLongOrNull() ?: getter()) }
        editCommitters += commit
        et.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) { commit(); et.setText(getter().toString()) } }
    }

    private fun bindIntField(et: android.widget.EditText, getter: () -> Int, setter: (Int) -> Unit) {
        val commit = { setter(et.text.toString().toIntOrNull() ?: getter()) }
        editCommitters += commit
        et.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) { commit(); et.setText(getter().toString()) } }
    }
}
