package com.wf11.safealert.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.uwb.UwbManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.wf11.safealert.BuildConfig
import com.wf11.safealert.service.BleService
import com.wf11.safealert.utils.BeaconRegistry
import com.wf11.safealert.utils.DevSettings
import com.wf11.safealert.utils.UwbRanger
import com.wf11.safealert.databinding.ActivityDevSettingsBinding

class DevSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDevSettingsBinding
    // [v1.1.15] 저장 버튼 제거에 따른 EditText 지연 확정용 — 포커스 아웃/onPause 시 일괄 commit
    private val editCommitters = mutableListOf<() -> Unit>()

    // [v1.1.26 D] '앱 정보' 빠른 7회 터치로 숨김 고급 옵션 노출용 카운터
    private var appInfoTapCount = 0
    private var lastAppInfoTapMs = 0L

    // [v1.0.42] 거리 교정(calibRssiAt1m/pathLossExp)·거리 교정 마법사 전면 제거.
    //   거리 추정은 칼만 필터(RSSI)만으로 수행 — RSSI→거리 변환·교정 UI/핸들러 모두 폐지.
    //   (rssiToDistance / updateCalibSummary / updatePathLossLabel / showCalibrateWizard /
    //    updateCalibLabel 및 seekCalib·교정 마법사 버튼 핸들러 삭제됨.)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDevSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // (v1.1.63) 테마 NoActionBar → supportActionBar 는 null(no-op). 헤더는 레이아웃 SA.TopBar 가 담당.
        supportActionBar?.apply { title = "개발자 설정"; setDisplayHomeAsUpEnabled(true) }
        loadValues()
        setupListeners()
        setupAccordion()
        updateSectionSummaries()
    }

    private fun loadValues() {
        // (v1.1.63) 경보 볼륨은 BLE 감지 설정 [경보 기본]으로 이동(50~100%).
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
        binding.etEmaWarmup.setText(DevSettings.emaWarmupPushes.toString())
        binding.etPreserveBand.setText(DevSettings.filterPreserveBandDb.toString())
        binding.etWakeRssi.setText(DevSettings.wakeRssiDbm.toString())
        binding.etStaleMs.setText(DevSettings.signalStaleMs.toString())
        binding.etFbThrottle.setText(DevSettings.firebaseThrottleMs.toString())
        binding.etSpeedPush.setText(DevSettings.speedPushIntervalMs.toString())
        // [v1.1.24] 역할별 조기경보 오프셋 — SeekBar(0~15) 값 직접 매핑
        binding.seekWalkerEquipBias.progress = DevSettings.walkerVsEquipBiasDb
        binding.tvWalkerEquipBiasVal.text    = "${DevSettings.walkerVsEquipBiasDb} dB"
        binding.seekWalkerEpjBias.progress   = DevSettings.walkerVsEpjBiasDb
        binding.tvWalkerEpjBiasVal.text      = "${DevSettings.walkerVsEpjBiasDb} dB"
        binding.seekEquipEquipBias.progress  = DevSettings.equipVsEquipBiasDb
        binding.tvEquipEquipBiasVal.text     = "${DevSettings.equipVsEquipBiasDb} dB"
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
        // (v1.1.38 B·C) UWB 강제 스위치 상태 복원 + 진단 라인 초기 갱신
        binding.swUwbForce.isChecked = DevSettings.uwbForce
        refreshUwbDiag()
        // (v1.1.63) [협력·교환] — BLE 감지 설정에서 이관: 상호 RSSI 교환 + 협력 수용 완화(0~20 dB)
        binding.swReciprocalRssi.isChecked = DevSettings.reciprocalRssiEnabled
        binding.seekCoopSlack.progress = DevSettings.coopSlackDb.coerceIn(0, 20)
        updateCoopSlackLabel()
        // (v1.1.63) [UWB 고급] — BLE 감지 설정에서 이관: 위험 승격(v1.1.32)·접근속도 승격·이탈 해제(v1.1.34).
        //   기본 OFF(옵트인) 유지, UWB 미지원 기기는 비활성.
        binding.swUwbPromote.isChecked    = DevSettings.uwbPromoteEnabled
        binding.swUwbVelPromote.isChecked = DevSettings.uwbVelPromoteEnabled
        binding.swUwbVelRelease.isChecked = DevSettings.uwbVelReleaseEnabled
        if (!UwbRanger.isHardwareSupported(this)) {
            binding.swUwbPromote.isEnabled    = false
            binding.swUwbVelPromote.isEnabled = false
            binding.swUwbVelRelease.isEnabled = false
        }
        // [v1.1.55→v1.1.63] Level 2 에코 자동보정(스위치·튜너블 3종·진단·초기화)은 BLE 감지 설정으로 이관.
    }

    private fun setupListeners() {
        // [v1.1.15] 저장 버튼 제거 — 모든 위젯 변경을 즉시 DevSettings 에 기록(라이브 반영).
        //   BleService 가 SharedPreferences 변경을 구독(registerOnChange→applyLiveSettings)하므로,
        //   슬라이더/스위치/스피너는 만지는 즉시, EditText 는 포커스가 빠질 때(또는 화면 이탈 시
        //   onPause 안전망) 반영된다. 값 변환식은 이전 saveValues 와 동일. 화면 종료는 기기 뒤로가기.

        // ── SeekBar : 라벨 갱신 + 즉시 기록 ──────────────────────────────
        //   (v1.1.63) 경보 볼륨 슬라이더는 BLE 감지 설정으로 이동.
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
        // [v1.1.24] 역할별 조기경보 오프셋 — 즉시 라이브 반영(0~15 직접 매핑)
        binding.seekWalkerEquipBias.setOnSeekBarChangeListener(seekListener { v ->
            binding.tvWalkerEquipBiasVal.text = "$v dB"
            DevSettings.walkerVsEquipBiasDb = v
            updateSectionSummaries()
        })
        binding.seekWalkerEpjBias.setOnSeekBarChangeListener(seekListener { v ->
            binding.tvWalkerEpjBiasVal.text = "$v dB"
            DevSettings.walkerVsEpjBiasDb = v
            updateSectionSummaries()
        })
        binding.seekEquipEquipBias.setOnSeekBarChangeListener(seekListener { v ->
            binding.tvEquipEquipBiasVal.text = "$v dB"
            DevSettings.equipVsEquipBiasDb = v
            updateSectionSummaries()
        })

        // ── Switch : 즉시 기록 (+ 의존 UI 갱신) ─────────────────────────
        binding.switchWalkerDetectsWalker.setOnCheckedChangeListener { _, c -> DevSettings.walkerDetectsWalker = c }
        binding.switchDeviceTx.setOnCheckedChangeListener { _, c -> DevSettings.deviceTx = c }
        binding.switchDeviceRx.setOnCheckedChangeListener { _, c -> DevSettings.deviceRx = c }
        binding.switchWalkerTx.setOnCheckedChangeListener { _, c -> DevSettings.walkerTx = c }
        binding.switchWalkerRx.setOnCheckedChangeListener { _, c -> DevSettings.walkerRx = c }
        binding.switchVibration.setOnCheckedChangeListener { _, c -> DevSettings.vibrationEnabled = c; updateSectionSummaries() }
        binding.switchSound.setOnCheckedChangeListener { _, c -> DevSettings.soundEnabled = c; updateSectionSummaries() }
        binding.switchAutoSave.setOnCheckedChangeListener { _, c -> DevSettings.autoSaveAlerts = c }
        binding.switchVerbose.setOnCheckedChangeListener { _, c -> DevSettings.logVerbose = c }
        binding.switchDebug.setOnCheckedChangeListener { _, c ->
            DevSettings.debugMode = c; updateDebugBadge(); updateSimRssiEnabled()
        }
        binding.switchReversePrep.setOnCheckedChangeListener { _, c ->
            DevSettings.reversePrepEnabled = c; updateReversePrepEnabled()
        }
        // (v1.1.38 B) UWB 강제 활성화 — 즉시 기록 + 서비스 재적용 넛지 + 진단 즉시 갱신
        binding.swUwbForce.setOnCheckedChangeListener { _, c ->
            DevSettings.uwbForce = c
            nudgeUwbReapply()
            refreshUwbDiag()
            updateSectionSummaries()
        }

        // ── Spinner : 선택 즉시 기록 ────────────────────────────────────
        //   scanPeriod·advertise 는 적용 시 스캐너/광고 재구성을 유발 → 값이 실제로 바뀔 때만 기록
        //   (화면 진입 시 복원 선택으로 인한 불필요한 스캔/광고 재시작 방지). 그 외는 라이브 read 라 무해.
        bindSpinner(binding.spinnerScanPeriod) { val nv = scanPeriodValues[it]; if (DevSettings.scanPeriodMs != nv) DevSettings.scanPeriodMs = nv; updateSectionSummaries() }
        bindSpinner(binding.spinnerAdvertise)  { val nv = advertiseValues[it];  if (DevSettings.advertiseInterval != nv) DevSettings.advertiseInterval = nv; updateSectionSummaries() }
        bindSpinner(binding.spinnerVibWarning) { DevSettings.vibrationWarningMs = vibWarningValues[it] }
        bindSpinner(binding.spinnerVibCount)   { DevSettings.vibrationDangerCount = vibCountValues[it] }
        bindSpinner(binding.spTtcThreshold)    { DevSettings.ttcThresholdSec = ttcPresets[it]; updateSectionSummaries() }
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
        bindIntField (binding.etEmaWarmup,           { DevSettings.emaWarmupPushes },        { DevSettings.emaWarmupPushes = it })
        bindIntField (binding.etPreserveBand,        { DevSettings.filterPreserveBandDb },   { DevSettings.filterPreserveBandDb = it })
        bindIntField (binding.etWakeRssi,            { DevSettings.wakeRssiDbm },            { DevSettings.wakeRssiDbm = it })
        bindLongField(binding.etStaleMs,             { DevSettings.signalStaleMs },          { DevSettings.signalStaleMs = it })
        bindLongField(binding.etFbThrottle,          { DevSettings.firebaseThrottleMs },     { DevSettings.firebaseThrottleMs = it })
        bindLongField(binding.etSpeedPush,           { DevSettings.speedPushIntervalMs },    { DevSettings.speedPushIntervalMs = it })

        // [v1.1.55→v1.1.63] Level 2 에코 자동보정(스위치·튜너블·진단·통계 초기화)은 BLE 감지 설정으로 이관.

        // (v1.1.63) [협력·교환] — BLE 감지 설정에서 이관. 값 변환·기록 로직은 원본 그대로(즉시 라이브 반영).
        binding.swReciprocalRssi.setOnCheckedChangeListener { _, c ->
            DevSettings.reciprocalRssiEnabled = c
            updateSectionSummaries()
        }
        binding.seekCoopSlack.setOnSeekBarChangeListener(seekListener { v ->
            updateCoopSlackLabel()
            DevSettings.coopSlackDb = v
            updateSectionSummaries()
        })
        // (v1.1.63) [UWB 고급] — BLE 감지 설정에서 이관. 스위치 3종 즉시 기록(BleService 가 라이브 read).
        binding.swUwbPromote.setOnCheckedChangeListener    { _, c -> DevSettings.uwbPromoteEnabled    = c; updateSectionSummaries() }
        binding.swUwbVelPromote.setOnCheckedChangeListener { _, c -> DevSettings.uwbVelPromoteEnabled = c; updateSectionSummaries() }
        binding.swUwbVelRelease.setOnCheckedChangeListener { _, c -> DevSettings.uwbVelReleaseEnabled = c; updateSectionSummaries() }

        binding.btnReset.setOnClickListener { resetValues() }

        // 앱 정보 — 버전 표시 + 오픈소스 라이선스 이동
        binding.tvAppVersion.text = "SafeAlert v${BuildConfig.VERSION_NAME}"
        binding.btnOpenSourceLicenses.setOnClickListener {
            startActivity(Intent(this, OpenSourceLicensesActivity::class.java))
        }
        // [v1.1.26 D] 앱 버전 빠른 7회 터치 → 숨김 고급 옵션(Firebase·디버그·후진대비·초기화) 노출
        binding.tvAppVersion.setOnClickListener {
            val now = System.currentTimeMillis()
            appInfoTapCount = if (now - lastAppInfoTapMs <= 2000L) appInfoTapCount + 1 else 1
            lastAppInfoTapMs = now
            if (appInfoTapCount >= 7) {
                appInfoTapCount = 0
                if (binding.hiddenAdvancedGroup.visibility != View.VISIBLE) {
                    binding.hiddenAdvancedGroup.visibility = View.VISIBLE
                    Toast.makeText(this, "고급 옵션이 표시됩니다", Toast.LENGTH_SHORT).show()
                }
            }
        }
        // 비콘 관리는 메인 화면으로 이동됨
    }

    private fun resetValues() {
        DevSettings.resetToDefault()
        loadValues()
        updateSectionSummaries()   // (v1.1.63) 섹션 헤더 요약도 기본값으로 재표시
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

    // ── (v1.1.38 B·C) UWB 진단·강제 ─────────────────────────────────────
    private val uwbDiagHandler = Handler(Looper.getMainLooper())
    private var uwbSystemAvailable: Boolean? = null   // null=미확인, 시스템 UWB 토글 상태(비동기 조회)
    private val uwbDiagPoller = object : Runnable {
        override fun run() {
            refreshUwbDiag()
            // [v1.1.54→v1.1.63] 에코편차 집계 패널 폴링은 BLE 감지 설정으로 이관(같은 1.2s 주기).
            uwbDiagHandler.postDelayed(this, 1200L)
        }
    }

    // 진단 라인 갱신 — HW/권한은 동기 판정, 시스템 UWB 토글은 suspend 라 코루틴 비동기 조회 후 다음 폴에서 반영
    private fun refreshUwbDiag() {
        val hw = UwbRanger.isHardwareSupported(this)
        val perm = ContextCompat.checkSelfPermission(this, Manifest.permission.UWB_RANGING) ==
                PackageManager.PERMISSION_GRANTED
        if (hw && perm) {
            lifecycleScope.launch {
                uwbSystemAvailable = try {
                    UwbManager.createInstance(this@DevSettingsActivity).isAvailable()
                } catch (e: Exception) { null }
            }
        } else {
            uwbSystemAvailable = if (!hw) false else null
        }
        binding.tvUwbDiag.text = buildUwbDiagText(hw, perm, uwbSystemAvailable)
    }

    private fun buildUwbDiagText(hw: Boolean, perm: Boolean, sys: Boolean?): String {
        fun mk(b: Boolean) = if (b) "✓" else "✗"
        val sysMark = when (sys) { true -> "✓"; false -> "✗"; null -> "…" }
        val active = UwbRanger.liveActive
        val role   = UwbRanger.liveRole
        val sess   = UwbRanger.liveSessionCount
        val line1  = "HW ${mk(hw)}    권한 ${mk(perm)}    시스템 $sysMark"
        val line2  = "세션 ${if (active) "가동" else "정지"} · 역할 $role · 실측 ${sess}대"
        val hint = when {
            !hw          -> "→ 이 기기는 UWB 하드웨어가 없습니다(BLE 신호만 사용)."
            !perm        -> "→ UWB 권한 없음. BLE 설정 화면에서 권한을 허용하세요."
            sys == false -> "→ 기기 UWB가 꺼져 있습니다. 시스템 설정에서 켜세요."
            !active && UwbRanger.liveInitError != null ->
                "→ 초기화 실패: ${UwbRanger.liveInitError} (자동 재시도 중)"
            !active      -> if (DevSettings.uwbForce) "→ 강제 ON. 상대 UWB 기기가 잡히면 세션이 열립니다."
                            else "→ 대기 중. 상대가 근접(시작 게이트 통과)하면 세션이 열립니다."
            else         -> "→ UWB 실측 중 — 목록 거리가 m로 표시됩니다."
        }
        return "$line1\n$line2\n$hint"
    }

    // ── [v1.1.54→55→v1.1.63] 에코편차 집계(상호 RSSI) 진단(fmtDb·refreshEchoDiag)은 BLE 감지 설정으로 이관 ──

    // (v1.1.63) [협력·교환] 협력 수용 완화 라벨 — BLE 감지 설정 원본과 동일 표기
    private fun updateCoopSlackLabel() {
        val v = binding.seekCoopSlack.progress
        binding.tvCoopSlack.text = if (v == 0) "0 dB (완화 없음)" else "+${v} dB"
    }

    // ── (v1.1.63) 아코디언 — 6섹션 전부 기본 접힘(레이아웃 SA.SectionBody visibility=gone), 헤더 탭 토글 ──
    private fun setupAccordion() {
        bindSection(binding.secTxrxHeader,    binding.secTxrxBody,    binding.secTxrxChevron)
        bindSection(binding.secSoundHeader,   binding.secSoundBody,   binding.secSoundChevron)
        bindSection(binding.secParamHeader,   binding.secParamBody,   binding.secParamChevron)
        bindSection(binding.secCoopHeader,    binding.secCoopBody,    binding.secCoopChevron)
        bindSection(binding.secUwbadvHeader,  binding.secUwbadvBody,  binding.secUwbadvChevron)
        bindSection(binding.secAppinfoHeader, binding.secAppinfoBody, binding.secAppinfoChevron)
    }

    // 초기 셰브런 각도는 바디 가시성에 맞추고, 헤더 탭마다 바디 토글 + 셰브런 0(접힘)/180(펼침)
    private fun bindSection(header: View, body: View, chevron: View) {
        chevron.rotation = if (body.visibility == View.VISIBLE) 180f else 0f
        header.setOnClickListener {
            val open = body.visibility != View.VISIBLE
            body.visibility = if (open) View.VISIBLE else View.GONE
            chevron.rotation = if (open) 180f else 0f
        }
    }

    // (v1.1.63) 섹션 헤더 요약값 — 접힌 상태에서도 핵심값이 보이도록. 값 변경 리스너마다 호출.
    //   Spinner 는 setSelection 직후 selectedItemPosition 이 늦게 반영될 수 있어 DevSettings 를 직독.
    private fun updateSectionSummaries() {
        fun onOff(b: Boolean) = if (b) "ON" else "OFF"
        binding.secTxrxSummary.text =
            "스캔 ${DevSettings.scanPeriodMs}ms · 광고 ${DevSettings.advertiseInterval}ms"
        binding.secSoundSummary.text =
            "진동 ${onOff(binding.switchVibration.isChecked)} · 소리 ${onOff(binding.switchSound.isChecked)}"
        binding.secParamSummary.text =
            "TTC ${DevSettings.ttcThresholdSec}s · 오프셋 ${binding.seekWalkerEquipBias.progress}/" +
            "${binding.seekWalkerEpjBias.progress}/${binding.seekEquipEquipBias.progress} dB"
        binding.secCoopSummary.text =
            "상호 RSSI ${onOff(binding.swReciprocalRssi.isChecked)} · 완화 +${binding.seekCoopSlack.progress} dB"
        binding.secUwbadvSummary.text =
            "강제 ${onOff(binding.swUwbForce.isChecked)} · 승격 ${onOff(binding.swUwbPromote.isChecked)}/" +
            "${onOff(binding.swUwbVelPromote.isChecked)}/${onOff(binding.swUwbVelRelease.isChecked)}"
        binding.secAppinfoSummary.text = "v${BuildConfig.VERSION_NAME}"
    }

    // 권한 부여·강제 토글 직후, 서비스에 UWB 세션 재평가를 명시 요청(동일값 쓰기는 변경 리스너 미발화)
    private fun nudgeUwbReapply() {
        try {
            startService(Intent(this, BleService::class.java).setAction(BleService.ACTION_REAPPLY_UWB))
        } catch (e: Exception) { /* 서비스 미기동 등 — 다음 스캔 주기에 자연 반영 */ }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // (v1.1.38 C) 화면 표시 중에만 UWB 진단 폴링 — onResume 시작 / onPause 정지(배터리·리소스 절약)
    override fun onResume() {
        super.onResume()
        uwbDiagHandler.removeCallbacks(uwbDiagPoller)
        uwbDiagHandler.post(uwbDiagPoller)
    }

    // [v1.1.15] 포커스 아웃을 거치지 않고 화면을 떠나는 경우의 안전망 — 입력란 전체 확정
    override fun onPause() {
        super.onPause()
        editCommitters.forEach { it() }
        uwbDiagHandler.removeCallbacks(uwbDiagPoller)   // (v1.1.38 C) 진단 폴링 중지
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
    private val emaFallPresets     = doubleArrayOf(0.2, 0.15, 0.12, 0.1, 0.07, 0.05, 0.04, 0.03, 0.02, 0.01)   // (v1.1.56 U4a) 0.12 삽입(새 기본·index2), 10단계 — ema_fall_labels 와 1:1
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
