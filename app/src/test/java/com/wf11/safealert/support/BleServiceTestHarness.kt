package com.wf11.safealert.support

import com.wf11.safealert.ble.BleConstants
import com.wf11.safealert.service.BleService
import com.wf11.safealert.utils.DevSettings
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * 02-01 골든 캐스케이드 하네스 — Robolectric 위에서 BleService.processAlert() 를 리플렉션으로
 * 구동하기 위한 최소 배선(D-2B/D-2E). processAlert 가 private 이므로 여기서만 리플렉션을 쓰고
 * app/src/main 에는 테스트 전용 코드를 단 1줄도 두지 않는다(backstop truth #6).
 *
 * 시나리오 반복(다기기·다프레임 루프)은 여기 두지 않는다 — 02-02 의 몫. 이 파일은 "한 번의
 * processAlert 호출을 어떻게 구동/관측하는가"만 책임진다.
 */
object BleServiceTestHarness {

    /**
     * onCreate() 를 실행하지 않는 서비스 인스턴스 생성(Assumption A1, get() 만 호출).
     * DevSettings.prefs 는 lateinit 이라 init() 없이 아무 property 나 건드리면 즉시 예외 —
     * processAlert 가 DevSettings 를 참조하므로 여기서 반드시 먼저 초기화한다.
     */
    fun newService(): BleService {
        DevSettings.init(RuntimeEnvironment.getApplication())
        return Robolectric.buildService(BleService::class.java).get()
    }

    /**
     * private fun processAlert(...) 리플렉션 호출. nowMs 는 (02-01 D-2C) seam — 골든 테스트는
     * 프레임 간격을 고정값으로 주입해 System.currentTimeMillis() 의존을 제거한다.
     */
    fun callProcessAlert(
        service: BleService,
        deviceId: String,
        rssi: Int,
        nowMs: Long,
        remoteState: Int = 0x00,
        remoteTurn: Int = BleConstants.TURN_STRAIGHT,
        payloadPresent: Boolean = false,
        peerEchoRssi: Int = BleConstants.NO_ECHO_RSSI
    ) {
        ReflectionHelpers.callInstanceMethod<Any?>(
            service,
            "processAlert",
            ClassParameter.from(String::class.java, deviceId),
            ClassParameter.from(Int::class.javaPrimitiveType, rssi),
            ClassParameter.from(Int::class.javaPrimitiveType, remoteState),
            ClassParameter.from(Int::class.javaPrimitiveType, remoteTurn),
            ClassParameter.from(Boolean::class.javaPrimitiveType, payloadPresent),
            ClassParameter.from(Int::class.javaPrimitiveType, peerEchoRssi),
            ClassParameter.from(kotlin.jvm.functions.Function0::class.java) { nowMs }
        )
    }

    /** private val alertState = mutableMapOf<String, Pair<Int, Long>>() (BleService.kt:384) 판독. */
    @Suppress("UNCHECKED_CAST")
    fun alertStateOf(service: BleService): Map<String, Pair<Int, Long>> =
        ReflectionHelpers.getField(service, "alertState") as Map<String, Pair<Int, Long>>

    fun alertLevelOf(service: BleService, deviceId: String): Int? =
        alertStateOf(service)[deviceId]?.first

    /** shadowOf(Application).broadcastIntents 중 BROADCAST_ALERT 만 필터링(발령 여부 관측). */
    fun alertBroadcastCount(deviceId: String): Int =
        shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents.count {
            it.action == BleService.BROADCAST_ALERT && it.getStringExtra(BleService.EXTRA_ID) == deviceId
        }
}
