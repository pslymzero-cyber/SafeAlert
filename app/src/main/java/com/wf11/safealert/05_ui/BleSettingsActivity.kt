package com.wf11.safealert.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.wf11.safealert.databinding.ActivityBleSettingsBinding
import com.wf11.safealert.utils.DevSettings

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
        val isKalman = DevSettings.detectionMode == DevSettings.MODE_KALMAN
        setMode(isKalman)

        // 칼만 필터 강도 프리셋
        binding.rgKalmanPreset.check(
            when (DevSettings.kalmanPreset) {
                DevSettings.KALMAN_PRESET_FAST   -> binding.rbKfFast.id
                DevSettings.KALMAN_PRESET_NORMAL -> binding.rbKfNormal.id
                else                             -> binding.rbKfSmooth.id
            }
        )

        // 칼만 모드: 거리 (m)
        binding.seekWarnDist.progress = (DevSettings.warningDistM.toInt() - 1).coerceIn(0, 49)
        binding.seekDangDist.progress = (DevSettings.dangerDistM.toInt()  - 1).coerceIn(0, 49)
        updateDistLabels()

        // 고정값 모드: 절댓값
        binding.seekDangerAbs.progress  = DevSettings.fixedDangerAbs
        binding.seekWarningAbs.progress = DevSettings.fixedWarningAbs
        updateAbsLabels()

        // 보조 모드 기여도
        binding.seekBlendRatio.progress = DevSettings.blendRatio
        updateBlendLabel()
    }

    private fun setMode(kalman: Boolean) {
        binding.rbKalman.isChecked = kalman
        binding.rbFixed.isChecked  = !kalman
        binding.cardKalmanConfig.alpha = if (kalman) 1f else 0.4f
        binding.cardFixedConfig.alpha  = if (kalman) 0.4f else 1f
        binding.seekWarnDist.isEnabled = kalman
        binding.seekDangDist.isEnabled = kalman
        binding.seekDangerAbs.isEnabled  = !kalman
        binding.seekWarningAbs.isEnabled = !kalman
        binding.rbKfFast.isEnabled   = kalman
        binding.rbKfNormal.isEnabled = kalman
        binding.rbKfSmooth.isEnabled = kalman
    }

    private fun setupListeners() {
        binding.cardKalman.setOnClickListener { setMode(true);  updateBlendLabel() }
        binding.cardFixed.setOnClickListener  { setMode(false); updateBlendLabel() }

        binding.seekWarnDist.setOnSeekBarChangeListener(seek { updateDistLabels() })
        binding.seekDangDist.setOnSeekBarChangeListener(seek { updateDistLabels() })
        binding.seekDangerAbs.setOnSeekBarChangeListener(seek  { updateAbsLabels() })
        binding.seekWarningAbs.setOnSeekBarChangeListener(seek { updateAbsLabels() })
        binding.seekBlendRatio.setOnSeekBarChangeListener(seek { updateBlendLabel() })

        binding.btnSave.setOnClickListener { saveAndClose() }
    }

    private fun saveAndClose() {
        val isKalman = binding.rbKalman.isChecked
        DevSettings.detectionMode = if (isKalman) DevSettings.MODE_KALMAN else DevSettings.MODE_FIXED_AVG

        if (isKalman) {
            var warnM = (binding.seekWarnDist.progress + 1).toFloat()
            var dangM = (binding.seekDangDist.progress  + 1).toFloat()
            if (warnM < dangM) { val t = warnM; warnM = dangM + 1f; dangM = t }
            DevSettings.warningDistM = warnM
            DevSettings.dangerDistM  = dangM
            DevSettings.kalmanPreset = when (binding.rgKalmanPreset.checkedRadioButtonId) {
                binding.rbKfFast.id   -> DevSettings.KALMAN_PRESET_FAST
                binding.rbKfNormal.id -> DevSettings.KALMAN_PRESET_NORMAL
                else                  -> DevSettings.KALMAN_PRESET_SMOOTH
            }
        } else {
            val dangerAbs  = binding.seekDangerAbs.progress
            val warningAbs = binding.seekWarningAbs.progress
            if (dangerAbs >= warningAbs) {
                Toast.makeText(this, "위험 임계값은 경고보다 작아야 합니다 (더 가까운 거리)", Toast.LENGTH_SHORT).show()
                return
            }
            DevSettings.fixedDangerAbs  = dangerAbs
            DevSettings.fixedWarningAbs = warningAbs
        }

        DevSettings.blendRatio = binding.seekBlendRatio.progress

        Toast.makeText(this, "저장됨", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun updateDistLabels() {
        val warnM = binding.seekWarnDist.progress + 1
        val dangM = binding.seekDangDist.progress  + 1
        binding.tvWarnDist.text = "${warnM}m"
        binding.tvDangDist.text = "${dangM}m"
    }

    private fun updateAbsLabels() {
        val d = binding.seekDangerAbs.progress
        val w = binding.seekWarningAbs.progress
        binding.tvDangerAbs.text  = "$d"
        binding.tvWarningAbs.text = "$w"
    }

    private fun updateBlendLabel() {
        val ratio = binding.seekBlendRatio.progress
        val isKalman = binding.rbKalman.isChecked
        binding.tvBlendVal.text = "${ratio}%"
        binding.tvBlendHint.text = when {
            ratio == 0  -> if (isKalman) "순수 칼만 필터" else "순수 1초 평균"
            ratio <= 10 -> if (isKalman) "칼만 위주 · 1초평균 보조 ${ratio}%" else "1초평균 위주 · 칼만 보조 ${ratio}%"
            ratio <= 30 -> if (isKalman) "칼만 ${100-ratio}% + 1초평균 ${ratio}%" else "1초평균 ${100-ratio}% + 칼만 ${ratio}%"
            else        -> if (isKalman) "칼만 ${100-ratio}% + 1초평균 ${ratio}% (강한 혼합)" else "1초평균 ${100-ratio}% + 칼만 ${ratio}% (강한 혼합)"
        }
    }

    private fun seek(onChange: () -> Unit) = object : android.widget.SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: android.widget.SeekBar, v: Int, b: Boolean) = onChange()
        override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
        override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
