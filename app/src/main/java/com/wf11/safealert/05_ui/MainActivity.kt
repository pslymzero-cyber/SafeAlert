package com.wf11.safealert.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.InputFilter
import android.text.InputType
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
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import com.google.android.material.button.MaterialButton
import com.wf11.safealert.BuildConfig
import com.wf11.safealert.R
import com.wf11.safealert.ble.BleConstants
import com.wf11.safealert.ble.LocalState
import com.wf11.safealert.model.PitType
import com.wf11.safealert.utils.BeaconRegistry
import com.wf11.safealert.utils.DevSettings
import com.wf11.safealert.utils.OverlayManager
import com.wf11.safealert.databinding.ActivityMainBinding
import com.wf11.safealert.databinding.DialogPitSelectBinding
import com.wf11.safealert.firebase.FirebaseManager
import com.wf11.safealert.service.BleService
import com.wf11.safealert.utils.UwbCalibrator
import com.wf11.safealert.utils.UpdateManager
import com.wf11.safealert.utils.UwbRanger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("safealert_prefs", MODE_PRIVATE) }
    private var currentMode: String? = null
    // Selected role (Category) — passed BleService → BleAdvertiser as bits[1:0] of the 1-byte payload
    private var currentCategory: Int = BleConstants.CAT_WALKER
    private var testAlertRunning = false

    // Detected-device list — replaced wholesale with each BleService.alertState snapshot.
    // Sorted list of (displayName, alertLevel, rssi, dist). Single source of truth: no partial add/remove, so it cannot drift.
    // dist = distance string computed by the service (4th field, may be empty); when empty, rendering falls back to dBm.
    private data class DetectedRow(val name: String, val level: Int, val rssi: Int, val dist: String)
    private val detectedDevices = mutableListOf<DetectedRow>()

    // Last-applied snapshot shared by the broadcast receiver and the polling fallback.
    //   Same value → both are no-ops (no duplicate render). The initial sentinel() also differs from an empty list ("").
    private var lastSyncedSnapshot = ""

    // Last-applied snapshot for my own device (Local) state only.
    //   Shared by the broadcast (BROADCAST_LOCAL_STATE) and the 800ms poll (BleService.localSnapshot).
    //   Note: this channel carries only the state I advertise. It is physically separate from received targets
    //     (detectedSnapshot), so a peer payload can never overwrite my device display (core invariant).
    private var lastLocalSnapshot = ""

    // Poll the service state directly every second (in case broadcasts fail)
    private val statusHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var muteAnimator: ObjectAnimator? = null

    // UI render throttle — coalesces detected-list (TextView) updates to at least uiRenderThrottleMs (500ms)
    //   apart to cut UI-thread/GPU redraw load and power. Data intake (detectedDevices) and
    //   background computation (BleService/Kalman) stay real-time — only drawing to the screen is limited.
    //   A risk-level increase (especially entering DANGER = warning background/icon)
    //   bypasses the throttle and renders immediately (safety first).
    private val uiThrottleHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val uiRenderThrottleMs = 500L
    private var lastRenderMs = 0L
    private var pendingRender = false
    private var lastRenderedTopLevel = BleConstants.LEVEL_SAFE
    private val renderRunnable = Runnable {
        pendingRender = false
        lastRenderMs = android.os.SystemClock.elapsedRealtime()
        lastRenderedTopLevel = detectedDevices.maxOfOrNull { it.level } ?: BleConstants.LEVEL_SAFE
        updateDetectedDisplay()
    }

    private val statusRunnable = object : Runnable {
        private var lastText = ""
        private var lastMuted = false
        override fun run() {
            LoneWorkerUi.onPoll(this@MainActivity, binding.tvLwStatus, binding.layoutPermissionWarning, binding.tvPermissionMsg, currentMode == null, this@MainActivity::showPermissionWarning) { restoreRunningState() }   // Check/SOS screen entry, stop-race recovery, charging hint, reachability recheck
            if (binding.cardRunning.visibility == View.VISIBLE) {
                // Fallback for missed broadcasts — reads the service snapshot (BleService.detectedSnapshot)
                //   directly to sync the list. With working broadcasts the value is the same (no-op);
                //   if one is missed (RECEIVER_NOT_EXPORTED / implicit delivery failure) this recovers within 800ms.
                val snap = BleService.detectedSnapshot
                if (snap != lastSyncedSnapshot) {
                    lastSyncedSnapshot = snap
                    applyDeviceListSnapshot(snap)
                    requestDetectedRender()
                }
                // My device (Local) state/speed — polls only the own-advertising channel (localSnapshot).
                //   It is a completely different source from tv_ble_status (received targets), so received data cannot leak in here.
                val localSnap = BleService.localSnapshot
                if (localSnap != lastLocalSnapshot) {
                    lastLocalSnapshot = localSnap
                    parseLocalSnapshot(localSnap)?.let { updateLocalDisplay(it) }
                }
                // tv_ble_status is dedicated to the detected-device list.
                // Only when the list is empty does it temporarily show a one-line service status there.
                val text = when {
                    !BleService.isRunning -> "서비스 시작 중..."
                    BleService.lastStatus.isNotEmpty() -> BleService.lastStatus
                    else -> "블루투스 ON · BLE 시작 중..."
                }
                if (detectedDevices.isEmpty()) {
                    if (text != lastText) {
                        binding.tvBleStatus.text = text
                        lastText = text
                    }
                } else {
                    // List is showing → invalidate the cache so the status line always comes back once the list empties
                    lastText = ""
                }
                // Clear the detected list once the service has fully stopped
                if (!BleService.isRunning && detectedDevices.isNotEmpty()) {
                    detectedDevices.clear()
                    updateDetectedDisplay()
                }

                // Mute indicator (blinking banner)
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

    private val blePermissions = LoneWorkerUi.runPermissions   // Service start permissions (ServiceStartGate.required) + fine location

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Only the required BLE permissions are checked — UWB_RANGING is optional (BLE works if denied)
        if (hasAllPermissions()) afterPermissions()
        else showPermissionWarning("BLE · 위치 권한이 필요합니다. 탭하여 허용해주세요.") { openAppSettings() }
    }

    // Android 11 location "항상 허용" (allow all the time) — if denied, startup continues (main-screen warning only)
    private val bgLocationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        requestBatteryOptimizationExclusion()
    }

    private fun afterPermissions() =
        if (LoneWorkerUi.needsBackgroundLocation(this)) bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        else requestBatteryOptimizationExclusion()

    private val batteryOptLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { startServiceWithCurrentMode() }

    // Alert / detection broadcast receiver
    private val alertReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {

                // Receives the full alertState snapshot (serialized list) sent by BleService
                // and rebuilds detectedDevices each time. No partial add/remove, so it cannot drift.
                BleService.BROADCAST_DETECTED -> {
                    val raw = intent.getStringExtra(BleService.EXTRA_DEVICE_LIST) ?: ""
                    // Shares the same parser as the polling fallback and syncs lastSyncedSnapshot (no duplicate render).
                    lastSyncedSnapshot = raw
                    applyDeviceListSnapshot(raw)
                    // Render through the throttle instead of immediately (500ms coalescing; risk-level increases bypass it).
                    //   Data (detectedDevices) is already up to date above — only screen output is limited.
                    requestDetectedRender()
                }

                BleService.BROADCAST_BLE_STATUS -> {
                    val status = intent.getStringExtra(BleService.EXTRA_STATUS) ?: return
                    // Show the service status at the bottom only when the list is empty (otherwise keep the list).
                    if (detectedDevices.isEmpty()) {
                        binding.tvBleStatus.text = status
                    }
                }

                // My device (Local) state push — a separate channel that received-target data can never touch.
                //   Updates only tv_local_state (my device); never touches detectedDevices/tv_ble_status.
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

        // Dark UI — match the system bars to the screen background (this screen only; theme resources unchanged)
        window.statusBarColor = 0xFF0B1220.toInt()
        window.navigationBarColor = 0xFF0B1220.toInt()

        // Launch splash — shown for 1.5 s on a fresh launch, then fades out. On re-creation (rotation etc.)
        //   savedInstanceState is present, so hide it immediately instead of showing it again.
        if (savedInstanceState == null) {
            binding.ivSplash.postDelayed({
                binding.ivSplash.animate().alpha(0f).setDuration(300L)
                    .withEndAction { binding.ivSplash.visibility = View.GONE }
                    .start()
            }, 1500L)
        } else {
            binding.ivSplash.visibility = View.GONE
        }

        // Selection screen background — warehouse view (bg_main) + scrim. When running starts, applyRoleVisuals
        //   replaces it with the role background; on stop, stopServiceImmediately restores bg_main.
        binding.ivRoleBackground.setImageResource(R.drawable.bg_main)
        binding.ivRoleBackground.visibility = View.VISIBLE
        binding.viewBgScrim.visibility      = View.VISIBLE

        // Version shown at the bottom — read from BuildConfig so it is always current
        binding.tvVersionFooter.text = "v${BuildConfig.VERSION_NAME}  ·  Created by Ian"
        // Once on the first launch after an update (fresh installs included) — show what's new. Text = strings.xml whats_new
        if (prefs.getInt("last_seen_version_code", 0) < BuildConfig.VERSION_CODE) {
            prefs.edit().putInt("last_seen_version_code", BuildConfig.VERSION_CODE).apply()
            AlertDialog.Builder(this)
                .setTitle("v${BuildConfig.VERSION_NAME} 변경 사항")
                .setMessage(R.string.whats_new)
                .setPositiveButton("확인", null)
                .show()
        }
        // Migrate stored old-format values (person names) — runs before restore so stale values never show on screen
        migrateDisplayNameToPitId()
        // The display name is not typed. Tapping opens a type/number picker popup.
        //   Removing the input method itself is the point — the screen has no path for a person's name to come in.
        binding.etDisplayName.apply {
            isFocusable = false
            isFocusableInTouchMode = false
            isCursorVisible = false
            keyListener = null                     // Block soft-keyboard and hardware-key input
            setOnClickListener { showPitSelectDialog { } }   // For picking in advance, before starting
        }
        renderDisplayName()
        // Restore the saved site code — same value as the BLE settings UWB section (dev_settings.uwb_site_code).
        // Editable only while empty; once set it is locked and can be changed only in developer settings.
        refreshSiteCodeField()

        // Role selection — walker (WALKER) / EPJ·forklift (DEVICE), setting Category at the same time.
        //   Without a site code, requireSiteCode shows the input popup and blocks start.
        // There is a single equipment card. The chosen equipment sets the role (Category) —
        //   picking a role first and then equipment could make the two disagree.
        //   EPJ and walkie stacker are picked from the equipment list.
        binding.cardRoleWalker.setOnClickListener   { requireSiteCode { onRoleSelected("WALKER", BleConstants.CAT_WALKER) } }
        binding.cardRoleForklift.setOnClickListener { requireSiteCode { startAsPitOperator() } }
        binding.btnStop.setOnClickListener       { if (!LoneWorkerUi.blockIfOwnSos(this)) stopServiceImmediately() }
        binding.btnSwitchRole.setOnClickListener { confirmSwitchRole() }   // Role switch
        binding.cardSettings.setOnClickListener  { showDevPinDialog { startActivity(Intent(this, DevSettingsActivity::class.java)) } }
        binding.cardBleSettings.setOnClickListener {
            startActivity(Intent(this, BleSettingsActivity::class.java))
        }
        // Beacon management (register, safe zone, delete, search, receive shared) opens after a PIN check
        binding.cardBeacon.setOnClickListener    {
            showDevPinDialog { startActivity(Intent(this, BeaconManagerActivity::class.java)) }
        }
        binding.btnTestAlert.setOnClickListener  { toggleTestAlert() }
        binding.tvMutedIndicator.setOnClickListener {
            startService(Intent(this, BleService::class.java).apply {
                action = BleService.ACTION_UNMUTE
            })
        }

        val filter = IntentFilter().apply {
            // Not subscribed to BROADCAST_ALERT — BleService (alertState) alone manages both the list and the floating view.
            addAction(BleService.BROADCAST_DETECTED)
            addAction(BleService.BROADCAST_BLE_STATUS)
            addAction(BleService.BROADCAST_LOCAL_STATE)   // My device state channel
        }
        registerReceiver(alertReceiver, filter, RECEIVER_NOT_EXPORTED)
        restoreRunningState()
        checkBluetoothStatus()
        requestBatteryOptimizationOnStart()
        requestOverlayPermissionIfNeeded()  // Request overlay permission
        handleSwitchRoleIntent(intent)      // Entered via the persistent notification's switch action
    }

    // Path for a notification action tapped while the app is already open.
    //   The PendingIntent is CLEAR_TOP|SINGLE_TOP, so it arrives here instead of in a new instance.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSwitchRoleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Limit the 800ms status poll to when the screen is visible — post in onResume / stop in onPause.
        //   Background monitoring is BleService's job alone, so Activity polling would only waste power.
        statusHandler.removeCallbacks(statusRunnable)
        statusHandler.post(statusRunnable)
        // Restore the service after a shift handover (saved SOS), or when only the running state remains after a failed start / force stop
        if (!LoneWorkerUi.reviveIfStoredSos(this)) LoneWorkerUi.reviveIfStopped(this)
        // Reflect a site code changed in developer settings when returning to this screen
        refreshSiteCodeField()
        // Update the BLE settings summary — Kalman filter only (no fixed/mixed modes)
        binding.tvBleModeSummary.text =
            "칼만 필터 · 위험 ${DevSettings.rssiDanger}dBm / 경고 ${DevSettings.rssiWarning}dBm"
        val beaconCount = BeaconRegistry.count()
        binding.tvBeaconSummary.text = if (beaconCount > 0)
            "UUID ${beaconCount}개 등록됨 · iOS/앱 없는 보행자 감지 중"
        else
            "iOS/앱 없는 보행자 감지 설정"

        // Check the version in onResume, not onCreate — so it is also checked every time the main screen is re-entered while
        //   the app stays alive in the background. (Checking only in onCreate would miss the update popup until the process
        //   dies. The showUpdateDialog guard blocks duplicate popups.)
        checkUpdate()
    }

    override fun onPause() {
        super.onPause()
        statusHandler.removeCallbacks(statusRunnable)
        saveSiteCode()   // Keep the entered code even when leaving without starting a mode
    }

    override fun onDestroy() {
        super.onDestroy()
        statusHandler.removeCallbacks(statusRunnable)
        uiThrottleHandler.removeCallbacks(renderRunnable)   // Clean up the UI throttle timer
        try { unregisterReceiver(alertReceiver) } catch (_: Exception) {}
        muteAnimator?.cancel()
    }

    /**
     * Shared parser: parses the BleService serialized snapshot into detectedDevices.
     *   Records are separated by U+001E; fields = "level / rssi / name" (separated by U+001F).
     *   The broadcast receiver and the 800ms polling fallback use this same parser so the two paths never disagree.
     */
    private fun applyDeviceListSnapshot(raw: String) {
        detectedDevices.clear()
        if (raw.isEmpty()) return
        val recSep  = 30.toChar()   // U+001E record separator (same as BleService output)
        val unitSep = 31.toChar()   // U+001F field separator
        raw.split(recSep).forEach { rec ->
            val f = rec.split(unitSep)
            if (f.size >= 3) {
                val level = f[0].toIntOrNull() ?: BleConstants.LEVEL_SAFE
                val rssi  = f[1].toIntOrNull() ?: -99
                val name  = f[2]
                // 4th field = distance string (optional) — still compatible with old 3-field snapshots.
                val dist  = if (f.size >= 4) f[3] else ""
                detectedDevices.add(DetectedRow(name, level, rssi, dist))
            }
        }
    }

    /**
     * Parser for my device (Local) snapshot — only for BleService.localSnapshot / EXTRA_LOCAL_STATE.
     *   Format: "category / state / turnDir" (U+001F field separator, same as BleService.broadcastLocalState).
     *   Deliberately separate from the received-target parser (applyDeviceListSnapshot) — the two channels never mix.
     */
    private fun parseLocalSnapshot(raw: String): LocalState? {
        if (raw.isEmpty()) return null
        val f = raw.split(31.toChar())   // U+001F field separator
        if (f.size < 3) return null
        val cat     = f[0].toIntOrNull() ?: return null
        val st      = f[1].toIntOrNull() ?: return null
        val turnDir = f[2].toIntOrNull() ?: BleConstants.TURN_STRAIGHT
        val inZone  = f.getOrNull(3) == "1"                              // No 4th field → false (old-format compatibility)
        return LocalState(cat, st, turnDir, inZone)
    }

    /**
     * Writes my device (Local) state/turn only to tv_local_state.
     *   The role (Category) is shown by tv_running_mode (roleDisplayName), so only state and turn are shown here (no duplication).
     *   This method never touches tv_ble_status (received targets) or detectedDevices.
     */
    private fun updateLocalDisplay(local: LocalState) {
        binding.tvLocalState.text =
            (if (local.inZone) "세이프존 · " else "") + "상태: ${local.stateLabel} · 회전: ${local.turnLabel}"
    }

    /**
     * UI throttle: request a detected-list render, coalesced to at least uiRenderThrottleMs (500ms) apart.
     *  - A risk-level increase (topLevel↑ vs the last render, especially entering DANGER) bypasses the throttle:
     *    the danger background color, icon and size emphasis reach the screen with no delay (safety first).
     *  - Other updates (RSSI number changes, safe/warning list changes) reach the screen only every 500ms,
     *    lowering UI-thread/GPU redraw frequency (saves power). Background computation is unaffected.
     *  - If the last change is throttled, a trailing timer (renderRunnable) renders once with the latest snapshot.
     */
    private fun requestDetectedRender() {
        val topLevel = detectedDevices.maxOfOrNull { it.level } ?: BleConstants.LEVEL_SAFE
        val now = android.os.SystemClock.elapsedRealtime()
        val escalated = topLevel > lastRenderedTopLevel          // Risk level up = critical → render now
        if (escalated || now - lastRenderMs >= uiRenderThrottleMs) {
            uiThrottleHandler.removeCallbacks(renderRunnable)     // Cancel the scheduled trailing render
            pendingRender = false
            lastRenderMs = now
            lastRenderedTopLevel = topLevel
            updateDetectedDisplay()
        } else if (!pendingRender) {
            // First update within the throttle window → schedule one trailing render after the remaining time (no duplicate scheduling)
            pendingRender = true
            uiThrottleHandler.postDelayed(renderRunnable, uiRenderThrottleMs - (now - lastRenderMs))
        }
        // pendingRender==true means already scheduled — nothing more to do (latest data is kept in detectedDevices)
    }

    /**
     * Writes the detected-device list directly to tv_ble_status, the box at the bottom of the screen.
     * - At least one device: hide the center hint (tv_approaching) with GONE (no ghost text),
     *   and setText a risk-colored Spannable list (max 10) on a dark background tinted by the top risk level.
     * - Empty: show the center hint again; the bottom box returns to the dark inset style with a one-line service status.
     * detectedDevices has a single source (the BleService snapshot), so alarms and the list never disagree.
     */
    private fun updateDetectedDisplay() {
        if (detectedDevices.isEmpty()) {
            // Restore the center hint
            binding.tvApproaching.visibility = View.VISIBLE
            binding.tvApproaching.setBackgroundColor(Color.TRANSPARENT)
            binding.tvApproaching.setTextColor(0xFF8AAFC4.toInt())
            binding.tvApproaching.text = "주변 감지 기기 없음 · 감시 중"
            // Bottom list area → back to the dark inset box + one-line service status
            // Set backgroundTint so shape_target_box keeps its rounded corners.
            binding.tvBleStatus.backgroundTintList = ColorStateList.valueOf(0xE8101A2C.toInt())
            binding.tvBleStatus.setTextColor(0xFF7C93A8.toInt())
            val status = BleService.lastStatus
            binding.tvBleStatus.text = if (status.isNotEmpty()) status else "· 감시 중 ·"
            return
        }

        // With devices present, hide the center 'no detection' ghost text immediately.
        binding.tvApproaching.visibility = View.GONE

        // Risk level first → same level: stronger (closer) RSSI first; max 10
        val sorted = detectedDevices
            .sortedWith(
                compareByDescending<DetectedRow> { it.level }
                    .thenByDescending { it.rssi }
            )
            .take(10)

        val sb = SpannableStringBuilder()
        // Header "주변 감지 기기 N건" (nearby detected devices: N) at the top of the list (small, light gray).
        run {
            val hStart = sb.length
            sb.append("주변 감지 기기 ${detectedDevices.size}건\n")
            sb.setSpan(ForegroundColorSpan(0xFF9FB8C8.toInt()), hStart, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(0.78f), hStart, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        sorted.forEachIndexed { idx, (name, level, rssi, dist) ->
            if (idx > 0) sb.append("\n")
            val prefix = when (level) {
                BleConstants.LEVEL_DANGER  -> "위험"
                BleConstants.LEVEL_WARNING -> "경고"
                else                       -> "감지"
            }
            // Use the distance string from the service as is if present; otherwise fall back to dBm.
            val meas = if (dist.isNotEmpty()) dist else "${rssi}dBm"
            val line = "$prefix  $name   $meas"
            val start = sb.length
            sb.append(line)
            val end = sb.length
            val color = when (level) {
                BleConstants.LEVEL_DANGER  -> Color.rgb(255,  80,  70)   // Danger = red
                BleConstants.LEVEL_WARNING -> Color.rgb(255, 200,  40)   // Warning = yellow
                else                       -> Color.rgb(170, 210, 230)   // Safe = light blue
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

        // Write the list to the bottom tv_ble_status — yellow/light blue get lost on the light default background,
        // so a dark background matching the risk level is applied for readability.
        val topLevel = sorted.first().level
        val bgColor = when {
            topLevel >= BleConstants.LEVEL_DANGER  -> 0xDD1A0000.toInt()  // Dark red
            topLevel == BleConstants.LEVEL_WARNING -> 0xDD1A1400.toInt()  // Dark amber
            else                                   -> 0xDD051220.toInt()  // Dark navy
        }
        binding.tvBleStatus.backgroundTintList = ColorStateList.valueOf(bgColor)   // Keep rounded corners
        binding.tvBleStatus.setTextColor(0xFFE0F0FF.toInt())
        binding.tvBleStatus.text = sb
    }

    private fun onRoleSelected(mode: String, category: Int) {
        currentMode = mode
        currentCategory = category
        // Save the role (Category) for restore — keeps labels consistent after a START_STICKY service restart or app relaunch
        prefs.edit().putInt("running_category", category).apply()
        binding.layoutPermissionWarning.visibility = View.GONE
        // On UWB-capable devices also request the UWB_RANGING runtime permission — only the three BLE permissions are required
        val wantUwb = UwbRanger.isHardwareSupported(this) &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.UWB_RANGING) !=
                    PackageManager.PERMISSION_GRANTED
        // Notification permission. On targetSdk 33+ without it even the foreground service's persistent notification
        //   doesn't show in the status bar. Treated as optional like UWB — monitoring and alerts keep running if denied.
        val wantNotif = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        // Physical activity (step detection) is optional too — if denied, strong motion is used instead
        val wantActivity = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) !=
                    PackageManager.PERMISSION_GRANTED
        if (hasAllPermissions() && !wantUwb && !wantNotif && !wantActivity) afterPermissions()
        else {
            var req = blePermissions
            if (wantUwb)   req += Manifest.permission.UWB_RANGING
            if (wantNotif) req += Manifest.permission.POST_NOTIFICATIONS
            if (wantActivity) req += Manifest.permission.ACTIVITY_RECOGNITION
            permissionLauncher.launch(req)
        }
    }

    private fun hasAllPermissions() = blePermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun isBatteryOptimizationExempt(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    // Request battery-optimization exemption at app start (first time only)
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
                    // Only asks for the exemption: monitoring may already run, so no result handler re-sends a start
                    startActivity(
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

    // Check the battery permission after mode selection
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

    // Open the manufacturer-specific battery settings screen
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
        // If a name was entered, use it as the BLE advertising ID (shown on the peer's screen)
        val displayName = prefs.getString("display_name", "")?.trim()
        val id = if (!displayName.isNullOrEmpty()) displayName else myId()
        val since = System.currentTimeMillis()
        val action = if (mode == "DEVICE") BleService.ACTION_START_DEVICE else BleService.ACTION_START_WALKER

        val intent = Intent(this, BleService::class.java).apply {
            this.action = action
            putExtra(BleService.EXTRA_ID, id)
            putExtra(BleService.EXTRA_CATEGORY, currentCategory)   // Pass the role Category
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
        // No delayed post: during a delay the START_STICKY restore could read leftover prefs (running_mode)
        //   and revive the service. prefs are written with commit (synchronous) so the on-disk state is
        //   'stopped' before ACTION_STOP arrives.
        prefs.edit()
            .remove("running_mode")
            .remove("running_since")
            .remove("running_category")
            .commit()
        currentMode = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // Allow the screen to turn off after stopping

        // ── Stop sound/vibration/overlay immediately (don't wait for the service to stop) ──
        com.wf11.safealert.service.AlertSoundPlayer.stopSound()
        com.wf11.safealert.service.VibrationHelper.stopVibration(this)
        com.wf11.safealert.utils.OverlayManager.hideOverlay()

        // Clear the detected list
        detectedDevices.clear()

        // Reset the button state if a test alarm was running
        if (testAlertRunning) {
            testAlertRunning = false
            styleTestButton(testAccent)   // Restore the TEST button's normal style
        }

        // Reset the mute indicator
        muteAnimator?.cancel()
        binding.tvMutedIndicator.visibility = View.GONE

        // Back to the selection screen — switch the role background to the warehouse view (bg_main), keeping the scrim
        binding.ivRoleBackground.setImageResource(R.drawable.bg_main)
        binding.ivRoleBackground.visibility = View.VISIBLE
        binding.viewBgScrim.visibility      = View.VISIBLE

        binding.cardRunning.visibility  = View.GONE
        binding.layoutSelect.visibility = View.VISIBLE
        binding.layoutPermissionWarning.visibility = View.GONE

        // Post immediately — BLE stack cleanup is BleService.stopAll()'s own job.
        startService(Intent(this, BleService::class.java).apply { action = BleService.ACTION_STOP })
    }

    // Role switch while running (stop → restart) — the role prefix (DEVICE_/WALKER_) is baked into the advertised fullId,
    //   and there is no API to change the category while running, so stopServiceImmediately() and then a fresh start
    //   is the only safe path. EMA warm-up softens the cold start in the switch gap.
    //   Mapping: WALKER → equipment picker (startAsPitOperator; the chosen equipment sets the category) /
    //   DEVICE (incl. legacy EPJ) → walker (WALKER, CAT_WALKER).
    private fun switchTargetLabel(): String =
        if (currentMode == "WALKER") "장비 작업자" else "보행자"

    // Handle the persistent notification's switch action. Consume the action (action=null) so the same intent
    //   doesn't revive the dialog on rotation/resume. Ignored when not monitoring.
    private fun handleSwitchRoleIntent(intent: Intent?) {
        if (intent?.action != BleService.ACTION_OPEN_SWITCH_ROLE) return
        intent.action = null
        if (currentMode == null) return
        confirmSwitchRole()
    }

    private fun confirmSwitchRole() {
        if (LoneWorkerUi.blockIfOwnSos(this)) return   // Block switching during an SOS
        val mode = currentMode ?: return
        val target = switchTargetLabel()
        AlertDialog.Builder(this)
            .setTitle("작업 전환")
            .setMessage("현재 감시를 중지하고 '${target}' 역할로 다시 시작합니다.\n전환 중 1~2초간 감시가 중단됩니다.")
            .setPositiveButton("전환") { _, _ -> performSwitchRole(mode) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun performSwitchRole(fromMode: String) {
        if (LoneWorkerUi.blockIfOwnSos(this)) return   // An SOS started while the confirm dialog was open
        stopServiceImmediately()
        // 800ms delay between stop and restart — if ACTION_STOP handling (BLE teardown) overlaps the new start,
        //   advertising/scan re-init races with the previous instance's cleanup.
        statusHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            if (LoneWorkerUi.blockIfOwnSos(this)) return@postDelayed   // An SOS started while waiting to switch
            // Switching to equipment also asks to pick the equipment — the chosen equipment sets the role
            if (fromMode == "WALKER") startAsPitOperator()
            else onRoleSelected("WALKER", BleConstants.CAT_WALKER)
        }, 800L)
    }

    private fun restoreRunningState() {
        val mode  = prefs.getString("running_mode", null) ?: return
        val since = prefs.getLong("running_since", 0L)
        currentMode = mode
        currentCategory = prefs.getInt("running_category", BleConstants.CAT_WALKER)  // Restore role
        showRunningUi(mode, since)
    }

    private fun showRunningUi(mode: String, since: Long) {
        // Keep the screen on only on the monitoring screen, not unconditionally in onCreate — the idle (role selection)
        //   screen may turn off normally; otherwise just leaving the app open would keep the screen on forever.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.layoutSelect.visibility  = View.GONE
        binding.cardRunning.visibility   = View.VISIBLE
        binding.tvApproaching.visibility = View.VISIBLE  // Center hint (the list is in the bottom tv_ble_status)
        binding.layoutPermissionWarning.visibility = View.GONE
        updateDetectedDisplay()  // Show the initial hint ("감지 기기 없음")
        // Init my device status line — default until the service's first localSnapshot arrives.
        binding.tvLocalState.text = "상태: 정지·일반 · 회전: 직진"
        lastLocalSnapshot = ""
        binding.tvRunningMode.text  = roleDisplayName(currentCategory)   // 3-role label
        binding.btnSwitchRole.text  = "${switchTargetLabel()}로 전환"    // Dynamic label for the switch target
        binding.tvRunningSince.text = SimpleDateFormat("HH:mm 시작", Locale.KOREA).format(Date(since))
        // Show the entered name, otherwise the auto ID
        val displayName = prefs.getString("display_name", "")?.trim()
        binding.tvRunningId.text = if (!displayName.isNullOrEmpty()) displayName else myId()
        // Dark UI — apply the role background photo/icon/TEST accent (visuals only, no functional change)
        applyRoleVisuals(currentCategory)
        // Show Bluetooth state immediately
        val btManager = getSystemService(android.bluetooth.BluetoothManager::class.java)
        binding.tvBleStatus.text = when {
            btManager?.adapter?.isEnabled != true -> "블루투스 꺼짐! 설정에서 켜주세요"
            else -> "블루투스 ON · BLE 시작 중..."
        }
    }

    /** Category (CAT_*) -> role name on the running card. No emoji — iv_role_icon (vector) shows the icon. */
    private fun roleDisplayName(category: Int): String = when (category) {
        BleConstants.CAT_EPJ      -> "EPJ 작업자"
        BleConstants.CAT_FORKLIFT -> "지게차"
        BleConstants.CAT_WALKER   -> "보행자"
        else                      -> "보행자"
    }

    // TEST button's normal accent (per role) — used by toggleTestAlert/stopServiceImmediately to restore it
    private var testAccent = 0xFFD7DEE8.toInt()

    /**
     * Per-role running-screen visuals — background photo / role icon / TEST button accent.
     *   Visuals only (unrelated to detection, alert and advertising logic).
     *   stopServiceImmediately hides them when returning to the selection screen.
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

    /** TEST button normal style — transparent background + role-accent outline/text/bell icon. */
    private fun styleTestButton(accent: Int) {
        testAccent = accent
        val btn = binding.btnTestAlert as MaterialButton
        btn.text = "TEST"
        btn.setTextColor(accent)
        btn.strokeColor = ColorStateList.valueOf(accent)
        btn.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        TextViewCompat.setCompoundDrawableTintList(btn, ColorStateList.valueOf(accent))
    }

    /** Draw the display-name field — saved equipment ID, else the auto-ID hint */
    private fun renderDisplayName() {
        val id = prefs.getString("display_name", "") ?: ""
        binding.etDisplayName.setText(id)
        binding.tilDisplayName.helperText =
            if (id.isEmpty()) "미선택 — 자동 ID 로 송출됩니다 (보행자)"
            else "경보 로그: ${FirebaseManager.withSite(id)}"
    }

    /**
     * Equipment picker popup — type dropdown + number dropdown (1~99).
     *
     * Replaces free text input. No keyboard appears, so there is no path for a person's name to come in.
     * Dropdowns are large (items 64dp, 22sp) for gloved hands and warehouse lighting.
     *
     * The list is always complete. Instead of picking a role and then equipment,
     * **picking the equipment brings its role (Category)** — someone who picked a forklift
     * can never end up running with EPJ radii.
     *
     * [onPicked] is called only after a selection is confirmed; never on cancel.
     */
    private fun showPitSelectDialog(onPicked: (PitType) -> Unit) {
        val types = PitType.values().toList()
        val nos   = (PitType.NO_MIN..PitType.NO_MAX).toList()
        val dlg   = DialogPitSelectBinding.inflate(layoutInflater)

        fun <T> bind(sp: android.widget.Spinner, items: List<T>, label: (T) -> String) {
            sp.adapter = ArrayAdapter(this, R.layout.item_spinner_large, items.map(label)).apply {
                setDropDownViewResource(R.layout.item_spinner_dropdown_large)
            }
        }
        // Abbreviations are built from the advertised code — they match the peer's screen (CB-01), and new equipment can't be missed
        bind(dlg.spPitType, types) { "${it.code} (${it.label})" }
        bind(dlg.spPitNo, nos) { "%02d".format(it) }

        // Restore the previous selection — most people keep the same equipment, so one confirm tap finishes it
        PitType.parse(prefs.getString("display_name", "") ?: "")?.let { (t, n) ->
            types.indexOf(t).takeIf { it >= 0 }?.let { dlg.spPitType.setSelection(it) }
            dlg.spPitNo.setSelection(n - PitType.NO_MIN)
        }

        fun pickedType() = types[dlg.spPitType.selectedItemPosition]
        fun pickedId()   = PitType.buildId(pickedType(), nos[dlg.spPitNo.selectedItemPosition])
        fun refresh() {
            val id = pickedId()
            dlg.tvPitPreview.text =
                "역할  ${roleDisplayName(pickedType().category)}\n" +
                "상대 화면 표시  $id\n경보 로그  ${FirebaseManager.withSite(id)}"
        }
        val watcher = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = refresh()
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
        dlg.spPitType.onItemSelectedListener = watcher
        dlg.spPitNo.onItemSelectedListener   = watcher
        refresh()

        AlertDialog.Builder(this)
            .setTitle("내 장비 선택")
            .setView(dlg.root)
            .setPositiveButton("확인") { _, _ ->
                val type = pickedType()
                prefs.edit().putString("display_name", pickedId()).apply()
                renderDisplayName()
                onPicked(type)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /**
     * Start as an equipment operator — starts with the chosen equipment's Category.
     * Asks every time: the equipment changes per shift, and just starting with the previous value would make
     * alert logs point at different equipment. The previous choice is restored, so one confirm tap is enough.
     */
    private fun startAsPitOperator() {
        showPitSelectDialog { type -> onRoleSelected("DEVICE", type.category) }
    }

    /**
     * Equipment ID format migration — removes person names stored by old versions from the advertising path.
     *
     * Checks both keys. display_name is the display name users used to type, and
     * device_id is overwritten with that display name by BleService.saveRunningMode on start, so
     * clearing only display_name would leave the old name in the auto-ID slot and keep advertising it.
     *
     * Alerting is never interrupted either way — when a value is cleared, myId() issues an auto ID,
     * and the start path (startServiceWithCurrentMode) carries that value as is.
     */
    private fun migrateDisplayNameToPitId() {
        val savedName = prefs.getString("display_name", "") ?: ""
        val savedId   = prefs.getString("device_id", "") ?: ""
        val editor    = prefs.edit()
        var notify    = false

        if (savedName.isNotEmpty() && PitType.parse(savedName) == null) {
            editor.remove("display_name")
            notify = true
        }
        // A value that is neither an equipment ID nor an auto ID = a person's name pushed
        // in by an old version → replace it with an auto ID right away.
        //   Merely clearing it would make the START_STICKY restore path (BleService.onStartCommand) carry "SA-DEFAULT",
        //   and every migrated device would advertise the same ID, breaking peer identification. So issue a new one instead of clearing.
        if (savedId.isNotEmpty() && !FirebaseManager.isUsableAdvertisedId(savedId)) {
            editor.putString("device_id", newAutoId())
            notify = true
        }
        editor.apply()
        if (!notify) return

        AlertDialog.Builder(this)
            .setTitle("장비 선택 방식으로 변경")
            .setMessage(
                "표시 이름을 직접 입력하지 않고, 장비 종류와 번호를 선택하도록 바뀌었습니다.\n" +
                "${FirebaseManager.PIT_ID_HINT}\n\n" +
                "형식에 맞지 않는 기존 이름은 삭제되었습니다. 역할을 선택하면 장비 선택 창이 뜹니다.\n" +
                "선택 전에는 자동 ID 로 송출되며 경보는 그대로 동작합니다."
            )
            .setPositiveButton("확인", null)
            .show()
    }

    /**
     * Save the site code — the setter normalizes to uppercase [A-Z0-9_-], so lowercase input is accepted as is.
     * UwbCalibrator.applySite is a no-op if the code is unchanged. With the service off, BleService's live-apply
     * path doesn't run and the previous site's profile would remain, so switch it here directly.
     */
    private fun saveSiteCode() {
        // Don't save a locked field (has a value) — prevents a stale displayed value from overwriting one changed in developer settings
        if (!binding.etSiteCode.isEnabled) return
        DevSettings.siteCode = binding.etSiteCode.text?.toString() ?: ""
        UwbCalibrator.applySite()
        refreshSiteCodeField()
    }

    /** Site code is editable on the main screen only while empty. Once set, the field is disabled with a hint. */
    private fun refreshSiteCodeField() {
        val locked = DevSettings.siteCode.isNotEmpty()
        binding.etSiteCode.setText(DevSettings.siteCode)
        binding.etSiteCode.isEnabled = !locked
        binding.tilSiteCode.helperText = if (locked) "변경은 개발자 설정에서" else null
    }

    /**
     * Mode start gate — a site code is required to start.
     * Without a code, alert logs and calibration data would mix in a namespace shared by all sites,
     * so show the input popup and don't call onReady until a code is entered.
     */
    private fun requireSiteCode(onReady: () -> Unit) {
        saveSiteCode()
        if (DevSettings.siteCode.isNotEmpty()) { onReady(); return }

        val input = EditText(this).apply {
            hint = "예: WF11"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(InputFilter.LengthFilter(DevSettings.SITE_CODE_MAX_LEN))
            setPadding(56, 32, 56, 32)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("센터명 입력")
            .setMessage("센터마다 경보 기록과 보정 데이터가 따로 관리됩니다.\n센터명을 입력해야 시작할 수 있습니다. (대소문자 무관)")
            .setView(input)
            .setPositiveButton("확인", null)   // Handled below so an empty value doesn't close the dialog
            .setNegativeButton("취소", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val code = DevSettings.normalizeSite(input.text?.toString() ?: "")
                if (code.isEmpty()) {
                    input.error = "영문·숫자로 입력하세요"
                } else {
                    binding.etSiteCode.setText(code)
                    saveSiteCode()
                    dialog.dismiss()
                    onReady()
                }
            }
        }
        dialog.show()
    }

    /** Generate an auto-issued ID — same format as FirebaseManager.AUTO_ID_REGEX ("SA-" + 8 uppercase UUID chars) */
    private fun newAutoId(): String = "SA-" + UUID.randomUUID().toString().take(8).uppercase()

    private fun myId(): String {
        val saved = prefs.getString("device_id", null)
        if (saved != null) return saved
        val newId = newAutoId()
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
        // Can't test while the service isn't running
        if (!BleService.isRunning && !testAlertRunning) return
        testAlertRunning = !testAlertRunning
        val action = if (testAlertRunning) BleService.ACTION_TEST_START else BleService.ACTION_TEST_STOP
        startService(Intent(this, BleService::class.java).apply { this.action = action })
        // Dark UI — red emphasis while testing; otherwise back to the role-accent outline
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
            btManager?.adapter == null         -> "블루투스 미지원 기기"
            btManager.adapter?.isEnabled != true -> "블루투스 꺼짐 — 설정에서 켜주세요"
            else                                -> "블루투스 ON — 모드 선택 후 시작하세요"
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

    // ── Auto update ──────────────────────────────────────────
    // Checked on every onResume, so hold the dialog reference to prevent duplicate dialogs
    private var updateDialog: AlertDialog? = null

    private fun checkUpdate() {
        UpdateManager.checkForUpdate(this) { info ->
            info ?: return@checkForUpdate
            runOnUiThread { showUpdateDialog(info) }
        }
    }

    private fun showUpdateDialog(info: UpdateManager.UpdateInfo) {
        // Don't re-show if already showing, and prevent a crash when the async callback reaches a finishing activity
        if (updateDialog?.isShowing == true) return
        if (isFinishing || isDestroyed) return
        val msg = "v${UpdateManager.CURRENT_VERSION}  →  v${info.latest}" +
                if (info.changelog.isNotBlank()) "\n\n${info.changelog}" else ""
        val builder = AlertDialog.Builder(this)
            .setTitle("새 버전이 있습니다")
            .setMessage(msg)
            .setPositiveButton("지금 업데이트") { _, _ ->
                UpdateManager.downloadAndInstall(this, info.apkUrl, info.apkSha256)
            }
        if (!info.forceUpdate) builder.setNegativeButton("나중에", null)
        updateDialog = builder.setCancelable(!info.forceUpdate).show()
    }

}
