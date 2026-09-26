package com.wf11.safealert.ble

import android.content.Context
import com.wf11.safealert.model.BeaconProfile
import com.wf11.safealert.service.BleService
import com.wf11.safealert.support.BleServiceTestHarness as H
import com.wf11.safealert.utils.BeaconRegistry
import com.wf11.safealert.utils.DevSettings
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.util.UUID

/**
 * 2026-09-14 검증 에이전트 3 — 비콘 fullId 전체 키(87b0a87, v1.1.91)와 승급·이탈 일관성 측정.
 * 측정 전용(단언 없음). 같은 파일을 16ee857 기준 트리에서도 돌려 값을 비교한다.
 * 출력: "[S0914-A3] 시나리오 tree=... key=value"
 */
@RunWith(RobolectricTestRunner::class)
class Sim0914BeaconFullIdTest {

    // BeaconFullIdTest 와 같은 조합 — 앞 8자(FDA50693) 공유
    private val equipUuid   = "FDA50693-A4E2-4FB1-AFCF-C6EB07647825"
    private val visitorUuid = "FDA50693-0000-0000-0000-000000000001"
    private val unregUuid   = "FDA50693-FFFF-FFFF-FFFF-FFFFFFFFFFFF"
    private val mac         = "AA:BB:CC:DD:EE:FF"

    private lateinit var service: BleService
    private var clock = 1000L

    /** HEAD(v1.1.91+) 판별 — shortFullId 는 87b0a87 에서 추가됐다 */
    private val isHead by lazy {
        runCatching { BeaconRegistry::class.java.getMethod("shortFullId", String::class.java) }.isSuccess
    }
    private val tree get() = if (isHead) "HEAD" else "16ee857"

    @Before
    fun setUp() {
        service = H.newService()
        val app = RuntimeEnvironment.getApplication()
        BeaconRegistry.init(app)
        BeaconRegistry.onChanged = null
        app.getSharedPreferences(DevSettings.sitePrefName("beacon_registry"), Context.MODE_PRIVATE)
            .edit().clear().commit()
        // 방문자용을 먼저 등록 — 접두사 매칭이면 firstOrNull 이 방문자 프로필을 고른다
        BeaconRegistry.add(BeaconProfile(uuid = visitorUuid, label = "방문자비콘", visitorBeacon = true, rssiOffset = 3))
        BeaconRegistry.add(BeaconProfile(uuid = equipUuid, label = "지게차비콘", visitorBeacon = false, rssiOffset = -7))
        BeaconRegistry.add(BeaconProfile(uuid = mac, label = "MAC비콘", type = "MAC", visitorBeacon = false, rssiOffset = 5))
        H.resetBetweenTests(service)
    }

    // ── BleScanner fullId 생성식 인용 ──
    // HEAD BleScanner.kt:202 / 16ee857 :202 take(8)
    private fun iBeaconHead(u: String) = BleConstants.WALKER_PREFIX + "BEA_" + u.replace("-", "")
    private fun iBeaconOld(u: String)  = BleConstants.WALKER_PREFIX + "BEA_" + u.take(8)
    // HEAD BleScanner.kt:225 / 16ee857 :225 take(8) — parcelUuid.uuid.toString().uppercase()
    private fun serviceHead(u: String) = BleConstants.WALKER_PREFIX + "BEA_" + UUID.fromString(u).toString().uppercase().replace("-", "")
    private fun serviceOld(u: String)  = BleConstants.WALKER_PREFIX + "BEA_" + UUID.fromString(u).toString().uppercase().take(8)
    // BleScanner.kt:241 (두 트리 동일)
    private fun macId(m: String) = BleConstants.WALKER_PREFIX + "BEA_" + m.replace(":", "")
    /** 이 트리의 스캐너가 실제로 만드는 iBeacon fullId */
    private fun scannerId(u: String) = if (isHead) iBeaconHead(u) else iBeaconOld(u)

    // ── 헬퍼 ──
    private fun p(sc: String, kv: String) = println("[S0914-A3] $sc tree=$tree $kv")

    private fun label(id: String): String = runCatching {
        BeaconRegistry::class.java.getMethod("labelForFullId", String::class.java).invoke(BeaconRegistry, id) as String
    }.getOrElse { "N/A" }

    private fun short(id: String): String = runCatching {
        BeaconRegistry::class.java.getMethod("shortFullId", String::class.java).invoke(BeaconRegistry, id) as String
    }.getOrElse { "N/A" }

    /** BleService.kt:808-811 onDeviceDetected 게이트 재현(myMode=WALKER 가정). true = 차단(경보 경로 미진입) */
    private fun walkerGateBlocks(id: String) =
        id.startsWith(BleConstants.WALKER_PREFIX) &&
            !(id.contains("BEA_") && !BeaconRegistry.isVisitorBeacon(id)) &&
            !DevSettings.walkerDetectsWalker

    private fun lv(id: String) = H.alertLevelOf(service, id) ?: 0
    private fun inState(id: String) = H.alertStateOf(service).containsKey(id)

    private fun describe(id: String) =
        "profile=${BeaconRegistry.findProfileByFullId(id)?.label} isVisitor=${BeaconRegistry.isVisitorBeacon(id)} " +
            "offset=${BeaconRegistry.getRssiOffsetForFullId(id)} label=${label(id)} short=${short(id)} gateBlocks=${walkerGateBlocks(id)}"

    /** onDeviceLost(BleService.kt:841) 대리 — asm.registry.purge(id, cold=true) */
    private fun purge(id: String) {
        val asm = ReflectionHelpers.getField<Any>(service, "asm")
        val reg = ReflectionHelpers.getField<Any>(asm, "registry")
        ReflectionHelpers.callInstanceMethod<Any?>(
            reg, "purge",
            ClassParameter.from(String::class.java, id),
            ClassParameter.from(Boolean::class.javaPrimitiveType!!, true)
        )
    }

    /** 틱마다 frames 를 같은 시각으로 넣고 120ms 전진. 반환: frame 순서대로 (첫 WARNING, 첫 DANGER) 상대 ms */
    private fun promote(frames: List<Pair<String, Int>>, ticks: Int): List<Pair<Long?, Long?>> {
        val t0 = clock
        val warn = arrayOfNulls<Long>(frames.size)
        val danger = arrayOfNulls<Long>(frames.size)
        repeat(ticks) {
            frames.forEachIndexed { i, (id, rssi) ->
                H.callProcessAlert(service, id, rssi, nowMs = clock)
                val l = lv(id)
                if (l >= 1 && warn[i] == null) warn[i] = clock - t0
                if (l >= 2 && danger[i] == null) danger[i] = clock - t0
            }
            clock += 120L
        }
        return frames.indices.map { warn[it] to danger[it] }
    }

    /** frames 를 계속 넣으며 watch 의 레벨이 SAFE(0/미존재)가 되는 상대 ms. maxTicks 안에 안 되면 null */
    private fun release(watch: String, frames: List<Pair<String, Int>>, maxTicks: Int): Long? {
        val t0 = clock
        repeat(maxTicks) {
            frames.forEach { (id, rssi) -> H.callProcessAlert(service, id, rssi, nowMs = clock) }
            if (lv(watch) == 0) return clock - t0
            clock += 120L
        }
        return null
    }

    // ── a. 경로별 fullId 일치 + 승급·정리 ──
    @Test
    fun a_fullIdConsistentAcrossPaths() {
        val sc = "a"
        p(sc, "iBeaconHead=${iBeaconHead(equipUuid)} serviceHead=${serviceHead(equipUuid)} sameHead=${iBeaconHead(equipUuid) == serviceHead(equipUuid)}")
        p(sc, "iBeaconOld=${iBeaconOld(equipUuid)} serviceOld=${serviceOld(equipUuid)} sameOld=${iBeaconOld(equipUuid) == serviceOld(equipUuid)}")
        p(sc, "lookup.head32 ${describe(iBeaconHead(equipUuid))}")
        p(sc, "lookup.old8 ${describe(iBeaconOld(equipUuid))}")
        p(sc, "lookup.mac ${describe(macId(mac))}")
        p(sc, "lookup.macLower ${describe(macId(mac.lowercase()))}")

        val id = scannerId(equipUuid)
        val r = promote(listOf(id to -45), 40)
        p(sc, "promote id=$id keys=${H.alertStateOf(service).keys} level=${lv(id)} warnMs=${r[0].first} dangerMs=${r[0].second}")
        purge(id)
        p(sc, "purge inState=${inState(id)} keys=${H.alertStateOf(service).size}")

        val m = macId(mac)
        val rm = promote(listOf(m to -45), 40)
        p(sc, "promoteMac id=$m level=${lv(m)} warnMs=${rm[0].first} dangerMs=${rm[0].second}")
        purge(m)
        p(sc, "purgeMac inState=${inState(m)} keys=${H.alertStateOf(service).size}")
    }

    // ── b. 앞 8자 같은 비콘 2개의 독립 승급·해제 ──
    @Test
    fun b_samePrefixBeaconsIndependent() {
        val sc = "b"
        val eq = scannerId(equipUuid)
        val vi = scannerId(visitorUuid)
        p(sc, "equipId=$eq visitorId=$vi distinct=${eq != vi}")
        p(sc, "equip ${describe(eq)}")
        p(sc, "visitor ${describe(vi)}")

        val r = promote(listOf(eq to -45, vi to -45), 40)
        p(sc, "promote keys=${H.alertStateOf(service).size} eqLevel=${lv(eq)} viLevel=${lv(vi)} " +
            "eqWarnMs=${r[0].first} eqDangerMs=${r[0].second} viWarnMs=${r[1].first} viDangerMs=${r[1].second}")

        // 장비 비콘만 멀어짐(-95), 방문자 비콘은 근접 유지(-45)
        val rel = release(eq, listOf(eq to -95, vi to -45), 250)
        p(sc, "equipLeaves eqReleaseMs=$rel eqLevel=${lv(eq)} viLevel=${lv(vi)}")

        purge(eq)
        p(sc, "purgeEquip eqInState=${inState(eq)} viInState=${inState(vi)} viLevel=${lv(vi)} keys=${H.alertStateOf(service).size}")
    }

    // ── c. 방문자용·장비용·미등록 발령/해제 ──
    @Test
    fun c_visitorEquipUnregistered() {
        val sc = "c"
        p(sc, "walkerDetectsWalker=${DevSettings.walkerDetectsWalker}")
        listOf("visitor" to visitorUuid, "equip" to equipUuid, "unreg" to unregUuid).forEach { (k, u) ->
            val id = scannerId(u)
            p(sc, "$k id=$id ${describe(id)}")
            val r = promote(listOf(id to -45), 40)
            p(sc, "$k promote level=${lv(id)} warnMs=${r[0].first} dangerMs=${r[0].second}")
            val rel = release(id, listOf(id to -95), 250)
            p(sc, "$k leave releaseMs=$rel level=${lv(id)} inState=${inState(id)}")
            purge(id)
            p(sc, "$k purge inState=${inState(id)}")
        }
    }
}
