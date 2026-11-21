package com.yatralens

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureLightPolicyTest {
    @Test fun measuredDimSceneRequestsLightOnlyWithFreshLuminance() {
        assertTrue(CaptureLightPolicy.isDim(89, true))
        assertFalse(CaptureLightPolicy.isDim(100, true))
        assertFalse(CaptureLightPolicy.isDim(20, false))
        assertFalse(CaptureLightPolicy.isDim(null, true))
    }
}
