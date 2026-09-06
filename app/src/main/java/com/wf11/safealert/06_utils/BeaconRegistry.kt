package com.wf11.safealert.utils

import android.content.Context
import android.content.SharedPreferences
import com.wf11.safealert.model.BeaconProfile
import org.json.JSONArray
import org.json.JSONObject

object BeaconRegistry {

    const val MAX_PROFILES = 200  // UUID 프로파일 최대 200개 (각 UUID당 비콘 수 무제한)
    private const val PREF_NAME = "beacon_registry"
    private const val KEY_LIST  = "beacon_profiles"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /**
     * (v1.1.72) UUID 표기 정규화 — 레지스트리 안팎의 유일한 정규화 지점.
     * 대시 없는 32자를 저장하면 BleScanner.buildFilters() 의 UUID.fromString 이 던지고
     * 안쪽 runCatching 이 삼켜 해당 프로파일의 HW 필터가 조용히 누락됐다.
     * 동시에 스캔 표본은 bytesToUuidString 이 만든 대시 36자라 문자열 비교가 영원히 어긋났다.
     * MAC(콜론 포함) 과 형식 불명 문자열은 대문자·trim 만 하고 그대로 통과시킨다.
     */
    fun normUuid(raw: String): String {
        val s = raw.trim().uppercase()
        if (s.contains(':')) return s
        val hex = s.replace("-", "")
        if (hex.length != 32 || !hex.all { it in "0123456789ABCDEF" }) return s
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
               "${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    fun getAll(): List<BeaconProfile> {
        val json = prefs.getString(KEY_LIST, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                BeaconProfile(
                    uuid          = normUuid(obj.getString("uuid")),
                    label         = obj.getString("label"),
                    type          = obj.optString("type", "IBEACON"),
                    addedAt       = obj.optLong("addedAt", 0L),
                    rssiOffset    = obj.optInt("rssiOffset", 0),
                    zoneMute      = obj.optBoolean("zoneMute", false),
                    zoneEnterRssi = obj.optInt("zoneEnterRssi", -65)
                )
            }
        }.getOrDefault(emptyList())
    }

    fun containsUuid(uuid: String): Boolean =
        getAll().any { it.type != "MAC" && it.uuid.equals(normUuid(uuid), ignoreCase = true) }

    fun containsMac(mac: String): Boolean =
        getAll().any { it.type == "MAC" && it.uuid.equals(normUuid(mac), ignoreCase = true) }

    // (v1.1.62) 존 비콘(zoneMute) 프로파일 조회 — 스캐너가 경보 대상에서 제외하고 존 신호로 돌리기 위함
    fun findZoneProfileByUuid(uuid: String): BeaconProfile? =
        getAll().firstOrNull { it.zoneMute && it.type != "MAC" && it.uuid.equals(normUuid(uuid), ignoreCase = true) }

    fun findZoneProfileByMac(mac: String): BeaconProfile? =
        getAll().firstOrNull { it.zoneMute && it.type == "MAC" && it.uuid.equals(normUuid(mac), ignoreCase = true) }

    fun getLabelByUuid(uuid: String): String =
        getAll().firstOrNull { it.uuid.equals(normUuid(uuid), ignoreCase = true) }?.label ?: uuid

    fun getLabelByMac(mac: String): String =
        getAll().firstOrNull { it.type == "MAC" && it.uuid.equals(normUuid(mac), ignoreCase = true) }?.label ?: mac

    fun add(profile: BeaconProfile): Boolean {
        val list = getAll().toMutableList()
        if (list.size >= MAX_PROFILES) return false
        val uuid = normUuid(profile.uuid)
        if (list.any { it.uuid.equals(uuid, ignoreCase = true) }) return false
        list.add(profile.copy(uuid = uuid))
        save(list)
        return true
    }

    fun remove(uuid: String) {
        val list = getAll().filter { !it.uuid.equals(normUuid(uuid), ignoreCase = true) }
        save(list)
    }

    fun count(): Int = getAll().size

    /**
     * 레지스트리 변경 통지. add·remove·mergeProfiles 가 전부 save() 를 경유하므로 여기가 유일 지점.
     * 저장 자체는 정상이었으나 소비자(HW 스캔필터·상태맵)가 변경을 통보받지 못해
     * 삭제한 UUID 가 상태맵에 잔류하고 신규 UUID 는 칩셋 필터에서 누락됐다.
     */
    var onChanged: (() -> Unit)? = null

    private fun save(list: List<BeaconProfile>) {
        prefs.edit().putString(KEY_LIST, exportToJson(list)).apply()
        onChanged?.invoke()
    }

    // ── 기기 간 공유 (export / import) ──────────────────────────

    /** 프로파일 목록을 공유용 JSON 배열 문자열로 직렬화 (저장 포맷과 동일) */
    fun exportToJson(list: List<BeaconProfile>): String {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().apply {
                put("uuid",          p.uuid)
                put("label",         p.label)
                put("type",          p.type)
                put("addedAt",       p.addedAt)
                put("rssiOffset",    p.rssiOffset)
                put("zoneMute",      p.zoneMute)
                put("zoneEnterRssi", p.zoneEnterRssi)
            })
        }
        return arr.toString()
    }

    /** 공유받은 JSON 배열 문자열을 BeaconProfile 목록으로 파싱 (불량 항목은 건너뜀) */
    fun parseProfiles(json: String): List<BeaconProfile> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val uuid = normUuid(obj.optString("uuid", ""))
            if (uuid.isEmpty()) return@mapNotNull null
            BeaconProfile(
                uuid          = uuid,
                label         = obj.optString("label", uuid),
                type          = obj.optString("type", "IBEACON"),
                addedAt       = obj.optLong("addedAt", 0L),
                rssiOffset    = obj.optInt("rssiOffset", 0),
                zoneMute      = obj.optBoolean("zoneMute", false),
                zoneEnterRssi = obj.optInt("zoneEnterRssi", -65)
            )
        }
    }.getOrDefault(emptyList())

    /** 공유 병합 결과 (추가·갱신·한도초과 건수) */
    data class MergeResult(val added: Int, val updated: Int, val skipped: Int)

    /**
     * 받은 프로파일을 로컬에 병합. 같은 UUID 는 받은 값으로 갱신,
     * 신규는 MAX_PROFILES 한도 내에서 추가, 로컬 고유 프로파일은 보존.
     */
    fun mergeProfiles(incoming: List<BeaconProfile>): MergeResult {
        val list = getAll().toMutableList()
        var added = 0; var updated = 0; var skipped = 0
        incoming.forEach { p ->
            val uuid = normUuid(p.uuid)
            if (uuid.isEmpty()) return@forEach
            val norm = p.copy(uuid = uuid)
            val idx = list.indexOfFirst { it.uuid.equals(uuid, ignoreCase = true) }
            when {
                idx >= 0                 -> { list[idx] = norm.copy(addedAt = list[idx].addedAt); updated++ }
                list.size < MAX_PROFILES -> { list.add(norm); added++ }
                else                     -> skipped++
            }
        }
        save(list)
        return MergeResult(added, updated, skipped)
    }

    /** 이 fullId 가 비콘인지(BEA_ 마커 포함). 전역 비콘 수신 강도(게인)를 비콘에만 적용하기 위함. */
    fun isBeaconFullId(fullId: String): Boolean = fullId.contains("BEA_")

    /** BleService의 fullId (예: SAFEALERT_WALKER_BEA_AABBCCDDEEFF)에서 rssiOffset 조회 */
    fun getRssiOffsetForFullId(fullId: String): Int {
        if (!fullId.contains("BEA_")) return 0
        val key = fullId.substringAfter("BEA_")
        return getAll().firstOrNull { profile ->
            when (profile.type) {
                "MAC" -> {
                    // BEA_AABBCCDDEEFF → AA:BB:CC:DD:EE:FF
                    val mac = runCatching { key.chunked(2).take(6).joinToString(":").uppercase() }.getOrDefault("")
                    profile.uuid.equals(mac, ignoreCase = true)
                }
                else -> {
                    // BEA_UUID첫8자
                    profile.uuid.replace("-", "").startsWith(key.take(8), ignoreCase = true)
                }
            }
        }?.rssiOffset ?: 0
    }

    // iBeacon manufacturer data에서 UUID 추출
    // 형식: [0x02, 0x15, 16-byte UUID, 2-byte major, 2-byte minor, 1-byte power]
    fun parseIBeaconUuid(data: ByteArray): String? {
        if (data.size < 18) return null
        if (data[0] != 0x02.toByte() || data[1] != 0x15.toByte()) return null
        return bytesToUuidString(data.slice(2..17).toByteArray())
    }

    fun bytesToUuidString(b: ByteArray): String {
        if (b.size < 16) return ""
        return "%02X%02X%02X%02X-%02X%02X-%02X%02X-%02X%02X-%02X%02X%02X%02X%02X%02X".format(
            b[0].toInt() and 0xFF, b[1].toInt() and 0xFF,
            b[2].toInt() and 0xFF, b[3].toInt() and 0xFF,
            b[4].toInt() and 0xFF, b[5].toInt() and 0xFF,
            b[6].toInt() and 0xFF, b[7].toInt() and 0xFF,
            b[8].toInt() and 0xFF, b[9].toInt() and 0xFF,
            b[10].toInt() and 0xFF, b[11].toInt() and 0xFF,
            b[12].toInt() and 0xFF, b[13].toInt() and 0xFF,
            b[14].toInt() and 0xFF, b[15].toInt() and 0xFF
        )
    }

    // MAC 기반 구버전 호환 (삭제 예정)
    @Deprecated("UUID 방식으로 전환")
    fun contains(mac: String): Boolean = false
    @Deprecated("UUID 방식으로 전환")
    fun getNameByMac(mac: String): String = mac
}
