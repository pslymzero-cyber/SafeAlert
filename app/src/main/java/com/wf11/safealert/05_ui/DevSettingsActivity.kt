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
import com.wf11.safealert.service.CalibrationEngine
import com.wf11.safealert.service.DeviceStateRegistry
import com.wf11.safealert.service.LoneWorkerSosSync
import com.wf11.safealert.service.SosMail
import com.wf11.safealert.utils.DevSettings
import com.wf11.safealert.utils.UwbCalibrator
import com.wf11.safealert.utils.UwbRanger
import com.wf11.safealert.databinding.ActivityDevSettingsBinding

class DevSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDevSettingsBinding
    // Deferred EditText commits (there is no save button) — committed in bulk on focus loss/onPause
    private val editCommitters = mutableListOf<() -> Unit>()

    // Counter for revealing hidden advanced options with 7 quick taps on "앱 정보"
    private var appInfoTapCount = 0
    private var lastAppInfoTapMs = 0L


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDevSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // NoActionBar theme → supportActionBar is null (no-op). The layout's SA.TopBar provides the header.
        supportActionBar?.apply { title = "개발자 설정"; setDisplayHomeAsUpEnabled(true) }
        loadValues()
        setupListeners()
        setupAccordion()
        updateSectionSummaries()
    }

    private fun loadValues() {
        // Send/receive mode
        binding.switchWalkerDetectsWalker.isChecked = DevSettings.walkerDetectsWalker
        // Lone-worker protection
        binding.switchLoneWorker.isChecked = DevSettings.lwEnabled
        binding.etLwStillMin.setText(DevSettings.lwStillMin.toString())
        binding.etLwResponseMin.setText(DevSettings.lwResponseMin.toString())
        binding.etLwZoneFallCm.setText(DevSettings.lwZoneFallCm.toString())
        binding.etLwZoneFallG.setText(DevSettings.lwZoneFallG.toString())
        binding.etLwZoneFallDeg.setText(DevSettings.lwZoneFallDeg.toString())
        binding.switchDeviceTx.isChecked = DevSettings.deviceTx
        binding.switchDeviceRx.isChecked = DevSettings.deviceRx
        binding.switchWalkerTx.isChecked = DevSettings.walkerTx
        binding.switchWalkerRx.isChecked = DevSettings.walkerRx
        binding.spinnerScanPeriod.setSelection(scanPeriodIndex(DevSettings.scanPeriodMs))
        binding.spinnerAdvertise.setSelection(advertiseIndex(DevSettings.advertiseInterval))
        // Alerts
        binding.switchVibration.isChecked = DevSettings.vibrationEnabled
        binding.spinnerVibWarning.setSelection(vibWarningIndex(DevSettings.vibrationWarningMs))
        binding.spinnerVibCount.setSelection(vibCountIndex(DevSettings.vibrationDangerCount))
        binding.switchSound.isChecked = DevSettings.soundEnabled
        // Items BLE detection settings also shows — same dev_settings prefs, so the values stay in sync automatically
        binding.seekDevAlarmVolume.progress = DevSettings.alarmVolume.coerceIn(50, 100)
        binding.seekDevWarnRssi.progress    = (-DevSettings.rssiWarning).coerceIn(30, 100)
        binding.seekDevDangRssi.progress    = (-DevSettings.rssiDanger ).coerceIn(30, 100)
        updateDevAlarmLabels()
        binding.swDevEchoAutoCalib.isChecked = DevSettings.echoAutoCalibEnabled
        binding.etDevEchoMinTicks.setText(DevSettings.echoCalMinTicks.toString())
        binding.etDevEchoMaxIqr.setText(DevSettings.echoCalMaxIqrDb.toString())
        binding.etDevEchoClamp.setText(DevSettings.echoCalClampDb.toString())
        // Firebase
        binding.etFirebaseRoot.setText(DevSettings.firebaseRoot)
        binding.etDevSiteCode.setText(DevSettings.siteCode)   // Site code change path
        binding.etSosMailTo.setText(LoneWorkerSosSync.mailTo(this, DevSettings.siteCode))
        binding.etSosMailTo.isEnabled = DevSettings.siteCode.isNotEmpty()
        binding.tvSosMailOff.visibility = if (LoneWorkerSosSync.mailEnabled) View.GONE else View.VISIBLE
        binding.switchAutoSave.isChecked = DevSettings.autoSaveAlerts
        // Debug
        binding.switchDebug.isChecked = DevSettings.debugMode
        binding.seekSimRssi.progress = DevSettings.simulatedRssi + 100
        binding.tvSimRssiVal.text = "${DevSettings.simulatedRssi} dBm"
        binding.switchVerbose.isChecked = DevSettings.logVerbose
        // Judging parameters (advanced) — show stored values (defaults when unset)
        //   Decimal items use a sensitivity-preset Spinner (9 steps; EMA fall 10): the preset closest to the stored value is selected
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
        // Per-role early-warning offset — SeekBar (0~15) value mapped directly
        binding.seekWalkerEquipBias.progress = DevSettings.walkerVsEquipBiasDb
        binding.tvWalkerEquipBiasVal.text    = "${DevSettings.walkerVsEquipBiasDb} dB"
        binding.seekWalkerEpjBias.progress   = DevSettings.walkerVsEpjBiasDb
        binding.tvWalkerEpjBiasVal.text      = "${DevSettings.walkerVsEpjBiasDb} dB"
        binding.seekEquipEquipBias.progress  = DevSettings.equipVsEquipBiasDb
        binding.tvEquipEquipBiasVal.text     = "${DevSettings.equipVsEquipBiasDb} dB"
        // Reverse (forward) prep detection — Switch + sliders (recommended values by default)
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
        // Restore the UWB force switch state + initial refresh of the diagnostic line
        binding.swUwbForce.isChecked = DevSettings.uwbForce
        // UWB measurement sample upload — OFF by default. Disabled without hardware support, since there is no reason to turn it on.
        binding.swUwbProbeUpload.isChecked = DevSettings.uwbProbeUploadEnabled
        refreshUwbDiag()
        // "협력·교환" — mutual RSSI exchange + cooperative acceptance relaxation (0~20 dB)
        binding.swReciprocalRssi.isChecked = DevSettings.reciprocalRssiEnabled
        binding.seekCoopSlack.progress = DevSettings.coopSlackDb.coerceIn(0, 20)
        updateCoopSlackLabel()
        // "UWB 고급" — danger escalation, approach-speed escalation, exit release.
        //   OFF by default (opt-in); disabled on devices without UWB.
        binding.swUwbPromote.isChecked    = DevSettings.uwbPromoteEnabled
        binding.swUwbVelPromote.isChecked = DevSettings.uwbVelPromoteEnabled
        binding.swUwbVelRelease.isChecked = DevSettings.uwbVelReleaseEnabled
        // Distance display mode and UWB judging radius — owned here (BLE detection settings shows them read-only).
        //   0=dBm only / 1=m for UWB only / 2=m for all (non-UWB as back-calculated estimate); radius progress = meters × 2 (0.5m steps).
        binding.rgDevDistMode.check(
            when (DevSettings.distanceDisplayMode) {
                0    -> binding.rbDevDistDbm.id
                1    -> binding.rbDevDistUwbM.id
                else -> binding.rbDevDistAllM.id
            }
        )
        binding.seekDevUwbFkWarn.progress     = (DevSettings.uwbForkliftWarnMeters   * 2).toInt().coerceIn(2, 80)
        binding.seekDevUwbFkDanger.progress   = (DevSettings.uwbForkliftDangerMeters * 2).toInt().coerceIn(1, 60)
        binding.seekDevUwbPairWarn.progress   = (DevSettings.uwbPairWarnMeters       * 2).toInt().coerceIn(2, 40)
        binding.seekDevUwbPairDanger.progress = (DevSettings.uwbPairDangerMeters     * 2).toInt().coerceIn(1, 30)
        updateDevUwbRadiusLabels()
        if (!UwbRanger.isHardwareSupported(this)) {
            binding.swUwbProbeUpload.isEnabled = false
            binding.swUwbPromote.isEnabled    = false
            binding.swUwbVelPromote.isEnabled = false
            binding.swUwbVelRelease.isEnabled = false
            binding.seekDevUwbFkWarn.isEnabled     = false
            binding.seekDevUwbFkDanger.isEnabled   = false
            binding.seekDevUwbPairWarn.isEnabled   = false
            binding.seekDevUwbPairDanger.isEnabled = false
        }
    }

    private fun setupListeners() {
        // No save button — every widget change is written to DevSettings immediately (applied live).
        //   BleService subscribes to SharedPreferences changes (registerOnChange→applyLiveSettings), so sliders/switches/spinners
        //   apply as soon as they are touched, and EditTexts when they lose focus (or via the onPause safety net when leaving
        //   the screen). Leave the screen with the device back button.

        // ── SeekBar: refresh label + write immediately ──────────────────────────────
        binding.seekSimRssi.setOnSeekBarChangeListener(seekListener { v ->
            binding.tvSimRssiVal.text = "${v - 100} dBm"
            DevSettings.simulatedRssi = v - 100
        })
        // Reverse (forward) prep sliders — convert progress↔actual value, then refresh label + write
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
        // Per-role early-warning offset — applied live at once (0~15 mapped directly)
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

        // ── Switch: write immediately (+ refresh dependent UI) ─────────────────────────
        binding.switchWalkerDetectsWalker.setOnCheckedChangeListener { _, c -> DevSettings.walkerDetectsWalker = c }
        binding.switchLoneWorker.setOnCheckedChangeListener { _, c -> DevSettings.lwEnabled = c }
        bindIntField(binding.etLwStillMin,     { DevSettings.lwStillMin },     { DevSettings.lwStillMin = it })
        bindIntField(binding.etLwResponseMin,  { DevSettings.lwResponseMin },  { DevSettings.lwResponseMin = it })
        bindIntField(binding.etLwZoneFallCm,   { DevSettings.lwZoneFallCm },   { DevSettings.lwZoneFallCm = it })
        bindDoubleField(binding.etLwZoneFallG, { DevSettings.lwZoneFallG },    { DevSettings.lwZoneFallG = it })
        bindIntField(binding.etLwZoneFallDeg,  { DevSettings.lwZoneFallDeg },  { DevSettings.lwZoneFallDeg = it })
        binding.switchDeviceTx.setOnCheckedChangeListener { _, c -> DevSettings.deviceTx = c }
        binding.switchDeviceRx.setOnCheckedChangeListener { _, c -> DevSettings.deviceRx = c }
        binding.switchWalkerTx.setOnCheckedChangeListener { _, c -> DevSettings.walkerTx = c }
        binding.switchWalkerRx.setOnCheckedChangeListener { _, c -> DevSettings.walkerRx = c }
        binding.switchVibration.setOnCheckedChangeListener { _, c -> DevSettings.vibrationEnabled = c; updateSectionSummaries() }
        binding.switchSound.setOnCheckedChangeListener { _, c -> DevSettings.soundEnabled = c; updateSectionSummaries() }
        // Source items — BLE detection settings only shows these values, locked
        binding.seekDevAlarmVolume.setOnSeekBarChangeListener(seekListener { v ->
            DevSettings.alarmVolume = v.coerceIn(50, 100); updateDevAlarmLabels()
        })
        binding.seekDevWarnRssi.setOnSeekBarChangeListener(seekListener { v ->
            DevSettings.rssiWarning = -v.coerceIn(30, 100); updateDevAlarmLabels()
        })
        binding.seekDevDangRssi.setOnSeekBarChangeListener(seekListener { v ->
            DevSettings.rssiDanger = -v.coerceIn(30, 100); updateDevAlarmLabels()
        })
        binding.swDevEchoAutoCalib.setOnCheckedChangeListener { _, c -> DevSettings.echoAutoCalibEnabled = c }
        bindIntField(binding.etDevEchoMinTicks, { DevSettings.echoCalMinTicks }, { DevSettings.echoCalMinTicks = it })
        bindIntField(binding.etDevEchoMaxIqr,   { DevSettings.echoCalMaxIqrDb }, { DevSettings.echoCalMaxIqrDb = it })
        bindIntField(binding.etDevEchoClamp,    { DevSettings.echoCalClampDb },  { DevSettings.echoCalClampDb = it })
        binding.btnDevEchoReset.setOnClickListener {
            CalibrationEngine.echoDiffLive.clear()
            // Echo calibration is one global file, independent of site — clear it entirely (including the fb prior cache, re-fetched on next start)
            getSharedPreferences(CalibrationEngine.ECHO_PREFS, MODE_PRIVATE)
                .edit().clear().apply()
            Toast.makeText(this, "에코편차 통계 초기화 완료", Toast.LENGTH_SHORT).show()
        }
        binding.switchAutoSave.setOnCheckedChangeListener { _, c -> DevSettings.autoSaveAlerts = c }
        binding.switchVerbose.setOnCheckedChangeListener { _, c -> DevSettings.logVerbose = c }
        binding.switchDebug.setOnCheckedChangeListener { _, c ->
            DevSettings.debugMode = c; updateDebugBadge(); updateSimRssiEnabled()
        }
        binding.switchReversePrep.setOnCheckedChangeListener { _, c ->
            DevSettings.reversePrepEnabled = c; updateReversePrepEnabled()
        }
        // UWB force enable — write immediately + nudge the service to re-apply + refresh diagnostics at once
        binding.swUwbForce.setOnCheckedChangeListener { _, c ->
            DevSettings.uwbForce = c
            nudgeUwbReapply()
            refreshUwbDiag()
            updateSectionSummaries()
        }

        // ── Spinner: write on selection ────────────────────────────────────
        //   scanPeriod and advertise rebuild the scanner/advertiser when applied → write only when the value actually changes
        //   (avoids needless scan/advertise restarts from the restored selection on screen entry). The rest are read live, so harmless.
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

        // ── EditText: commit on focus loss (+clamp applied) · onPause safety net (editCommitters) ──
        run {
            val et = binding.etFirebaseRoot
            val commit = { DevSettings.firebaseRoot = et.text.toString().trim().ifEmpty { "wf11" } }
            editCommitters += commit
            et.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) { commit(); et.setText(DevSettings.firebaseRoot) } }
        }
        // Site code — saving switches the UwbCalibrator profile. The alert log path alerts/<site>/<date>/ stays the same
        run {
            val et = binding.etDevSiteCode
            val commit: () -> Unit = {
                val before = DevSettings.siteCode
                DevSettings.siteCode = et.text.toString(); UwbCalibrator.applySite()
                // When the site changes, refill the mail field with that site's value (if unchanged, don't wipe what is being typed)
                if (DevSettings.siteCode != before) binding.etSosMailTo.setText(LoneWorkerSosSync.mailTo(this, DevSettings.siteCode))
                binding.etSosMailTo.isEnabled = DevSettings.siteCode.isNotEmpty()
            }
            editCommitters += commit
            et.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) { commit(); et.setText(DevSettings.siteCode) } }
        }
        // SOS mail recipient address — per site. Empty = don't send; an invalid format is not saved
        run {
            val et = binding.etSosMailTo
            val commit: () -> Unit = {
                val sc = DevSettings.siteCode
                val v = et.text.toString().trim()
                // Don't save without a site code or when the value is unchanged (so just opening and leaving doesn't pin the default)
                if (sc.isNotEmpty() && v != LoneWorkerSosSync.mailTo(this, sc)) {
                    if (v.isEmpty() || SosMail.validAddress(v)) LoneWorkerSosSync.setMailTo(this, sc, v)
                    else Toast.makeText(this, "이메일 주소 형식이 맞지 않아 저장하지 않았습니다.", Toast.LENGTH_LONG).show()
                }
            }
            editCommitters += commit
            et.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) { commit(); et.setText(LoneWorkerSosSync.mailTo(this, DevSettings.siteCode)) }
            }
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

        // Level 2 echo auto-calibration: the switch, tunables and stats reset are bound above (source items); the
        //   BLE detection settings shows them locked and owns the diagnostics panel.

        // "협력·교환" (cooperation/exchange) section — values are converted and written immediately (applied live).
        binding.swReciprocalRssi.setOnCheckedChangeListener { _, c ->
            DevSettings.reciprocalRssiEnabled = c
            updateSectionSummaries()
        }
        binding.seekCoopSlack.setOnSeekBarChangeListener(seekListener { v ->
            updateCoopSlackLabel()
            DevSettings.coopSlackDb = v
            updateSectionSummaries()
        })
        // "UWB 고급" (UWB advanced) section — its 4 switches (probe upload, promote, speed promote, separation release) are
        // written immediately; AlertStateMachine reads them live.
        binding.swUwbProbeUpload.setOnCheckedChangeListener { _, c -> DevSettings.uwbProbeUploadEnabled = c; updateSectionSummaries() }
        binding.swUwbPromote.setOnCheckedChangeListener    { _, c -> DevSettings.uwbPromoteEnabled    = c; updateSectionSummaries() }
        binding.swUwbVelPromote.setOnCheckedChangeListener { _, c -> DevSettings.uwbVelPromoteEnabled = c; updateSectionSummaries() }
        binding.swUwbVelRelease.setOnCheckedChangeListener { _, c -> DevSettings.uwbVelReleaseEnabled = c; updateSectionSummaries() }

        // Distance display mode — applied live immediately (from the next list broadcast)
        binding.rgDevDistMode.setOnCheckedChangeListener { _, checkedId ->
            DevSettings.distanceDisplayMode = when (checkedId) {
                binding.rbDevDistDbm.id  -> 0
                binding.rbDevDistUwbM.id -> 1
                else                     -> 2
            }
        }
        // UWB judgment radius — progress/2 = meters (0.5m steps). judgeUwbOnly reads DevSettings directly
        //   on every judgment, so no service notification is needed. A warning < danger misconfiguration is not
        //   auto-corrected — the danger branch is evaluated first, so the danger radius wins (harmless).
        binding.seekDevUwbFkWarn.setOnSeekBarChangeListener(seekListener { v ->
            DevSettings.uwbForkliftWarnMeters = v / 2f
            updateDevUwbRadiusLabels(); updateSectionSummaries()
        })
        binding.seekDevUwbFkDanger.setOnSeekBarChangeListener(seekListener { v ->
            DevSettings.uwbForkliftDangerMeters = v / 2f
            updateDevUwbRadiusLabels(); updateSectionSummaries()
        })
        binding.seekDevUwbPairWarn.setOnSeekBarChangeListener(seekListener { v ->
            DevSettings.uwbPairWarnMeters = v / 2f
            updateDevUwbRadiusLabels(); updateSectionSummaries()
        })
        binding.seekDevUwbPairDanger.setOnSeekBarChangeListener(seekListener { v ->
            DevSettings.uwbPairDangerMeters = v / 2f
            updateDevUwbRadiusLabels(); updateSectionSummaries()
        })

        binding.btnReset.setOnClickListener { resetValues() }

        // App info — version display + link to open-source licenses
        binding.tvAppVersion.text = "SafeAlert v${BuildConfig.VERSION_NAME}"
        binding.btnOpenSourceLicenses.setOnClickListener {
            startActivity(Intent(this, OpenSourceLicensesActivity::class.java))
        }
        // Tapping the app version 7 times quickly reveals the hidden advanced options (Firebase, debug, reverse prep, reset)
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
        // Beacon management is on the main screen
    }

    private fun resetValues() {
        DevSettings.resetToDefault()
        loadValues()
        updateSectionSummaries()   // Also redraw the section header summaries with the defaults
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

    // Reverse-prep switch OFF → disable and dim its 4 sub-sliders
    private fun updateReversePrepEnabled() {
        val enabled = binding.switchReversePrep.isChecked
        val a = if (enabled) 1f else 0.4f
        for (sb in arrayOf(binding.seekReverseRise, binding.seekReverseWindow,
                           binding.seekReverseStabletol, binding.seekReverseHold)) {
            sb.isEnabled = enabled
            sb.alpha = a
        }
    }

    // ── UWB diagnostics / force ─────────────────────────────────────
    private val uwbDiagHandler = Handler(Looper.getMainLooper())
    private var uwbSystemAvailable: Boolean? = null   // System UWB toggle state (queried async); null = unknown
    private val uwbDiagPoller = object : Runnable {
        override fun run() {
            refreshUwbDiag()
            refreshStateDiag()   // Device status gauge
            // Echo-deviation stats panel polling is in the BLE detection settings (same 1.2s period).
            uwbDiagHandler.postDelayed(this, 1200L)
        }
    }

    // ── Device status gauge — read-only. Never takes part in judgment.
    private fun refreshStateDiag() {
        val reg = DeviceStateRegistry.live
        if (reg == null) {
            binding.tvStateDiag.text = "서비스 정지 — 계기 없음"
            binding.secStateSummary.text = "서비스 정지"
        } else {
            val tracked = reg.sizeOf("alertState")?.toString() ?: "?"
            val entries = reg.entryCount()
            val purges = reg.purgeCount
            binding.tvStateDiag.text = "추적 ${tracked}대 · 엔트리 ${entries}개 / 슬롯 ${reg.slotCount()}개 · 정리 ${purges}회"
            binding.secStateSummary.text = "추적 ${tracked}대 · 엔트리 ${entries} · 정리 ${purges}회"
        }
    }

    // Refresh the diagnostic line — HW/permission are checked synchronously; the system UWB toggle is
    // a suspend call, so it is queried async in a coroutine and shown on the next poll
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
        fun mk(b: Boolean) = if (b) "OK" else "NO"
        val sysMark = when (sys) { true -> "OK"; false -> "NO"; null -> "…" }
        val active = UwbRanger.liveActive
        val role   = UwbRanger.liveRole
        val sess   = UwbRanger.liveSessionCount
        val err    = UwbRanger.liveInitError
        val line1  = "HW ${mk(hw)}    권한 ${mk(perm)}    시스템 $sysMark"
        val line2  = "세션 ${if (active) "가동" else "정지"} · 역할 $role · 실측 ${sess}대"
        // liveActive mirrors isSupported, so it stays true when only reconfiguration fails after a successful init —
        // show err even while active, otherwise the failure is silent.
        val hint = when {
            !hw          -> "→ 이 기기는 UWB 하드웨어가 없습니다(BLE 신호만 사용)."
            !perm        -> "→ UWB 권한 없음. BLE 설정 화면에서 권한을 허용하세요."
            sys == false -> "→ 기기 UWB가 꺼져 있습니다. 시스템 설정에서 켜세요."
            err != null  -> if (active) "→ $err (자동 재시도 중)"
                            else "→ 초기화 실패: $err (자동 재시도 중)"
            !active      -> if (DevSettings.uwbForce) "→ 강제 ON. 상대 UWB 기기가 잡히면 세션이 열립니다."
                            else "→ 대기 중. 상대가 근접(시작 게이트 통과)하면 세션이 열립니다."
            else         -> "→ UWB 실측 중 — 목록 거리가 m로 표시됩니다."
        }
        return "$line1\n$line2\n$hint"
    }

    // ── Echo-deviation stats (mutual RSSI) diagnostics (fmtDb, refreshEchoDiag) are in the BLE detection settings ──

    // Cooperation acceptance slack label ("협력·교환" section) — same notation as in the BLE detection settings
    private fun updateCoopSlackLabel() {
        val v = binding.seekCoopSlack.progress
        binding.tvCoopSlack.text = if (v == 0) "0 dB (완화 없음)" else "+${v} dB"
    }

    // ── Accordion — all 7 sections collapsed by default (layout SA.SectionBody visibility=gone); header tap toggles ──
    private fun setupAccordion() {
        bindSection(binding.secTxrxHeader,    binding.secTxrxBody,    binding.secTxrxChevron)
        bindSection(binding.secSoundHeader,   binding.secSoundBody,   binding.secSoundChevron)
        bindSection(binding.secParamHeader,   binding.secParamBody,   binding.secParamChevron)
        bindSection(binding.secCoopHeader,    binding.secCoopBody,    binding.secCoopChevron)
        bindSection(binding.secUwbadvHeader,  binding.secUwbadvBody,  binding.secUwbadvChevron)
        bindSection(binding.secStateHeader,   binding.secStateBody,   binding.secStateChevron)
        bindSection(binding.secAppinfoHeader, binding.secAppinfoBody, binding.secAppinfoChevron)
    }

    // Initial chevron angle follows body visibility; each header tap toggles the body + chevron 0 (collapsed)/180 (expanded)
    private fun bindSection(header: View, body: View, chevron: View) {
        chevron.rotation = if (body.visibility == View.VISIBLE) 180f else 0f
        header.setOnClickListener {
            val open = body.visibility != View.VISIBLE
            body.visibility = if (open) View.VISIBLE else View.GONE
            chevron.rotation = if (open) 180f else 0f
        }
    }

    // Section header summaries — key values stay visible while collapsed. Called from every value-change listener.
    //   Spinner's selectedItemPosition can lag right after setSelection, so read DevSettings directly.
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
            "${onOff(binding.swUwbVelPromote.isChecked)}/${onOff(binding.swUwbVelRelease.isChecked)}" +
            // Shown in the collapsed summary too: if left on and forgotten, samples keep uploading.
            (if (binding.swUwbProbeUpload.isChecked) " · 표본업로드 ON" else "") +
            // Radii visible while collapsed too — forklift pair warning/danger · others warning/danger
            " · 반경 ${binding.tvDevUwbFkWarn.text}/${binding.tvDevUwbFkDanger.text}" +
            "·${binding.tvDevUwbPairWarn.text}/${binding.tvDevUwbPairDanger.text}"
        binding.secAppinfoSummary.text = "v${BuildConfig.VERSION_NAME}"
    }

    // Right after granting permission or toggling force, explicitly ask the service to
    // re-evaluate the UWB session (writing the same value doesn't fire the change listener)
    private fun nudgeUwbReapply() {
        try {
            startService(Intent(this, BleService::class.java).setAction(BleService.ACTION_REAPPLY_UWB))
        } catch (e: Exception) { /* Service not running etc. — picked up on the next scan cycle */ }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // Poll UWB diagnostics only while the screen is shown — start in onResume / stop in onPause (saves battery/resources)
    override fun onResume() {
        super.onResume()
        uwbDiagHandler.removeCallbacks(uwbDiagPoller)
        uwbDiagHandler.post(uwbDiagPoller)
    }

    // Safety net for leaving the screen without a focus loss — commit all input fields
    override fun onPause() {
        super.onPause()
        editCommitters.forEach { it() }
        uwbDiagHandler.removeCallbacks(uwbDiagPoller)   // Stop diagnostic polling
    }

    // Spinner index helpers
    private val scanPeriodValues = longArrayOf(1000, 2000, 3000, 5000)
    private val advertiseValues  = intArrayOf(100, 200, 500, 1000)
    private val vibWarningValues = longArrayOf(300, 500, 1000)
    private val vibCountValues   = intArrayOf(1, 3, 5)

    // Fall back to the default index only when indexOfFirst finds nothing (-1). coerceAtLeast would wrongly promote
    //   valid low indices (0, 1) to the default, reverting fast scan (1000ms, idx0) and short vibration choices to slower values.
    private fun scanPeriodIndex(v: Long)  = scanPeriodValues.indexOfFirst { it == v }.let { if (it < 0) 0 else it }
    private fun advertiseIndex(v: Int)    = advertiseValues.indexOfFirst { it == v }.let { if (it < 0) 1 else it }
    private fun vibWarningIndex(v: Long)  = vibWarningValues.indexOfFirst { it == v }.let { if (it < 0) 1 else it }
    private fun vibCountIndex(v: Int)     = vibCountValues.indexOfFirst { it == v }.let { if (it < 0) 1 else it }

    // Decimal judgment parameters — 9-step sensitivity presets (order matches the arrays.xml labels).
    //   Center index 4 = normal = default, except emaFallPresets (10 steps, default 0.12 at index 2).
    private val ttcPresets         = doubleArrayOf(5.0, 4.5, 4.0, 3.5, 3.0, 2.5, 2.0, 1.5, 1.0)
    private val approachVelPresets = doubleArrayOf(0.2, 0.25, 0.3, 0.4, 0.5, 0.7, 1.0, 1.25, 1.5)
    private val gateVelPresets     = doubleArrayOf(0.2, 0.25, 0.3, 0.4, 0.5, 0.7, 1.0, 1.25, 1.5)
    private val closingPresets     = doubleArrayOf(0.2, 0.3, 0.35, 0.42, 0.5, 0.65, 0.8, 1.0, 1.2)
    private val headOnPresets      = doubleArrayOf(0.4, 0.45, 0.5, 0.55, 0.6, 0.65, 0.7, 0.75, 0.8)
    private val sidePresets        = doubleArrayOf(0.1, 0.15, 0.2, 0.25, 0.3, 0.35, 0.4, 0.45, 0.5)
    private val emaRisePresets     = doubleArrayOf(0.6, 0.52, 0.45, 0.37, 0.3, 0.25, 0.2, 0.15, 0.1)
    private val emaFallPresets     = doubleArrayOf(0.2, 0.15, 0.12, 0.1, 0.07, 0.05, 0.04, 0.03, 0.02, 0.01)   // 0.12 at index2 (default); 10 steps, 1:1 with ema_fall_labels
    private val emaDBoostPresets   = doubleArrayOf(0.7, 0.62, 0.55, 0.47, 0.4, 0.32, 0.25, 0.17, 0.1)

    // Pick the preset step closest to the stored value (safe even if a non-preset value is stored). Fallback = center index4.
    private fun presetIndex(presets: DoubleArray, v: Double) =
        presets.indices.minByOrNull { kotlin.math.abs(presets[it] - v) } ?: 4

    /** Value labels for alarm volume and RSSI thresholds — slider progress is the absolute value; stored as negative dBm */
    private fun updateDevAlarmLabels() {
        binding.tvDevAlarmVolumeVal.text = "${DevSettings.alarmVolume}%"
        binding.tvDevWarnRssiVal.text    = "${DevSettings.rssiWarning} dBm"
        binding.tvDevDangRssiVal.text    = "${DevSettings.rssiDanger} dBm"
    }

    // UWB judgment radius label — progress/2 = meters. Whole values show "15m", half meters "7.5m".
    private fun updateDevUwbRadiusLabels() {
        binding.tvDevUwbFkWarn.text     = fmtMeters(binding.seekDevUwbFkWarn.progress / 2f)
        binding.tvDevUwbFkDanger.text   = fmtMeters(binding.seekDevUwbFkDanger.progress / 2f)
        binding.tvDevUwbPairWarn.text   = fmtMeters(binding.seekDevUwbPairWarn.progress / 2f)
        binding.tvDevUwbPairDanger.text = fmtMeters(binding.seekDevUwbPairDanger.progress / 2f)
    }

    private fun fmtMeters(v: Float): String =
        if (v == v.toInt().toFloat()) "${v.toInt()}m" else "%.1fm".format(v)

    private fun seekListener(onChange: (Int) -> Unit) = object : android.widget.SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: android.widget.SeekBar, v: Int, b: Boolean) = onChange(v)
        override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
        override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
    }

    // Live-apply binding helpers — use fully qualified widget types (no new imports)
    private fun bindSpinner(sp: android.widget.Spinner, onSelect: (Int) -> Unit) {
        sp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) = onSelect(position)
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    // EditText commits on focus loss: applying every keystroke would store blank/partial input.
    //   Parse failure (blank etc.) keeps the old value, then shows the actual value
    //   as clamped by the setter. Registers the onPause safety net.
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

    private fun bindDoubleField(et: android.widget.EditText, getter: () -> Double, setter: (Double) -> Unit) {
        val commit = { setter(et.text.toString().toDoubleOrNull()?.takeIf { it.isFinite() } ?: getter()) }
        editCommitters += commit
        et.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) { commit(); et.setText(getter().toString()) } }
    }
}
