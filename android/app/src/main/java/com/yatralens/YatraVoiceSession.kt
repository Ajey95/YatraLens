package com.yatralens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.Executors

/** System-hosted assistant surface; invoked while the main app is closed. */
class YatraVoiceSession(private val host: android.content.Context) : VoiceInteractionSession(host) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private lateinit var prompt: TextView
    private lateinit var request: EditText
    private lateinit var answer: EditText
    private lateinit var action: Button
    private lateinit var cloud: CheckBox
    private lateinit var language: Spinner
    private var planAction = ""
    private var destination = ""
    private val codes = listOf("te-IN", "ml-IN", "en-IN", "hi-IN")

    override fun onCreateContentView(): View {
        val card = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(20))
            background = GradientDrawable().apply {
                setColor(host.getColor(R.color.yl_panel))
                cornerRadius = dp(26).toFloat()
                setStroke(dp(2), host.getColor(R.color.yl_sage))
            }
        }
        val root = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            setPadding(dp(12), dp(12), dp(12), dp(24))
            addView(card)
        }
        val mark = TextView(host).apply {
            text = "●  YatraLens"
            textSize = 22f
            setTextColor(host.getColor(R.color.yl_ivory))
        }
        card.addView(mark)
        mark.alpha = 0f
        mark.scaleX = 0.75f
        mark.scaleY = 0.75f
        mark.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(300).start()
        prompt = TextView(host).apply {
            text = "What do you need help with?"
            textSize = 19f
            setTextColor(host.getColor(R.color.yl_sand))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        card.addView(prompt)
        language = Spinner(host).also {
            it.adapter = object : ArrayAdapter<String>(host, android.R.layout.simple_spinner_item,
                listOf("Telugu", "Malayalam", "English", "Hindi")) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    super.getView(position, convertView, parent).also { view ->
                        (view as? TextView)?.setTextColor(host.getColor(R.color.yl_ivory))
                    }
            }
            it.setSelection(2)
            card.addView(it)
        }
        request = EditText(host).apply {
            hint = "Your request"
            setTextColor(host.getColor(R.color.yl_ivory))
            setHintTextColor(host.getColor(R.color.yl_mist))
            minLines = 2
        }
        card.addView(request)
        answer = EditText(host).apply {
            hint = "Your answer"
            setTextColor(host.getColor(R.color.yl_ivory))
            setHintTextColor(host.getColor(R.color.yl_mist))
            visibility = View.GONE
        }
        card.addView(answer)
        button(card, "Speak one request") { listen() }
        cloud = CheckBox(host).apply {
            text = "Send this selected request to Sarvam"
            setTextColor(host.getColor(R.color.yl_sand))
        }
        card.addView(cloud)
        button(card, "Understand request") { plan() }
        action = button(card, "Continue with this task") { continueTask() }
        action.visibility = View.GONE
        button(card, "Close") { finish() }
        root.requestFocus()
        return root
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        window.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        prompt.text = "What do you need help with?"
        tts = TextToSpeech(host) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.ENGLISH
                tts?.speak("What do you need help with?", TextToSpeech.QUEUE_FLUSH, null, "assistant-welcome")
            }
        }
    }

    private fun listen() {
        if (host.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            prompt.text = "Open YatraLens once to grant microphone permission."
            return
        }
        if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(host)) {
            prompt.text = "On-device recognition is unavailable here. Type the request."
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(host).also { speech ->
            speech.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { prompt.text = "Listening…" }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { prompt.text = "Understanding your words…" }
                override fun onError(error: Int) { prompt.text = "Could not hear a clear request. Try again or type it." }
                override fun onResults(results: Bundle?) {
                    val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    if (answer.visibility == View.VISIBLE) answer.setText(heard) else request.setText(heard)
                    prompt.text = "Review the words, then choose Understand request."
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            speech.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, codes[language.selectedItemPosition])
            })
        }
    }

    private fun plan() {
        val spoken = request.text.toString().trim()
        if (spoken.isBlank()) { prompt.text = "Say or type a request first."; return }
        if (!cloud.isChecked) { prompt.text = "Approve sending this selected request to Sarvam."; return }
        prompt.text = "Working out the next step…"
        action.visibility = View.GONE
        val selectedLanguage = codes[language.selectedItemPosition]
        val followUp = answer.text.toString().trim().ifBlank { null }
        worker.execute {
            try {
                val result = CloudGateway.planAssistant(spoken, followUp, selectedLanguage)
                main.post {
                    if (result.optString("status") == "NEEDS_FOLLOW_UP") {
                        val question = result.optString("follow_up_question")
                        answer.visibility = View.VISIBLE
                        prompt.text = question
                        tts?.speak(question, TextToSpeech.QUEUE_FLUSH, null, "assistant-followup")
                    } else {
                        planAction = result.optString("action")
                        destination = result.optString("destination")
                        prompt.text = when (planAction) {
                            "CAPTURE_BUS_BOARD" -> "I can read the pointed bus board for your destination."
                            "CAPTURE_SCENE" -> "I can inspect the pointed scene."
                            "CAPTURE_TEXT" -> "I can read the pointed text."
                            else -> "I found a matching travel task."
                        }
                        action.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                main.post { prompt.text = "Planning unavailable. Check the connection and try again." }
            }
        }
    }

    private fun continueTask() {
        if (planAction.isBlank()) return
        val intent = Intent(host, MainActivity::class.java).apply {
            action = Intent.ACTION_ASSIST
            putExtra(MainActivity.EXTRA_ASSISTANT_REQUEST, request.text.toString().trim())
            putExtra(MainActivity.EXTRA_ASSISTANT_FOLLOW_UP, answer.text.toString().trim())
            putExtra(MainActivity.EXTRA_ASSISTANT_ACTION, planAction)
            putExtra(MainActivity.EXTRA_ASSISTANT_DESTINATION, destination)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        try {
            startAssistantActivity(intent)
            finish()
        } catch (_: Exception) {
            prompt.text = "Could not open the task. Try again from YatraLens."
        }
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.shutdown()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun button(parent: LinearLayout, label: String, onClick: () -> Unit): Button =
        Button(host).apply {
            text = label
            isAllCaps = false
            setTextColor(host.getColor(R.color.yl_ivory))
            backgroundTintList = android.content.res.ColorStateList.valueOf(host.getColor(R.color.yl_sage))
            setOnClickListener { onClick() }
            parent.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    private fun dp(value: Int): Int = (value * host.resources.displayMetrics.density).toInt()
}
