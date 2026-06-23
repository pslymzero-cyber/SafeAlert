package com.wf11.safealert.ui

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
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

    // 스캔으로 발견된 기기: key(mac or uuid) → 정보
    data class FoundBeacon(
        val mac: String,         // 기기 MAC 주소
        val uuid: String,        // iBeacon/ServiceUUID (없으면 "")
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

    // ── MAC 주소 직접 입력 ─────────────────────────────────────
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
                val ok = BeaconRegistry.add(BeaconProfile(mac, label, "MAC", rssiOffset = 15))
                // SmartTag/하드웨어 비콘은 기본 +15dBm (약 3배 범위) 적용
                if (ok) { Toast.makeText(this, "등록됨: $label (범위 +15dBm)", Toast.LENGTH_SHORT).show(); refreshProfiles() }
                else    Toast.makeText(this, "이미 등록되어 있거나 한도 초과", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ── UUID 직접 입력 ──────────────────────────────────────────
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
        // [v1.1.14] 유형·감지범위를 모두 한 다이얼로그의 Spinner 로 통합.
        //   구버전은 setSingleChoiceItems(유형)+setMultiChoiceItems(placeholder) 충돌로 유형 리스트가 깨지고,
        //   '감지 범위…' NeutralButton 이 showManualAddDialog 를 재귀 호출 → 새 호출의 selectedRange 가 0 으로
        //   리셋되어 범위 선택이 저장되지 않던 버그(= 감지범위 설정이 안 먹던 원인)를 제거한다.
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
            setSelection(1)   // (v1.1.17) 신규 등록 기본 = 넓게 +10dBm (비콘이 더 먼 거리에서 알림)
        }

        layout.addView(etLabel)
        layout.addView(etUuid)
        layout.addView(TextView(this).apply { text = "유형"; setPadding(0, 24, 0, 4) })
        layout.addView(spType)
        layout.addView(TextView(this).apply { text = "감지 범위 (이 UUID 그룹 전체)"; setPadding(0, 24, 0, 4) })
        layout.addView(spRange)

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
                val offset = when (spRange.selectedItemPosition) { 1 -> 10; 2 -> 20; else -> 0 }
                val ok = BeaconRegistry.add(BeaconProfile(uuid, label, type, rssiOffset = offset))
                if (ok) {
                    val rangeNote = if (offset > 0) " (범위 +${offset}dBm)" else ""
                    Toast.makeText(this, "등록됨: $label$rangeNote", Toast.LENGTH_SHORT).show()
                    refreshProfiles()
                } else Toast.makeText(this, "이미 등록되어 있거나 한도 초과", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ── 기기 간 공유: 선택 후 업로드 ───────────────────────────
    private fun showShareDialog() {
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
            .setNegativeButton("취소", null)
            .show()
    }

    // ── 받기: 세트 목록 → 미리보기 → 병합 ──────────────────────
    private fun showReceiveDialog() {
        Toast.makeText(this, "세트 목록 불러오는 중...", Toast.LENGTH_SHORT).show()
        FirebaseManager.listBeaconSets { sets ->
            runOnUiThread {
                if (sets.isEmpty()) {
                    Toast.makeText(this, "공유된 세트가 없습니다", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
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
        }
    }

    private fun showReceivePreview(set: FirebaseManager.BeaconSetMeta) {
        FirebaseManager.downloadBeaconSet(set.key) { json ->
            runOnUiThread {
                if (json == null) {
                    Toast.makeText(this, "세트를 불러오지 못했습니다", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val incoming = BeaconRegistry.parseProfiles(json)
                if (incoming.isEmpty()) {
                    Toast.makeText(this, "세트가 비어 있습니다", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val existing = BeaconRegistry.getAll().map { it.uuid.uppercase() }.toSet()
                val newCnt = incoming.count { it.uuid.uppercase() !in existing }
                val updCnt = incoming.size - newCnt
                val msg = "세트: ${set.name}\n보낸 기기: ${set.sender}\n" +
                          "비콘 ${incoming.size}개 (신규 $newCnt · 갱신 $updCnt)\n\n" +
                          "받으면 같은 UUID는 받은 값으로 갱신되고, 신규는 추가됩니다. 내 기기에만 있는 비콘은 그대로 유지됩니다."
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
                    .setNeutralButton("이 세트 삭제") { _, _ ->
                        AlertDialog.Builder(this)
                            .setTitle("세트 삭제")
                            .setMessage("'${set.name}' 공유 세트를 클라우드에서 삭제합니다.\n(내 기기에 등록된 비콘은 삭제되지 않습니다)")
                            .setPositiveButton("삭제") { _, _ ->
                                FirebaseManager.deleteBeaconSet(set.key) { ok ->
                                    runOnUiThread {
                                        Toast.makeText(this, if (ok) "삭제됨: ${set.name}" else "삭제 실패", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            .setNegativeButton("취소", null)
                            .show()
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
        }
    }

    // ── BLE 스캔으로 비콘 발견 ──────────────────────────────────
    private fun startScan() {
        if (!hasPermissions()) { Toast.makeText(this, "BLE 권한이 필요합니다", Toast.LENGTH_SHORT).show(); return }
        val scanner = (getSystemService(BluetoothManager::class.java))
            ?.adapter?.bluetoothLeScanner
            ?: run { Toast.makeText(this, "블루투스를 켜주세요", Toast.LENGTH_SHORT).show(); return }

        isScanning = true
        foundMap.clear()
        foundAdapter.update(emptyList())
        binding.layoutScanResult.visibility = View.VISIBLE
        binding.tvScanStatus.text = "스캔 중... (15초)"
        binding.btnScan.text = "⏹ 중지"
        binding.layoutScanResult.visibility = View.VISIBLE  // 스캔 섹션 표시

        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, scanCallback)
        stopHandler.postDelayed({ if (isScanning) stopScan() }, 15_000)
    }

    private fun stopScan() {
        isScanning = false
        stopHandler.removeCallbacksAndMessages(null)
        runCatching {
            (getSystemService(BluetoothManager::class.java))?.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        }
        binding.btnScan.text = "🔍 스캔으로 발견"
        binding.tvScanStatus.text = "${foundMap.size}개 발견"
        foundAdapter.update(foundMap.values.sortedByDescending { it.rssi })
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord ?: return
            val rssi   = result.rssi
            val mac    = result.device.address ?: return
            // 1순위: 광고 패킷 내 이름, 2순위: 시스템 캐시 이름, 3순위: MAC 주소
            val name   = record.deviceName?.takeIf { it.isNotBlank() }
                ?: result.device.name?.takeIf { it.isNotBlank() }
                ?: mac
            var handled = false

            // iBeacon (Apple CompanyID 0x004C)
            val iBeaconData = record.getManufacturerSpecificData(0x004C)
            if (iBeaconData != null) {
                val uuid = BeaconRegistry.parseIBeaconUuid(iBeaconData)
                if (uuid != null) {
                    foundMap[uuid] = FoundBeacon(mac, uuid, "IBEACON", rssi, name)
                    handled = true
                }
            }

            // Service UUID 방식 (SafeAlert UUID 제외)
            record.serviceUuids?.forEach { parcelUuid ->
                val uuid = parcelUuid.uuid.toString().uppercase()
                if (!uuid.equals(com.wf11.safealert.ble.BleConstants.SERVICE_UUID, true)) {
                    foundMap[uuid] = FoundBeacon(mac, uuid, "SERVICE_UUID", rssi, name)
                    handled = true
                }
            }

            // UUID 없는 일반 BLE 기기 → MAC으로만 등록
            if (!handled) foundMap[mac] = FoundBeacon(mac, "", "MAC_ONLY", rssi, name)

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
        foundAdapter.notifyDataSetChanged()  // 등록됨 표시 갱신
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
            h.b.tvMacAddr.text    = d.mac  // MAC 주소 직접 표시

            // UUID 버튼 (iBeacon/ServiceUUID인 경우만 표시)
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

            // MAC 버튼 항상 표시
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
            val rangeStr = when {
                p.rssiOffset >= 20 -> "범위 매우 넓음(+${p.rssiOffset}dBm)"
                p.rssiOffset > 0   -> "범위 +${p.rssiOffset}dBm"
                else               -> "기본 범위"
            }
            h.b.tvType.text = "$typeStr · $rangeStr"
            h.b.btnDelete.setOnClickListener {
                AlertDialog.Builder(this@BeaconManagerActivity)
                    .setTitle("삭제 확인").setMessage("'${p.label}' UUID 프로파일을 삭제하시겠습니까?\n이 UUID의 비콘이 전부 감지되지 않습니다.")
                    .setPositiveButton("삭제") { _, _ -> BeaconRegistry.remove(p.uuid); refreshProfiles() }
                    .setNegativeButton("취소", null).show()
            }
        }
    }
}
