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
import com.wf11.safealert.R
import com.wf11.safealert.ble.BleConstants
import com.wf11.safealert.utils.BeaconRegistry
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
    private var overlayAnimator: ObjectAnimator? = null
    private var testAlertRunning = false

    // 1초마다 서비스 상태 직접 폴링 (Broadcast 실패 대비)
    private val statusHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var muteAnimator: ObjectAnimator? = null

    private val statusRunnable = object : Runnable {
        private var lastText = ""
        private var lastMuted = false
        override fun run() {
            if (binding.cardRunning.visibility == View.VISIBLE) {
                // BLE 상태 텍스트
                val text = when {
                    !BleService.isRunning -> "서비스 시작 중..."
                    BleService.lastStatus.isNotEmpty() -> BleService.lastStatus
                    else -> "✓ 블루투스 ON · BLE 시작 중..."
                }
                if (text != lastText) { binding.tvBleStatus.text = text; lastText = text }

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

    // 경보 브로드캐스트 수신 → 화면 테두리 라이팅
    private val alertReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BleService.BROADCAST_ALERT -> {
                    val level = intent.getIntExtra(BleService.EXTRA_ALERT_LEVEL, BleConstants.LEVEL_SAFE)
                    when (level) {
                        BleConstants.LEVEL_DANGER  -> showAlertOverlay(danger = true)
                        BleConstants.LEVEL_WARNING -> showAlertOverlay(danger = false)
                        BleConstants.LEVEL_SAFE    -> hideAlertOverlay()
                    }
                }
                BleService.BROADCAST_BLE_STATUS -> {
                    val status = intent.getStringExtra(BleService.EXTRA_STATUS) ?: return
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

        binding.cardDevice.setOnClickListener    { onModeSelected("DEVICE") }
        binding.cardWalker.setOnClickListener    { onModeSelected("WALKER") }
        binding.btnStop.setOnClickListener       { stopServiceWithDelay() }
        binding.cardSettings.setOnClickListener  { showPinDialog() }
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
        overlayAnimator?.cancel()
        muteAnimator?.cancel()
    }

    // ── 경보 오버레이 ──────────────────────────────────────────
    private fun showAlertOverlay(danger: Boolean) {
        overlayAnimator?.cancel()
        binding.alertOverlay.visibility = View.VISIBLE
        binding.alertOverlay.setBackgroundResource(
            if (danger) R.drawable.shape_danger_border else R.drawable.shape_warning_border
        )
        overlayAnimator = ObjectAnimator.ofFloat(binding.alertOverlay, "alpha", 0.2f, 1.0f).apply {
            duration = if (danger) 400 else 700
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
        // 인앱 오버레이 터치 → 10초 무음
        binding.alertOverlay.isClickable = true
        binding.alertOverlay.isFocusable = false
        binding.alertOverlay.setOnClickListener {
            startService(Intent(this, BleService::class.java).apply {
                action = BleService.ACTION_MUTE_TEMP
            })
            hideAlertOverlay()
        }
    }

    private fun hideAlertOverlay() {
        overlayAnimator?.cancel()
        binding.alertOverlay.visibility = View.GONE
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
        val id    = myId()
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

        hideAlertOverlay()

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

    private fun stopService() {
        startService(Intent(this, BleService::class.java).apply { action = BleService.ACTION_STOP })
        prefs.edit().remove("running_mode").remove("running_since").apply()
        currentMode = null
        hideAlertOverlay()
        binding.cardRunning.visibility  = View.GONE
        binding.layoutSelect.visibility = View.VISIBLE
        binding.layoutPermissionWarning.visibility = View.GONE
    }

    private fun restoreRunningState() {
        val mode  = prefs.getString("running_mode", null) ?: return
        val since = prefs.getLong("running_since", 0L)
        currentMode = mode
        showRunningUi(mode, since)
    }

    private fun showRunningUi(mode: String, since: Long) {
        binding.layoutSelect.visibility = View.GONE
        binding.cardRunning.visibility  = View.VISIBLE
        binding.layoutPermissionWarning.visibility = View.GONE
        binding.tvRunningMode.text  = if (mode == "DEVICE") "🚛 장비 작업자" else "🚶 보행자"
        binding.tvRunningSince.text = SimpleDateFormat("HH:mm 시작", Locale.KOREA).format(Date(since))
        val bgColor = if (mode == "DEVICE") 0xFFEFF6FF.toInt() else 0xFFF0FDF4.toInt()
        binding.cardRunning.setCardBackgroundColor(bgColor)
        // 블루투스 상태 즉시 표시
        val btManager = getSystemService(android.bluetooth.BluetoothManager::class.java)
        binding.tvBleStatus.text = when {
            btManager?.adapter?.isEnabled != true -> "⚠ 블루투스 꺼짐! 설정에서 켜주세요"
            else -> "✓ 블루투스 ON · BLE 시작 중..."
        }
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
                .setMessage("다른 앱 사용 중에도 경보 테두리를 표시하려면\n'다른 앱 위에 표시' 권한이 필요합니다.")
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
