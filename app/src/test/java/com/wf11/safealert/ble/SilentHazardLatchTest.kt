package com.wf11.safealert.ble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import android.os.SystemClock
import com.wf11.safealert.service.AlertStateMachine
import com.wf11.safealert.service.BleService
import com.wf11.safealert.support.BleServiceTestHarness
import com.wf11.safealert.utils.DevSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.time.Duration

/**
 * State that outlives its moment and keeps a nearby hazard silent: a mute that hides the collision pre-alert or outlives its
 * alert, an acknowledged device holding the sound, dwell time counted while nothing could be heard, a departure state that
 * never ends, and a departure velocity that outlives its 30 s.
 * Each test drives the real BleService decision path through BleServiceTestHarness.
 */
@RunWith(RobolectricTestRunner::class)
class SilentHazardLatchTest {

    private fun frame(s: BleService, f: Int, rssi: Int) =
        BleServiceTestHarness.callProcessAlert(s, ID, rssi, nowMs = T0 + f * DT)

    private fun level(s: BleService) = BleServiceTestHarness.alertLevelOf(s, ID)
    private fun soundLevel(s: BleService) = ReflectionHelpers.getField<Int>(s, "activeSoundLevel")
    private fun asmOf(s: BleService) = ReflectionHelpers.getField<AlertStateMachine>(s, "asm")
    private fun idle(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

    // The collision pre-alert (TTC) must break through a driver's 30 s acknowledge on that device: the acknowledge is for a
    //   hazard already seen, not for one now rushing in.
    @Test
    fun ttcPreAlertBreaksThroughAcknowledgeMute() {
        val s = BleServiceTestHarness.newService()
        var f = 0
        var rssi = -90
        while (level(s) == null && f < 100) { frame(s, f++, rssi); rssi += 1 }
        assertEquals(BleConstants.LEVEL_WARNING, level(s))
        ReflectionHelpers.callInstanceMethod<Unit>(s, "muteDevice", ClassParameter.from(String::class.java, ID))

        while (f < 140 && level(s) != BleConstants.LEVEL_DANGER) { rssi = minOf(rssi + 2, -45); frame(s, f++, rssi) }

        assertFalse("충돌 예측 경보가 기기별 묵음을 풀어야 한다", asmOf(s).mutedDevices.containsKey(ID))
        assertEquals(BleConstants.LEVEL_DANGER, level(s))
        assertEquals("묵음 중에도 위험 사이렌이 울려야 한다", BleConstants.LEVEL_DANGER, soundLevel(s))
    }

    // A temporary mute (volume key, notification tap) means "I've seen what is sounding": it quiets the devices alerting
    //   when it starts (a short dropout of one does not end it), a device that comes in during it alerts at once, and a
    //   quieted device still near sounds again when the mute ends (its silenced time does not count toward the 5 s dwell
    //   auto-mute).
    @Test
    fun temporaryMuteQuietsOnlyTheDevicesAlertingWhenItStarts() {
        val s = BleServiceTestHarness.newService()
        var f = 0
        repeat(10) { frame(s, f++, -55) }
        assertEquals(BleConstants.LEVEL_DANGER, level(s))
        muteTemporarily(s)

        val newcomerHeard = (0 until 20).any {
            frame(s, f, -55)
            BleServiceTestHarness.callProcessAlert(s, OTHER, -74, nowMs = T0 + f++ * DT)
            soundLevel(s) == BleConstants.LEVEL_WARNING
        }
        assertTrue("일시 묵음 중 새로 들어온 기기는 바로 경보", newcomerHeard)
        assertTrue("묵음 때 울리던 기기는 조용", asmOf(s).mutedDevices.containsKey(ID))
        BleServiceTestHarness.deviceLost(s, ID)   // drops out behind a rack for a moment
        repeat(5) { frame(s, f++, -55) }
        assertTrue("잠깐 끊겼다 다시 잡혀도 본 장비는 조용", soundLevel(s) < BleConstants.LEVEL_DANGER)

        idle(11)   // the 10 s mute runs out
        val sounded = (0 until 10).any { frame(s, f++, -55); soundLevel(s) == BleConstants.LEVEL_DANGER }
        assertTrue("묵음이 풀리면 다시 울려야 한다", sounded)
    }

    // '즉시 재개' lifts the temporary mute it started, not a longer acknowledge the user gave a device.
    @Test
    fun immediateResumeLiftsOnlyTheTemporaryMute() {
        val s = BleServiceTestHarness.newService()
        var f = 0
        repeat(10) {
            frame(s, f, -55)
            BleServiceTestHarness.callProcessAlert(s, OTHER, -55, nowMs = T0 + f++ * DT)
        }
        ReflectionHelpers.callInstanceMethod<Unit>(s, "muteDevice", ClassParameter.from(String::class.java, OTHER))
        muteTemporarily(s)
        ReflectionHelpers.callInstanceMethod<Unit>(s, "unmuteImmediately")

        assertFalse("일시 묵음은 풀린다", asmOf(s).mutedDevices.containsKey(ID))
        assertTrue("30초 확인은 그대로", asmOf(s).mutedDevices.containsKey(OTHER))
    }

    // The test alert puts nothing in alertState, yet a volume key or notification tap still pauses it.
    @Test
    fun temporaryMutePausesTheTestAlert() {
        val s = BleServiceTestHarness.newService()
        ReflectionHelpers.callInstanceMethod<Unit>(s, "startTestAlert")
        muteTemporarily(s)
        assertTrue("테스트 경보도 볼륨 버튼으로 멈춘다", BleService.isMutedPublic)
        ReflectionHelpers.callInstanceMethod<Unit>(s, "unmuteImmediately")
        ReflectionHelpers.callInstanceMethod<Unit>(s, "stopTestAlert")
    }

    // The app's own siren-volume change reported after its 300 ms guard is not a volume press: it reports the alarm stream at
    //   the value the app set (taken as a press it would mute whatever is alerting, a newcomer during a mute included),
    //   while a press moves the volume off it.
    @Test
    fun lateEchoOfTheAppsOwnVolumeChangeIsNotAPress() {
        val s = BleServiceTestHarness.newService()
        var f = 0
        repeat(10) { frame(s, f++, -55) }
        idle(1)   // the 300 ms guard is over
        val applied = (s.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getStreamVolume(AudioManager.STREAM_ALARM)
        val receiver = ReflectionHelpers.getField<BroadcastReceiver>(s, "volumeReceiver")
        fun volume(v: Int) = receiver.onReceive(s, Intent("android.media.VOLUME_CHANGED_ACTION")
            .putExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", AudioManager.STREAM_ALARM)
            .putExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", v))
        volume(applied)
        assertFalse("앱이 맞춘 볼륨이 늦게 알려져도 묵음이 아니다", asmOf(s).mutedDevices.containsKey(ID))
        volume(applied - 1)
        assertTrue("사용자가 볼륨을 움직이면 묵음", asmOf(s).mutedDevices.containsKey(ID))
        ReflectionHelpers.callInstanceMethod<Unit>(s, "unmuteImmediately")
    }

    private fun muteTemporarily(s: BleService) =
        ReflectionHelpers.callInstanceMethod<Unit>(s, "muteTemporarily", ClassParameter.from(String::class.java, "볼륨 버튼"))

    // A departure state left behind after a pass ends once its cooldown is over: a hazard that comes back and stops close by
    //   is judged as a fresh contact (alerted and audible), not as one still moving away.
    @Test
    fun staleDepartureStateDoesNotHoldAReturningHazardSilent() {
        val s = BleServiceTestHarness.newService()
        val asm = asmOf(s)
        asm.trackingStateMap[ID] = AlertStateMachine.TrackingState.DEPARTING
        asm.departingStartMap[ID] = T0 - 60_000

        var f = 0
        val alerted = (0 until 10).any { frame(s, f++, -60); level(s) != null }

        assertTrue("오래전 출발 상태가 돌아온 위험을 막으면 안 된다", alerted)
        assertTrue("경보가 소리로도 나야 한다", soundLevel(s) > BleConstants.LEVEL_SAFE)
    }

    // A departure velocity kept for a quick re-registration expires 30 s after it was captured, however it is passed on:
    //   frames out of range and a flicker across the band edge hand it on with its original capture time, so a much later
    //   sudden contact is not seeded with it and is alerted promptly.
    @Test
    fun departureVelocityExpires30sAfterItsCaptureHoweverItIsPassedOn() {
        val s = BleServiceTestHarness.newService()
        val asm = asmOf(s)
        val capturedAt = SystemClock.elapsedRealtime()
        asm.lastKfVelMap[ID] = AlertStateMachine.LastKfVelState(-1.2, capturedAt)

        var f = 0
        idle(5)
        repeat(5) { frame(s, f++, -100) }   // out of range
        idle(5)
        repeat(3) {                         // flicker: in the band (the filter is kept), then out again
            frame(s, f++, -85)
            assertTrue(asm.kalmanFilters.containsKey(ID))
            frame(s, f++, -100)
        }
        assertEquals("넘겨받은 출발 속도는 처음 찍힌 시각을 유지", capturedAt, asm.lastKfVelMap[ID]?.timestamp)

        idle(21)
        repeat(3) { frame(s, f++, -100) }
        assertNull("30초가 지나면 출발 속도는 사라진다", asm.lastKfVelMap[ID])

        val alerted = (0 until 10).any { frame(s, f++, -60); level(s) != null }
        assertTrue("갑자기 가까이 나타난 위험은 곧바로 경보", alerted)
    }

    // The seed's capture time lives as long as the filter it seeded: a filter kept warm across a short loss still hands the
    //   seed on with its original time, so a loss does not restart the 30 s.
    @Test
    fun seedTimeSurvivesAWarmRestoreAfterALoss() {
        val s = BleServiceTestHarness.newService()
        val asm = asmOf(s)
        val capturedAt = SystemClock.elapsedRealtime()
        asm.lastKfVelMap[ID] = AlertStateMachine.LastKfVelState(-1.2, capturedAt)

        var f = 0
        idle(2)
        frame(s, f++, -85)                        // back in the band: a filter built from the seed
        BleServiceTestHarness.deviceLost(s, ID)   // a short loss keeps the filters warm
        idle(2)
        frame(s, f++, -85)                        // found again close by: the warm filter is reused
        repeat(3) { frame(s, f++, -100) }         // out of range (the warm median needs a few): the seed is handed on

        assertEquals("소실을 거쳐도 처음 찍힌 시각을 유지", capturedAt, asm.lastKfVelMap[ID]?.timestamp)
    }

    // An acknowledge covers one alert: when the device is released as departing (trend or receding release) it ends with it,
    //   so the hazard coming back within the 30 s is not registered silently.
    @Test
    fun acknowledgeEndsWhenTheDeviceIsReleasedAsDeparting() {
        val trend = BleServiceTestHarness.newService()   // rose well above where it entered, then turns down
        var f = 0
        for (rssi in -85..-55) frame(trend, f++, rssi)
        repeat(10) { frame(trend, f++, -55) }
        f = acknowledge(trend, f)
        while (level(trend) != null && f < 200) frame(trend, f++, -65)
        assertFalse("추세 해제와 함께 확인 묵음도 끝나야 한다", asmOf(trend).mutedDevices.containsKey(ID))

        val receding = BleServiceTestHarness.newService()   // sat at the danger range, then pulls back
        DevSettings.recedingClearMs = 200L   // released before the departure state could end it as SAFE
        f = 0
        repeat(10) { frame(receding, f++, -55) }
        f = acknowledge(receding, f)
        while (level(receding) != null && f < 200) frame(receding, f++, -70)
        assertFalse("이탈 해제와 함께 확인 묵음도 끝나야 한다", asmOf(receding).mutedDevices.containsKey(ID))
    }

    // An acknowledged hazard makes no sound, so it must not own the sound either: another device's warning is heard while
    //   the acknowledged one sits at DANGER.
    @Test
    fun acknowledgedDangerDoesNotSilenceAnotherDevicesWarning() {
        val s = BleServiceTestHarness.newService()
        var f = acknowledgedAtDanger(s)

        val heard = (0 until 20).any {
            frame(s, f, -55)
            BleServiceTestHarness.callProcessAlert(s, OTHER, -74, nowMs = T0 + f++ * DT)
            soundLevel(s) == BleConstants.LEVEL_WARNING
        }

        assertTrue("확인한 위험 기기가 다른 기기의 경고를 막으면 안 된다", heard)
    }

    // A muted device is silent, so it keeps nothing sounding either: when the device that is heard starts to pull away its
    //   siren stops at once, though the acknowledged one still sits at DANGER.
    @Test
    fun mutedDangerDoesNotKeepADepartingDevicesSirenOn() {
        val s = BleServiceTestHarness.newService()
        var f = acknowledgedAtDanger(s)
        val heard = (0 until 20).any {
            frame(s, f, -55)
            BleServiceTestHarness.callProcessAlert(s, OTHER, -55, nowMs = T0 + f++ * DT)
            soundLevel(s) == BleConstants.LEVEL_DANGER
        }
        assertTrue(heard)
        val asm = asmOf(s)
        while (!asm.recedingStartMap.containsKey(OTHER) && f < 100) {
            frame(s, f, -55)
            BleServiceTestHarness.callProcessAlert(s, OTHER, -70, nowMs = T0 + f++ * DT)
        }
        assertTrue(asm.recedingStartMap.containsKey(OTHER))
        assertEquals("조용히 둔 기기가 떠나는 기기의 사이렌을 붙잡으면 안 된다", BleConstants.LEVEL_SAFE, soundLevel(s))
    }

    // Dwell counts only time a device is actually heard: a warning under a higher device's sound, one whose sound was
    //   stopped, or one its own judgement keeps quiet is never dwell-muted for that time; heard time still is.
    @Test
    fun dwellCountsOnlyTimeTheDeviceIsHeard() {
        val s = BleServiceTestHarness.newService()
        val asm = asmOf(s)
        asm.alertState[ID] = Pair(BleConstants.LEVEL_DANGER, T0)
        asm.alertState[OTHER] = Pair(BleConstants.LEVEL_WARNING, T0)
        var t = T0
        ReflectionHelpers.setField(s, "activeSoundLevel", BleConstants.LEVEL_DANGER)
        repeat(60) { dwell(s, t, quiet = false); t += DT }   // 7.2 s under ID's danger siren
        assertFalse("더 높은 기기 소리에 묻힌 시간은 세지 않는다", dwellMuted(s))

        asm.alertState.remove(ID)
        ReflectionHelpers.setField(s, "activeSoundLevel", BleConstants.LEVEL_SAFE)
        repeat(60) { dwell(s, t, quiet = false); t += DT }
        assertFalse("소리가 멈춘 동안은 세지 않는다", dwellMuted(s))

        ReflectionHelpers.setField(s, "activeSoundLevel", BleConstants.LEVEL_WARNING)
        repeat(60) { dwell(s, t, quiet = true); t += DT }
        assertFalse("스스로 조용히 둔 시간은 세지 않는다", dwellMuted(s))

        repeat(60) { dwell(s, t, quiet = false); t += DT }
        assertTrue("실제로 들린 5초는 센다", dwellMuted(s))
    }

    private fun dwell(s: BleService, now: Long, quiet: Boolean) =
        ReflectionHelpers.callInstanceMethod<Unit>(s, "updateDwellMute", ClassParameter.from(String::class.java, OTHER),
            ClassParameter.from(Int::class.javaPrimitiveType, BleConstants.LEVEL_WARNING),
            ClassParameter.from(Long::class.javaPrimitiveType, now), ClassParameter.from(Boolean::class.javaPrimitiveType, quiet))

    private fun dwellMuted(s: BleService) =
        ReflectionHelpers.callInstanceMethod<Boolean>(s, "isDwellMuted", ClassParameter.from(String::class.java, OTHER),
            ClassParameter.from(Int::class.javaPrimitiveType, BleConstants.LEVEL_WARNING))

    /** A device sitting at the danger range (not rushing in, so no collision pre-alert clears it), then acknowledged. */
    private fun acknowledgedAtDanger(s: BleService): Int {
        var f = 0
        repeat(10) { frame(s, f++, -55) }
        return acknowledge(s, f)
    }

    private fun acknowledge(s: BleService, frameIdx: Int): Int {
        assertEquals(BleConstants.LEVEL_DANGER, level(s))
        ReflectionHelpers.callInstanceMethod<Unit>(s, "muteDevice", ClassParameter.from(String::class.java, ID))
        frame(s, frameIdx, -55)
        assertTrue(asmOf(s).mutedDevices.containsKey(ID))
        return frameIdx + 1
    }

    // The departure state ends once its cooldown is over and the device no longer moves away, also while it is still
    //   registered (a peer's risk or a UWB promotion can keep it there): kept DEPARTING it would stay silent.
    @Test
    fun departureStateEndsOnceTheDeviceStopsMovingAway() {
        val s = BleServiceTestHarness.newService()
        val asm = asmOf(s)
        asm.alertState[ID] = Pair(BleConstants.LEVEL_WARNING, T0)
        asm.trackingStateMap[ID] = AlertStateMachine.TrackingState.DEPARTING
        asm.departingStartMap[ID] = T0
        val afterCooldown = T0 + asm.DEPARTING_REENTRY_COOLDOWN_MS

        updateTrackingState(asm, kfVel = -1.0, now = afterCooldown)
        assertEquals("아직 멀어지는 중이면 유지", AlertStateMachine.TrackingState.DEPARTING, asm.trackingStateMap[ID])
        updateTrackingState(asm, kfVel = 0.0, now = afterCooldown)
        assertEquals("멈추면 새 접촉으로 판단", AlertStateMachine.TrackingState.APPROACHING, asm.trackingStateMap[ID])
    }

    private fun updateTrackingState(asm: AlertStateMachine, kfVel: Double, now: Long) =
        ReflectionHelpers.callInstanceMethod<Unit>(asm, "updateTrackingState",
            ClassParameter.from(String::class.java, ID), ClassParameter.from(Double::class.javaPrimitiveType, kfVel),
            ClassParameter.from(Long::class.javaPrimitiveType, now))

    private companion object {
        const val T0 = 3_000_000L
        const val DT = 120L
        const val ID = BleConstants.DEVICE_PREFIX + "LATCH01"
        const val OTHER = BleConstants.DEVICE_PREFIX + "LATCH02"
    }
}
