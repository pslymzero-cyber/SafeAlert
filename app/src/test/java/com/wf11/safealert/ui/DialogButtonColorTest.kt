package com.wf11.safealert.ui

import android.app.Activity
import android.app.AlertDialog
import android.widget.TextView
import com.wf11.safealert.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Checks that AlertDialog button text in the app theme does not get lost against the dark background.
 * The update, changelog, permission guide and beacon manager dialogs all go through this path (android.app.AlertDialog).
 */
@RunWith(RobolectricTestRunner::class)
class DialogButtonColorTest {

    @Test
    fun alertDialogButtonsUseReadableColors() {
        val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_SafeAlert)
        controller.setup()

        val dialog = AlertDialog.Builder(activity)
            .setTitle("새 버전이 있습니다")
            .setMessage("v1.1.92  →  v1.1.93")
            .setPositiveButton("지금 업데이트", null)
            .setNegativeButton("나중에", null)
            .show()

        val accent = activity.getColor(R.color.sa_accent)
        val secondary = activity.getColor(R.color.sa_text_secondary)
        val surface = activity.getColor(R.color.sa_surface)

        assertEquals(accent, dialog.getButton(AlertDialog.BUTTON_POSITIVE).currentTextColor)
        assertEquals(secondary, dialog.getButton(AlertDialog.BUTTON_NEGATIVE).currentTextColor)
        val message = dialog.findViewById<TextView>(android.R.id.message)
        assertNotEquals(surface, message.currentTextColor)
    }
}
