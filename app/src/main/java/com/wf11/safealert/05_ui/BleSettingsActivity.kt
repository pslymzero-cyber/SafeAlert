package com.wf11.safealert.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.uwb.UwbManager
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.wf11.safealert.databinding.ActivityBleSettingsBinding
import com.wf11.safealert.service.BleService
import com.wf11.safealert.utils.DevSettings
import com.wf11.safealert.utils.UwbCalibrator
import com.wf11.safealert.utils.UwbRanger

// [v1.1.8 ①②] 감지 방식(칼만/1초평균 고정값) 선택·모드 혼합(blend) 전면 제거 → 칼만 단일화.
//   화면은 칼만 강도 프리셋 + 경고/위험 신호세기(dBm) 임계만 남긴다.
class BleSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBleSettingsBinding

    // (v1.1.38 A) UWB_RANGING 권한 요청 런처 — 부여 시 서비스에 세션 재평가 요청 + 시스템 토글 이어서 확인.
    //   버그 원인: 이 권한이 역할선택(MainActivity)에서만 요청돼, 업그레이드·서비스 재시작 후 누락되면
    //   UWB 세션이 안 열려 목록 거리가 dBm 으로 폴백. 여기서 명시적 진입점 제공.
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
        supportActionBar?.apply { title = "BLE 감지 설정"; setDisplayHomeAsUpEnabled(true) }

        loadValues()
        setupListeners()
    }

    private fun loadValues() {
        // 칼만 필터 강도 프리셋
        binding.rgKalmanPreset.check(
            when (DevSettings.kalmanPreset) {
                DevSettings.KALMAN_PRESET_FAST   -> binding.rbKfFast.id
                DevSettings.KALMAN_PRESET_NORMAL -> binding.rbKfNormal.id
                else                             -> binding.rbKfSmooth.id
            }
        )

        // RSSI 임계 (dBm) — 슬라이더 progress=절댓값(30~100), 저장은 음수 dBm
        binding.seekWarnDist.progress = (-DevSettings.rssiWarning).coerceIn(30, 100)
        binding.seekDangDist.progress = (-DevSettings.rssiDanger ).coerceIn(30, 100)
        updateDistLabels()

        // 비콘 수신 강도(%) — 슬라이더 progress = percent/10 (0~30 → 0~300%)
        binding.seekBeaconGain.progress = (DevSettings.beaconGainPercent / 10).coerceIn(0, 30)
        updateBeaconGainLabel()

        // [v1.1.25] EPJ↔EPJ 오프셋 — 슬라이더 progress = 오프셋+10 (-10~+15 dB → 0~25)
        binding.seekEpjBias.progress = (DevSettings.epjVsEpjBiasDb + 10).coerceIn(0, 25)
        updateEpjBiasLabel()

        // (v1.1.30) UWB 정밀 거리 토글 — 미지원 기기는 스위치 비활성
        binding.swUwb.isChecked = DevSettings.uwbEnabled
        if (!UwbRanger.isHardwareSupported(this)) {
            binding.swUwb.isEnabled = false
            binding.tvUwbHint.text = "이 기기는 UWB 하드웨어가 없어 BLE 신호로만 동작합니다"
        }

        // (v1.1.31) 거리 표시 방식 — 0=dBm만 / 1=UWB만 m / 2=전부 m(비UWB는 역산 추정)
        binding.rgDistMode.check(
            when (DevSettings.distanceDisplayMode) {
                0    -> binding.rbDistDbm.id
                1    -> binding.rbDistUwbM.id
                else -> binding.rbDistAllM.id
            }
        )

        // (v1.1.32) UWB 위험 승격(promote-only) — 기본 OFF(옵트인), UWB 미지원 기기는 비활성
        binding.swUwbPromote.isChecked = DevSettings.uwbPromoteEnabled
        if (!UwbRanger.isHardwareSupported(this)) binding.swUwbPromote.isEnabled = false

        // (v1.1.34) UWB 접근속도 승격·이탈 해제 — 기본 OFF(옵트인), 사업장 코드 = 보정 프로파일 키
        binding.swUwbVelPromote.isChecked = DevSettings.uwbVelPromoteEnabled
        binding.swUwbVelRelease.isChecked = DevSettings.uwbVelReleaseEnabled
        binding.etUwbSite.setText(DevSettings.uwbSiteCode)
        if (!UwbRanger.isHardwareSupported(this)) {
            binding.swUwbVelPromote.isEnabled = false
            binding.swUwbVelRelease.isEnabled = false
            binding.etUwbSite.isEnabled = false
        }

        // (v1.1.38 A) UWB 권한/시스템 진입점 초기 상태 반영
        refreshUwbPermState()
    }

    private fun setupListeners() {
        // [v1.1.15] 저장 버튼 제거 — 위젯을 만지는 즉시 DevSettings 에 기록(라이브 반영).
        //   BleService 가 SharedPreferences 변경을 구독(registerOnChange→applyLiveSettings)하므로
        //   라디오/슬라이더를 움직이는 즉시 필터 강도·감지 임계가 반영된다. 화면 종료는 기기 뒤로가기.
        binding.rgKalmanPreset.setOnCheckedChangeListener { _, checkedId ->
            DevSettings.kalmanPreset = when (checkedId) {
                binding.rbKfFast.id   -> DevSettings.KALMAN_PRESET_FAST
                binding.rbKfNormal.id -> DevSettings.KALMAN_PRESET_NORMAL
                else                  -> DevSettings.KALMAN_PRESET_SMOOTH
            }
        }
        // 경고/위험 신호세기(dBm). 슬라이더 progress=절댓값(30~100), 저장은 음수 dBm.
        //   위험은 경고보다 가까워야(절댓값이 더 작아야) → 역전되면 방금 움직인 슬라이더를
        //   경계값으로 자가 보정(clamp). setProgress 재진입은 역전 조건이 깨져 즉시 수렴.
        //   (양 끝단 30/100 에서는 같아질 수 있으나 경고·위험 임계가 겹치는 무해한 설정)
        binding.seekWarnDist.setOnSeekBarChangeListener(seek {
            val dangAbs = binding.seekDangDist.progress
            var warnAbs = binding.seekWarnDist.progress
            if (warnAbs <= dangAbs) {
                warnAbs = (dangAbs + 1).coerceAtMost(100)
                binding.seekWarnDist.progress = warnAbs
            }
            DevSettings.rssiWarning = -warnAbs
            updateDistLabels()
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
        })
        // 비콘 수신 강도(%) — progress×10 = percent 저장(라이브 반영)
        binding.seekBeaconGain.setOnSeekBarChangeListener(seek {
            DevSettings.beaconGainPercent = binding.seekBeaconGain.progress * 10
            updateBeaconGainLabel()
        })
        // [v1.1.25] EPJ↔EPJ 오프셋 — progress-10 = 오프셋(-10~+15 dB) 저장(라이브 반영)
        binding.seekEpjBias.setOnSeekBarChangeListener(seek {
            DevSettings.epjVsEpjBiasDb = binding.seekEpjBias.progress - 10
            updateEpjBiasLabel()
        })

        // (v1.1.30) UWB 토글 — 즉시 라이브 반영(BleService 가 SharedPreferences 변경 구독)
        binding.swUwb.setOnCheckedChangeListener { _, checked -> DevSettings.uwbEnabled = checked }

        // (v1.1.31) 거리 표시 방식 — 즉시 라이브 반영(다음 목록 브로드캐스트부터 적용)
        binding.rgDistMode.setOnCheckedChangeListener { _, checkedId ->
            DevSettings.distanceDisplayMode = when (checkedId) {
                binding.rbDistDbm.id  -> 0
                binding.rbDistUwbM.id -> 1
                else                  -> 2
            }
        }

        // (v1.1.32) UWB 위험 승격 토글 — 즉시 라이브 반영(승격만 · 억제 방향 개입 없음)
        binding.swUwbPromote.setOnCheckedChangeListener { _, checked ->
            DevSettings.uwbPromoteEnabled = checked
        }

        // (v1.1.34) UWB 접근속도 승격/이탈 해제 토글 + 사업장 코드 — 즉시 라이브 반영.
        //   사업장 코드는 입력 즉시 저장 + applySite 직접 호출(서비스 미가동 시에도 즉시 전환) —
        //   BleService applyLiveSettings 경유 호출은 무변경 no-op 이라 이중 호출 무해. 타이핑
        //   중간값 프로파일은 파일이 생기지 않는다(persist dirty 게이트).
        binding.swUwbVelPromote.setOnCheckedChangeListener { _, checked ->
            DevSettings.uwbVelPromoteEnabled = checked
        }
        binding.swUwbVelRelease.setOnCheckedChangeListener { _, checked ->
            DevSettings.uwbVelReleaseEnabled = checked
        }
        binding.etUwbSite.doAfterTextChanged {
            DevSettings.uwbSiteCode = it?.toString() ?: ""
            UwbCalibrator.applySite()
        }

        // (v1.1.38 A) UWB 권한 허용 / 시스템 UWB 설정 진입 — 순차 게이트
        //   ① HW 없음 → 안내만 ② 권한 없음 → 권한 요청 ③ 권한 OK → 시스템 토글 확인·필요시 설정 딥링크
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

    // (v1.1.38 A) 권한/HW 상태를 버튼·안내에 반영
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

    // (v1.1.38 A) 시스템 UWB 토글 상태를 비동기 확인. 꺼져 있고 openIfOff=true 면 시스템 설정으로 딥링크.
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

    // 시스템 UWB 설정으로 이동 시도(비공개 액션) → 실패 시 일반 설정 + 위치 안내 토스트
    private fun openUwbSystemSettings() {
        try {
            startActivity(Intent("android.settings.UWB_SETTINGS"))
            return
        } catch (e: Exception) { /* 미지원 기기 — 일반 설정으로 폴백 */ }
        try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (e: Exception) { /* 무시 */ }
        Toast.makeText(this, "설정 > 연결(또는 네트워크) > UWB(초광대역)를 켜주세요", Toast.LENGTH_LONG).show()
    }

    // 권한 부여·강제 토글 직후 서비스에 UWB 세션 재평가 요청(동일값 SharedPreferences 쓰기는 리스너 미발화)
    private fun nudgeUwbReapply() {
        try {
            startService(Intent(this, BleService::class.java).setAction(BleService.ACTION_REAPPLY_UWB))
        } catch (e: Exception) { /* 서비스 미기동 등 — 다음 스캔 주기에 자연 반영 */ }
    }

    private fun updateDistLabels() {
        // 슬라이더 progress=절댓값(30~100) → 표시는 음수 dBm
        val warnAbs = binding.seekWarnDist.progress
        val dangAbs = binding.seekDangDist.progress
        binding.tvWarnDist.text = "-${warnAbs} dBm"
        binding.tvDangDist.text = "-${dangAbs} dBm"
    }

    private fun updateBeaconGainLabel() {
        // 슬라이더 progress(0~30)×10 = percent(0~300%), dBm = (percent-100)/5 (10%당 2dBm)
        val pct = binding.seekBeaconGain.progress * 10
        val dbm = (pct - 100) / 5
        val sign = if (dbm > 0) "+" else ""
        binding.tvBeaconGain.text = "${pct}% (${sign}${dbm} dBm)"
    }

    private fun updateEpjBiasLabel() {
        // 슬라이더 progress(0~25) - 10 = 오프셋(-10~+15 dB). 음수=더 가까이서만 발령(거리 변별), 양수=더 멀리서 미리.
        val v = binding.seekEpjBias.progress - 10
        val sign = if (v > 0) "+" else ""
        binding.tvEpjBias.text = "${sign}${v} dB"
    }

    private fun seek(onChange: () -> Unit) = object : android.widget.SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: android.widget.SeekBar, v: Int, b: Boolean) = onChange()
        override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
        override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
