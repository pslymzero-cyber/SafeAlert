package com.wf11.safealert.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.wf11.safealert.databinding.ActivityBleSettingsBinding
import com.wf11.safealert.utils.DevSettings

// [v1.1.8 ①②] 감지 방식(칼만/1초평균 고정값) 선택·모드 혼합(blend) 전면 제거 → 칼만 단일화.
//   화면은 칼만 강도 프리셋 + 경고/위험 신호세기(dBm) 임계만 남긴다.
class BleSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBleSettingsBinding

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

    private fun seek(onChange: () -> Unit) = object : android.widget.SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: android.widget.SeekBar, v: Int, b: Boolean) = onChange()
        override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
        override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
