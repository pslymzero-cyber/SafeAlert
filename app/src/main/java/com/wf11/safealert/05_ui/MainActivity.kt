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
import android.content.res.ColorStateList
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
import androidx.core.widget.TextViewCompat
import com.google.android.material.button.MaterialButton
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

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // [v1.0.50 #3] 다크 리워크 — 시스템 바를 화면 배경색에 맞춤 (이 화면 한정, 테마 리소스는 불변)
        window.statusBarColor = 0xFF0B1220.toInt()
        window.navigationBarColor = 0xFF0B1220.toInt()

        // [v1.1.1] 시작 스플래시 — 신규 실행 시 1.5초 표시 후 페이드아웃. 화면 재생성(회전 등)은
        //   savedInstanceState 가 남아 있으므로 다시 띄우지 않고 즉시 숨긴다.
        if (savedInstanceState == null) {
            binding.ivSplash.postDelayed({
                binding.ivSplash.animate().alpha(0f).setDuration(300L)
                    .withEndAction { binding.ivSplash.visibility = View.GONE }
                    .start()
            }, 1500L)
        } else {
            binding.ivSplash.visibility = View.GONE
        }

        // [v1.0.54] 선택 화면 배경 — 창고 전경(bg_main) + 스크림. 실행 전환 시 applyRoleVisual 이
        //   역할별 배경으로 덮어쓰고, 중지 복귀 시 stopServiceImmediately 가 bg_main 으로 되돌린다.
        binding.ivRoleBackground.setImageResource(R.drawable.bg_main)
        binding.ivRoleBackground.visibility = View.VISIBLE
        binding.viewBgScrim.visibility      = View.VISIBLE

        // 하단 버전 표시 — BuildConfig에서 읽어 항상 최신값 반영
        binding.tvVersionFooter.text = "v${BuildConfig.VERSION_NAME}  ·  Created by Ian"
        // 저장된 이름 복원
        binding.etDisplayName.setText(prefs.getString("display_name", ""))

        // [v1.0.34] 3-Role 선택 — 보행자(WALKER) / EPJ·지게차(DEVICE) + Category 동시 지정
        binding.cardRoleWalker.setOnClickListener   { saveDisplayName(); onRoleSelected("WALKER", BleConstants.CAT_WALKER) }
        binding.cardRoleEpj.setOnClickListener      { saveDisplayName(); onRoleSelected("DEVICE", BleConstants.CAT_EPJ) }
        binding.cardRoleForklift.setOnClickListener { saveDisplayName(); onRoleSelected("DEVICE", BleConstants.CAT_FORKLIFT) }
        binding.btnStop.setOnClickListener       { stopServiceImmediately() }
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
        restoreRunningState()
        checkBluetoothStatus()
        requestBatteryOptimizationOnStart()
        requestOverlayPermissionIfNeeded()  // 오버레이 권한 요청
    }

    override fun onResume() {
        super.onResume()
        // [v1.0.46 배터리(b)] 800ms 상태 폴링을 화면 가시 구간으로 한정 — onResume 게시 / onPause 중단.
        //   백그라운드 감시는 BleService 단독 책임이라 Activity 폴링은 순수 전력 낭비였다.
        statusHandler.removeCallbacks(statusRunnable)
        statusHandler.post(statusRunnable)
        // BLE 설정 요약 업데이트 — [v1.1.8] 칼만 단일화(고정값·혼합 제거)
        binding.tvBleModeSummary.text =
            "칼만 필터 · 위험 ${DevSettings.rssiDanger}dBm / 경고 ${DevSettings.rssiWarning}dBm"
        val beaconCount = BeaconRegistry.count()
        binding.tvBeaconSummary.text = if (beaconCount > 0)
            "UUID ${beaconCount}개 등록됨 · iOS/앱 없는 보행자 감지 중"
        else
            "iOS/앱 없는 보행자 감지 설정"

        // [v1.1.2] 버전 체크를 onCreate → onResume 으로 이동 — 앱이 백그라운드에 살아 있다가
        //   메인 화면에 재진입할 때도 매번 확인한다. (onCreate 단독이던 시절엔 프로세스가 죽기
        //   전까지 재체크가 없어 새 버전 팝업을 놓침. 중복 팝업은 showUpdateDialog 가드가 차단)
        checkUpdate()
    }

    override fun onPause() {
        super.onPause()
        statusHandler.removeCallbacks(statusRunnable)   // [v1.0.46 배터리(b)]
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
     *   형식: "category / state / turnDir" (U+001F 필드 구분, BleService.broadcastLocalState 와 동일).
     *   수신 타겟 파서(applyDeviceListSnapshot)와 의도적으로 분리 — 두 채널이 절대 섞이지 않는다.
     */
    private fun parseLocalSnapshot(raw: String): LocalState? {
        if (raw.isEmpty()) return null
        val f = raw.split(31.toChar())   // U+001F 필드 구분자
        if (f.size < 3) return null
        val cat     = f[0].toIntOrNull() ?: return null
        val st      = f[1].toIntOrNull() ?: return null
        val turnDir = f[2].toIntOrNull() ?: BleConstants.TURN_STRAIGHT   // [v1.1.7 #1] 속도→회전
        return LocalState(cat, st, turnDir)
    }

    /**
     * [v1.0.42 Req2→v1.1.7 #1] 내 장비(Local) 상태/회전을 tv_local_state 에만 출력.
     *   역할(Category)은 tv_running_mode(roleDisplayName)가 담당 → 여기선 상태·회전만 표시(중복 방지).
     *   이 메서드는 tv_ble_status(수신 타겟)·detectedDevices 를 절대 건드리지 않는다.
     */
    private fun updateLocalDisplay(local: LocalState) {
        binding.tvLocalState.text =
            "상태: ${local.stateLabel} · 회전: ${local.turnLabel}"
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
            // 하단 목록 영역 → 어두운 인셋 박스 + 서비스 상태 1줄로 복귀
            // [v1.0.50 #3] backgroundTint 갱신 — shape_target_box 의 둥근 모서리를 유지한다.
            binding.tvBleStatus.backgroundTintList = ColorStateList.valueOf(0xE8101A2C.toInt())
            binding.tvBleStatus.setTextColor(0xFF7C93A8.toInt())
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
        binding.tvBleStatus.backgroundTintList = ColorStateList.valueOf(bgColor)   // [v1.0.50 #3] 둥근 모서리 유지
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

    private fun stopServiceImmediately() {
        // [v1.0.46 중지버그①] 500ms 지연 게시 제거 — 지연 틈에 START_STICKY 복원이 잔존
        //   prefs(running_mode)를 읽어 서비스를 부활시키던 핵심 경로를 끊는다.
        //   prefs 는 commit(동기) — ACTION_STOP 도착 전에 디스크 상태부터 '중지'로 확정.
        prefs.edit()
            .remove("running_mode")
            .remove("running_since")
            .remove("running_category")
            .commit()
        currentMode = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // [v1.0.46 배터리(a)] 중지 후 화면 소등 허용

        // ── 소리/진동/오버레이 즉시 중지 (서비스 종료 기다리지 않음) ──
        com.wf11.safealert.service.AlertSoundPlayer.stopSound()
        com.wf11.safealert.service.VibrationHelper.stopVibration(this)
        com.wf11.safealert.utils.OverlayManager.hideOverlay()

        // 감지 목록 비우기
        detectedDevices.clear()

        // 테스트 경보 실행 중이었으면 버튼 상태 초기화
        if (testAlertRunning) {
            testAlertRunning = false
            styleTestButton(testAccent)   // [v1.0.50 #3] TEST 버튼 평상 스타일 복귀
        }

        // 무음 인디케이터 초기화
        muteAnimator?.cancel()
        binding.tvMutedIndicator.visibility = View.GONE

        // [v1.0.54] 선택 화면 복귀 — 역할 배경을 창고 전경(bg_main)으로 전환 (스크림 유지)
        binding.ivRoleBackground.setImageResource(R.drawable.bg_main)
        binding.ivRoleBackground.visibility = View.VISIBLE
        binding.viewBgScrim.visibility      = View.VISIBLE

        binding.cardRunning.visibility  = View.GONE
        binding.layoutSelect.visibility = View.VISIBLE
        binding.layoutPermissionWarning.visibility = View.GONE

        // [v1.0.46 중지버그①] 즉시 게시 — BLE 스택 정리는 BleService.stopAll() 내부 책임이다.
        startService(Intent(this, BleService::class.java).apply { action = BleService.ACTION_STOP })
    }

    private fun restoreRunningState() {
        val mode  = prefs.getString("running_mode", null) ?: return
        val since = prefs.getLong("running_since", 0L)
        currentMode = mode
        currentCategory = prefs.getInt("running_category", BleConstants.CAT_WALKER)  // [v1.0.34] 역할 복원
        showRunningUi(mode, since)
    }

    private fun showRunningUi(mode: String, since: Long) {
        // [v1.0.46 배터리(a)] 화면 상시 점등을 '감시 중' 화면으로 한정 — 대기(역할 선택) 화면은
        //   정상 소등 허용. 기존엔 onCreate 무조건이라 앱만 띄워 두면 화면이 영구 점등됐다.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.layoutSelect.visibility  = View.GONE
        binding.cardRunning.visibility   = View.VISIBLE
        binding.tvApproaching.visibility = View.VISIBLE  // [v1.0.26 Req3] 중앙 안내(목록은 하단 tv_ble_status 로 이동)
        binding.layoutPermissionWarning.visibility = View.GONE
        updateDetectedDisplay()  // 초기 안내("감지 기기 없음") 표시
        // [v1.0.42 Req2] 내 장비 상태 라인 초기화 — 서비스 첫 localSnapshot 수신 전 기본값.
        binding.tvLocalState.text = "상태: 정지·일반 · 회전: 직진"
        lastLocalSnapshot = ""
        binding.tvRunningMode.text  = roleDisplayName(currentCategory)   // [v1.0.34] 3-Role 라벨
        binding.tvRunningSince.text = SimpleDateFormat("HH:mm 시작", Locale.KOREA).format(Date(since))
        // 이름 입력 시 이름, 없으면 자동 ID 표시
        val displayName = prefs.getString("display_name", "")?.trim()
        binding.tvRunningId.text = if (!displayName.isNullOrEmpty()) displayName else myId()
        // [v1.0.50 #3] 다크 리워크 — 역할별 배경 사진/아이콘/TEST 액센트 적용 (시각 요소만, 기능 불변)
        applyRoleVisuals(currentCategory)
        // 블루투스 상태 즉시 표시
        val btManager = getSystemService(android.bluetooth.BluetoothManager::class.java)
        binding.tvBleStatus.text = when {
            btManager?.adapter?.isEnabled != true -> "⚠ 블루투스 꺼짐! 설정에서 켜주세요"
            else -> "✓ 블루투스 ON · BLE 시작 중..."
        }
    }

    /** v1.0.34 Category(CAT_*) -> 실행 중 카드 역할명. [v1.0.50 #3] 이모지 제거 — 아이콘은 iv_role_icon(벡터)이 담당. */
    private fun roleDisplayName(category: Int): String = when (category) {
        BleConstants.CAT_EPJ      -> "EPJ 작업자"
        BleConstants.CAT_FORKLIFT -> "지게차"
        BleConstants.CAT_WALKER   -> "보행자"
        else                      -> "보행자"
    }

    // [v1.0.50 #3] TEST 버튼 평상시 액센트(역할별) — toggleTestAlert/stopServiceImmediately 가 복귀에 사용
    private var testAccent = 0xFFD7DEE8.toInt()

    /**
     * [v1.0.50 #3] 역할별 실행 화면 비주얼 — 배경 사진 / 역할 아이콘 / TEST 버튼 액센트.
     *   시각 요소만 바꾼다(감지·경보·송출 로직과 무관). 선택 화면 복귀 시 stopServiceImmediately 가 숨긴다.
     */
    private fun applyRoleVisuals(category: Int) {
        val (bgRes, iconRes, accent) = when (category) {
            BleConstants.CAT_FORKLIFT -> Triple(R.drawable.bg_forklift, R.drawable.ic_forklift, 0xFFF97316.toInt())
            BleConstants.CAT_EPJ      -> Triple(R.drawable.bg_epj,      R.drawable.ic_epj,      0xFFD7DEE8.toInt())
            else                      -> Triple(R.drawable.bg_walker,   R.drawable.ic_walker,   0xFF4ADE80.toInt())
        }
        binding.ivRoleBackground.setImageResource(bgRes)
        binding.ivRoleBackground.visibility = View.VISIBLE
        binding.viewBgScrim.visibility      = View.VISIBLE
        binding.ivRoleIcon.setImageResource(iconRes)
        styleTestButton(accent)
    }

    /** [v1.0.50 #3] TEST 버튼 평상시 스타일 — 투명 배경 + 역할 액센트 외곽선/텍스트/종 아이콘. */
    private fun styleTestButton(accent: Int) {
        testAccent = accent
        val btn = binding.btnTestAlert as MaterialButton
        btn.text = "TEST"
        btn.setTextColor(accent)
        btn.strokeColor = ColorStateList.valueOf(accent)
        btn.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        TextViewCompat.setCompoundDrawableTintList(btn, ColorStateList.valueOf(accent))
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
        // [v1.0.50 #3] 다크 리워크 — 테스트 중엔 적색 강조, 평상시엔 역할 액센트 외곽선 복귀
        if (testAlertRunning) {
            val btn = binding.btnTestAlert as MaterialButton
            btn.text = "STOP"
            btn.setTextColor(0xFFF87171.toInt())
            btn.strokeColor = ColorStateList.valueOf(0xFFF87171.toInt())
            btn.backgroundTintList = ColorStateList.valueOf(0x33DC2626)
            TextViewCompat.setCompoundDrawableTintList(btn, ColorStateList.valueOf(0xFFF87171.toInt()))
        } else {
            styleTestButton(testAccent)
        }
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
    // [v1.1.2] onResume 마다 체크하므로 다이얼로그 참조를 들고 중복 표시를 막는다
    private var updateDialog: AlertDialog? = null

    private fun checkUpdate() {
        UpdateManager.checkForUpdate(this) { info ->
            info ?: return@checkForUpdate
            runOnUiThread { showUpdateDialog(info) }
        }
    }

    private fun showUpdateDialog(info: UpdateManager.UpdateInfo) {
        // [v1.1.2] 이미 떠 있으면 재표시 금지 + 비동기 콜백이 종료 중 액티비티에 닿는 크래시 방지
        if (updateDialog?.isShowing == true) return
        if (isFinishing || isDestroyed) return
        val msg = "v${UpdateManager.CURRENT_VERSION}  →  v${info.latest}" +
                if (info.changelog.isNotBlank()) "\n\n${info.changelog}" else ""
        val builder = AlertDialog.Builder(this)
            .setTitle("새 버전이 있습니다")
            .setMessage(msg)
            .setPositiveButton("지금 업데이트") { _, _ ->
                UpdateManager.downloadAndInstall(this, info.apkUrl)
            }
        if (!info.forceUpdate) builder.setNegativeButton("나중에", null)
        updateDialog = builder.setCancelable(!info.forceUpdate).show()
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
