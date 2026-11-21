package com.yatralens

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.vosk.Model
import org.vosk.Recognizer

@RunWith(AndroidJUnit4::class)
class WakeModelStoreTest {
    @Test fun bundledModelUnpacksAndLoadsWithWakeGrammar() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val path = WakeModelStore.modelPath(context)
        assertTrue(path.resolve("am/final.mdl").isFile)
        Model(path.absolutePath).use { model ->
            Recognizer(model, 16_000f, "[\"hello assistant\",\"hey assistant\",\"[unk]\"]")
                .use { recognizer ->
                    assertEquals("", org.json.JSONObject(recognizer.partialResult).optString("partial"))
                }
        }
    }

    @Test fun syntheticHelloAssistantDecodesOffline() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        Model(WakeModelStore.modelPath(app).absolutePath).use { model ->
            Recognizer(model, 16_000f, "[\"hello assistant\",\"hey assistant\",\"[unk]\"]")
                .use { recognizer ->
                    val heard = mutableListOf<String>()
                    testAssets.open("hello_assistant_synthetic.wav").use { audio ->
                        assertEquals(44, audio.skip(44))
                        val chunk = ByteArray(3200)
                        while (true) {
                            val count = audio.read(chunk)
                            if (count < 0) break
                            if (recognizer.acceptWaveForm(chunk, count))
                                heard.add(org.json.JSONObject(recognizer.result).optString("text"))
                        }
                    }
                    heard.add(org.json.JSONObject(recognizer.finalResult).optString("text"))
                    assertTrue("Decoded: $heard", heard.any { it == "hello assistant" })
                }
        }
    }

}
