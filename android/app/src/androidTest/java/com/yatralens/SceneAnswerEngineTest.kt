package com.yatralens

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class SceneAnswerEngineTest {
    @Test fun objectsDoNotVerifyBoardingPoint() {
        val now = Instant.parse("2026-11-21T12:00:00Z")
        val scene = JSONObject("""{"objects":[{"name":"ticket window","score":0.8}]}""")
        val answer = SceneAnswerEngine.answer(scene, now, "Where do I board?", now)
        assertEquals("UNKNOWN", answer.status)
        assertTrue(answer.text.contains("does not verify"))
        assertTrue(answer.text.contains("Ask staff"))
    }

    @Test fun recentlyCapturedObjectsCanBeDescribedTentatively() {
        val now = Instant.parse("2026-11-21T12:00:00Z")
        val scene = JSONObject("""{"objects":[{"name":"boat","score":0.36}]}""")
        val answer = SceneAnswerEngine.answer(scene, now, "What can you see?", now)
        assertEquals("TENTATIVE_DESCRIPTION", answer.status)
        assertTrue(answer.text.contains("may see boat"))
        assertEquals("STALE_OR_MISSING", SceneAnswerEngine.answer(scene, now.minusSeconds(121),
            "What can you see?", now).status)
    }
}
