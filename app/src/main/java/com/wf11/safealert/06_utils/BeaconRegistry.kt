package com.wf11.safealert.utils

import android.content.Context
import android.content.SharedPreferences
import com.wf11.safealert.model.BeaconProfile
import org.json.JSONArray
import org.json.JSONObject

object BeaconRegistry {

    const val MAX_PROFILES = 50  // UUID 프로파일 최대 50개 (각 UUID당 비콘 수 무제한)
    private const val PREF_NAME = "beacon_registry"
    private const val KEY_LIST  = "beacon_profiles"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getAll(): List<BeaconProfile> {
        val json = prefs.getString(KEY_LIST, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                BeaconProfile(
                    uuid       = obj.getString("uuid").uppercase(),
                    label      = obj.getString("label"),
                    type       = obj.optString("type", "IBEACON"),
                    addedAt    = obj.optLong("addedAt", 0L),
                    rssiOffset = obj.optInt("rssiOffset", 0)
                )
            }
        }.getOrDefault(emptyList())
    }

    fun containsUuid(uuid: String): Boolean =
        getAll().any { it.type != "MAC" && it.uuid.equals(uuid.trim(), ignoreCase = true) }

    fun containsMac(mac: String): Boolean =
        getAll().any { it.type == "MAC" && it.uuid.equals(mac.trim(), ignoreCase = true) }

    fun getLabelByUuid(uuid: String): String =
        getAll().firstOrNull { it.uuid.equals(uuid.trim(), ignoreCase = true) }?.label ?: uuid

    fun getLabelByMac(mac: String): String =
        getAll().firstOrNull { it.type == "MAC" && it.uuid.equals(mac.trim(), ignoreCase = true) }?.label ?: mac

    fun add(profile: BeaconProfile): Boolean {
        val list = getAll().toMutableList()
        if (list.size >= MAX_PROFILES) return false
        if (list.any { it.uuid.equals(profile.uuid, ignoreCase = true) }) return false
        list.add(profile.copy(uuid = profile.uuid.uppercase()))
        save(list)
        return true
    }

    fun remove(uuid: String) {
        val list = getAll().filter { !it.uuid.equals(uuid, ignoreCase = true) }
        save(list)
    }

    fun count(): Int = getAll().size

    private fun save(list: List<BeaconProfile>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().apply {
                put("uuid",       p.uuid)
                put("label",      p.label)
                put("type",       p.type)
                put("addedAt",    p.addedAt)
                put("rssiOffset", p.rssiOffset)
            })
        }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }

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
