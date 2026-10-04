package com.wf11.safealert.ui

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wf11.safealert.databinding.ActivityBeaconManagerBinding
import com.wf11.safealert.databinding.ItemBeaconFoundBinding
import com.wf11.safealert.databinding.ItemBeaconProfileBinding
import com.wf11.safealert.model.BeaconProfile
import com.wf11.safealert.utils.BeaconRegistry
import com.wf11.safealert.utils.DevSettings
import com.wf11.safealert.firebase.FirebaseManager
import android.widget.CheckBox
import android.widget.ScrollView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BeaconManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBeaconManagerBinding
    private val foundAdapter    = FoundAdapter()
    private val profileAdapter  = ProfileAdapter()
    private var isScanning = false
    private val stopHandler = Handler(Looper.getMainLooper())

    // Devices found by scan: key (mac or uuid) → info
    data class FoundBeacon(
        val mac: String,         // Device MAC address
        val uuid: String,        // iBeacon/ServiceUUID ("" if none)
        val type: String,        // "IBEACON", "SERVICE_UUID", "MAC_ONLY"
        val rssi: Int,
        val deviceName: String
    )
    private val foundMap = mutableMapOf<String, FoundBeacon>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBeaconManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.apply { title = "비콘 관리"; setDisplayHomeAsUpEnabled(true) }

        binding.rvNearby.layoutManager = LinearLayoutManager(this)
        binding.rvNearby.adapter = foundAdapter
        binding.rvNearby.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))

        binding.rvRegistered.layoutManager = LinearLayoutManager(this)
        binding.rvRegistered.adapter = profileAdapter
        binding.rvRegistered.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))

        binding.btnAddUuid.setOnClickListener { showManualAddDialog() }
        binding.btnAddMac.setOnClickListener  { showMacAddDialog() }
        binding.btnScan.setOnClickListener    { if (isScanning) stopScan() else startScan() }
        binding.btnShare.setOnClickListener   { showShareDialog() }
        binding.btnReceive.setOnClickListener { showReceiveDialog() }

        refreshProfiles()
    }

    // ── Manual MAC address entry ─────────────────────────────────────
    private fun showMacAddDialog(prefillMac: String = "") {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val etLabel = EditText(this).apply { hint = "이름 (예: 작업자01)" }
        val etMac   = EditText(this).apply {
            hint = "MAC 주소 (예: AA:BB:CC:DD:EE:FF)"
            setText(prefillMac)
            textSize = 13f
        }
        layout.addView(etLabel)
        layout.addView(etMac)
        // Zone beacon (safe zone) registration option — a device in contact broadcasts IN_ZONE and goes silent; peers judge it harmless
        val cbZone = CheckBox(this).apply { text = "존 비콘(안전구역) — 존 안에서는 경보 송·수신 전면 중지" }
        val etZoneRssi = EditText(this).apply {
            hint = "존 반경 dBm · 작을수록 넓다 (-80 기본·방 전체 / -65 약 1~2m / -50 코앞)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            visibility = View.GONE
        }
        cbZone.setOnCheckedChangeListener { _, checked ->
            etZoneRssi.visibility = if (checked) View.VISIBLE else View.GONE
        }
        layout.addView(cbZone)
        layout.addView(etZoneRssi)
        // Visitor beacon — when checked, walker-mode PDAs get no alert (only forklifts and EPJs receive it)
        val cbVisitor = CheckBox(this).apply { text = "방문자용 (보행자 단말에는 경보 안 함)"; isChecked = true }
        layout.addView(cbVisitor)

        AlertDialog.Builder(this)
            .setTitle("MAC 주소 비콘 등록")
            .setMessage("특정 기기 1개를 MAC 주소로 등록합니다.")
            .setView(layout)
            .setPositiveButton("등록") { _, _ ->
                val label = etLabel.text.toString().trim().ifEmpty { "비콘" }
                val mac   = etMac.text.toString().trim().uppercase()
                val macRegex = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")
                if (!macRegex.matches(mac)) {
                    Toast.makeText(this, "MAC 형식이 올바르지 않습니다\n예: AA:BB:CC:DD:EE:FF", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val zoneRssi = (etZoneRssi.text.toString().trim().toIntOrNull() ?: -80).coerceIn(-100, -30)
                // SmartTag/hardware beacons get +15dBm by default (about 3x the range).
                // Except zone beacons — zone judging uses only zoneEnterRssi and ignores rssiOffset.
                //   Setting +15 would not affect judging and would only print "범위 +15dBm" on screen, inviting misreading.
                val offset = if (cbZone.isChecked) 0 else 15
                val ok = BeaconRegistry.add(BeaconProfile(mac, label, "MAC", rssiOffset = offset,
                    zoneMute = cbZone.isChecked, zoneEnterRssi = zoneRssi, visitorBeacon = cbVisitor.isChecked))
                val rangeNote = if (offset > 0) " (범위 +${offset}dBm)" else ""
                val zoneNote = if (cbZone.isChecked) " · 존 반경 ${zoneRangeLabel(zoneRssi)}(${zoneRssi}dBm)" else ""
                if (ok) { Toast.makeText(this, "등록됨: $label$rangeNote$zoneNote", Toast.LENGTH_SHORT).show(); refreshProfiles() }
                else    Toast.makeText(this, "이미 등록되어 있거나 한도 초과", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ── Manual UUID entry ──────────────────────────────────────────
    private fun showManualAddDialog(prefillUuid: String = "", prefillType: String = "IBEACON") {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val etLabel = EditText(this).apply { hint = "이름 (예: 현장 작업자)" }
        val etUuid  = EditText(this).apply {
            hint = "UUID (예: 550E8400-E29B-41D4-A716-446655440000)"
            setText(prefillUuid)
            textSize = 12f
        }
        // Type and detection range are both Spinners in a single dialog — combining setSingleChoiceItems (type) with
        //   setMultiChoiceItems (placeholder) breaks the type list, and a NeutralButton that re-calls showManualAddDialog
        //   resets selectedRange to 0 so the range choice is never saved.
        val typeOptions  = arrayOf("iBeacon (Proximity UUID)", "Service UUID (Eddystone 등)")
        val spType = Spinner(this).apply {
            adapter = ArrayAdapter(this@BeaconManagerActivity,
                android.R.layout.simple_spinner_item, typeOptions).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(if (prefillType == "IBEACON") 0 else 1)
        }
        val rangeOptions = arrayOf(
            "기본 범위 (전역 설정)",
            "넓게 +10dBm (약 2배, SmartTag 권장)",
            "매우 넓게 +20dBm (약 4배)"
        )
        val spRange = Spinner(this).apply {
            adapter = ArrayAdapter(this@BeaconManagerActivity,
                android.R.layout.simple_spinner_item, rangeOptions).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(1)   // New registrations default to wide +10dBm (beacon alerts from farther away)
        }

        // Zone beacon (safe zone) registration option — a device in contact broadcasts IN_ZONE and goes silent; peers judge it harmless
        val cbZone = CheckBox(this).apply { text = "존 비콘(안전구역) — 존 안에서는 경보 송·수신 전면 중지" }
        val etZoneRssi = EditText(this).apply {
            hint = "존 반경 dBm · 작을수록 넓다 (-80 기본·방 전체 / -65 약 1~2m / -50 코앞)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            visibility = View.GONE
        }
        cbZone.setOnCheckedChangeListener { _, checked ->
            etZoneRssi.visibility = if (checked) View.VISIBLE else View.GONE
        }

        layout.addView(etLabel)
        layout.addView(etUuid)
        layout.addView(TextView(this).apply { text = "유형"; setPadding(0, 24, 0, 4) })
        layout.addView(spType)
        layout.addView(TextView(this).apply { text = "감지 범위 (이 UUID 그룹 전체)"; setPadding(0, 24, 0, 4) })
        layout.addView(spRange)
        layout.addView(cbZone)
        layout.addView(etZoneRssi)
        // Visitor beacon — when checked, walker-mode PDAs get no alert (only forklifts and EPJs receive it)
        val cbVisitor = CheckBox(this).apply { text = "방문자용 (보행자 단말에는 경보 안 함)"; isChecked = true }
        layout.addView(cbVisitor)

        AlertDialog.Builder(this)
            .setTitle("UUID 프로파일 추가")
            .setView(layout)
            .setPositiveButton("등록") { _, _ ->
                val label = etLabel.text.toString().trim().ifEmpty { "비콘 그룹" }
                val uuid  = etUuid.text.toString().trim().uppercase()
                    .replace("[^0-9A-F-]".toRegex(), "")
                if (uuid.length < 32) {
                    Toast.makeText(this, "UUID 형식이 올바르지 않습니다", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val type   = if (spType.selectedItemPosition == 0) "IBEACON" else "SERVICE_UUID"
                // Zone beacons don't use rssiOffset — same rule as the MAC registration path
                val offset = if (cbZone.isChecked) 0
                             else when (spRange.selectedItemPosition) { 1 -> 10; 2 -> 20; else -> 0 }
                val zoneRssi = (etZoneRssi.text.toString().trim().toIntOrNull() ?: -80).coerceIn(-100, -30)
                val ok = BeaconRegistry.add(BeaconProfile(uuid, label, type, rssiOffset = offset,
                    zoneMute = cbZone.isChecked, zoneEnterRssi = zoneRssi, visitorBeacon = cbVisitor.isChecked))
                if (ok) {
                    val rangeNote = if (offset > 0) " (범위 +${offset}dBm)" else ""
                    val zoneNote  = if (cbZone.isChecked) " · 존 반경 ${zoneRangeLabel(zoneRssi)}(${zoneRssi}dBm)" else ""
                    Toast.makeText(this, "등록됨: $label$rangeNote$zoneNote", Toast.LENGTH_SHORT).show()
                    refreshProfiles()
                } else Toast.makeText(this, "이미 등록되어 있거나 한도 초과", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // Share path is beacon_share/<siteCode>/ — no upload, download or delete without a site code
    private fun requireSiteCode(): Boolean {
        if (DevSettings.siteCode.isNotEmpty()) return true
        Toast.makeText(this, "사업장 코드가 설정되지 않았습니다 (개발자 설정)", Toast.LENGTH_LONG).show()
        return false
    }

    // ── Device-to-device sharing: select, then upload ───────────────────────────
    private fun showShareDialog() {
        if (!requireSiteCode()) return
        val profiles = BeaconRegistry.getAll()
        if (profiles.isEmpty()) {
            Toast.makeText(this, "공유할 비콘이 없습니다", Toast.LENGTH_SHORT).show(); return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 8)
        }
        val cbAll = CheckBox(this).apply {
            text = "전체 선택"; isChecked = true
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        root.addView(cbAll)
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(0xFFEAECF0.toInt())
        })

        val listLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val checks = profiles.map { p ->
            CheckBox(this).apply {
                val typeStr = when (p.type) { "IBEACON" -> "iBeacon"; "MAC" -> "MAC"; else -> "SvcUUID" }
                text = "${p.label}  ·  $typeStr"
                isChecked = true
                listLayout.addView(this)
            }
        }
        val listH = (resources.displayMetrics.density * 220).toInt()
        root.addView(ScrollView(this).apply {
            addView(listLayout)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, listH)
        })

        cbAll.setOnCheckedChangeListener { _, isChecked -> checks.forEach { it.isChecked = isChecked } }

        root.addView(TextView(this).apply { text = "세트 이름"; setPadding(0, 24, 0, 4) })
        val etName = EditText(this).apply { hint = "예: 1구역, A동 입구" }
        root.addView(etName)

        AlertDialog.Builder(this)
            .setTitle("기기 간 공유 (전송)")
            .setView(root)
            .setPositiveButton("전송") { _, _ ->
                val selected = profiles.filterIndexed { i, _ -> checks[i].isChecked }
                if (selected.isEmpty()) {
                    Toast.makeText(this, "선택된 비콘이 없습니다", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val name = etName.text.toString().trim().ifEmpty { "공유세트" }
                val json = BeaconRegistry.exportToJson(selected)
                val sender = getSharedPreferences("safealert_prefs", MODE_PRIVATE)
                    .getString("device_id", "기기") ?: "기기"
                // Upload (including overwriting the same name) only after the settings PIN check
                showDevPinDialog {
                    Toast.makeText(this, "전송 중...", Toast.LENGTH_SHORT).show()
                    FirebaseManager.uploadBeaconSet(name, json, selected.size, sender) { ok ->
                        runOnUiThread {
                            Toast.makeText(this,
                                if (ok) "전송 완료: '$name' (${selected.size}개)"
                                else "전송 실패 (네트워크 확인)",
                                Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ── Receive: set list → preview → merge ──────────────────────
    private fun showReceiveDialog() {
        if (!requireSiteCode()) return
        Toast.makeText(this, "세트 목록 불러오는 중...", Toast.LENGTH_SHORT).show()
        FirebaseManager.listBeaconSets { sets ->
            runOnUiThread { onBeaconSetsLoaded(sets) }
        }
    }

    // Firebase async callback — don't show a dialog if the screen is already closed or
    // recreated (a BadTokenException would also kill BleService in the same process)
    internal fun onBeaconSetsLoaded(sets: List<FirebaseManager.BeaconSetMeta>) {
        if (isFinishing || isDestroyed) return
        if (sets.isEmpty()) {
            Toast.makeText(this, "공유된 세트가 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
        val labels = sets.map { s ->
            "${s.name}  (${s.count}개)\n${s.sender} · ${fmt.format(Date(s.timestamp))}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("받을 세트 선택")
            .setItems(labels) { _, which -> showReceivePreview(sets[which]) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showReceivePreview(set: FirebaseManager.BeaconSetMeta) {
        FirebaseManager.downloadBeaconSet(set.key) { json ->
            runOnUiThread { onBeaconSetDownloaded(set, json) }
        }
    }

    // Firebase async callback — don't show a dialog if the screen is already closed or
    // recreated (a BadTokenException would also kill BleService in the same process)
    internal fun onBeaconSetDownloaded(set: FirebaseManager.BeaconSetMeta, json: String?) {
        if (isFinishing || isDestroyed) return
        if (json == null) {
            Toast.makeText(this, "세트를 불러오지 못했습니다", Toast.LENGTH_SHORT).show()
            return
        }
        // Reject the whole set if any entry is unreadable or out of range
        val incoming = BeaconRegistry.parseProfiles(json)
            .getOrElse { return showRejectedSet(set, it.message ?: "읽을 수 없는 항목이 있습니다") }
        if (incoming.isEmpty()) {
            Toast.makeText(this, "세트가 비어 있습니다", Toast.LENGTH_SHORT).show()
            return
        }
        BeaconRegistry.validateShared(incoming)?.let { return showRejectedSet(set, it) }
        // Changes that receiving would make — preview alert-affecting changes such as safe zone or visitor flags
        val c = BeaconRegistry.summarizeChanges(BeaconRegistry.getAll(), incoming)
        val msg = buildString {
            append("세트: ${set.name}\n보낸 기기: ${set.sender}\n비콘 ${incoming.size}개\n\n")
            append("· 새로 추가 ${c.added}개 · 갱신 ${incoming.size - c.added}개\n")
            append("· 감지 범위 보정값 변경 ${c.offsetChanged}개\n")
            append("· 안전구역 지정 ${c.zoneOn}개 · 해제 ${c.zoneOff}개\n")
            if (c.zoneWidened > 0) append("· 안전구역 반경 넓어짐 ${c.zoneWidened}개\n")
            if (c.visitorChanged > 0) append("· 방문자용 설정 변경 ${c.visitorChanged}개\n")
            append("\n받으면 같은 UUID는 받은 값으로 갱신되고, 신규는 추가됩니다. 내 기기에만 있는 비콘은 그대로 유지됩니다.")
        }
        AlertDialog.Builder(this)
            .setTitle("받기 확인")
            .setMessage(msg)
            .setPositiveButton("받기") { _, _ ->
                val r = BeaconRegistry.mergeProfiles(incoming)
                refreshProfiles()
                Toast.makeText(this,
                    "병합 완료 — 추가 ${r.added} · 갱신 ${r.updated}" +
                    (if (r.skipped > 0) " · 한도초과 ${r.skipped}" else ""),
                    Toast.LENGTH_LONG).show()
            }
            .setNeutralButton("이 세트 삭제") { _, _ -> confirmDeleteSet(set) }
            .setNegativeButton("취소", null)
            .show()
    }

    /** Notice for a rejected set — shows the reason and still allows deleting it from the cloud. */
    private fun showRejectedSet(set: FirebaseManager.BeaconSetMeta, reason: String) {
        AlertDialog.Builder(this)
            .setTitle("받을 수 없는 세트")
            .setMessage("세트: ${set.name}\n보낸 기기: ${set.sender}\n\n$reason\n\n이 세트는 받지 않습니다.")
            .setPositiveButton("확인", null)
            .setNeutralButton("이 세트 삭제") { _, _ -> confirmDeleteSet(set) }
            .show()
    }

    private fun confirmDeleteSet(set: FirebaseManager.BeaconSetMeta) {
        AlertDialog.Builder(this)
            .setTitle("세트 삭제")
            .setMessage("'${set.name}' 공유 세트를 클라우드에서 삭제합니다.\n(내 기기에 등록된 비콘은 삭제되지 않습니다)")
            .setPositiveButton("삭제") { _, _ ->
                showDevPinDialog {
                    FirebaseManager.deleteBeaconSet(set.key) { ok ->
                        runOnUiThread {
                            Toast.makeText(this, if (ok) "삭제됨: ${set.name}" else "삭제 실패", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ── Beacon discovery via BLE scan ──────────────────────────────────
    private fun startScan() {
        if (!hasPermissions()) { Toast.makeText(this, "BLE 권한이 필요합니다", Toast.LENGTH_SHORT).show(); return }
        val scanner = (getSystemService(BluetoothManager::class.java))
            ?.adapter?.bluetoothLeScanner
            ?: run { Toast.makeText(this, "블루투스를 켜주세요", Toast.LENGTH_SHORT).show(); return }

        isScanning = true
        // Lift BleScanner's HW filter only during the discovery scan, so unregistered UUIDs are caught too.
        com.wf11.safealert.ble.BleScanner.setDiscoveryMode(true)
        foundMap.clear()
        foundAdapter.update(emptyList())
        binding.layoutScanResult.visibility = View.VISIBLE
        binding.tvScanStatus.text = "스캔 중... (15초)"
        binding.btnScan.text = "⏹ 중지"
        binding.layoutScanResult.visibility = View.VISIBLE  // Show the scan section

        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, scanCallback)
        com.wf11.safealert.ble.BleScanner.noteScanStart()   // Android counts it against the app's scan start limit too
        stopHandler.postDelayed({ if (isScanning) stopScan() }, 15_000)
    }

    private fun stopScan() {
        isScanning = false
        // The 15 s timer, the stop button and onDestroy all converge here — the single restore point.
        com.wf11.safealert.ble.BleScanner.setDiscoveryMode(false)
        stopHandler.removeCallbacksAndMessages(null)
        runCatching {
            (getSystemService(BluetoothManager::class.java))?.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        }
        binding.btnScan.text = "스캔으로 발견"
        binding.tvScanStatus.text = "${foundMap.size}개 발견"
        foundAdapter.update(foundMap.values.sortedByDescending { it.rssi })
    }

    /**
     * Picks at most one candidate UUID for registration from one advertising packet. Returns (UUID, type).
     *
     * One device advertises several UUIDs. A phone also mixes in common services broadcast by the OS (Fast Pair etc.), so accepting
     * all of them would show one beacon as several rows. Hence only one is picked.
     * Priority: iBeacon > 128-bit custom > others (16-bit SIG-assigned).
     * Why the 16-bit SIG format is not discarded — SafeAlert's own SERVICE_UUID (0x1234) uses that format, and some real beacon
     * hardware uses 16-bit custom values. It is only ranked last.
     */
    private fun pickBeaconUuid(record: ScanRecord): Pair<String, String>? {
        // iBeacon (Apple CompanyID 0x004C)
        record.getManufacturerSpecificData(0x004C)?.let { data ->
            BeaconRegistry.parseIBeaconUuid(data)?.let { return it to "IBEACON" }
        }
        // serviceUuids (AD 0x02/0x03/0x06/0x07) and serviceData (AD 0x16) are independent fields of an advertising packet.
        //   A beacon that advertises only service data has empty serviceUuids, so checking only those would hide the UUID
        //   register button (and a deleted UUID could never be registered again). Check both.
        val uuids = ((record.serviceUuids ?: emptyList()) + (record.serviceData?.keys ?: emptySet()))
            .map { it.uuid.toString().uppercase() }
            .filterNot { it.equals(com.wf11.safealert.ble.BleConstants.SERVICE_UUID, true) }
        if (uuids.isEmpty()) return null
        val sigSuffix = "-0000-1000-8000-00805F9B34FB"
        val custom = uuids.firstOrNull { !(it.startsWith("0000") && it.endsWith(sigSuffix)) }
        return (custom ?: uuids.first()) to "SERVICE_UUID"
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord ?: return
            val mac    = result.device.address ?: return
            // Priority 1: name in the advertising packet, 2: system cached name, 3: MAC address
            val name   = record.deviceName?.takeIf { it.isNotBlank() }
                ?: result.device.name?.takeIf { it.isNotBlank() }
                ?: mac

            // One device = one row: foundMap is keyed by MAC.
            //   Keying by UUID gives one row per advertised UUID; with serviceData.keys included, even the phone OS's system
            //   advertisements become separate rows, the same MAC shows up on several lines, and the rows keep multiplying the longer
            //   the scan runs (ADV and SCAN_RSP carry different AD fields). A device has only one MAC, so this cannot build up.
            val picked = pickBeaconUuid(record)
            val prev   = foundMap[mac]
            // The UUID is only ever upgraded. If a later packet without a UUID reverted an already captured UUID to
            // MAC_ONLY, the same device would turn into a row without a register button.
            val uuid = picked?.first ?: prev?.uuid.orEmpty()
            val type = if (uuid.isBlank()) "MAC_ONLY" else (picked?.second ?: prev?.type ?: "MAC_ONLY")
            foundMap[mac] = FoundBeacon(mac, uuid, type, result.rssi, name)

            runOnUiThread {
                foundAdapter.update(foundMap.values.sortedByDescending { it.rssi })
                binding.tvScanStatus.text = "${foundMap.size}개 발견"
            }
        }
    }

    private fun refreshProfiles() {
        val list = BeaconRegistry.getAll()
        profileAdapter.update(list)
        binding.tvCount.text = "${list.size} / ${BeaconRegistry.MAX_PROFILES}"
        foundAdapter.notifyDataSetChanged()  // Refresh the registered marker
    }

    private fun hasPermissions(): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.ACCESS_FINE_LOCATION)
        return perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
    override fun onResume() { super.onResume(); refreshProfiles() }
    override fun onDestroy() { super.onDestroy(); if (isScanning) stopScan() }

    // ── Adapters ────────────────────────────────────────────────

    inner class FoundAdapter : RecyclerView.Adapter<FoundAdapter.VH>() {
        private var items = listOf<FoundBeacon>()
        fun update(list: List<FoundBeacon>) { items = list; notifyDataSetChanged() }
        inner class VH(val b: ItemBeaconFoundBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(p: ViewGroup, v: Int) =
            VH(ItemBeaconFoundBinding.inflate(LayoutInflater.from(p.context), p, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, i: Int) {
            val d = items[i]
            h.b.tvDeviceName.text = d.deviceName.ifBlank { "이름 없음" }
            h.b.tvMacAddr.text    = d.mac  // Show the MAC address directly

            // UUID button (shown only for iBeacon/ServiceUUID)
            if (d.type != "MAC_ONLY" && d.uuid.isNotBlank()) {
                h.b.tvUuid.text           = d.uuid
                h.b.tvUuid.visibility     = android.view.View.VISIBLE
                h.b.btnAddUuid.visibility = android.view.View.VISIBLE
                val uuidReg = BeaconRegistry.containsUuid(d.uuid)
                h.b.btnAddUuid.text      = if (uuidReg) "UUID 등록됨" else "UUID 등록"
                h.b.btnAddUuid.isEnabled = !uuidReg
                h.b.btnAddUuid.setOnClickListener { showManualAddDialog(d.uuid, d.type) }
            } else {
                h.b.tvUuid.visibility     = android.view.View.GONE
                h.b.btnAddUuid.visibility = android.view.View.GONE
            }

            // MAC button always shown
            val macReg = BeaconRegistry.containsMac(d.mac)
            h.b.btnAddMac.text      = if (macReg) "MAC 등록됨" else "MAC 등록"
            h.b.btnAddMac.isEnabled = !macReg
            h.b.btnAddMac.setOnClickListener { showMacAddDialog(d.mac) }
        }
    }

    inner class ProfileAdapter : RecyclerView.Adapter<ProfileAdapter.VH>() {
        private var items = listOf<BeaconProfile>()
        fun update(list: List<BeaconProfile>) { items = list; notifyDataSetChanged() }
        inner class VH(val b: ItemBeaconProfileBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(p: ViewGroup, v: Int) =
            VH(ItemBeaconProfileBinding.inflate(LayoutInflater.from(p.context), p, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, i: Int) {
            val p = items[i]
            h.b.tvLabel.text = p.label
            h.b.tvUuid.text  = p.uuid
            val typeStr  = when (p.type) { "IBEACON" -> "iBeacon"; "MAC" -> "MAC 주소"; else -> "Service UUID" }
            // No range label on zone beacons — zone judging ignores rssiOffset, so showing
            //   "범위 +15dBm" would be misread as the zone radius widening by that much.
            val rangeStr = when {
                p.zoneMute         -> ""
                p.rssiOffset >= 20 -> " · 범위 매우 넓음(+${p.rssiOffset}dBm)"
                p.rssiOffset > 0   -> " · 범위 +${p.rssiOffset}dBm"
                else               -> " · 기본 범위"
            }
            // Zone beacon marker — identifies safe-zone profiles in the list
            val zoneStr = if (p.zoneMute) " · 존 반경 ${zoneRangeLabel(p.zoneEnterRssi)}(${p.zoneEnterRssi}dBm)" else ""
            val visitorStr = if (p.visitorBeacon) " · 방문자용" else " · 장비용"
            h.b.tvType.text = "$typeStr$rangeStr$zoneStr$visitorStr"
            h.b.btnDelete.setOnClickListener {
                AlertDialog.Builder(this@BeaconManagerActivity)
                    .setTitle("삭제 확인").setMessage("'${p.label}' UUID 프로파일을 삭제하시겠습니까?\n이 UUID의 비콘이 전부 감지되지 않습니다.")
                    .setPositiveButton("삭제") { _, _ -> BeaconRegistry.remove(p.uuid); refreshProfiles() }
                    .setNegativeButton("취소", null).show()
            }
        }
    }
}

/**
 * Felt zone-radius label — for dBm, 'lower = wider'.
 *   The rssiOffset on the same row follows the opposite rule, 'higher = wider'. Showing only the number lets
 *   -30dBm (the narrowest) be misread as 'the strongest setting', and the zone never forms (a real reported case).
 *   Zone sites (smoking area, office, break room, restroom) must cover the whole room, so around -80 is standard.
 */
internal fun zoneRangeLabel(dbm: Int): String = when {
    dbm <= -85 -> "매우 넓음(방 전체)"
    dbm <= -75 -> "넓음(약 3~5m)"
    dbm <= -60 -> "보통(약 1~2m)"
    dbm <= -45 -> "좁음(약 1m 이내)"
    else       -> "매우 좁음(코앞)"
}
