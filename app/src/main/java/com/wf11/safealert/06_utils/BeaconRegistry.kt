package com.wf11.safealert.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.wf11.safealert.model.BeaconProfile
import org.json.JSONArray
import org.json.JSONObject

object BeaconRegistry {

    const val MAX_PROFILES = 200  // UUID 프로파일 최대 200개 (각 UUID당 비콘 수 무제한)
    private const val PREF_NAME = "beacon_registry"
    private const val KEY_LIST  = "beacon_profiles"

    private lateinit var appCtx: Context

    // (v1.1.77) 사업장별 등록 정보 분리 — 매 접근마다 현재 사업장 파일을 연다(getSharedPreferences 는
    //   프로세스 내 캐시라 반복 호출이 저렴). getAll() 이 매번 디스크를 파싱하는 구조라 인메모리
    //   잔여분이 없어, 사업장이 바뀌면 별도 리로드 없이 즉시 해당 사업장 목록으로 전환된다.
    private val prefs: SharedPreferences
        get() = appCtx.getSharedPreferences(DevSettings.sitePrefName(PREF_NAME), Context.MODE_PRIVATE)

    fun init(context: Context) {
        appCtx = context.applicationContext
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

    // (quick-260927-bn9 결정 1) 항목 하나 손상으로 전체 목록(존 비콘 포함)이 비던 문제 — 배열 파싱만 실패 시 빈 목록,
    // 항목 단위는 개별 복구. ponytail: 손상 항목이 남아 있으면 호출마다 Log.w 1줄 — 손상 자체가 드물어 수용,
    // 로그가 잦아지면 prefs 재기록으로 정리하는 쪽을 검토한다.
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
                put("visitorBeacon", p.visitorBeacon)
            })
        }
        return arr.toString()
    }

    /**
     * 공유받은 JSON 배열 문자열을 BeaconProfile 목록으로 파싱.
     * (v1.1.97) 읽을 수 없는 항목(객체 아님·UUID 없음·알려진 필드의 타입 오류)이 하나라도 있거나
     * JSON 자체가 깨지면 실패 — 세트 전체를 받지 않는다. 빈 배열은 빈 목록.
     * (v1.1.98) 실패 사유에 몇 번째 항목의 어느 필드인지 담는다(보낸 기기에서 고칠 수 있게).
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

    // 값이 있으면 타입이 맞아야 한다 — opt*() 는 타입이 틀린 값을 조용히 기본값으로 바꾼다. 틀린 필드 이름, 없으면 null
    private fun wrongTypeField(o: JSONObject): String? =
        listOf("uuid", "label", "type").firstOrNull { o.has(it) && o.opt(it) !is String }
            ?: listOf("addedAt", "rssiOffset", "zoneEnterRssi").firstOrNull { o.has(it) && o.opt(it) !is Number }
            ?: listOf("zoneMute", "visitorBeacon").firstOrNull { o.has(it) && o.opt(it) !is Boolean }

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

    /**
     * (v1.1.97) 받은 세트 검증 — 하나라도 어긋나면 세트 전체를 받지 않는다. 통과면 null, 아니면 안내 문구.
     * 범위는 등록 화면의 입력 범위와 같다(감지 범위 보정 0~20, 존 진입 기준 −100~−30dBm).
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

    /** (v1.1.97) 받기 전 변경 내역 건수 */
    data class ChangeSummary(
        val added: Int, val offsetChanged: Int, val zoneOn: Int, val zoneOff: Int,
        val zoneWidened: Int, val visitorChanged: Int
    )

    /** (v1.1.97) 받으면 무엇이 바뀌는지 센다 — UUID 대조는 mergeProfiles 와 같다(로컬 대문자 = 받은 값 normUuid). */
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
                p.zoneMute && p.zoneEnterRssi < old.zoneEnterRssi -> widened++   // 더 약한 신호에서도 존 진입 = 반경 넓어짐
            }
            if (p.visitorBeacon != old.visitorBeacon) visitor++
        }
        return ChangeSummary(added, offset, zoneOn, zoneOff, widened, visitor)
    }

    /** 이 fullId 가 비콘인지(BEA_ 마커 포함). 전역 비콘 수신 강도(게인)를 비콘에만 적용하기 위함. */
    fun isBeaconFullId(fullId: String): Boolean = fullId.contains("BEA_")

    /** BleService의 fullId (예: SAFEALERT_WALKER_BEA_AABBCCDDEEFF)에서 rssiOffset 조회 */
    fun getRssiOffsetForFullId(fullId: String): Int = findProfileByFullId(fullId)?.rssiOffset ?: 0

    /** (v1.1.91) fullId 의 비콘이 방문자용인지. 미등록·조회 실패(삭제 직후 등) 시 false — 장비 취급해 울린다(애매하면 감지) */
    fun isVisitorBeacon(fullId: String): Boolean = findProfileByFullId(fullId)?.visitorBeacon ?: false

    /** (v1.1.91) fullId(BEA_ 마커) → 등록 프로파일 역조회. 키 전체 일치(MAC=12hex, UUID=32hex). 비콘 아님·미등록이면 null */
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
     * (v1.1.99) 구조 요청 광고의 비콘 짧은 ID 로 등록 비콘 라벨 역조회.
     * 정확히 한 프로파일만 일치하고 라벨이 비어 있지 않을 때만 돌려준다(0·미일치·중복은 null).
     */
    fun labelForShortId(sid: Int): String? {
        if (sid == 0) return null
        val hits = getAll().filter { p ->
            val key = if (p.type == "MAC") p.uuid.replace(":", "") else p.uuid.replace("-", "")
            com.wf11.safealert.ble.SosAdvert.beaconShortId(key) == sid
        }
        return hits.singleOrNull()?.label?.takeIf { it.isNotBlank() }
    }

    /** (v1.1.91) 표시·로그용 — BEA_ 뒤 32hex UUID 키만 앞 8자로 줄인다(v1.1.90 표기). MAC 12hex·비콘 아님은 그대로 */
    fun shortFullId(fullId: String): String {
        val key = fullId.substringAfter("BEA_", "")
        return if (key.length == 32) fullId.removeSuffix(key) + key.take(8) else fullId
    }

    /** (v1.1.91) 화면 표시용 라벨 — 세 경로(MAC·iBeacon·Service UUID) 공통. 미등록이면 BEA_+짧은 키 */
    fun labelForFullId(fullId: String): String =
        findProfileByFullId(fullId)?.label ?: ("BEA_" + shortFullId(fullId).substringAfter("BEA_"))

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
