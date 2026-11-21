package com.yatralens

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MirageNativeTest {
    @Test fun onDeviceModelReturnsInspectableOutputForSyntheticImage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        val result = MirageNative.describe(context, bitmap)
        bitmap.recycle()
        println("MIRAGE_SYNTHETIC_RESULT ${result.toString().take(2400)}")
        assertNotEquals("MODEL_LOAD_FAILED", result.optString("status"))
        assertNotEquals("UNPARSEABLE_MODEL_OUTPUT", result.optString("status"))
        assertTrue(result.has("scene") || result.has("objects"))
    }
}
