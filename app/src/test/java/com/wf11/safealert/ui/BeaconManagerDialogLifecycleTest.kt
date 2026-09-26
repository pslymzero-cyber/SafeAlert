package com.wf11.safealert.ui

import com.wf11.safealert.firebase.FirebaseManager
import com.wf11.safealert.utils.BeaconRegistry
import com.wf11.safealert.utils.DevSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowAlertDialog

/**
 * Firebase 비동기 콜백(세트 목록·세트 다운로드)이 destroy/finish 된 BeaconManagerActivity 에
 * 도착하면 AlertDialog.show() 가 BadTokenException 을 던져 같은 프로세스의 BleService 까지
 * 죽는 결함의 회귀 테스트.
 */
@RunWith(RobolectricTestRunner::class)
class BeaconManagerDialogLifecycleTest {

    private val meta = FirebaseManager.BeaconSetMeta(
        key = "k1", name = "세트", count = 1, sender = "dev", timestamp = 0L
    )
    private val validJson = """[{"uuid":"E2C56DB5-DFFB-48D2-B060-D0F5A71096E0","label":"t"}]"""

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        DevSettings.init(app)
        BeaconRegistry.init(app)
    }

    @Test
    fun destroyedActivity_setsLoaded_showsNoDialog() {
        val controller = Robolectric.buildActivity(BeaconManagerActivity::class.java)
        val activity = controller.get()
        controller.setup().pause().stop().destroy()
        assertTrue(activity.isDestroyed)
        assertFalse(activity.isFinishing)

        activity.onBeaconSetsLoaded(listOf(meta))

        assertNull(ShadowAlertDialog.getLatestAlertDialog())
    }

    @Test
    fun finishedActivity_setDownloaded_showsNoDialog() {
        val activity = Robolectric.buildActivity(BeaconManagerActivity::class.java).get()
        activity.finish()
        assertTrue(activity.isFinishing)

        activity.onBeaconSetDownloaded(meta, validJson)

        assertNull(ShadowAlertDialog.getLatestAlertDialog())
    }

    @Test
    fun liveActivity_setsLoaded_showsDialog() {
        val controller = Robolectric.buildActivity(BeaconManagerActivity::class.java)
        val activity = controller.get()
        controller.setup()

        activity.onBeaconSetsLoaded(listOf(meta))

        assertTrue(ShadowAlertDialog.getLatestAlertDialog() != null)
    }
}
