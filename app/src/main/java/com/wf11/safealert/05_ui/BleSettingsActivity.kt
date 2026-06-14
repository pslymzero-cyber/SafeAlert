package com.wf11.safealert.ui

import android.os.Bundle
import android.widget.Toast
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
    }

    private fun setupListeners() {
        binding.seekWarnDist.setOnSeekBarChangeListener(seek { updateDistLabels() })
        binding.seekDangDist.setOnSeekBarChangeListener(seek { updateDistLabels() })
        binding.btnSave.setOnClickListener { saveAndClose() }
    }

    private fun saveAndClose() {
        // 슬라이더 progress=절댓값(30~100). 위험은 경고보다 가까움 → 절댓값이 더 작아야.
        val warnAbs = binding.seekWarnDist.progress
        val dangAbs = binding.seekDangDist.progress
        if (dangAbs >= warnAbs) {
            Toast.makeText(this, "위험 임계는 경고보다 가까워야 합니다 (절댓값을 더 작게)", Toast.LENGTH_SHORT).show()
            return
        }
        DevSettings.rssiWarning = -warnAbs
        DevSettings.rssiDanger  = -dangAbs
        DevSettings.kalmanPreset = when (binding.rgKalmanPreset.checkedRadioButtonId) {
            binding.rbKfFast.id   -> DevSettings.KALMAN_PRESET_FAST
            binding.rbKfNormal.id -> DevSettings.KALMAN_PRESET_NORMAL
            else                  -> DevSettings.KALMAN_PRESET_SMOOTH
        }

        Toast.makeText(this, "저장됨", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun updateDistLabels() {
        // 슬라이더 progress=절댓값(30~100) → 표시는 음수 dBm
        val warnAbs = binding.seekWarnDist.progress
        val dangAbs = binding.seekDangDist.progress
        binding.tvWarnDist.text = "-${warnAbs} dBm"
        binding.tvDangDist.text = "-${dangAbs} dBm"
    }

    private fun seek(onChange: () -> Unit) = object : android.widget.SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: android.widget.SeekBar, v: Int, b: Boolean) = onChange()
        override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
        override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
