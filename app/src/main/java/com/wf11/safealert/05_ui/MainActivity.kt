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
    private var testAlertRunning = false

    // 감지된 기기 목록: deviceId → (displayName, alertLevel, rssi)
    // BROADCAST_DETECTED 로 갱신, BROADCAST_ALERT SAFE 로 제거
    private val detectedDevices = mutableMapOf<String, Triple<String, Int, Int>>()

    // 1초마다 서비스 상태 직접 폴링 (Broadcast 실패 대비)
    private val statusHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var muteAnimator: ObjectAnimator? = null

    private val statusRunnable = object : Runnable {
        private var lastText = ""
        private var lastMuted = false
        override fun run() {
            if (binding.cardRunning.visibility == View.VISIBLE) {
                // BLE 상태 텍스트 — 기기 감지 중에는 tvBleStatus 덮어쓰지 않음
                val text = when {
                    !BleService.isRunning -> "서비스 시작 중..."
                    BleService.lastStatus.isNotEmpty() -> BleService.lastStatus
                    else -> "✓ 블루투스 ON · BLE 시작 중..."
                }
                // [v1.0.25 Req3] tv_ble_status 는 항상 '서비스 상태' 1줄만 — 기기 목록과 분리
                if (text != lastText) {
                    binding.tvBleStatus.text = text
                    lastText = text
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

                // 기기 소실(SAFE) 또는 경보 레벨 확정 → 오버레이 관리
                BleService.BROADCAST_ALERT -> {
                    val level    = intent.getIntExtra(BleService.EXTRA_ALERT_LEVEL, BleConstants.LEVEL_SAFE)
                    val deviceId = intent.getStringExtra(BleService.EXTRA_ID) ?: ""

                    if (level == BleConstants.LEVEL_SAFE) {
                        // 기기 이탈 → 목록에서 제거 (플로팅 위젯은 BleService가 직접 관리)
                        detectedDevices.remove(deviceId)
                        updateDetectedDisplay()
                    } else {
                        // TTC DANGER 발령 등 — 레벨 업데이트
                        // null-safe: DETECTED가 throttle에 막혔을 때도 레벨 반영 보장
                        val alertDisplayName = intent.getStringExtra(BleService.EXTRA_DISPLAY_NAME) ?: ""
                        val existing = detectedDevices[deviceId]
                        detectedDevices[deviceId] = if (existing != null)
                            Triple(existing.first, level, existing.third)
                        else
                            Triple(alertDisplayName.ifEmpty { deviceId }, level, -99)
                        updateDetectedDisplay()
                    }
                }

                // 매 스캔 주기 감지 결과 (SAFE 포함) → 목록 갱신
                BleService.BROADCAST_DETECTED -> {
                    val deviceId    = intent.getStringExtra(BleService.EXTRA_ID) ?: return
                    val level       = intent.getIntExtra(BleService.EXTRA_ALERT_LEVEL, BleConstants.LEVEL_SAFE)
                    val displayName = intent.getStringExtra(BleService.EXTRA_DISPLAY_NAME) ?: return
                    val rssi        = intent.getIntExtra(BleService.EXTRA_RSSI, -99)
                    // SAFE 레벨이면 목록에서 제거 (경보 수준 미달 기기 영구 잔류 방지)
                    if (level == BleConstants.LEVEL_SAFE) {
                        detectedDevices.remove(deviceId)
                    } else {
                        detectedDevices[deviceId] = Triple(displayName, level, rssi)
                    }
                    updateDetectedDisplay()
                }

                BleService.BROADCAST_BLE_STATUS -> {
                    val status = intent.getStringExtra(BleService.EXTRA_STATUS) ?: return
                    // [v1.0.25 Req3] tv_ble_status 는 오직 서비스 상태 1줄만 — 항상 갱신
                    binding.tvBleStatus.text = status
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

        binding.cardDevice.setOnClickListener    { saveDisplayName(); onModeSelected("DEVICE") }
        binding.cardWalker.setOnClickListener    { saveDisplayName(); onModeSelected("WALKER") }
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
            addAction(BleService.BROADCAST_ALERT)
            addAction(BleService.BROADCAST_DETECTED)
            addAction(BleService.BROADCAST_BLE_STATUS)
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
                "칼만 필터 · 위험 ${DevSettings.dangerDistM.toInt()}m / 경고 ${DevSettings.warningDistM.toInt()}m"
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
        try { unregisterReceiver(alertReceiver) } catch (_: Exception) {}
        muteAnimator?.cancel()
    }

    /**
     * [v1.0.25 Req3] 감지 기기 목록을 tv_approaching 에 표시 (최대 10개).
     * 정렬: 위험도(level) 내림차순 → 같은 위험도면 RSSI 강한(가까운) 순.
     * 위험=빨강(1.22×·굵게), 경고=노랑(1.0×), 안전=연청(0.82×).
     * tv_ble_status 는 절대 건드리지 않는다 — 그쪽은 '서비스 상태' 1줄 전용.
     */
    private fun updateDetectedDisplay() {
        if (detectedDevices.isEmpty()) {
            binding.tvApproaching.setBackgroundColor(Color.TRANSPARENT)
            binding.tvApproaching.setTextColor(0xFF8AAFC4.toInt())
            binding.tvApproaching.text = "주변 감지 기기 없음 · 감시 중"
            return
        }
        // 위험도 우선 → 같은 위험도면 RSSI 강한(가까운) 순, 최대 10개
        val sorted = detectedDevices.values
            .sortedWith(
                compareByDescending<Triple<String, Int, Int>> { it.second }
                    .thenByDescending { it.third }
            )
            .take(10)

        val sb = SpannableStringBuilder()
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

        val topLevel = sorted.first().second
        val bgColor = when {
            topLevel >= BleConstants.LEVEL_DANGER  -> 0xDD1A0000.toInt()  // 짙은 적
            topLevel == BleConstants.LEVEL_WARNING -> 0xDD1A1400.toInt()  // 짙은 황
            else                                   -> 0xDD051220.toInt()  // 짙은 남색
        }
        binding.tvApproaching.setBackgroundColor(bgColor)
        binding.tvApproaching.setTextColor(0xFFE0F0FF.toInt())
        binding.tvApproaching.text = sb
    }

    private fun onModeSelected(mode: String) {
        currentMode = mode
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
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)

        prefs.edit().putString("running_mode", mode).putLong("running_since", since).apply()
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
        showRunningUi(mode, since)
    }

    private fun showRunningUi(mode: String, since: Long) {
        binding.layoutSelect.visibility  = View.GONE
        binding.cardRunning.visibility   = View.VISIBLE
        binding.tvApproaching.visibility = View.VISIBLE  // [v1.0.25 Req3] 감지 기기 목록 전용 영역
        binding.layoutPermissionWarning.visibility = View.GONE
        updateDetectedDisplay()  // 초기 안내("감지 기기 없음") 표시
        binding.tvRunningMode.text  = if (mode == "DEVICE") "🚛 장비 작업자" else "🚶 보행자"
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
