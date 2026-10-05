package com.wf11.safealert.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.uwb.UwbManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.wf11.safealert.databinding.ActivityBleSettingsBinding
import com.wf11.safealert.service.BleService
import com.wf11.safealert.service.CalibrationEngine
import com.wf11.safealert.utils.DevSettings
import com.wf11.safealert.utils.UwbRanger

// Detection is Kalman-only (no Kalman vs. fixed 1 s average selection, no mode blend). The screen keeps the Kalman
//   strength preset and the warning/danger signal-strength (dBm) thresholds.
// Accordion with 3 sections ("경보 기본" expanded by default · "비콘" · "UWB").
//   Alert volume (50~100%) and echo-offset auto-calibration (tap the switch row = expand; 3 tuner EditTexts;
//   per-device diagnostics; stats reset) are on this screen. The 2 cooperation settings (mutual RSSI exchange,
//   cooperative acceptance relaxation) and the 3 UWB escalate/release settings are in developer settings.
class BleSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBleSettingsBinding

    // Commit callbacks for input fields (echo tuner EditTexts) — committed in bulk
    // by the onPause safety net (same pattern as DevSettingsActivity)
    private val editCommitters = mutableListOf<() -> Unit>()

    // Echo-offset diagnostic panel poller — every 1.2s only while the screen is shown (start onResume / stop onPause)
    private val echoDiagHandler = Handler(Looper.getMainLooper())
    private val echoDiagPoller = object : Runnable {
        override fun run() {
            refreshEchoDiag()
            echoDiagHandler.postDelayed(this, 1200L)
        }
    }

    // UWB_RANGING permission request launcher — when granted, asks the service to re-evaluate sessions, then checks the system toggle.
    //   Otherwise the permission is requested only at role selection (MainActivity); if it goes missing after an upgrade or service
    //   restart, no UWB session opens and list distances fall back to dBm. This is the explicit entry point.
    private val uwbPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                Toast.makeText(this, "UWB 권한이 허용되었습니다", Toast.LENGTH_SHORT).show()
                nudgeUwbReapply()
                checkUwbSystemAndGuide(openIfOff = false)
            } else {
                Toast.makeText(this, "UWB 권한이 거부되어 거리는 신호(dBm)로 표시됩니다", Toast.LENGTH_LONG).show()
            }
            refreshUwbPermState()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBleSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // The header is SA.TopBar in the layout — with the NoActionBar theme the line
        // below is a no-op (kept for compatibility). Back = system back.
        supportActionBar?.apply { title = "BLE 감지 설정"; setDisplayHomeAsUpEnabled(true) }

        loadValues()
        setupListeners()
        setupAccordion()
        updateSectionSummaries()
        lockDevManaged()   // Lock after wiring listeners; order doesn't change the result, but shows intent
        listOf(binding.groupBeaconGain, binding.rowUwb, binding.rgKalmanPreset).forEach { row ->
            row.setOnClickListener {
                if (!pinUnlocked) showDevPinDialog { pinUnlocked = true; applyPinGate() }
            }
        }
        applyPinGate()   // After listeners: match clickable (set by setOnClickListener) to the lock state
    }

    // Beacon gain, UWB use and the filter strength are locked by default — tapping the row or a locked control and passing the PIN unlocks
    //   them only while this screen stays open (locked again on recreate/re-entry). Shown the same way as lockDevManaged.
    //   A locked slider does not handle touches, so its group (groupBeaconGain) gets them. A locked switch still eats
    //   touches if clickable even when disabled, so clickable is turned off while locked to pass touches to the row (rowUwb).
    private var pinUnlocked = false

    private fun applyPinGate() {
        binding.seekBeaconGain.isEnabled = pinUnlocked
        binding.swUwb.isEnabled = pinUnlocked && UwbRanger.isHardwareSupported(this)   // Unsupported devices stay disabled
        binding.swUwb.isClickable = pinUnlocked
        // Once unlocked, the row is not announced as a tappable item (screen reader)
        binding.groupBeaconGain.isClickable = !pinUnlocked
        binding.rowUwb.isClickable = !pinUnlocked
        binding.rgKalmanPreset.isClickable = !pinUnlocked   // While locked the group takes the tap and asks for the PIN
        val a = if (pinUnlocked) 1f else 0.4f
        binding.seekBeaconGain.alpha = a
        binding.swUwb.alpha = a
        for (rb in listOf(binding.rbKfFast, binding.rbKfNormal, binding.rbKfSmooth)) {
            rb.isEnabled = pinUnlocked
            rb.isClickable = pinUnlocked
            rb.alpha = a
        }
    }

    private fun loadValues() {
        // Kalman filter strength preset
        binding.rgKalmanPreset.check(
            when (DevSettings.kalmanPreset) {
                DevSettings.KALMAN_PRESET_FAST   -> binding.rbKfFast.id
                DevSettings.KALMAN_PRESET_NORMAL -> binding.rbKfNormal.id
                else                             -> binding.rbKfSmooth.id
            }
        )

        loadDevManagedValues()   // Owned by dev settings / main screen: shown only, saved only on user input

        // Beacon gain (%) — slider progress = percent/10 (0~30 → 0~300%)
        binding.seekBeaconGain.progress = (DevSettings.beaconGainPercent / 10).coerceIn(0, 30)
        updateBeaconGainLabel()

        // EPJ↔EPJ offset — slider progress = offset+10 (-10~+15 dB → 0~25)
        binding.seekEpjBias.progress = (DevSettings.epjVsEpjBiasDb + 10).coerceIn(0, 25)
        updateEpjBiasLabel()

        // UWB precise distance toggle — switch disabled on unsupported devices
        binding.swUwb.isChecked = DevSettings.uwbEnabled
        if (!UwbRanger.isHardwareSupported(this)) {
            binding.swUwb.isEnabled = false
            binding.tvUwbHint.text = "이 기기는 UWB 하드웨어가 없어 BLE 신호로만 동작합니다"
        }

        // Distance display mode — 0=dBm only / 1=m for UWB only / 2=m for all (non-UWB as back-calculated estimate)
        binding.rgDistMode.check(
            when (DevSettings.distanceDisplayMode) {
                0    -> binding.rbDistDbm.id
                1    -> binding.rbDistUwbM.id
                else -> binding.rbDistAllM.id
            }
        )

        // UWB judging radius (differs per role pair) — progress = meters × 2 (0.5m steps)
        binding.seekUwbFkWarn.progress     = (DevSettings.uwbForkliftWarnMeters   * 2).toInt().coerceIn(2, 80)
        binding.seekUwbFkDanger.progress   = (DevSettings.uwbForkliftDangerMeters * 2).toInt().coerceIn(1, 60)
        binding.seekUwbPairWarn.progress   = (DevSettings.uwbPairWarnMeters       * 2).toInt().coerceIn(2, 40)
        binding.seekUwbPairDanger.progress = (DevSettings.uwbPairDangerMeters     * 2).toInt().coerceIn(1, 30)
        updateUwbRadiusLabels()

        if (!UwbRanger.isHardwareSupported(this)) {
            binding.etUwbSite.isEnabled = false
            binding.seekUwbFkWarn.isEnabled = false
            binding.seekUwbFkDanger.isEnabled = false
            binding.seekUwbPairWarn.isEnabled = false
            binding.seekUwbPairDanger.isEnabled = false
        }

        // Reflect the initial state of the UWB permission/system entry point
        refreshUwbPermState()
    }

    /**
     * Shows the values set in developer settings plus the main screen's site code.
     * All target widgets are locked by lockDevManaged(), so re-reading and overwriting them loses no user input.
     * Also called from onResume — values changed in developer settings or the main screen must show on return.
     * Programmatic setProgress/setText never triggers saving or applySite (seek() passes only user input; the site field
     * has no save listener). The site code is entered in the main screen and developer settings (both handle applySite
     * themselves). onResume refreshes the header summary separately.
     */
    private fun loadDevManagedValues() {
        // RSSI thresholds (dBm) — slider progress = absolute value (30~100), stored as negative dBm
        binding.seekWarnDist.progress = (-DevSettings.rssiWarning).coerceIn(30, 100)
        binding.seekDangDist.progress = (-DevSettings.rssiDanger ).coerceIn(30, 100)
        updateDistLabels()

        // Alert volume — 50~100% (DevSettings also clamps to a floor of 50)
        val vol = DevSettings.alarmVolume.coerceIn(50, 100)
        binding.seekAlarmVolume.progress = vol
        binding.tvAlarmVolumeVal.text = "${vol}%"

        // Echo-offset auto-calibration — switch + 3 tuners + diagnostic panel (refreshed by the poller)
        binding.swEchoAutoCalib.isChecked = DevSettings.echoAutoCalibEnabled
        binding.etEchoMinTicks.setText(DevSettings.echoCalMinTicks.toString())
        binding.etEchoMaxIqr.setText(DevSettings.echoCalMaxIqrDb.toString())
        binding.etEchoClamp.setText(DevSettings.echoCalClampDb.toString())
        refreshEchoDiag()

        // Site code = global partition key. Entered in the main screen and developer settings; display only here (no save listener).
        binding.etUwbSite.setText(DevSettings.siteCode)
    }

    /**
     * Locks developer-settings-only items — values are shown but cannot be changed on this screen.
     * The only controls left in the UWB section are the use switch (swUwb) and the permission entry point (btnUwbPermission).
     * The permission button is a system permission entry point, not an option; locking it would make the switch itself pointless.
     * Beacon gain, the UWB switch and the filter strength (rgKalmanPreset) go through applyPinGate (unlocked after the
     * PIN check).
     * The echo detail expand row (rowEchoAutoCalib) is not locked — the diagnostics must stay viewable.
     * RadioGroup.isEnabled does not propagate to its children, so the 3 radios are locked individually.
     */
    private fun lockDevManaged() {
        listOf<View>(
            binding.seekWarnDist, binding.seekDangDist, binding.seekAlarmVolume,
            binding.swEchoAutoCalib, binding.etEchoMinTicks, binding.etEchoMaxIqr,
            binding.etEchoClamp, binding.btnEchoReset, binding.etUwbSite,
            binding.rbDistDbm, binding.rbDistUwbM, binding.rbDistAllM,
            binding.seekUwbFkWarn, binding.seekUwbFkDanger,
            binding.seekUwbPairWarn, binding.seekUwbPairDanger
        ).forEach { it.isEnabled = false; it.alpha = 0.4f }
    }

    private fun setupListeners() {
        // No save button — touching an unlocked widget writes to DevSettings immediately (applied live).
        //   BleService subscribes to SharedPreferences changes (registerOnChange→applyLiveSettings), so the filter strength
        //   and the other unlocked controls apply at once. The alert thresholds shown here are locked (lockDevManaged) and
        //   are edited in developer settings. Leave the screen with the device back button.
        binding.rgKalmanPreset.setOnCheckedChangeListener { _, checkedId ->
            DevSettings.kalmanPreset = when (checkedId) {
                binding.rbKfFast.id   -> DevSettings.KALMAN_PRESET_FAST
                binding.rbKfNormal.id -> DevSettings.KALMAN_PRESET_NORMAL
                else                  -> DevSettings.KALMAN_PRESET_SMOOTH
            }
        }
        // Warning/danger signal strength (dBm). Slider progress = absolute value (30~100), stored as negative dBm.
        //   Danger must be closer than warning (smaller absolute value) → if they cross, the slider just moved
        //   self-corrects to the boundary (clamp). The setProgress re-entry no longer meets the crossing condition, so it settles at once.
        //   (At the ends, 30/100, the two can become equal — a harmless setting where the warning and danger thresholds coincide.)
        binding.seekWarnDist.setOnSeekBarChangeListener(seek {
            val dangAbs = binding.seekDangDist.progress
            var warnAbs = binding.seekWarnDist.progress
            if (warnAbs <= dangAbs) {
                warnAbs = (dangAbs + 1).coerceAtMost(100)
                binding.seekWarnDist.progress = warnAbs
            }
            DevSettings.rssiWarning = -warnAbs
            updateDistLabels()
            updateSectionSummaries()
        })
        binding.seekDangDist.setOnSeekBarChangeListener(seek {
            val warnAbs = binding.seekWarnDist.progress
            var dangAbs = binding.seekDangDist.progress
            if (dangAbs >= warnAbs) {
                dangAbs = (warnAbs - 1).coerceAtLeast(30)
                binding.seekDangDist.progress = dangAbs
            }
            DevSettings.rssiDanger = -dangAbs
            updateDistLabels()
            updateSectionSummaries()
        })

        // Alert volume — 50~100% (layout min=50 plus a code clamp). Applied live at once (BleService subscription)
        binding.seekAlarmVolume.setOnSeekBarChangeListener(seek {
            val v = binding.seekAlarmVolume.progress.coerceIn(50, 100)
            if (binding.seekAlarmVolume.progress != v) binding.seekAlarmVolume.progress = v
            binding.tvAlarmVolumeVal.text = "${v}%"
            DevSettings.alarmVolume = v
            updateSectionSummaries()
        })

        // Echo-offset auto-calibration — switch, tuners, reset. Tapping the row outside the switch expands/collapses the sub-rows.
        binding.swEchoAutoCalib.setOnCheckedChangeListener { _, c ->
            DevSettings.echoAutoCalibEnabled = c
            refreshEchoDiag()
        }
        bindIntField(binding.etEchoMinTicks, { DevSettings.echoCalMinTicks }, { DevSettings.echoCalMinTicks = it })
        bindIntField(binding.etEchoMaxIqr,   { DevSettings.echoCalMaxIqrDb }, { DevSettings.echoCalMaxIqrDb = it })
        bindIntField(binding.etEchoClamp,    { DevSettings.echoCalClampDb },  { DevSettings.echoCalClampDb = it })
        binding.btnEchoReset.setOnClickListener {
            CalibrationEngine.echoDiffLive.clear()
            getSharedPreferences(CalibrationEngine.ECHO_PREFS, MODE_PRIVATE).edit().clear().apply()
            refreshEchoDiag()
            Toast.makeText(this, "에코편차 통계 초기화 완료", Toast.LENGTH_SHORT).show()
        }
        binding.rowEchoAutoCalib.setOnClickListener {
            val open = binding.echoDetailGroup.visibility != View.VISIBLE
            binding.echoDetailGroup.visibility = if (open) View.VISIBLE else View.GONE
            binding.ivEchoChevron.rotation = if (open) 180f else 0f
        }

        // Beacon gain (%) — saves progress×10 = percent (applied live)
        binding.seekBeaconGain.setOnSeekBarChangeListener(seek {
            DevSettings.beaconGainPercent = binding.seekBeaconGain.progress * 10
            updateBeaconGainLabel()
            updateSectionSummaries()
        })
        // EPJ↔EPJ offset — saves progress-10 = offset (-10~+15 dB) (applied live)
        binding.seekEpjBias.setOnSeekBarChangeListener(seek {
            DevSettings.epjVsEpjBiasDb = binding.seekEpjBias.progress - 10
            updateEpjBiasLabel()
        })

        // UWB toggle — applied live at once (BleService subscribes to SharedPreferences changes)
        binding.swUwb.setOnCheckedChangeListener { _, checked ->
            DevSettings.uwbEnabled = checked
            updateSectionSummaries()
        }

        // Distance display mode — applied live at once (from the next list broadcast)
        binding.rgDistMode.setOnCheckedChangeListener { _, checkedId ->
            DevSettings.distanceDisplayMode = when (checkedId) {
                binding.rbDistDbm.id  -> 0
                binding.rbDistUwbM.id -> 1
                else                  -> 2
            }
        }

        // UWB judging radius — saves progress/2 = meters (0.5m steps) (applied live: judgeUwbOnly reads DevSettings
        //   directly on every judgment, so no service notification is needed). An inverted warning<danger setting is not
        //   auto-corrected — the danger branch is evaluated first, so the danger radius wins (harmless).
        binding.seekUwbFkWarn.setOnSeekBarChangeListener(seek {
            DevSettings.uwbForkliftWarnMeters = binding.seekUwbFkWarn.progress / 2f
            updateUwbRadiusLabels()
            updateSectionSummaries()
        })
        binding.seekUwbFkDanger.setOnSeekBarChangeListener(seek {
            DevSettings.uwbForkliftDangerMeters = binding.seekUwbFkDanger.progress / 2f
            updateUwbRadiusLabels()
            updateSectionSummaries()
        })
        binding.seekUwbPairWarn.setOnSeekBarChangeListener(seek {
            DevSettings.uwbPairWarnMeters = binding.seekUwbPairWarn.progress / 2f
            updateUwbRadiusLabels()
            updateSectionSummaries()
        })
        binding.seekUwbPairDanger.setOnSeekBarChangeListener(seek {
            DevSettings.uwbPairDangerMeters = binding.seekUwbPairDanger.progress / 2f
            updateUwbRadiusLabels()
            updateSectionSummaries()
        })

        // Grant UWB permission / open system UWB settings — sequential gate
        //   ① no HW → notice only ② no permission → request it ③ permission OK → check the system toggle, deep-link to settings if needed
        binding.btnUwbPermission.setOnClickListener {
            when {
                !UwbRanger.isHardwareSupported(this) ->
                    Toast.makeText(this, "이 기기는 UWB 하드웨어가 없습니다", Toast.LENGTH_SHORT).show()
                ContextCompat.checkSelfPermission(this, Manifest.permission.UWB_RANGING) !=
                        PackageManager.PERMISSION_GRANTED ->
                    uwbPermLauncher.launch(Manifest.permission.UWB_RANGING)
                else ->
                    checkUwbSystemAndGuide(openIfOff = true)
            }
        }
    }

    // ── Accordion: header tap = expand/collapse body + rotate chevron. Only "경보 기본" starts expanded (layout) ──
    private fun setupAccordion() {
        bindSection(binding.secAlertHeader,  binding.secAlertBody,  binding.secAlertChevron)
        bindSection(binding.secBeaconHeader, binding.secBeaconBody, binding.secBeaconChevron)
        bindSection(binding.secUwbHeader,    binding.secUwbBody,    binding.secUwbChevron)
    }

    private fun bindSection(header: View, body: View, chevron: View) {
        chevron.rotation = if (body.visibility == View.VISIBLE) 180f else 0f
        header.setOnClickListener {
            val open = body.visibility != View.VISIBLE
            body.visibility = if (open) View.VISIBLE else View.GONE
            chevron.rotation = if (open) 180f else 0f
        }
    }

    // Section header summaries — refreshed by every value-change listener so key settings show even when collapsed
    private fun updateSectionSummaries() {
        val warnAbs = binding.seekWarnDist.progress
        val dangAbs = binding.seekDangDist.progress
        val vol = binding.seekAlarmVolume.progress
        binding.secAlertSummary.text = "경고 -${warnAbs} · 위험 -${dangAbs} dBm · 볼륨 ${vol}%"

        binding.secBeaconSummary.text = "수신 강도 ${binding.seekBeaconGain.progress * 10}%"

        binding.secUwbSummary.text = when {
            !UwbRanger.isHardwareSupported(this) -> "미지원"
            !binding.swUwb.isChecked -> "꺼짐"
            else -> "켜짐 · 지게차 쌍 ${fmtMeters(binding.seekUwbFkWarn.progress / 2f)}/" +
                    "${fmtMeters(binding.seekUwbFkDanger.progress / 2f)} · 그 외 " +
                    "${fmtMeters(binding.seekUwbPairWarn.progress / 2f)}/" +
                    fmtMeters(binding.seekUwbPairDanger.progress / 2f)
        }
    }

    // Reflect permission/HW state in the button and notice
    private fun refreshUwbPermState() {
        if (!UwbRanger.isHardwareSupported(this)) {
            binding.btnUwbPermission.isEnabled = false
            binding.tvUwbSystemHint.text = "이 기기는 UWB 하드웨어가 없어 거리는 항상 신호세기(dBm)로 표시됩니다."
            return
        }
        binding.btnUwbPermission.isEnabled = true
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.UWB_RANGING) ==
                PackageManager.PERMISSION_GRANTED
        if (granted) {
            binding.btnUwbPermission.text = "UWB 시스템 설정 확인"
            binding.tvUwbSystemHint.text = "UWB 권한 허용됨 · 버튼을 눌러 기기 UWB(시스템) 켜짐을 확인하세요. 상대 UWB 기기가 잡히면 거리가 m 로 표시됩니다."
        } else {
            binding.btnUwbPermission.text = "UWB 권한 허용"
            binding.tvUwbSystemHint.text = "UWB 권한이 없습니다 · 버튼을 눌러 허용하세요. 허용 전에는 거리가 신호세기(dBm)로 표시됩니다."
        }
    }

    // Check the system UWB toggle asynchronously. If off and openIfOff=true, deep-link to system settings.
    private fun checkUwbSystemAndGuide(openIfOff: Boolean) {
        lifecycleScope.launch {
            val available = try {
                UwbManager.createInstance(this@BleSettingsActivity).isAvailable()
            } catch (e: Exception) { false }
            if (available) {
                binding.tvUwbSystemHint.text = "UWB 준비 완료 · 상대 UWB 기기가 잡히면 거리가 m 로 표시됩니다."
                if (openIfOff) Toast.makeText(this@BleSettingsActivity, "UWB 사용 준비 완료", Toast.LENGTH_SHORT).show()
            } else {
                binding.tvUwbSystemHint.text = "기기 UWB(시스템)가 꺼져 있습니다 · 시스템 설정에서 UWB(초광대역)를 켜주세요."
                if (openIfOff) openUwbSystemSettings()
            }
        }
    }

    // Try opening system UWB settings (private action) → on failure, general settings + a toast saying where to find it
    private fun openUwbSystemSettings() {
        try {
            startActivity(Intent("android.settings.UWB_SETTINGS"))
            return
        } catch (e: Exception) { /* Unsupported device — fall back to general settings */ }
        try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (e: Exception) { /* ignore */ }
        Toast.makeText(this, "설정 > 연결(또는 네트워크) > UWB(초광대역)를 켜주세요", Toast.LENGTH_LONG).show()
    }

    // Right after a permission grant or forced toggle, ask the service to re-evaluate UWB
    // sessions (writing an identical SharedPreferences value fires no listener)
    private fun nudgeUwbReapply() {
        try {
            startService(Intent(this, BleService::class.java).setAction(BleService.ACTION_REAPPLY_UWB))
        } catch (e: Exception) { /* Service not running, etc. — picked up on the next scan cycle */ }
    }

    // ── Echo-offset aggregate (mutual RSSI) diagnostics ──
    //    Quantiles use the shared CalibrationEngine.echoQuantileDb (interpolated). Called every 1.2s by the poller echoDiagPoller.
    private fun fmtDb(v: Double) = "${if (v >= 0) "+" else ""}${"%.1f".format(v)}dB"

    // Overlay live data on the saved data to merge (a live entry = the total accumulation
    // seeded from saved data on the first tick), then a two-line summary per device.
    //   Line 1 = stats (median, spread, echo %, n); line 2 = Level 2 calibration
    //   state (candidate/applied/gate reason). FB prior summary at the end.
    private fun refreshEchoDiag() {
        val saved = CalibrationEngine.parseEchoBlob(
            getSharedPreferences(CalibrationEngine.ECHO_PREFS, MODE_PRIVATE).getString(CalibrationEngine.ECHO_KEY, "") ?: "")
        saved.putAll(CalibrationEngine.echoDiffLive)
        val on = DevSettings.echoAutoCalibEnabled
        val minT = DevSettings.echoCalMinTicks
        val sb = StringBuilder()
        for ((id, s) in saved.entries.sortedByDescending { it.value.totalTicks }) {
            if (s.totalTicks <= 0) continue
            if (sb.isNotEmpty()) sb.append('\n')
            if (s.echoTicks > 0) {
                val med = CalibrationEngine.echoQuantileDb(s.buckets, s.echoTicks, 0.50)
                val iqrHalf = (CalibrationEngine.echoQuantileDb(s.buckets, s.echoTicks, 0.75) -
                               CalibrationEngine.echoQuantileDb(s.buckets, s.echoTicks, 0.25)) / 2.0
                val pct = s.echoTicks * 100 / s.totalTicks
                sb.append("${id}  중앙값 ${fmtDb(med)} · 산포 ±${"%.1f".format(iqrHalf)} · 에코 ${pct}% · n=${s.echoTicks}")
                val local = CalibrationEngine.echoCalLocalDb(s)
                val state = when {
                    local == null -> {
                        val prior = CalibrationEngine.echoCalPriorDb(id)
                        if (prior != null) "n부족 ${s.echoTicks}/${minT} · FB프라이어 ${fmtDb(prior)}${if (on) " 적용중" else ""}"
                        else "n부족 ${s.echoTicks}/${minT}"
                    }
                    iqrHalf > DevSettings.echoCalMaxIqrDb -> "산포과다(>±${DevSettings.echoCalMaxIqrDb}) → 보정 0"
                    else -> "보정 ${fmtDb(local)}${if (on) " 적용중" else " (스위치 OFF)"}"
                }
                sb.append("\n    → ${state}")
            } else {
                sb.append("${id}  에코 없음(비콘·구버전) · 틱 ${s.totalTicks}")
            }
        }
        // Firebase model-pair prior summary (fold result relative to my model; loaded at service start)
        if (CalibrationEngine.echoFbPriorByModel.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append("FB프라이어: " + CalibrationEngine.echoFbPriorByModel.entries.joinToString(" · ") {
                "${it.key} ${fmtDb(it.value.first)}(n=${it.value.second})"
            })
        }
        binding.tvEchoDiag.text = if (sb.isEmpty())
            "수집된 에코 표본 없음 — 상호 RSSI 기기가 근접하면 자동 수집됩니다." else sb.toString()
    }

    private fun updateDistLabels() {
        // Slider progress = absolute value (30~100) → displayed as negative dBm
        val warnAbs = binding.seekWarnDist.progress
        val dangAbs = binding.seekDangDist.progress
        binding.tvWarnDist.text = "-${warnAbs} dBm"
        binding.tvDangDist.text = "-${dangAbs} dBm"
    }

    private fun updateBeaconGainLabel() {
        // Slider progress (0~30)×10 = percent (0~300%), dBm = (percent-100)/5 (2dBm per 10%)
        val pct = binding.seekBeaconGain.progress * 10
        val dbm = (pct - 100) / 5
        val sign = if (dbm > 0) "+" else ""
        binding.tvBeaconGain.text = "${pct}% (${sign}${dbm} dBm)"
    }

    private fun updateEpjBiasLabel() {
        // Slider progress (0~25) - 10 = offset (-10~+15 dB). Negative = alert only when
        // closer (distance discrimination), positive = alert earlier from farther.
        val v = binding.seekEpjBias.progress - 10
        val sign = if (v > 0) "+" else ""
        binding.tvEpjBias.text = "${sign}${v} dB"
    }

    // UWB judging radius label — progress/2 = meters. Whole values show as "15m", half meters as "7.5m".
    private fun updateUwbRadiusLabels() {
        binding.tvUwbFkWarn.text     = fmtMeters(binding.seekUwbFkWarn.progress / 2f)
        binding.tvUwbFkDanger.text   = fmtMeters(binding.seekUwbFkDanger.progress / 2f)
        binding.tvUwbPairWarn.text   = fmtMeters(binding.seekUwbPairWarn.progress / 2f)
        binding.tvUwbPairDanger.text = fmtMeters(binding.seekUwbPairDanger.progress / 2f)
    }

    private fun fmtMeters(v: Float): String =
        if (v == v.toInt().toFloat()) "${v.toInt()}m" else "%.1fm".format(v)

    private fun seek(onChange: () -> Unit) = object : android.widget.SeekBar.OnSeekBarChangeListener {
        // Programmatic setProgress (onResume re-read, crossing-correction re-entry) is not saved — only user input is saved
        override fun onProgressChanged(sb: android.widget.SeekBar, v: Int, fromUser: Boolean) { if (fromUser) onChange() }
        override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
        override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
    }

    // Integer input field — committed on focus loss (parse failure = restore the current value). Registered with the onPause safety net.
    private fun bindIntField(et: android.widget.EditText, getter: () -> Int, setter: (Int) -> Unit) {
        val commit = { setter(et.text.toString().toIntOrNull() ?: getter()) }
        editCommitters += commit
        et.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) { commit(); et.setText(getter().toString()) } }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // Poll echo diagnostics only while the screen is shown — start onResume / stop onPause (saves battery and resources)
    override fun onResume() {
        super.onResume()
        loadDevManagedValues()   // Re-apply values changed in dev settings / main screen
        updateSectionSummaries() // The guarded re-read skips onChange, so refresh header summaries here
        echoDiagHandler.removeCallbacks(echoDiagPoller)
        echoDiagHandler.post(echoDiagPoller)
    }

    // Safety net for leaving the screen without a focus loss — commit all input fields + stop polling
    override fun onPause() {
        super.onPause()
        editCommitters.forEach { it() }
        echoDiagHandler.removeCallbacks(echoDiagPoller)
    }
}
