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
// (v1.1.63) 설정 재편 — 아코디언 3섹션([경보 기본] 기본 펼침 · [비콘] · [UWB]).
//   경보 볼륨(50~100%)은 개발자 설정 → 여기로 이동, 에코편차 자동보정(스위치 행 탭 = 하위 펼침 ·
//   튜너 EditText 3종 · 기기별 진단 · 통계 초기화)도 개발자 설정 → 여기로 이관(단일 위치).
//   협력 2종(상호 RSSI 교환 · 협력 수용 완화)과 UWB 승격·해제 3종은 개발자 설정으로 이동.
class BleSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBleSettingsBinding

    // (v1.1.63) 입력란(에코 튜너 EditText) 확정 콜백 — onPause 안전망에서 일괄 커밋(DevSettingsActivity 와 동일 패턴)
    private val editCommitters = mutableListOf<() -> Unit>()

    // (v1.1.63) 에코편차 진단 패널 폴러 — 화면 표시 중에만 1.2s 주기(onResume 시작 / onPause 정지)
    private val echoDiagHandler = Handler(Looper.getMainLooper())
    private val echoDiagPoller = object : Runnable {
        override fun run() {
            refreshEchoDiag()
            echoDiagHandler.postDelayed(this, 1200L)
        }
    }

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
        // (v1.1.63) 헤더는 레이아웃의 SA.TopBar — NoActionBar 테마라 아래는 no-op(호환 유지). 뒤로가기 = 시스템 백.
        supportActionBar?.apply { title = "BLE 감지 설정"; setDisplayHomeAsUpEnabled(true) }

        loadValues()
        setupListeners()
        setupAccordion()
        updateSectionSummaries()
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

        // (v1.1.63) 경보 볼륨 — 개발자 설정에서 이동. 50~100% (DevSettings 도 50 하한 클램프)
        val vol = DevSettings.alarmVolume.coerceIn(50, 100)
        binding.seekAlarmVolume.progress = vol
        binding.tvAlarmVolumeVal.text = "${vol}%"

        // (v1.1.63) 에코편차 자동보정 — 개발자 설정에서 이관. 스위치 + 튜너 3종 + 진단 패널(폴러가 갱신)
        binding.swEchoAutoCalib.isChecked = DevSettings.echoAutoCalibEnabled
        binding.etEchoMinTicks.setText(DevSettings.echoCalMinTicks.toString())
        binding.etEchoMaxIqr.setText(DevSettings.echoCalMaxIqrDb.toString())
        binding.etEchoClamp.setText(DevSettings.echoCalClampDb.toString())
        refreshEchoDiag()

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

        // (v1.1.34) 사업장 코드 = 보정 프로파일 키 (UWB 승격·해제 토글 3종은 v1.1.63 부터 개발자 설정)
        binding.etUwbSite.setText(DevSettings.uwbSiteCode)

        // [v1.1.46] UWB 판정 반경(역할쌍 차등) — progress = 미터 × 2(0.5m 스텝)
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

        // (v1.1.63) 경보 볼륨 — 50~100% (레이아웃 min=50 + 코드 클램프 이중). 즉시 라이브 반영(BleService 구독)
        binding.seekAlarmVolume.setOnSeekBarChangeListener(seek {
            val v = binding.seekAlarmVolume.progress.coerceIn(50, 100)
            if (binding.seekAlarmVolume.progress != v) binding.seekAlarmVolume.progress = v
            binding.tvAlarmVolumeVal.text = "${v}%"
            DevSettings.alarmVolume = v
            updateSectionSummaries()
        })

        // (v1.1.63) 에코편차 자동보정 — 스위치·튜너·초기화(개발자 설정에서 이관). 스위치 밖 행을 탭하면 하위 펼침/접힘.
        binding.swEchoAutoCalib.setOnCheckedChangeListener { _, c ->
            DevSettings.echoAutoCalibEnabled = c
            refreshEchoDiag()
        }
        bindIntField(binding.etEchoMinTicks, { DevSettings.echoCalMinTicks }, { DevSettings.echoCalMinTicks = it })
        bindIntField(binding.etEchoMaxIqr,   { DevSettings.echoCalMaxIqrDb }, { DevSettings.echoCalMaxIqrDb = it })
        bindIntField(binding.etEchoClamp,    { DevSettings.echoCalClampDb },  { DevSettings.echoCalClampDb = it })
        binding.btnEchoReset.setOnClickListener {
            BleService.echoDiffLive.clear()
            getSharedPreferences(BleService.ECHO_PREFS, MODE_PRIVATE).edit().clear().apply()
            refreshEchoDiag()
            Toast.makeText(this, "에코편차 통계 초기화 완료", Toast.LENGTH_SHORT).show()
        }
        binding.rowEchoAutoCalib.setOnClickListener {
            val open = binding.echoDetailGroup.visibility != View.VISIBLE
            binding.echoDetailGroup.visibility = if (open) View.VISIBLE else View.GONE
            binding.ivEchoChevron.rotation = if (open) 180f else 0f
        }

        // 비콘 수신 강도(%) — progress×10 = percent 저장(라이브 반영)
        binding.seekBeaconGain.setOnSeekBarChangeListener(seek {
            DevSettings.beaconGainPercent = binding.seekBeaconGain.progress * 10
            updateBeaconGainLabel()
            updateSectionSummaries()
        })
        // [v1.1.25] EPJ↔EPJ 오프셋 — progress-10 = 오프셋(-10~+15 dB) 저장(라이브 반영)
        binding.seekEpjBias.setOnSeekBarChangeListener(seek {
            DevSettings.epjVsEpjBiasDb = binding.seekEpjBias.progress - 10
            updateEpjBiasLabel()
        })

        // (v1.1.30) UWB 토글 — 즉시 라이브 반영(BleService 가 SharedPreferences 변경 구독)
        binding.swUwb.setOnCheckedChangeListener { _, checked ->
            DevSettings.uwbEnabled = checked
            updateSectionSummaries()
        }

        // (v1.1.31) 거리 표시 방식 — 즉시 라이브 반영(다음 목록 브로드캐스트부터 적용)
        binding.rgDistMode.setOnCheckedChangeListener { _, checkedId ->
            DevSettings.distanceDisplayMode = when (checkedId) {
                binding.rbDistDbm.id  -> 0
                binding.rbDistUwbM.id -> 1
                else                  -> 2
            }
        }

        // (v1.1.34) 사업장 코드 — 입력 즉시 저장 + applySite 직접 호출(서비스 미가동 시에도 즉시 전환) —
        //   BleService applyLiveSettings 경유 호출은 무변경 no-op 이라 이중 호출 무해. 타이핑
        //   중간값 프로파일은 파일이 생기지 않는다(persist dirty 게이트).
        binding.etUwbSite.doAfterTextChanged {
            DevSettings.uwbSiteCode = it?.toString() ?: ""
            UwbCalibrator.applySite()
        }

        // [v1.1.46] UWB 판정 반경 — progress/2 = 미터(0.5m 스텝) 저장(라이브 반영: judgeUwbOnly 가
        //   매 판정마다 DevSettings 를 직독하므로 별도 서비스 통지 불필요). 경고<위험 역설정은
        //   자동 보정하지 않는다 — 위험 분기가 먼저 평가돼 위험 반경이 우선(무해).
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

    // ── (v1.1.63) 아코디언 — 섹션 헤더 탭 = 바디 펼침/접힘 + 셰브런 회전. [경보 기본]만 레이아웃에서 기본 펼침 ──
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

    // (v1.1.63) 섹션 헤더 요약값 — 접힌 상태에서도 핵심 설정이 보이도록 값 변경 리스너마다 갱신
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

    // ── [v1.1.54→55] 에코편차 집계(상호 RSSI) 진단 — 분위수는 BleService.echoQuantileDb(보간) 공용 ──
    //    (v1.1.63) DevSettingsActivity 에서 이관(단일 위치). 폴러 echoDiagPoller 가 1.2s 마다 호출.
    private fun fmtDb(v: Double) = "${if (v >= 0) "+" else ""}${"%.1f".format(v)}dB"

    // 저장분 위에 라이브를 덮어써 병합(라이브 항목 = 첫 틱에 저장분을 시드한 총 누적치) 후 기기별 두 줄 요약.
    //   1줄=통계(중앙값·산포·에코%·n), 2줄=Level 2 보정 상태(후보/적용중/게이트 사유). 말미에 FB 프라이어 요약.
    private fun refreshEchoDiag() {
        val saved = BleService.parseEchoBlob(
            getSharedPreferences(BleService.ECHO_PREFS, MODE_PRIVATE).getString(BleService.ECHO_KEY, "") ?: "")
        saved.putAll(BleService.echoDiffLive)
        val on = DevSettings.echoAutoCalibEnabled
        val minT = DevSettings.echoCalMinTicks
        val sb = StringBuilder()
        for ((id, s) in saved.entries.sortedByDescending { it.value.totalTicks }) {
            if (s.totalTicks <= 0) continue
            if (sb.isNotEmpty()) sb.append('\n')
            if (s.echoTicks > 0) {
                val med = BleService.echoQuantileDb(s.buckets, s.echoTicks, 0.50)
                val iqrHalf = (BleService.echoQuantileDb(s.buckets, s.echoTicks, 0.75) -
                               BleService.echoQuantileDb(s.buckets, s.echoTicks, 0.25)) / 2.0
                val pct = s.echoTicks * 100 / s.totalTicks
                sb.append("${id}  중앙값 ${fmtDb(med)} · 산포 ±${"%.1f".format(iqrHalf)} · 에코 ${pct}% · n=${s.echoTicks}")
                val local = BleService.echoCalLocalDb(s)
                val state = when {
                    local == null -> {
                        val prior = BleService.echoCalPriorDb(id)
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
        // [v1.1.55] Firebase 모델쌍 프라이어 요약(내 모델 기준 fold 결과·서비스 기동 시 로드)
        if (BleService.echoFbPriorByModel.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append("FB프라이어: " + BleService.echoFbPriorByModel.entries.joinToString(" · ") {
                "${it.key} ${fmtDb(it.value.first)}(n=${it.value.second})"
            })
        }
        binding.tvEchoDiag.text = if (sb.isEmpty())
            "수집된 에코 표본 없음 — 상호 RSSI 기기가 근접하면 자동 수집됩니다." else sb.toString()
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

    // [v1.1.46] UWB 판정 반경 라벨 — progress/2 = 미터. 정수 값은 "15m", 반미터는 "7.5m".
    private fun updateUwbRadiusLabels() {
        binding.tvUwbFkWarn.text     = fmtMeters(binding.seekUwbFkWarn.progress / 2f)
        binding.tvUwbFkDanger.text   = fmtMeters(binding.seekUwbFkDanger.progress / 2f)
        binding.tvUwbPairWarn.text   = fmtMeters(binding.seekUwbPairWarn.progress / 2f)
        binding.tvUwbPairDanger.text = fmtMeters(binding.seekUwbPairDanger.progress / 2f)
    }

    private fun fmtMeters(v: Float): String =
        if (v == v.toInt().toFloat()) "${v.toInt()}m" else "%.1fm".format(v)

    private fun seek(onChange: () -> Unit) = object : android.widget.SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: android.widget.SeekBar, v: Int, b: Boolean) = onChange()
        override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
        override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
    }

    // (v1.1.63) 정수 입력란 — 포커스 아웃 시 확정(파싱 실패=현재값 복원). onPause 안전망 등록.
    private fun bindIntField(et: android.widget.EditText, getter: () -> Int, setter: (Int) -> Unit) {
        val commit = { setter(et.text.toString().toIntOrNull() ?: getter()) }
        editCommitters += commit
        et.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) { commit(); et.setText(getter().toString()) } }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // (v1.1.63) 화면 표시 중에만 에코 진단 폴링 — onResume 시작 / onPause 정지(배터리·리소스 절약)
    override fun onResume() {
        super.onResume()
        echoDiagHandler.removeCallbacks(echoDiagPoller)
        echoDiagHandler.post(echoDiagPoller)
    }

    // 포커스 아웃을 거치지 않고 화면을 떠나는 경우의 안전망 — 입력란 전체 확정 + 폴링 중지
    override fun onPause() {
        super.onPause()
        editCommitters.forEach { it() }
        echoDiagHandler.removeCallbacks(echoDiagPoller)
    }
}
