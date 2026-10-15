package com.wf11.safealert.ui

import com.wf11.safealert.firebase.FirebaseManager
import com.wf11.safealert.utils.BeaconRegistry
import com.wf11.safealert.utils.DevSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowAlertDialog

/**
 * Regression test for the defect where a Firebase async callback (set list, set download) arriving at a
 * destroyed/finished BeaconManagerActivity made AlertDialog.show() throw BadTokenException, which also killed
 * BleService in the same process.
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
        controller.setup()

        // positive control on the same activity: while alive, the loaded sets show a dialog
        activity.onBeaconSetsLoaded(listOf(meta))
        val live = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue("살아 있는 액티비티는 대화상자를 띄워야 한다", live != null)

        controller.pause().stop().destroy()
        assertTrue(activity.isDestroyed)
        assertFalse(activity.isFinishing)

        activity.onBeaconSetsLoaded(listOf(meta))

        // the latest dialog is still the one from the live step: the destroyed activity showed no new one
        assertSame(live, ShadowAlertDialog.getLatestAlertDialog())
    }

    @Test
    fun finishedActivity_setDownloaded_showsNoDialog() {
        val activity = Robolectric.buildActivity(BeaconManagerActivity::class.java).get()
        activity.finish()
        assertTrue(activity.isFinishing)

        activity.onBeaconSetDownloaded(meta, validJson)

        assertNull(ShadowAlertDialog.getLatestAlertDialog())
    }
}
