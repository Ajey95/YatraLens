package com.yatralens

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/** System assistant metadata requires a recognizer. Delegates to the phone's on-device engine. */
class YatraRecognitionService : RecognitionService() {
    private var engine: SpeechRecognizer? = null

    override fun onStartListening(intent: Intent, callback: Callback) {
        if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            callback.error(SpeechRecognizer.ERROR_CLIENT)
            return
        }
        engine?.destroy()
        engine = SpeechRecognizer.createOnDeviceSpeechRecognizer(this).also { recognizer ->
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { callback.readyForSpeech(params ?: Bundle()) }
                override fun onBeginningOfSpeech() { callback.beginningOfSpeech() }
                override fun onRmsChanged(rmsdB: Float) { callback.rmsChanged(rmsdB) }
                override fun onBufferReceived(buffer: ByteArray?) { buffer?.let(callback::bufferReceived) }
                override fun onEndOfSpeech() { callback.endOfSpeech() }
                override fun onError(error: Int) { callback.error(error) }
                override fun onResults(results: Bundle?) { callback.results(results ?: Bundle()) }
                override fun onPartialResults(partialResults: Bundle?) {
                    callback.partialResults(partialResults ?: Bundle())
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            recognizer.startListening(intent)
        }
    }

    override fun onStopListening(callback: Callback) { engine?.stopListening() }
    override fun onCancel(callback: Callback) { engine?.cancel() }
    override fun onDestroy() { engine?.destroy(); super.onDestroy() }
}
