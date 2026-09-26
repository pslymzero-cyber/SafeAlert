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
 * (v1.1.93) 앱 테마의 AlertDialog 버튼 글자가 어두운 바탕에 묻히지 않는지 검증.
 * 업데이트 창·변경 사항·권한 안내·비콘 관리 창이 모두 이 경로(android.app.AlertDialog)를 쓴다.
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
