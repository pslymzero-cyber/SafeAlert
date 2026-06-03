package com.wf11.safealert.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.wf11.safealert.ble.BleAdvertiser
import com.wf11.safealert.ble.BleConstants
import com.wf11.safealert.ble.BleScanner
import com.wf11.safealert.ble.BleScanCallback
import com.wf11.safealert.firebase.FirebaseManager
import com.wf11.safealert.utils.BeaconRegistry
import com.wf11.safealert.utils.DevSettings
import com.wf11.safealert.utils.OverlayManager

class BleService : LifecycleService() {

    companion object {
        const val TAG                  = "BleService"
        const val ACTION_START_DEVICE  = "ACTION_START_DEVICE"
        const val ACTION_START_WALKER  = "ACTION_START_WALKER"
        const val ACTION_STOP          = "ACTION_STOP"
        const val ACTION_TEST_START    = "ACTION_TEST_START"
        const val ACTION_TEST_STOP     = "ACTION_TEST_STOP"
        const val ACTION_MUTE_TEMP     = "ACTION_MUTE_TEMP"   // 임시 무음
        const val ACTION_UNMUTE        = "ACTION_UNMUTE"      // 즉시 무음 해제
        const val EXTRA_ID             = "extra_id"
        const val EXTRA_ALERT_LEVEL    = "extra_alert_level"
        const val EXTRA_STATUS         = "extra_status"
        const val BROADCAST_ALERT      = "com.wf11.safealert.ALERT"
        const val BROADCAST_BLE_STATUS = "com.wf11.safealert.BLE_STATUS"
        private const val CHANNEL_ID   = "safealert_channel"
        private const val NOTIF_ID     = 1001

        // MainActivity에서 직접 읽는 공유 상태 (Broadcast 실패 대비)
        @Volatile var lastStatus: String = ""
        @Volatile var bleScanCount: Int  = 0
        @Volatile var safeAlertFound: Int = 0
        @Volatile var isRunning: Boolean = false
        @Volatile var isMutedPublic: Boolean = false  // MainActivity 폴링용
    }

    private var bleAdvertiser: BleAdvertiser? = null
    private var bleScanner: BleScanner? = null
    private var myId = ""
    private var myMode = ""
    private var wakeLock: PowerManager.WakeLock? = null
    private var testRunnable: Runnable? = null
    private val testHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val MUTE_DURATION_MS = 10_000L
    private val muteHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var isMuted = false

    // 우리가 직접 volAlarm 설정 중인지 플래그 (자체 설정이 mute 트리거 되는 것 방지)
    @Volatile private var ignoringVolumeChange = false

    // 볼륨 업/다운 감지 — 우리가 직접 설정한 경우만 무시
    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (isMuted || ignoringVolumeChange) return
            muteTemporarily("볼륨 버튼")
        }
    }

    // 화면 켜짐/꺼짐 → 전원 버튼 감지
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (isMuted) return
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> muteTemporarily("전원 버튼")
                Intent.ACTION_SCREEN_ON  -> muteTemporarily("전원 버튼")
            }
        }
    }

    // 블루투스 ON/OFF 감지 → 자동 재시작
    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
            when (state) {
                BluetoothAdapter.STATE_ON -> {
                    Log.d(TAG, "블루투스 켜짐 → BLE 재시작")
                    sendStatusBroadcast("블루투스 켜짐 → BLE 재시작")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        stopBle()
                        applyMode()
                    }, 1000)
                }
                BluetoothAdapter.STATE_OFF -> {
                    Log.d(TAG, "블루투스 꺼짐")
                    sendStatusBroadcast("⚠ 블루투스 꺼짐")
                    stopBle()
                }
            }
        }
    }

    // 장비별 마지막 경보 상태: deviceId → (alertLevel, lastAlertTimeMs)
    private val alertState = mutableMapOf<String, Pair<Int, Long>>()

    // 같은 단계 유지 시 재경보 주기 (첫 감지는 항상 즉시)
    private val WARNING_COOLDOWN_MS = 5000L
    private val DANGER_COOLDOWN_MS  = 3000L
    private val SCAN_HEALTH_CHECK_MS = 15_000L

    // 마지막 BLE 패킷 수신 시각 (스캔 헬스체크용)
    @Volatile private var lastScanResultMs = 0L
    private val healthCheckHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // ── RSSI 안정화 ──────────────────────────────────────────
    // RSSI 최근 N회 평균 (BLE 신호 흔들림 억제)
    private val rssiHistory = mutableMapOf<String, ArrayDeque<Int>>()
    private val RSSI_HISTORY_SIZE = 3  // 3회 평균 (노이즈 억제 + 빠른 반응 균형)

    // 히스테리시스 (dBm): 경보 진입보다 높은 임계값을 넘어야 '안전'으로 복귀
    // 예) 경고 진입: -70, 경고 해제: -65 (5dBm 히스테리시스)
    private val HYSTERESIS_DBM = 5

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        acquireWakeLock()
        registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        // 볼륨 버튼 감지
        registerReceiver(volumeReceiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"))
        // 전원 버튼: 화면 켜짐/꺼짐 모두 감지
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, screenFilter)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SafeAlert::BleWakeLock"
        ).also { it.acquire(24 * 60 * 60 * 1000L) } // 최대 24시간
        Log.d(TAG, "WakeLock 획득")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // null intent = 시스템이 START_STICKY로 재시작 → SharedPrefs에서 마지막 모드 복원
        if (intent?.action == null && myMode.isEmpty()) {
            val savedMode = getSharedPreferences("safealert_prefs", MODE_PRIVATE)
                .getString("running_mode", null)
            if (savedMode != null) {
                myId   = getSharedPreferences("safealert_prefs", MODE_PRIVATE)
                    .getString("device_id", "SA-DEFAULT") ?: "SA-DEFAULT"
                myMode = savedMode
                val title = if (savedMode == "DEVICE") "🚛 장비 작업자 실행 중" else "🚶 보행자 실행 중"
                startForeground(NOTIF_ID, buildNotification(title, "재시작됨"))
                applyMode()
            }
            return START_STICKY
        }

        when (intent?.action) {
            ACTION_START_DEVICE -> {
                myId   = intent.getStringExtra(EXTRA_ID) ?: "DEVICE_001"
                myMode = "DEVICE"
                startForeground(NOTIF_ID, buildNotification(
                    "🚛 장비 작업자 실행 중",
                    buildSubText(DevSettings.deviceTx, DevSettings.deviceRx)
                ))
                applyMode()
            }
            ACTION_START_WALKER -> {
                myId   = intent.getStringExtra(EXTRA_ID) ?: "WALKER_001"
                myMode = "WALKER"
                startForeground(NOTIF_ID, buildNotification(
                    "🚶 보행자 실행 중",
                    buildSubText(DevSettings.walkerTx, DevSettings.walkerRx)
                ))
                applyMode()
            }
            ACTION_STOP       -> stopAll()
            ACTION_TEST_START -> startTestAlert()
            ACTION_TEST_STOP  -> stopTestAlert()
            ACTION_MUTE_TEMP  -> muteTemporarily("화면 터치")
            ACTION_UNMUTE     -> unmuteImmediately()
        }
        return START_STICKY  // 시스템이 죽여도 자동 재시작
    }

    private fun applyMode() {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val btAdapter = btManager.adapter

        // 블루투스 기본 상태 진단
        if (btAdapter == null) { sendStatusBroadcast("❌ 블루투스 미지원 기기"); return }
        if (!btAdapter.isEnabled) { sendStatusBroadcast("❌ 블루투스 꺼짐 — 켜주세요"); return }

        val doTx = if (myMode == "DEVICE") DevSettings.deviceTx else DevSettings.walkerTx
        val doRx = if (myMode == "DEVICE") DevSettings.deviceRx else DevSettings.walkerRx

        if (doTx) {
            // isMultipleAdvertisementSupported 는 다중 광고 여부일 뿐,
            // 단일 광고는 거의 모든 BLE 폰에서 지원 → 무조건 시도 후 콜백으로 판단
            val advertiser = btAdapter.bluetoothLeAdvertiser
            if (advertiser != null) {
                val prefix = if (myMode == "DEVICE") BleConstants.DEVICE_PREFIX else BleConstants.WALKER_PREFIX
                bleAdvertiser = BleAdvertiser(advertiser, prefix,
                    onStatusUpdate = { msg -> sendStatusBroadcast(msg) }
                ).also { it.startAdvertising(myId) }
                sendStatusBroadcast("TX 송출 요청: $myId")
                Log.d(TAG, "TX 시작: $prefix$myId")
            } else {
                sendStatusBroadcast("❌ TX 오류: 이 기기는 BLE 광고 미지원")
                Log.e(TAG, "TX: bluetoothLeAdvertiser null")
            }
        }

        if (doRx) {
            val scanner = btAdapter.bluetoothLeScanner
            if (scanner != null) {
                lastScanResultMs = System.currentTimeMillis()
                startScanHealthCheck()
                bleScanner = BleScanner(scanner).also { s ->
                    s.onStatusUpdate = { msg -> sendStatusBroadcast(msg) }
                    s.startScanning(object : BleScanCallback {
                        override fun onDeviceDetected(deviceId: String, rssi: Int, alertLevel: Int) {
                            lastScanResultMs = System.currentTimeMillis()

                            // 보행자 모드에서 다른 보행자 신호는 설정에 따라 무시
                            if (myMode == "WALKER"
                                && deviceId.startsWith(BleConstants.WALKER_PREFIX)
                                && !DevSettings.walkerDetectsWalker) return

                            val effectiveRssi  = if (DevSettings.debugMode) DevSettings.simulatedRssi else rssi
                            val shortId = deviceId.replace(BleConstants.DEVICE_PREFIX, "D:").replace(BleConstants.WALKER_PREFIX, "W:")
                            sendStatusBroadcast("감지 $shortId  RSSI $effectiveRssi dBm")

                            val effectiveLevel = when {
                                effectiveRssi >= BleConstants.rssiDanger  -> BleConstants.LEVEL_DANGER
                                effectiveRssi >= BleConstants.rssiWarning -> BleConstants.LEVEL_WARNING
                                else -> BleConstants.LEVEL_SAFE
                            }
                            processAlert(deviceId, effectiveRssi, effectiveLevel)
                        }
                        override fun onDeviceLost(deviceId: String) {
                            Log.d(TAG, "신호 소실 (중지 감지): $deviceId")
                            alertState.remove(deviceId)
                            rssiHistory.remove(deviceId)
                            // 모든 기기가 사라졌으면 경보 즉시 중지
                            if (alertState.isEmpty()) {
                                AlertSoundPlayer.stopSound()
                                VibrationHelper.stopVibration(this@BleService)
                                OverlayManager.hideOverlay()
                                sendAlertBroadcast(deviceId, BleConstants.LEVEL_SAFE)
                                sendStatusBroadcast("기기 이탈 감지 → 경보 중지")
                                Log.d(TAG, "모든 기기 이탈 → 경보 중지")
                            }
                        }
                        override fun onScanError(errorCode: Int) { Log.e(TAG, "스캔 오류: $errorCode") }
                    })
                }
                sendStatusBroadcast("RX 스캔 시작")
                Log.d(TAG, "RX 시작")
            } else {
                sendStatusBroadcast("❌ RX 오류: BluetoothLeScanner null")
                Log.e(TAG, "RX: bluetoothLeScanner null")
            }
        }
    }

    // RSSI 평균 계산 (최근 N회, 신호 흔들림 억제)
    private fun smoothedRssi(deviceId: String, rssi: Int): Int {
        val history = rssiHistory.getOrPut(deviceId) { ArrayDeque() }
        history.addLast(rssi)
        if (history.size > RSSI_HISTORY_SIZE) history.removeFirst()
        return history.average().toInt()
    }

    // 히스테리시스 적용 경보 단계 계산
    // - 진입: 실제 임계값 (예: -70)
    // - 이탈: 임계값 + HYSTERESIS_DBM (예: -65) → 경보 해제가 더 어려움
    // RSSI ≥ 임계값 = 가까이 있음 = 경보
    // 히스테리시스: 경보 해제는 임계값보다 HYSTERESIS_DBM 더 멀어져야 (RSSI 더 낮아져야)
    // rssiOffset > 0 → 더 약한 신호도 감지 = 더 먼 거리 (SmartTag 등 느린 비콘용)
    private fun calcLevelWithHysteresis(deviceId: String, rssi: Int, rssiOffset: Int = 0): Int {
        val prevLevel = alertState[deviceId]?.first ?: BleConstants.LEVEL_SAFE
        val danger  = BleConstants.rssiDanger  - rssiOffset
        val warning = BleConstants.rssiWarning - rssiOffset

        return when {
            rssi >= danger  -> BleConstants.LEVEL_DANGER
            prevLevel == BleConstants.LEVEL_DANGER  && rssi >= danger  - HYSTERESIS_DBM -> BleConstants.LEVEL_DANGER
            rssi >= warning -> BleConstants.LEVEL_WARNING
            prevLevel == BleConstants.LEVEL_WARNING && rssi >= warning - HYSTERESIS_DBM -> BleConstants.LEVEL_WARNING
            else -> BleConstants.LEVEL_SAFE
        }
    }

    // ── 이중 EMA 급접근 감지 ──────────────────────────────────────
    // 빠른EMA(α=0.5) - 느린EMA(α=0.15) 차이가 임계값 초과 시 진짜 접근으로 판단
    // 노이즈(±5dBm 랜덤 변동)는 두 EMA가 함께 움직이므로 차이가 작음
    // 실제 접근은 빠른EMA가 먼저 올라가서 차이 벌어짐
    private val FAST_ALPHA  = 0.50   // 빠른 EMA (최근 집중)
    private val SLOW_ALPHA  = 0.15   // 느린 EMA (배경 기준)
    private val EMA_TRIGGER = 5.0    // 5dBm 차이 지속 시 급접근 판정
    private val fastEma = mutableMapOf<String, Double>()
    private val slowEma = mutableMapOf<String, Double>()
    private val rapidApproachSet = mutableSetOf<String>()

    private fun processAlert(deviceId: String, rssi: Int, alertLevel: Int) {
        val avgRssi     = smoothedRssi(deviceId, rssi)
        val beaconOffset = runCatching { BeaconRegistry.getRssiOffsetForFullId(deviceId) }.getOrDefault(0)
        val stableLevel  = calcLevelWithHysteresis(deviceId, avgRssi, beaconOffset)

        // ── 이중 EMA 급접근 감지 ──────────────────────────────────
        val rssiD = rssi.toDouble()
        val prevFast = fastEma[deviceId] ?: rssiD
        val prevSlow = slowEma[deviceId] ?: rssiD
        val newFast  = FAST_ALPHA * rssiD + (1 - FAST_ALPHA) * prevFast
        val newSlow  = SLOW_ALPHA * rssiD + (1 - SLOW_ALPHA) * prevSlow
        fastEma[deviceId] = newFast
        slowEma[deviceId] = newSlow
        val emaDiff = newFast - newSlow  // 양수 = 빠른EMA > 느린EMA = 접근 중

        if (stableLevel == BleConstants.LEVEL_SAFE && emaDiff >= EMA_TRIGGER
                && !rapidApproachSet.contains(deviceId)) {
            // 노이즈가 아닌 지속적 접근 확인 (두 EMA 차이가 임계값 이상)
            rapidApproachSet.add(deviceId)
            Log.w(TAG, "급접근 감지 (EMA): $deviceId diff=%.1f dBm → 선제 경고".format(emaDiff))
            forceAlarmVolume()
            if (DevSettings.vibrationEnabled) VibrationHelper.vibrateOnce(this, DevSettings.vibrationWarningMs)
            if (DevSettings.soundEnabled)     AlertSoundPlayer.playWarning(this)
            OverlayManager.showDangerOverlay(this, danger = false)
            sendAlertBroadcast(deviceId, BleConstants.LEVEL_WARNING)
            sendStatusBroadcast("⚡ 급접근 감지: ${deviceId.take(20)}")
            return
        }
        // EMA 차이가 줄어들면(멀어지거나 안정화) 급접근 플래그 해제
        if (emaDiff < EMA_TRIGGER / 2.0) {
            rapidApproachSet.remove(deviceId)
        }

        Log.d(TAG, "RSSI raw=$rssi avg=$avgRssi level=$alertLevel→$stableLevel")

        if (stableLevel == BleConstants.LEVEL_SAFE) {
            if (alertState.containsKey(deviceId)) {
                // 안전 복귀: 즉시 상태 초기화 → 재진입 시 바로 경보 가능
                alertState.remove(deviceId)
                rssiHistory.remove(deviceId)
                if (alertState.isEmpty()) {
                    AlertSoundPlayer.stopSound()
                    sendAlertBroadcast(deviceId, BleConstants.LEVEL_SAFE)
                }
                Log.d(TAG, "안전 복귀 (상태 초기화): $deviceId")
            }
            return
        }

        val now = System.currentTimeMillis()
        val prev = alertState[deviceId]
        val prevLevel     = prev?.first  ?: BleConstants.LEVEL_SAFE
        val lastAlertTime = prev?.second ?: 0L
        val cooldown = if (stableLevel == BleConstants.LEVEL_DANGER) DANGER_COOLDOWN_MS else WARNING_COOLDOWN_MS

        val isFirstDetection = prev == null
        val levelEscalated   = stableLevel > prevLevel
        val cooldownPassed   = now - lastAlertTime >= cooldown

        val shouldAlert = isFirstDetection || levelEscalated || cooldownPassed
        if (!shouldAlert) return

        // 상태는 항상 갱신 (무음 중이어도 레벨 추적 유지)
        alertState[deviceId] = Pair(stableLevel, now)

        if (isMuted) return  // 임시 무음 중 → 경보음/진동만 건너뜀

        // 알람 볼륨 강제 최대화
        forceAlarmVolume()

        when (stableLevel) {
            BleConstants.LEVEL_WARNING -> {
                if (DevSettings.vibrationEnabled)
                    VibrationHelper.vibrateOnce(this, DevSettings.vibrationWarningMs)
                if (DevSettings.soundEnabled)
                    AlertSoundPlayer.playWarning(this)
                if (DevSettings.autoSaveAlerts)
                    FirebaseManager.saveAlert(deviceId, myId, avgRssi, "WARNING")
                // 시스템 오버레이 (경고 = 노란 테두리)
                OverlayManager.showDangerOverlay(this, danger = false)
                sendAlertBroadcast(deviceId, BleConstants.LEVEL_WARNING)
                Log.d(TAG, "경고 발생: $deviceId avgRssi=$avgRssi")
            }
            BleConstants.LEVEL_DANGER -> {
                if (DevSettings.vibrationEnabled)
                    VibrationHelper.vibrateRepeat(this, DevSettings.vibrationDangerCount)
                if (DevSettings.soundEnabled)
                    AlertSoundPlayer.playDanger(this)
                if (DevSettings.autoSaveAlerts)
                    FirebaseManager.saveAlert(deviceId, myId, avgRssi, "DANGER")
                // 시스템 오버레이 (위험 = 빨간 테두리)
                OverlayManager.showDangerOverlay(this, danger = true)
                sendAlertBroadcast(deviceId, BleConstants.LEVEL_DANGER)
                Log.d(TAG, "위험 발생: $deviceId avgRssi=$avgRssi")
            }
        }
    }

    private fun forceAlarmVolume() {
        // 자체 볼륨 설정이 volumeReceiver를 트리거하지 않도록 플래그 설정
        ignoringVolumeChange = true
        try {
            val am     = getSystemService(AUDIO_SERVICE) as AudioManager
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val target = (maxVol * DevSettings.alarmVolume / 100f).toInt().coerceIn(0, maxVol)
            am.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
            Log.d(TAG, "알람 볼륨: $target/$maxVol (${DevSettings.alarmVolume}%)")
        } catch (e: Exception) { Log.w(TAG, "볼륨 강제 설정 실패: ${e.message}") }
        // 300ms 후 플래그 해제 (볼륨 브로드캐스트 전파 시간 고려)
        muteHandler.postDelayed({ ignoringVolumeChange = false }, 300)
    }

    // 스캔 헬스체크: 15초간 BLE 결과 없으면 스캔 재시작
    // 임시 무음: 소리/진동/오버레이 중지, 쿨다운 초기화 (재경보 허용)
    private fun muteTemporarily(source: String) {
        isMuted = true
        isMutedPublic = true
        AlertSoundPlayer.stopSound()
        VibrationHelper.stopVibration(this)
        OverlayManager.hideOverlay()
        val now = System.currentTimeMillis()
        alertState.entries.forEach { (key, value) ->
            alertState[key] = Pair(value.first, now)
        }
        sendStatusBroadcast("🔇 무음 ($source) — 탭하여 즉시 재개")
        sendAlertBroadcast("", BleConstants.LEVEL_SAFE)
        Log.d(TAG, "임시 무음: $source")

        muteHandler.removeCallbacksAndMessages(null)
        muteHandler.postDelayed({
            isMuted = false
            isMutedPublic = false
            sendStatusBroadcast("🔔 무음 해제 — 재경보 준비")
            Log.d(TAG, "무음 해제")
        }, MUTE_DURATION_MS)
    }

    private fun unmuteImmediately() {
        muteHandler.removeCallbacksAndMessages(null)
        isMuted = false
        isMutedPublic = false
        sendStatusBroadcast("🔔 즉시 재개됨")
        Log.d(TAG, "즉시 무음 해제")
    }

    private fun startScanHealthCheck() {
        healthCheckHandler.removeCallbacksAndMessages(null)
        healthCheckHandler.postDelayed(object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - lastScanResultMs
                if (elapsed > SCAN_HEALTH_CHECK_MS) {
                    Log.w(TAG, "스캔 헬스체크: ${elapsed / 1000}초간 결과 없음 → 재시작")
                    sendStatusBroadcast("⟳ 스캔 응답 없음 → 자동 재시작")
                    stopBle()
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (isRunning) applyMode()
                    }, 500)
                    lastScanResultMs = System.currentTimeMillis()
                }
                if (isRunning) healthCheckHandler.postDelayed(this, SCAN_HEALTH_CHECK_MS)
            }
        }, SCAN_HEALTH_CHECK_MS)
    }

    private fun startTestAlert() {
        stopTestAlert()
        sendAlertBroadcast("TEST", BleConstants.LEVEL_DANGER)
        sendStatusBroadcast("🔔 테스트 경보 실행 중")
        testRunnable = object : Runnable {
            override fun run() {
                forceAlarmVolume()
                if (DevSettings.vibrationEnabled) VibrationHelper.vibrateRepeat(this@BleService, DevSettings.vibrationDangerCount)
                if (DevSettings.soundEnabled)     AlertSoundPlayer.playDanger(this@BleService)
                testHandler.postDelayed(this, 3000)
            }
        }
        testHandler.post(testRunnable!!)
        Log.d(TAG, "테스트 경보 시작")
    }

    private fun stopTestAlert() {
        testRunnable?.let { testHandler.removeCallbacks(it) }
        testRunnable = null
        AlertSoundPlayer.stopSound()
        VibrationHelper.stopVibration(this)
        sendAlertBroadcast("TEST", BleConstants.LEVEL_SAFE)
        sendStatusBroadcast("테스트 중지")
        Log.d(TAG, "테스트 경보 중지")
    }

    private fun sendStatusBroadcast(status: String) {
        lastStatus = status  // 공유 변수에도 저장 (Broadcast 실패 대비)
        sendBroadcast(Intent(BROADCAST_BLE_STATUS).putExtra(EXTRA_STATUS, status))
        Log.d(TAG, "상태: $status")
    }

    private fun sendAlertBroadcast(deviceId: String, level: Int) {
        sendBroadcast(Intent(BROADCAST_ALERT).apply {
            putExtra(EXTRA_ID, deviceId)
            putExtra(EXTRA_ALERT_LEVEL, level)
        })
    }

    // BLE만 중지 (서비스는 유지)
    private fun stopBle() {
        bleAdvertiser?.stopAdvertising()
        bleAdvertiser = null
        bleScanner?.stopScanning()
        bleScanner = null
    }

    private fun stopAll() {
        stopBle()
        AlertSoundPlayer.stopSound()
        VibrationHelper.stopVibration(this)
        alertState.clear()
        rssiHistory.clear()
        fastEma.clear()
        slowEma.clear()
        rapidApproachSet.clear()
        // 테스트 경보 중지
        testRunnable?.let { testHandler.removeCallbacks(it) }
        testRunnable = null
        // 무음 타이머 정리
        muteHandler.removeCallbacksAndMessages(null)
        isMuted = false
        isMutedPublic = false
        // 헬스체크 타이머 정리
        healthCheckHandler.removeCallbacksAndMessages(null)
        // WakeLock 해제
        wakeLock?.let { if (it.isHeld) it.release() }
        // 리시버 해제
        try { unregisterReceiver(btStateReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(volumeReceiver)  } catch (_: Exception) {}
        try { unregisterReceiver(screenReceiver)  } catch (_: Exception) {}
        // 공유 상태 초기화
        isRunning = false
        lastStatus = ""
        bleScanCount = 0
        safeAlertFound = 0
        // 시스템 오버레이 제거
        OverlayManager.hideOverlay()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "서비스 완전 중지")
    }

    override fun onDestroy() {
        // isRunning이 true일 때만 정리 (stopAll에서 이미 했으면 중복 방지)
        if (isRunning) stopAll()
        super.onDestroy()
    }
    override fun onBind(intent: Intent): IBinder? { super.onBind(intent); return null }

    private fun buildSubText(tx: Boolean, rx: Boolean) =
        listOfNotNull(if (tx) "송신 ON" else null, if (rx) "수신 ON" else null)
            .joinToString(" · ").ifEmpty { "TX/RX 모두 비활성" }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "SafeAlert 실행 중", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, subText: String): android.app.Notification {
        // 알림에 "무음" 버튼 추가
        val mutePi = android.app.PendingIntent.getService(
            this, 0,
            Intent(this, BleService::class.java).apply { action = ACTION_MUTE_TEMP },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(subText)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_lock_silent_mode, "🔇 무음", mutePi)
            .build()
    }
}
