package com.wf11.safealert.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.wf11.safealert.model.BeaconProfile
import org.json.JSONArray
import org.json.JSONObject

object BeaconRegistry {

    const val MAX_PROFILES = 200  // Max 200 UUID profiles (unlimited beacons per UUID)
    private const val PREF_NAME = "beacon_registry"
    private const val KEY_LIST  = "beacon_profiles"

    private lateinit var appCtx: Context

    // Registrations are separated per site — every access opens the current site's file (getSharedPreferences
    //   is an in-process cache, so repeated calls are cheap). getAll() parses from disk every time and nothing
    //   lingers in memory, so a site change switches to that site's list immediately without a reload.
    private val prefs: SharedPreferences
        get() = appCtx.getSharedPreferences(DevSettings.sitePrefName(PREF_NAME), Context.MODE_PRIVATE)

    fun init(context: Context) {
        appCtx = context.applicationContext
    }

    /**
     * UUID notation normalization — the only normalization point in and around the registry.
     * Storing a 32-char UUID without dashes makes UUID.fromString in BleScanner.buildFilters() throw,
     * and the inner runCatching swallows it, silently dropping that profile's HW filter.
     * Scan samples are also the 36-char dashed form from bytesToUuidString, so string comparison would never match.
     * MAC addresses (with colons) and unknown formats are only uppercased and trimmed, then passed through.
     */
    fun normUuid(raw: String): String {
        val s = raw.trim().uppercase()
        if (s.contains(':')) return s
        val hex = s.replace("-", "")
        if (hex.length != 32 || !hex.all { it in "0123456789ABCDEF" }) return s
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
               "${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    // One corrupt entry must not empty the whole list (zone beacons included) — only an array parse failure yields an empty list;
    // entries are recovered individually. ponytail: a corrupt entry left in place logs one Log.w per call — accepted since corruption is rare;
    // if the logs get frequent, consider cleaning up by rewriting prefs.
    fun getAll(): List<BeaconProfile> {
        val json = prefs.getString(KEY_LIST, "[]") ?: "[]"
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val result = (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val obj = arr.getJSONObject(i)
                BeaconProfile(
                    uuid          = normUuid(obj.getString("uuid")),
                    label         = obj.getString("label"),
                    type          = obj.optString("type", "IBEACON"),
                    addedAt       = obj.optLong("addedAt", 0L),
                    rssiOffset    = obj.optInt("rssiOffset", 0),
                    zoneMute      = obj.optBoolean("zoneMute", false),
                    zoneEnterRssi = obj.optInt("zoneEnterRssi", -80),
                    visitorBeacon = obj.optBoolean("visitorBeacon", true)
                )
            }.getOrNull()
        }
        if (result.size < arr.length()) {
            Log.w("BeaconRegistry", "손상 비콘 항목 ${arr.length() - result.size}개 건너뜀")
        }
        return result
    }

    fun containsUuid(uuid: String): Boolean =
        getAll().any { it.type != "MAC" && it.uuid.equals(normUuid(uuid), ignoreCase = true) }

    fun containsMac(mac: String): Boolean =
        getAll().any { it.type == "MAC" && it.uuid.equals(normUuid(mac), ignoreCase = true) }

    // Zone beacon (zoneMute) profile lookup — lets the scanner exclude them from alert targets and route them as zone signals
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
     * Registry change notification. add, remove and mergeProfiles all go through save(), so this is the single point.
     * Without it, saving still works but consumers (HW scan filter, state maps) are not told about changes:
     * deleted UUIDs linger in state maps and new UUIDs are missing from the chipset filter.
     */
    var onChanged: (() -> Unit)? = null

    private fun save(list: List<BeaconProfile>) {
        prefs.edit().putString(KEY_LIST, exportToJson(list)).apply()
        onChanged?.invoke()
    }

    // ── Device-to-device sharing (export / import) ──────────────────────────

    /** Serialize the profile list into a JSON array string for sharing (same as the storage format) */
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
                put("visitorBeacon", p.visitorBeacon)
            })
        }
        return arr.toString()
    }

    /**
     * Parse a received shared JSON array string into a BeaconProfile list.
     * Fails if any entry is unreadable (not an object, no UUID, wrong type in a known field)
     * or the JSON itself is broken — the whole set is rejected. An empty array gives an empty list.
     * The failure reason names which entry and which field (so the sending device can fix it).
     */
    fun parseProfiles(json: String): Result<List<BeaconProfile>> {
        val arr = runCatching { JSONArray(json) }.getOrElse { return parseFail("JSON 형식이 깨졌습니다") }
        val out = ArrayList<BeaconProfile>(arr.length())
        for (i in 0 until arr.length()) {
            val no = i + 1
            val obj = arr.optJSONObject(i) ?: return parseFail("${no}번째 항목이 비콘 정보 형식이 아닙니다")
            wrongTypeField(obj)?.let { return parseFail("${no}번째 항목의 $it 값 형식이 틀렸습니다") }
            val uuid = normUuid(obj.optString("uuid", ""))
            if (uuid.isEmpty()) return parseFail("${no}번째 항목에 UUID 가 없습니다")
            out += BeaconProfile(
                uuid          = uuid,
                label         = obj.optString("label", uuid),
                type          = obj.optString("type", "IBEACON"),
                addedAt       = obj.optLong("addedAt", 0L),
                rssiOffset    = obj.optInt("rssiOffset", 0),
                zoneMute      = obj.optBoolean("zoneMute", false),
                zoneEnterRssi = obj.optInt("zoneEnterRssi", -80),
                visitorBeacon = obj.optBoolean("visitorBeacon", true)
            )
        }
        return Result.success(out)
    }

    private fun parseFail(reason: String) = Result.failure<List<BeaconProfile>>(IllegalArgumentException(reason))

    // A present value must have the right type — opt*() silently replaces a
    // wrong-typed value with the default. Returns the bad field name, or null
    private fun wrongTypeField(o: JSONObject): String? =
        listOf("uuid", "label", "type").firstOrNull { o.has(it) && o.opt(it) !is String }
            ?: listOf("addedAt", "rssiOffset", "zoneEnterRssi").firstOrNull { o.has(it) && o.opt(it) !is Number }
            ?: listOf("zoneMute", "visitorBeacon").firstOrNull { o.has(it) && o.opt(it) !is Boolean }

    /** Share merge result (counts added, updated, over the limit) */
    data class MergeResult(val added: Int, val updated: Int, val skipped: Int)

    /**
     * Merge received profiles into the local ones. The same UUID is updated with the received values,
     * new ones are added within the MAX_PROFILES limit, and local-only profiles are kept.
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

    /**
     * Validate a received set — if any entry fails, the whole set is rejected. null if valid, else a message.
     * Ranges match the registration screen's input ranges (detection range offset 0~20, zone entry threshold −100~−30dBm).
     */
    fun validateShared(incoming: List<BeaconProfile>): String? {
        val seen = HashSet<String>()
        for (p in incoming) {
            val name = p.label.ifBlank { p.uuid }
            if (!seen.add(normUuid(p.uuid))) return "같은 비콘이 두 번 들어 있습니다: $name"
            if (p.rssiOffset !in 0..20) return "$name: 감지 범위 보정값 ${p.rssiOffset} (허용 0~20)"
            if (p.zoneEnterRssi !in -100..-30) return "$name: 존 진입 기준 ${p.zoneEnterRssi}dBm (허용 −100~−30)"
        }
        return null
    }

    /** Change counts before receiving */
    data class ChangeSummary(
        val added: Int, val offsetChanged: Int, val zoneOn: Int, val zoneOff: Int,
        val zoneWidened: Int, val visitorChanged: Int
    )

    /** Count what receiving would change — UUID matching is the same as mergeProfiles (local uppercase = received normUuid). */
    fun summarizeChanges(local: List<BeaconProfile>, incoming: List<BeaconProfile>): ChangeSummary {
        val byUuid = local.associateBy { it.uuid.uppercase() }
        var added = 0; var offset = 0; var zoneOn = 0; var zoneOff = 0; var widened = 0; var visitor = 0
        for (p in incoming) {
            val old = byUuid[normUuid(p.uuid)]
            if (old == null) {
                added++
                if (p.zoneMute) zoneOn++
                continue
            }
            if (p.rssiOffset != old.rssiOffset) offset++
            when {
                p.zoneMute && !old.zoneMute -> zoneOn++
                !p.zoneMute && old.zoneMute -> zoneOff++
                p.zoneMute && p.zoneEnterRssi < old.zoneEnterRssi -> widened++   // Zone entry at a weaker signal = wider radius
            }
            if (p.visitorBeacon != old.visitorBeacon) visitor++
        }
        return ChangeSummary(added, offset, zoneOn, zoneOff, widened, visitor)
    }

    /**
     * Whether this fullId is a beacon (contains the BEA_ marker). Used to apply the global beacon reception strength (gain) to beacons only.
     */
    fun isBeaconFullId(fullId: String): Boolean = fullId.contains("BEA_")

    /** Look up rssiOffset from a BleService fullId (e.g. SAFEALERT_WALKER_BEA_AABBCCDDEEFF) */
    fun getRssiOffsetForFullId(fullId: String): Int = findProfileByFullId(fullId)?.rssiOffset ?: 0

    /**
     * Whether the fullId's beacon is a visitor beacon. Unregistered or lookup failure (e.g. right
     * after deletion) → false: treated as equipment and alerts (when in doubt, detect)
     */
    fun isVisitorBeacon(fullId: String): Boolean = findProfileByFullId(fullId)?.visitorBeacon ?: false

    /**
     * Reverse lookup fullId (BEA_ marker) → registered profile. Full key match (MAC=12hex, UUID=32hex). null if not a beacon or unregistered
     */
    fun findProfileByFullId(fullId: String): BeaconProfile? {
        if (!fullId.contains("BEA_")) return null
        val key = fullId.substringAfter("BEA_")
        return getAll().firstOrNull { profile ->
            when (profile.type) {
                "MAC" -> profile.uuid.replace(":", "").equals(key, ignoreCase = true)
                else  -> profile.uuid.replace("-", "").equals(key, ignoreCase = true)
            }
        }
    }

    /**
     * Reverse lookup of a registered beacon label from the short beacon ID in an SOS advertisement.
     * Returned only when exactly one profile matches and its label is not empty (none, no match or duplicates → null).
     */
    fun labelForShortId(sid: Int): String? {
        if (sid == 0) return null
        val hits = getAll().filter { p ->
            val key = if (p.type == "MAC") p.uuid.replace(":", "") else p.uuid.replace("-", "")
            com.wf11.safealert.ble.SosAdvert.beaconShortId(key) == sid
        }
        return hits.singleOrNull()?.label?.takeIf { it.isNotBlank() }
    }

    /** For display/logs — shortens only a 32hex UUID key after BEA_ to its first 8 chars. MAC 12hex and non-beacons stay as is */
    fun shortFullId(fullId: String): String {
        val key = fullId.substringAfter("BEA_", "")
        return if (key.length == 32) fullId.removeSuffix(key) + key.take(8) else fullId
    }

    /** Display label — shared by all three paths (MAC, iBeacon, Service UUID). Unregistered → BEA_ + short key */
    fun labelForFullId(fullId: String): String =
        findProfileByFullId(fullId)?.label ?: ("BEA_" + shortFullId(fullId).substringAfter("BEA_"))

    // Extract the UUID from iBeacon manufacturer data
    // Format: [0x02, 0x15, 16-byte UUID, 2-byte major, 2-byte minor, 1-byte power]
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

    // MAC-based legacy compatibility (to be removed)
    @Deprecated("UUID 방식으로 전환")
    fun contains(mac: String): Boolean = false
    @Deprecated("UUID 방식으로 전환")
    fun getNameByMac(mac: String): String = mac
}
