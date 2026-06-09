package com.wf11.safealert.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.wf11.safealert.BuildConfig
import com.wf11.safealert.R
import com.wf11.safealert.ble.BleConstants
import com.wf11.safealert.ble.LocalState
import com.wf11.safealert.utils.BeaconRegistry
import com.wf11.safealert.utils.DevSettings
import com.wf11.safealert.utils.OverlayManager
import com.wf11.safealert.databinding.ActivityMainBinding
import com.wf11.safealert.databinding.DialogPinBinding
import com.wf11.safealert.service.BleService
import com.wf11.safealert.utils.UpdateManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("safealert_prefs", MODE_PRIVATE) }
    private var currentMode: String? = null
    // [v1.0.34] 선택된 역할(Category) — 1바이트 페이로드 bits[1:0] 로 BleService→BleAdvertiser 에 전달
    private var currentCategory: Int = BleConstants.CAT_WALKER
    private var testAlertRunning = false

    // [v1.0.26 Req2] 감지 기기 목록 — BleService.alertState 스냅샷을 통째로 받아 매번 교체.
    // (displayName, alertLevel, rssi) 정렬 리스트. 단일 진실 공급원이라 부분 add/remove 없음 = 불일치 불가.
    private val detectedDevices = mutableListOf<Triple<String, Int, Int>>()

    // [v1.0.42] Broadcast 수신과 폴링 폴백이 공유하는 '마지막 반영 스냅샷'.
    //   같은 값이면 양쪽 모두 no-op → 중복 렌더 방지. 초기 sentinel()은 빈 목록("")과도 구분.
    private var lastSyncedSnapshot = ""

    // [v1.0.42 Req2] 내 장비(Local) 상태 전용 '마지막 반영 스냅샷'.
    //   Broadcast(BROADCAST_LOCAL_STATE)와 800ms 폴링(BleService.localSnapshot)이 공유한다.
    //   ※ 이 채널은 '내가 송출하는' 상태만 운반한다 — 수신 타겟(detectedSnapshot)과 물리적으로 분리되어
    //     상대 페이로드가 내 장비 표시를 절대 덮어쓸 수 없다(Req2 핵심 불변식).
    private var lastLocalSnapshot = ""

    // 1초마다 서비스 상태 직접 폴링 (Broadcast 실패 대비)
    private val statusHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var muteAnimator: ObjectAnimator? = null

    // [v1.0.37] UI 렌더 스로틀 — 감지 목록(TextView) 갱신을 최소 uiRenderThrottleMs(500ms)
    //   간격으로 코얼레싱해 UI 스레드/GPU 재드로우 부하·전력을 줄인다. 데이터 수신(detectedDevices)과
    //   백그라운드 계산(BleService/Kalman)은 실시간 유지 — '화면에 뿌리는' 작업만 제한한다.
    //   단 위험도 '상승'(특히 DANGER 진입=경고 배경색/아이콘)은 스로틀 우회 즉시 렌더(안전 우선).
    private val uiThrottleHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val uiRenderThrottleMs = 500L
    private var lastRenderMs = 0L
    private var pendingRender = false
    private var lastRenderedTopLevel = BleConstants.LEVEL_SAFE
    private val renderRunnable = Runnable {
        pendingRender = false
        lastRenderMs = android.os.SystemClock.elapsedRealtime()
        lastRenderedTopLevel = detectedDevices.maxOfOrNull { it.second } ?: BleConstants.LEVEL_SAFE
        updateDetectedDisplay()
    }

    private val statusRunnable = object : Runnable {
        private var lastText = ""
        private var lastMuted = false
        override fun run() {
            if (binding.cardRunning.visibility == View.VISIBLE) {
                // [v1.0.42] Broadcast 누락 대비 폴백 — 서비스 스냅샷(BleService.detectedSnapshot)을
                //   직접 읽어 목록을 동기화한다. 브로드캐스트가 정상이면 같은 값이라 no-op,
                //   누락(RECEIVER_NOT_EXPORTED/암시적 전달 실패)되면 여기서 800ms 내 복구된다.
                val snap = BleService.detectedSnapshot
                if (snap != lastSyncedSnapshot) {
                    lastSyncedSnapshot = snap
                    applyDeviceListSnapshot(snap)
                    requestDetectedRender()
                }
                // [v1.0.42 Req2] 내 장비(Local) 상태/속도 — '내 송출' 전용 채널(localSnapshot)만 폴링.
                //   tv_ble_status(수신 타겟)와 완전히 다른 소스라 수신 데이터가 여기로 새지 않는다.
                val localSnap = BleService.localSnapshot
                if (localSnap != lastLocalSnapshot) {
                    lastLocalSnapshot = localSnap
                    parseLocalSnapshot(localSnap)?.let { updateLocalDisplay(it) }
                }
                // [v1.0.26 Req2] tv_ble_status 는 '감지 기기 목록' 전용 영역으로 전환.
                // 목록이 비었을 때만 그 자리에 '서비스 상태' 1줄을 임시로 표시한다.
                val text = when {
                    !BleService.isRunning -> "서비스 시작 중..."
                    BleService.lastStatus.isNotEmpty() -> BleService.lastStatus
                    else -> "✓ 블루투스 ON · BLE 시작 중..."
                }
                if (detectedDevices.isEmpty()) {
                    if (text != lastText) {
                        binding.tvBleStatus.text = text
                        lastText = text
                    }
                } else {
                    // 목록 표시 중 → 다음에 목록이 비면 무조건 상태로 되돌리도록 캐시 무효화
                    lastText = ""
                }
                // 서비스가 완전히 종료되면 감지 목록 초기화
                if (!BleService.isRunning && detectedDevices.isNotEmpty()) {
                    detectedDevices.clear()
                    updateDetectedDisplay()
                }

                // 무음 인디케이터 (깜빡이는 배너)
                val muted = BleService.isMutedPublic
                if (muted != lastMuted) {
                    lastMuted = muted
                    if (muted) {
                        binding.tvMutedIndicator.visibility = View.VISIBLE
                        muteAnimator?.cancel()
                        muteAnimator = ObjectAnimator.ofFloat(binding.tvMutedIndicator, "alpha", 0.3f, 1.0f).apply {
                            duration = 600
                            repeatMode = ObjectAnimator.REVERSE
                            repeatCount = ObjectAnimator.INFINITE
                            start()
                        }
                    } else {
                        muteAnimator?.cancel()
                        muteAnimator = null
                        binding.tvMutedIndicator.visibility = View.GONE
                        binding.tvMutedIndicator.alpha = 1f
                    }
                }
            }
            statusHandler.postDelayed(this, 800)
        }
    }

    private val blePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) requestBatteryOptimizationExclusion()
        else showPermissionWarning("BLE · 위치 권한이 필요합니다. 탭하여 허용해주세요.") { openAppSettings() }
    }

    private val batteryOptLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { startServiceWithCurrentMode() }

    // 경보 / 감지 브로드캐스트 수신
    private val alertReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {

                // [v1.0.26 Req2/Req3] BleService 가 보낸 alertState 전체 스냅샷(직렬화 목록)을
                // 통째로 수신 → detectedDevices 를 매번 새로 구성. 부분 add/remove 없음 = 불일치 불가.
                BleService.BROADCAST_DETECTED -> {
                    val raw = intent.getStringExtra(BleService.EXTRA_DEVICE_LIST) ?: ""
                    // [v1.0.42] 폴링 폴백과 '동일 파서' 공유 + lastSyncedSnapshot 동기화(중복 렌더 방지).
                    lastSyncedSnapshot = raw
                    applyDeviceListSnapshot(raw)
                    // [v1.0.37] 즉시 렌더 대신 throttle 경유(500ms 코얼레싱, 위험도 상승은 우회 즉시).
                    //   데이터(detectedDevices)는 위에서 이미 최신으로 반영됨 — 화면 출력만 제한된다.
                    requestDetectedRender()
                }

                BleService.BROADCAST_BLE_STATUS -> {
                    val status = intent.getStringExtra(BleService.EXTRA_STATUS) ?: return
                    // [v1.0.26 Req2] 목록이 비었을 때만 하단에 서비스 상태 표시(목록이 있으면 목록 유지).
                    if (detectedDevices.isEmpty()) {
                        binding.tvBleStatus.text = status
                    }
                }

                // [v1.0.42 Req2] 내 장비(Local) 상태 푸시 — 수신 타겟 데이터가 절대 건드리지 못하는 별도 채널.
                //   tv_local_state(내 장비) 만 갱신한다. detectedDevices/tv_ble_status 는 손대지 않는다.
                BleService.BROADCAST_LOCAL_STATE -> {
                    val raw = intent.getStringExtra(BleService.EXTRA_LOCAL_STATE) ?: return
                    lastLocalSnapshot = raw
                    parseLocalSnapshot(raw)?.let { updateLocalDisplay(it) }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 화면 꺼지지 않도록
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 하단 버전 표시 — BuildConfig에서 읽어 항상 최신값 반영
        binding.tvVersionFooter.text = "v${BuildConfig.VERSION_NAME}  ·  Created by Ian"
        // 저장된 이름 복원
        binding.etDisplayName.setText(prefs.getString("display_name", ""))

        // [v1.0.34] 3-Role 선택 — 보행자(WALKER) / EPJ·지게차(DEVICE) + Category 동시 지정
        binding.cardRoleWalker.setOnClickListener   { saveDisplayName(); onRoleSelected("WALKER", BleConstants.CAT_WALKER) }
        binding.cardRoleEpj.setOnClickListener      { saveDisplayName(); onRoleSelected("DEVICE", BleConstants.CAT_EPJ) }
        binding.cardRoleForklift.setOnClickListener { saveDisplayName(); onRoleSelected("DEVICE", BleConstants.CAT_FORKLIFT) }
        binding.btnStop.setOnClickListener       { stopServiceWithDelay() }
        binding.cardSettings.setOnClickListener  { showPinDialog() }
        binding.cardBleSettings.setOnClickListener {
            startActivity(Intent(this, BleSettingsActivity::class.java))
        }
        binding.cardBeacon.setOnClickListener    {
            startActivity(Intent(this, BeaconManagerActivity::class.java))
        }
        binding.btnTestAlert.setOnClickListener  { toggleTestAlert() }
        binding.tvMutedIndicator.setOnClickListener {
            startService(Intent(this, BleService::class.java).apply {
                action = BleService.ACTION_UNMUTE
            })
        }

        val filter = IntentFilter().apply {
            // [v1.0.26 Req2] BROADCAST_ALERT 구독 제거 — 목록·플로팅 모두 BleService(alertState)가 단일 관리.
            addAction(BleService.BROADCAST_DETECTED)
            addAction(BleService.BROADCAST_BLE_STATUS)
            addAction(BleService.BROADCAST_LOCAL_STATE)   // [v1.0.42 Req2] 내 장비 상태 채널
        }
        registerReceiver(alertReceiver, filter, RECEIVER_NOT_EXPORTED)
        statusHandler.post(statusRunnable)
        restoreRunningState()
        checkUpdate()
        checkBluetoothStatus()
        requestBatteryOptimizationOnStart()
        requestOverlayPermissionIfNeeded()  // 오버레이 권한 요청
    }

    override fun onResume() {
        super.onResume()
        // BLE 설정 요약 업데이트
        binding.tvBleModeSummary.text = when (DevSettings.detectionMode) {
            DevSettings.MODE_FIXED_AVG ->
                "1초 평균 고정값 · 위험 ${DevSettings.fixedDangerAbs} / 경고 ${DevSettings.fixedWarningAbs}"
            else ->
                "칼만 필터 · 위험 ${DevSettings.rssiDanger}dBm / 경고 ${DevSettings.rssiWarning}dBm"
        }
        val beaconCount = BeaconRegistry.count()
        binding.tvBeaconSummary.text = if (beaconCount > 0)
            "UUID ${beaconCount}개 등록됨 · iOS/앱 없는 보행자 감지 중"
        else
            "iOS/앱 없는 보행자 감지 설정"
    }

    override fun onDestroy() {
        super.onDestroy()
        statusHandler.removeCallbacks(statusRunnable)
        uiThrottleHandler.removeCallbacks(renderRunnable)   // [v1.0.37] UI 스로틀 타이머 정리
        try { unregisterReceiver(alertReceiver) } catch (_: Exception) {}
        muteAnimator?.cancel()
    }

    /**
     * [v1.0.42 공용 파서] BleService 직렬화 스냅샷을 detectedDevices 로 파싱.
     *   레코드 구분 = U+001E, 필드 = "level / rssi / name"(U+001F 구분).
     *   Broadcast 수신과 800ms 폴링 폴백이 '같은 파서'를 쓰게 해 두 경로 결과가 절대 어긋나지 않게 한다.
     */
    private fun applyDeviceListSnapshot(raw: String) {
        detectedDevices.clear()
        if (raw.isEmpty()) return
        val recSep  = 30.toChar()   // U+001E 레코드 구분자 (BleService 출력과 동일)
        val unitSep = 31.toChar()   // U+001F 필드 구분자
        raw.split(recSep).forEach { rec ->
            val f = rec.split(unitSep)
            if (f.size >= 3) {
                val level = f[0].toIntOrNull() ?: BleConstants.LEVEL_SAFE
                val rssi  = f[1].toIntOrNull() ?: -99
                val name  = f[2]
                detectedDevices.add(Triple(name, level, rssi))
            }
        }
    }

    /**
     * [v1.0.42 Req2] 내 장비(Local) 스냅샷 파서 — BleService.localSnapshot / EXTRA_LOCAL_STATE 전용.
     *   형식: "category / state / speedKmh" (U+001F 필드 구분, BleService.broadcastLocalState 와 동일).
     *   수신 타겟 파서(applyDeviceListSnapshot)와 의도적으로 분리 — 두 채널이 절대 섞이지 않는다.
     */
    private fun parseLocalSnapshot(raw: String): LocalState? {
        if (raw.isEmpty()) return null
        val f = raw.split(31.toChar())   // U+001F 필드 구분자
        if (f.size < 3) return null
        val cat = f[0].toIntOrNull() ?: return null
        val st  = f[1].toIntOrNull() ?: return null
        val sp  = f[2].toDoubleOrNull() ?: 0.0
        return LocalState(cat, st, sp)
    }

    /**
     * [v1.0.42 Req2] 내 장비(Local) 상태/속도를 tv_local_state 에만 출력.
     *   역할(Category)은 tv_running_mode(roleDisplayName)가 담당 → 여기선 상태·속도만 표시(중복 방지).
     *   이 메서드는 tv_ble_status(수신 타겟)·detectedDevices 를 절대 건드리지 않는다.
     */
    private fun updateLocalDisplay(local: LocalState) {
        binding.tvLocalState.text =
            "상태: ${local.stateLabel} · 속도: ${local.speedKmh.toInt()} km/h"
    }

    /**
     * [v1.0.37 UI 스로틀] 감지 목록 렌더 요청 — 최소 uiRenderThrottleMs(500ms) 간격으로 코얼레싱한다.
     *  - 위험도 '상승'(직전 렌더 대비 topLevel↑, 특히 DANGER 진입)은 스로틀 우회 즉시 렌더:
     *    위험 경고의 배경색·아이콘·크기 강조가 지연 없이 즉각 화면에 반영된다(안전 최우선).
     *  - 그 외 일반 갱신(RSSI 숫자 변동·안전/경고 목록 증감)은 500ms 간격으로만 화면 반영해
     *    UI 스레드·GPU 재드로우 빈도를 낮춘다(전력 절감). 백그라운드 계산은 영향받지 않는다.
     *  - 마지막 변경이 스로틀에 걸리면 trailing 타이머(renderRunnable)가 최신 스냅샷으로 1회 렌더.
     */
    private fun requestDetectedRender() {
        val topLevel = detectedDevices.maxOfOrNull { it.second } ?: BleConstants.LEVEL_SAFE
        val now = android.os.SystemClock.elapsedRealtime()
        val escalated = topLevel > lastRenderedTopLevel          // 위험도 상승 = 크리티컬 → 즉시
        if (escalated || now - lastRenderMs >= uiRenderThrottleMs) {
            uiThrottleHandler.removeCallbacks(renderRunnable)     // 예약된 trailing 렌더 무효화
            pendingRender = false
            lastRenderMs = now
            lastRenderedTopLevel = topLevel
            updateDetectedDisplay()
        } else if (!pendingRender) {
            // 스로틀 구간 내 첫 갱신 → 남은 시간 뒤 1회 trailing 렌더 예약(중복 예약 방지)
            pendingRender = true
            uiThrottleHandler.postDelayed(renderRunnable, uiRenderThrottleMs - (now - lastRenderMs))
        }
        // pendingRender==true 면 이미 예약됨 — 추가 동작 불필요(최신 데이터는 detectedDevices 에 보존)
    }

    /**
     * [v1.0.26 Req2/Req3] 감지 기기 목록을 '화면 하단' tv_ble_status(파란 박스)에 직접 출력.
     * - 기기 1대라도 있으면: 중앙 안내(tv_approaching)를 GONE 으로 숨기고(고스트 텍스트 제거),
     *   하단 tv_ble_status 에 위험도 색상 Spannable 목록(최대 10)을 setText.
     * - 비어 있으면: 중앙 안내를 다시 보이고, 하단은 밝은 배경의 '서비스 상태' 1줄로 복귀.
     * detectedDevices 는 BleService 스냅샷 단일 소스라 알람과 목록이 절대 어긋나지 않는다.
     */
    private fun updateDetectedDisplay() {
        if (detectedDevices.isEmpty()) {
            // 중앙 안내 복귀
            binding.tvApproaching.visibility = View.VISIBLE
            binding.tvApproaching.setBackgroundColor(Color.TRANSPARENT)
            binding.tvApproaching.setTextColor(0xFF8AAFC4.toInt())
            binding.tvApproaching.text = "주변 감지 기기 없음 · 감시 중"
            // 하단 목록 영역 → 밝은 파란 박스 + 서비스 상태 1줄로 복귀
            binding.tvBleStatus.setBackgroundColor(0xFFDDE9F0.toInt())
            binding.tvBleStatus.setTextColor(0xFF5B8FA8.toInt())
            val status = BleService.lastStatus
            binding.tvBleStatus.text = if (status.isNotEmpty()) status else "· 감시 중 ·"
            return
        }

        // [Req3] 기기가 있으면 중앙 '감지 없음' 고스트 텍스트를 즉시 숨긴다.
        binding.tvApproaching.visibility = View.GONE

        // 위험도 우선 → 같은 위험도면 RSSI 강한(가까운) 순, 최대 10개
        val sorted = detectedDevices
            .sortedWith(
                compareByDescending<Triple<String, Int, Int>> { it.second }
                    .thenByDescending { it.third }
            )
            .take(10)

        val sb = SpannableStringBuilder()
        // [v1.0.42] 사용자 요청 — 목록 최상단에 '주변 감지 기기 N건' 헤더(연회색 소형).
        run {
            val hStart = sb.length
            sb.append("주변 감지 기기 ${detectedDevices.size}건\n")
            sb.setSpan(ForegroundColorSpan(0xFF9FB8C8.toInt()), hStart, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(0.78f), hStart, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        sorted.forEachIndexed { idx, (name, level, rssi) ->
            if (idx > 0) sb.append("\n")
            val prefix = when (level) {
                BleConstants.LEVEL_DANGER  -> "🚨"
                BleConstants.LEVEL_WARNING -> "⚠"
                else                       -> "📡"
            }
            val line = "$prefix  $name   ${rssi}dBm"
            val start = sb.length
            sb.append(line)
            val end = sb.length
            val color = when (level) {
                BleConstants.LEVEL_DANGER  -> Color.rgb(255,  80,  70)   // 위험 = 빨강
                BleConstants.LEVEL_WARNING -> Color.rgb(255, 200,  40)   // 경고 = 노랑
                else                       -> Color.rgb(170, 210, 230)   // 안전 = 연청
            }
            val sizeMul = when (level) {
                BleConstants.LEVEL_DANGER  -> 1.22f
                BleConstants.LEVEL_WARNING -> 1.0f
                else                       -> 0.82f
            }
            sb.setSpan(ForegroundColorSpan(color),   start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(sizeMul),     start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (level == BleConstants.LEVEL_DANGER)
                sb.setSpan(StyleSpan(Typeface.BOLD),  start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // [Req2] 목록을 하단 tv_ble_status 에 출력 — 밝은 기본 배경에선 노랑/연청이 묻히므로
        // 위험도에 맞춰 어두운 배경을 동적으로 깔아 가독성 확보.
        val topLevel = sorted.first().second
        val bgColor = when {
            topLevel >= BleConstants.LEVEL_DANGER  -> 0xDD1A0000.toInt()  // 짙은 적
            topLevel == BleConstants.LEVEL_WARNING -> 0xDD1A1400.toInt()  // 짙은 황
            else                                   -> 0xDD051220.toInt()  // 짙은 남색
        }
        binding.tvBleStatus.setBackgroundColor(bgColor)
        binding.tvBleStatus.setTextColor(0xFFE0F0FF.toInt())
        binding.tvBleStatus.text = sb
    }

    private fun onRoleSelected(mode: String, category: Int) {
        currentMode = mode
        currentCategory = category
        // 역할(Category) 복원용 저장 — 서비스 START_STICKY 재시작·앱 재실행 시 라벨 일관성 확보
        prefs.edit().putInt("running_category", category).apply()
        binding.layoutPermissionWarning.visibility = View.GONE
        if (hasAllPermissions()) requestBatteryOptimizationExclusion()
        else permissionLauncher.launch(blePermissions)
    }

    private fun hasAllPermissions() = blePermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun isBatteryOptimizationExempt(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    // 앱 시작 시 배터리 최적화 제외 요청 (최초 1회)
    private fun requestBatteryOptimizationOnStart() {
        if (isBatteryOptimizationExempt()) return
        AlertDialog.Builder(this)
            .setTitle("백그라운드 실행 권한 필요")
            .setMessage(
                "SafeAlert가 화면이 꺼진 상태에서도 경보를 울리려면 배터리 최적화 제외가 필요합니다.\n\n" +
                "다음 화면에서 '허용'을 탭해주세요."
            )
            .setPositiveButton("권한 허용") { _, _ ->
                try {
                    batteryOptLauncher.launch(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                } catch (_: Exception) {
                    openManufacturerBatterySettings()
                }
            }
            .setNegativeButton("나중에", null)
            .setCancelable(true)
            .show()
    }

    // 모드 선택 후 배터리 권한 확인
    private fun requestBatteryOptimizationExclusion() {
        if (isBatteryOptimizationExempt()) {
            startServiceWithCurrentMode(); return
        }
        try {
            batteryOptLauncher.launch(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (_: Exception) {
            openManufacturerBatterySettings()
            startServiceWithCurrentMode()
        }
    }

    // 제조사별 배터리 설정 화면으로 이동
    private fun openManufacturerBatterySettings() {
        val brand = Build.MANUFACTURER.lowercase()
        val intent = when {
            brand.contains("samsung") -> Intent().apply {
                component = android.content.ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.battery.ui.BatteryActivity"
                )
            }
            brand.contains("xiaomi") || brand.contains("redmi") -> Intent().apply {
                component = android.content.ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                )
            }
            brand.contains("huawei") || brand.contains("honor") -> Intent().apply {
                action = "huawei.intent.action.HSM_PROTECTED_APPS"
            }
            brand.contains("oppo") || brand.contains("realme") -> Intent().apply {
                component = android.content.ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )
            }
            else -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun startServiceWithCurrentMode() {
        // 혹시 pending stop이 있으면 취소 (중지→재시작 레이스 방지)
        pendingStopRunnable?.let { pendingStopHandler.removeCallbacks(it) }
        pendingStopRunnable = null
        val mode  = currentMode ?: return
        // 이름 입력 시 그 이름을 BLE 송출 ID로 사용 (상대방 화면에 표시됨)
        val displayName = prefs.getString("display_name", "")?.trim()
        val id = if (!displayName.isNullOrEmpty()) displayName else myId()
        val since = System.currentTimeMillis()
        val action = if (mode == "DEVICE") BleService.ACTION_START_DEVICE else BleService.ACTION_START_WALKER

        val intent = Intent(this, BleService::class.java).apply {
            this.action = action
            putExtra(BleService.EXTRA_ID, id)
            putExtra(BleService.EXTRA_CATEGORY, currentCategory)   // [v1.0.34] 역할 Category 전달
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)

        prefs.edit()
            .putString("running_mode", mode)
            .putLong("running_since", since)
            .putInt("running_category", currentCategory)
            .apply()
        showRunningUi(mode, since)
    }

    private var pendingStopRunnable: Runnable? = null
    private val pendingStopHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun stopServiceWithDelay() {
        prefs.edit().remove("running_mode").remove("running_since").apply()
        currentMode = null

        // ── 소리/진동/오버레이 즉시 중지 (서비스 종료 기다리지 않음) ──
        com.wf11.safealert.service.AlertSoundPlayer.stopSound()
        com.wf11.safealert.service.VibrationHelper.stopVibration(this)
        com.wf11.safealert.utils.OverlayManager.hideOverlay()

        // 감지 목록 비우기
        detectedDevices.clear()

        // 테스트 경보 실행 중이었으면 버튼 상태 초기화
        if (testAlertRunning) {
            testAlertRunning = false
            binding.btnTestAlert.text = "🔔 테스트 경보"
            binding.btnTestAlert.setBackgroundColor(0xFFFEF3C7.toInt())
            binding.btnTestAlert.setTextColor(0xFFB45309.toInt())
        }

        // 무음 인디케이터 초기화
        muteAnimator?.cancel()
        binding.tvMutedIndicator.visibility = View.GONE

        binding.cardRunning.visibility  = View.GONE
        binding.layoutSelect.visibility = View.VISIBLE
        binding.layoutPermissionWarning.visibility = View.GONE

        // 서비스 종료는 500ms 후 (BLE 스택 정리 시간)
        pendingStopRunnable?.let { pendingStopHandler.removeCallbacks(it) }
        val r = Runnable {
            startService(Intent(this, BleService::class.java).apply { action = BleService.ACTION_STOP })
        }
        pendingStopRunnable = r
        pendingStopHandler.postDelayed(r, 500)
    }

    private fun restoreRunningState() {
        val mode  = prefs.getString("running_mode", null) ?: return
        val since = prefs.getLong("running_since", 0L)
        currentMode = mode
        currentCategory = prefs.getInt("running_category", BleConstants.CAT_WALKER)  // [v1.0.34] 역할 복원
        showRunningUi(mode, since)
    }

    private fun showRunningUi(mode: String, since: Long) {
        binding.layoutSelect.visibility  = View.GONE
        binding.cardRunning.visibility   = View.VISIBLE
        binding.tvApproaching.visibility = View.VISIBLE  // [v1.0.26 Req3] 중앙 안내(목록은 하단 tv_ble_status 로 이동)
        binding.layoutPermissionWarning.visibility = View.GONE
        updateDetectedDisplay()  // 초기 안내("감지 기기 없음") 표시
        // [v1.0.42 Req2] 내 장비 상태 라인 초기화 — 서비스 첫 localSnapshot 수신 전 기본값.
        binding.tvLocalState.text = "상태: 정지·일반 · 속도: 0 km/h"
        lastLocalSnapshot = ""
        binding.tvRunningMode.text  = roleDisplayName(currentCategory)   // [v1.0.34] 3-Role 라벨
        binding.tvRunningSince.text = SimpleDateFormat("HH:mm 시작", Locale.KOREA).format(Date(since))
        // 이름 입력 시 이름, 없으면 자동 ID 표시
        val displayName = prefs.getString("display_name", "")?.trim()
        binding.tvRunningId.text = if (!displayName.isNullOrEmpty()) displayName else myId()
        val bgColor = if (mode == "DEVICE") 0xFFEFF6FF.toInt() else 0xFFF0FDF4.toInt()
        binding.cardRunning.setCardBackgroundColor(bgColor)
        // 블루투스 상태 즉시 표시
        val btManager = getSystemService(android.bluetooth.BluetoothManager::class.java)
        binding.tvBleStatus.text = when {
            btManager?.adapter?.isEnabled != true -> "⚠ 블루투스 꺼짐! 설정에서 켜주세요"
            else -> "✓ 블루투스 ON · BLE 시작 중..."
        }
    }

    /** v1.0.34 Category(CAT_*) -> 실행 중 카드 역할명(이모지는 기존 자산 재사용, EPJ는 텍스트). */
    private fun roleDisplayName(category: Int): String = when (category) {
        BleConstants.CAT_EPJ      -> "EPJ 작업자"
        BleConstants.CAT_FORKLIFT -> "🚛 지게차"
        BleConstants.CAT_WALKER   -> "🚶 보행자"
        else                      -> "🚶 보행자"
    }

    private fun saveDisplayName() {
        val name = binding.etDisplayName.text?.toString()?.trim() ?: ""
        prefs.edit().putString("display_name", name).apply()
    }

    private fun myId(): String {
        val saved = prefs.getString("device_id", null)
        if (saved != null) return saved
        val newId = "SA-" + UUID.randomUUID().toString().take(8).uppercase()
        prefs.edit().putString("device_id", newId).apply()
        return newId
    }

    private fun showPermissionWarning(msg: String, onClick: () -> Unit) {
        binding.layoutPermissionWarning.visibility = View.VISIBLE
        binding.tvPermissionMsg.text = msg
        binding.btnGrant.visibility  = View.VISIBLE
        binding.btnGrant.setOnClickListener { onClick() }
    }

    private fun requestOverlayPermissionIfNeeded() {
        if (!OverlayManager.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("화면 표시 권한 필요")
                .setMessage("다른 앱 사용 중에도 경보 위젯을 띄우려면\n'다른 앱 위에 표시' 권한이 필요합니다.")
                .setPositiveButton("권한 설정") { _, _ ->
                    startActivity(Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ))
                }
                .setNegativeButton("나중에", null)
                .show()
        }
    }

    private fun toggleTestAlert() {
        // 서비스 미실행 상태면 테스트 불가
        if (!BleService.isRunning && !testAlertRunning) return
        testAlertRunning = !testAlertRunning
        val action = if (testAlertRunning) BleService.ACTION_TEST_START else BleService.ACTION_TEST_STOP
        startService(Intent(this, BleService::class.java).apply { this.action = action })
        binding.btnTestAlert.text = if (testAlertRunning) "⏹ 테스트 중지" else "🔔 테스트 경보"
        val color = if (testAlertRunning) 0xFFFEE2E2.toInt() else 0xFFFEF3C7.toInt()
        val textColor = if (testAlertRunning) 0xFFDC2626.toInt() else 0xFFB45309.toInt()
        binding.btnTestAlert.setBackgroundColor(color)
        binding.btnTestAlert.setTextColor(textColor)
    }

    private fun checkBluetoothStatus() {
        val btManager = getSystemService(android.bluetooth.BluetoothManager::class.java)
        val status = when {
            btManager?.adapter == null         -> "❌ 블루투스 미지원 기기"
            btManager.adapter?.isEnabled != true -> "⚠ 블루투스 꺼짐 — 설정에서 켜주세요"
            else                                -> "✓ 블루투스 ON — 모드 선택 후 시작하세요"
        }
        if (::binding.isInitialized && binding.cardRunning.visibility == android.view.View.VISIBLE) {
            binding.tvBleStatus.text = status
        }
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        })
    }

    // ── 자동 업데이트 ──────────────────────────────────────────
    private fun checkUpdate() {
        UpdateManager.checkForUpdate(this) { info ->
            info ?: return@checkForUpdate
            runOnUiThread { showUpdateDialog(info) }
        }
    }

    private fun showUpdateDialog(info: UpdateManager.UpdateInfo) {
        val msg = "v${UpdateManager.CURRENT_VERSION}  →  v${info.latest}" +
                if (info.changelog.isNotBlank()) "\n\n${info.changelog}" else ""
        val builder = AlertDialog.Builder(this)
            .setTitle("새 버전이 있습니다")
            .setMessage(msg)
            .setPositiveButton("지금 업데이트") { _, _ ->
                UpdateManager.downloadAndInstall(this, info.apkUrl)
            }
        if (!info.forceUpdate) builder.setNegativeButton("나중에", null)
        builder.setCancelable(!info.forceUpdate).show()
    }

    // ── PIN 다이얼로그 ──────────────────────────────────────────
    private fun showPinDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val pb = DialogPinBinding.inflate(layoutInflater)
        dialog.setContentView(pb.root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                (resources.displayMetrics.widthPixels * 0.92).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }

        val dots  = listOf(pb.dot1, pb.dot2, pb.dot3)
        val input = StringBuilder()

        fun updateDots() {
            dots.forEachIndexed { i, dot ->
                dot.setBackgroundResource(
                    if (i < input.length) R.drawable.shape_pin_dot_filled
                    else R.drawable.shape_pin_dot_empty
                )
            }
        }

        fun onDigit(d: String) {
            if (input.length >= 3) return
            input.append(d)
            updateDots()
            pb.tvError.visibility = View.INVISIBLE
            if (input.length == 3) {
                if (input.toString() == "368") {
                    dialog.dismiss()
                    startActivity(Intent(this, DevSettingsActivity::class.java))
                } else {
                    pb.tvError.visibility = View.VISIBLE
                    input.clear()
                    updateDots()
                }
            }
        }

        mapOf(pb.btn1 to "1", pb.btn2 to "2", pb.btn3 to "3",
              pb.btn4 to "4", pb.btn5 to "5", pb.btn6 to "6",
              pb.btn7 to "7", pb.btn8 to "8", pb.btn9 to "9",
              pb.btn0 to "0").forEach { (btn, digit) ->
            btn.setOnClickListener { onDigit(digit) }
        }
        pb.btnBack.setOnClickListener {
            if (input.isNotEmpty()) {
                input.deleteCharAt(input.length - 1)
                updateDots()
                pb.tvError.visibility = View.INVISIBLE
            }
        }

        dialog.show()
    }
}
