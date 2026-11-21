package com.yatralens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.voice.VoiceInteractionSession
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.TextView
import android.widget.ScrollView
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
    private lateinit var sensorConsent: CheckBox
    private lateinit var shareConsent: CheckBox
    private lateinit var speechConsent: CheckBox
    private lateinit var localLanguage: Spinner
    private lateinit var taskInput: EditText
    private lateinit var placeName: EditText
    private lateinit var placeLat: EditText
    private lateinit var placeLon: EditText
    private lateinit var placeSource: EditText
    private lateinit var taskControls: LinearLayout
    private lateinit var dynamicControls: LinearLayout
    private lateinit var output: TextView
    private lateinit var reviewed: EditText
    private lateinit var speakResult: Button
    private lateinit var saveResult: Button
    private lateinit var stopTrip: Button
    private lateinit var language: Spinner
    private var planAction = ""
    private var destination = ""
    private var sessionVisible = false
    private var taskStage = ""
    private var stageStartedMs = 0L
    private var recordOwner = "TRAVELLER"
    private var sessionStartedTrip = false
    private var reviewLanguage = "en-IN"
    private var saveKind = ""
    private var conversationControlsAdded = false
    private var sensorControlsAdded = false
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
        val inner = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            setPadding(dp(12), dp(12), dp(12), dp(24))
            addView(card)
        }
        val root = ScrollView(host).apply {
            isFillViewport = false
            addView(inner)
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
        card.addView(TextView(host).apply {
            val recent = try { TripMemoryStore(host).use { it.recentTimeline(3) } }
                catch (_: Exception) { emptyList() }
            text = if (recent.isEmpty()) "No saved trip context yet" else
                "Recent context\n" + recent.joinToString("\n") {
                    "${it.observedAt.take(16).replace('T', ' ')} • ${it.title}"
                }
            textSize = 13f
            setTextColor(host.getColor(R.color.yl_mist))
        })
        val languageSettings = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        button(card, "Change languages") {
            languageSettings.visibility = if (languageSettings.visibility == View.GONE) View.VISIBLE else View.GONE
        }
        card.addView(languageSettings)
        languageSettings.addView(TextView(host).apply {
            text = "My language"
            setTextColor(host.getColor(R.color.yl_mist))
        })
        language = Spinner(host).also {
            it.adapter = object : ArrayAdapter<String>(host, android.R.layout.simple_spinner_item,
                listOf("Telugu", "Malayalam", "English", "Hindi")) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    super.getView(position, convertView, parent).also { view ->
                        (view as? TextView)?.setTextColor(host.getColor(R.color.yl_ivory))
                    }
            }
            it.setSelection(host.getSharedPreferences("assistant_languages", 0)
                .getInt("traveller", 2).coerceIn(0, codes.lastIndex))
            languageSettings.addView(it)
        }
        languageSettings.addView(TextView(host).apply {
            text = "Local language"
            setTextColor(host.getColor(R.color.yl_mist))
        })
        localLanguage = Spinner(host).also {
            it.adapter = object : ArrayAdapter<String>(host, android.R.layout.simple_spinner_item,
                listOf("Telugu", "Malayalam", "English", "Hindi")) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    super.getView(position, convertView, parent).also { view ->
                        (view as? TextView)?.setTextColor(host.getColor(R.color.yl_ivory))
                    }
            }
            it.setSelection(host.getSharedPreferences("assistant_languages", 0)
                .getInt("local", 1).coerceIn(0, codes.lastIndex))
            languageSettings.addView(it)
        }
        language.onItemSelectedListener = languageListener("traveller")
        localLanguage.onItemSelectedListener = languageListener("local")
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
            text = "Send selected request and task text to Sarvam"
            setTextColor(host.getColor(R.color.yl_sand))
        }
        card.addView(cloud)
        button(card, "Understand request") { plan() }
        action = button(card, "Continue with this task") { continueTask() }
        action.visibility = View.GONE
        taskControls = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        card.addView(taskControls)
        taskInput = edit(taskControls, "Task detail, such as activity or landmark").apply {
            visibility = View.GONE
        }
        placeName = edit(taskControls, "Booking point name").apply { visibility = View.GONE }
        placeLat = edit(taskControls, "Latitude").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
            visibility = View.GONE
        }
        placeLon = edit(taskControls, "Longitude").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
            visibility = View.GONE
        }
        placeSource = edit(taskControls, "Booking source words").apply { visibility = View.GONE }
        sensorConsent = CheckBox(host).apply {
            text = "Allow camera, mic and location for this trip task"
            setTextColor(host.getColor(R.color.yl_sand))
            visibility = View.GONE
        }
        taskControls.addView(sensorConsent)
        shareConsent = CheckBox(host).apply {
            text = "Send this selected image crop or GPS fix for this task"
            setTextColor(host.getColor(R.color.yl_sand))
            visibility = View.GONE
        }
        taskControls.addView(shareConsent)
        dynamicControls = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        taskControls.addView(dynamicControls)
        output = TextView(host).apply {
            textSize = 17f
            setTextColor(host.getColor(R.color.yl_ivory))
            setTextIsSelectable(true)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        card.addView(output)
        reviewed = edit(card, "Review answer before speaking").apply { visibility = View.GONE }
        speechConsent = CheckBox(host).apply {
            text = "Send this reviewed answer to Sarvam for speech"
            setTextColor(host.getColor(R.color.yl_sand))
            visibility = View.GONE
        }
        card.addView(speechConsent)
        speakResult = button(card, "Speak reviewed answer") { speakReviewed() }
        speakResult.visibility = View.GONE
        saveResult = button(card, "Save reviewed text to trip memory") { saveCurrentEvidence() }
        saveResult.visibility = View.GONE
        stopTrip = button(card, "End trip and stop sensors") { send(TripSensingService.END); output.text = "Stopping trip sensors." }
        stopTrip.visibility = View.GONE
        button(card, "Close") { finish() }
        inner.requestFocus()
        return root
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        sessionVisible = true
        YatraVoiceService.active?.pauseForSession()
        window.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        prompt.text = "What do you need help with?"
        tts = TextToSpeech(host) { status -> main.post {
            if (!sessionVisible) return@post
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.ENGLISH
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == "assistant-welcome" || utteranceId == "assistant-followup")
                            main.post { if (sessionVisible) listen() }
                    }
                    override fun onError(utteranceId: String?) {
                        main.post { if (sessionVisible) listen() }
                    }
                })
                if (tts?.speak("What do you need help with?", TextToSpeech.QUEUE_FLUSH,
                        null, "assistant-welcome") == TextToSpeech.ERROR) listen()
            } else listen()
        } }
    }

    override fun onHide() {
        super.onHide()
        sessionVisible = false
        taskStage = ""
        main.removeCallbacks(pollTask)
        recognizer?.destroy()
        recognizer = null
        if (sessionStartedTrip) {
            send(TripSensingService.END)
            sessionStartedTrip = false
        }
        YatraVoiceService.active?.resumeAfterSession()
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
                    if (answer.visibility == View.VISIBLE && cloud.isChecked && heard.isNotBlank())
                        plan()
                    else prompt.text = "Review the words and approve sending before continuing."
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
        val localTask = when {
            Regex("\\b(offline|emergency|helpline|no internet)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(spoken) -> "OPEN_OFFLINE"
            Regex("\\b(memory|saved context|what did i save|remembered)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(spoken) -> "OPEN_MEMORY"
            Regex("\\b(accessibility|talkback|large text)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(spoken) -> "OPEN_ACCESSIBILITY"
            Regex("\\b(sensor status|mute microphone|pause camera|stop sensors)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(spoken) -> "OPEN_SENSOR_CONTROLS"
            else -> null
        }
        if (localTask != null) {
            planAction = localTask
            destination = ""
            prompt.text = "I can help with that on this phone."
            prepareTask()
            if (localTask == "OPEN_ACCESSIBILITY") action.visibility = View.VISIBLE
            else { action.visibility = View.GONE; continueTask() }
            return
        }
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
                        prepareTask()
                        if (planAction in setOf("OPEN_MEMORY", "OPEN_OFFLINE", "OPEN_SENSOR_CONTROLS",
                                "OPEN_TASKS")) {
                            action.visibility = View.GONE
                            continueTask()
                        } else action.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                main.post { prompt.text = "Planning unavailable. Check the connection and try again." }
            }
        }
    }

    private fun continueTask() {
        if (planAction.isBlank()) return
        when (planAction) {
            "OPEN_MEMORY" -> showMemory()
            "OPEN_OFFLINE" -> showOffline()
            "OPEN_ACCESSIBILITY" -> {
                try {
                    host.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    output.text = "Android accessibility settings opened. Return to the bubble to continue."
                } catch (_: Exception) { output.text = "Accessibility settings are unavailable here." }
            }
            "OPEN_SENSOR_CONTROLS" -> showSensors()
            "OPEN_TASKS" -> output.text = "Ask about a scene, sign, bus, conversation, booking point, weather, landmark, local craft, trip memory or phone sensors."
            else -> ensureTrip()
        }
    }

    private fun prepareTask() {
        dynamicControls.removeAllViews()
        conversationControlsAdded = false
        sensorControlsAdded = false
        saveKind = ""
        taskInput.visibility = if (planAction in setOf("OPEN_WEATHER", "OPEN_CULTURE", "OPEN_DISCOVERY"))
            View.VISIBLE else View.GONE
        taskInput.setText(when (planAction) {
            "OPEN_WEATHER" -> answer.text.toString().trim().ifBlank { "Boat ride" }
            "OPEN_CULTURE", "OPEN_DISCOVERY" -> answer.text.toString().trim().ifBlank {
                request.text.toString().trim()
            }
            else -> ""
        })
        val booking = planAction == "OPEN_BOOKING"
        listOf(placeName, placeLat, placeLon, placeSource).forEach {
            it.visibility = if (booking) View.VISIBLE else View.GONE
        }
        val needsSensors = planAction in setOf("CAPTURE_SCENE", "CAPTURE_TEXT", "CAPTURE_BUS_BOARD",
            "OPEN_TALK", "OPEN_BOOKING", "OPEN_WEATHER", "OPEN_CULTURE", "OPEN_DISCOVERY")
        sensorConsent.visibility = if (needsSensors) View.VISIBLE else View.GONE
        sensorConsent.text = if (planAction in setOf("CAPTURE_SCENE", "CAPTURE_TEXT", "CAPTURE_BUS_BOARD"))
            "Allow camera, microphone and location for this trip task" else
            if (planAction == "OPEN_TALK") "Allow microphone and location for this conversation" else
                "Allow location for this trip check"
        shareConsent.visibility = if (planAction in setOf("CAPTURE_TEXT", "CAPTURE_BUS_BOARD",
                "OPEN_TALK", "OPEN_BOOKING", "OPEN_WEATHER", "OPEN_CULTURE", "OPEN_DISCOVERY"))
            View.VISIBLE else View.GONE
        shareConsent.text = when (planAction) {
            "CAPTURE_TEXT", "CAPTURE_BUS_BOARD" -> "Send one selected camera crop to Sarvam"
            "OPEN_TALK" -> "Send each selected spoken turn to Sarvam"
            else -> "Send this current GPS fix for the selected check"
        }
        output.text = ""
        reviewed.visibility = View.GONE
        speakResult.visibility = View.GONE
        speechConsent.visibility = View.GONE
        saveResult.visibility = View.GONE
        stopTrip.visibility = if (PhysicalContextEngine.situation.value.tripId != null) View.VISIBLE else View.GONE
        action.text = "Do this in the bubble"
    }

    private fun ensureTrip() {
        val needsCamera = planAction in setOf("CAPTURE_SCENE", "CAPTURE_TEXT", "CAPTURE_BUS_BOARD")
        if (!sensorConsent.isChecked) {
            prompt.text = "Approve the selected trip sensors in this bubble first."
            return
        }
        val required = mutableListOf<String>()
        if (needsCamera || planAction == "OPEN_TALK") required.add(Manifest.permission.RECORD_AUDIO)
        if (needsCamera) required.add(Manifest.permission.CAMERA)
        if (planAction in setOf("OPEN_BOOKING", "OPEN_WEATHER", "OPEN_CULTURE", "OPEN_DISCOVERY"))
            required.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (required.any { host.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            prompt.text = "Grant the needed camera, microphone or location permission once from the YatraLens home setup."
            return
        }
        if (planAction in setOf("CAPTURE_TEXT", "CAPTURE_BUS_BOARD", "OPEN_WEATHER",
                "OPEN_CULTURE", "OPEN_DISCOVERY") && !shareConsent.isChecked) {
            prompt.text = "Approve this selected image or GPS sharing first."
            return
        }
        if (PhysicalContextEngine.situation.value.tripId == null) {
            try {
                host.startForegroundService(Intent(host, TripSensingService::class.java)
                    .setAction(TripSensingService.START)
                    .putExtra("mode", when {
                        needsCamera -> "CONTINUOUS"
                        planAction == "OPEN_TALK" -> "CONVERSATION"
                        else -> "LOCATION_ONLY"
                    }))
                sessionStartedTrip = true
                output.text = "Starting selected trip sensors…"
                setStage("WAIT_TRIP")
            } catch (e: Exception) {
                output.text = "Could not start sensors: ${e.javaClass.simpleName}. Check setup permissions."
            }
        } else {
            setStage("WAIT_TRIP")
        }
        stopTrip.visibility = View.VISIBLE
    }

    private fun executeTask() {
        when (planAction) {
            "CAPTURE_SCENE" -> {
                SceneState.set(null)
                send(TripSensingService.CAPTURE_SCENE)
                output.text = "Looking at the pointed scene on this phone…"
                setStage("WAIT_SCENE")
            }
            "CAPTURE_TEXT", "CAPTURE_BUS_BOARD" -> {
                SensorState.setOcrText("")
                send(TripSensingService.CAPTURE_TEXT, "cloudApproved" to true,
                    "language" to codes[localLanguage.selectedItemPosition])
                output.text = "Reading the selected camera crop…"
                setStage("WAIT_OCR")
            }
            "OPEN_TALK" -> showConversationControls()
            "OPEN_WEATHER" -> {
                WeatherState.set(null)
                send(TripSensingService.CHECK_WEATHER, "locationCloudApproved" to true,
                    "activity" to taskInput.text.toString().trim().ifBlank { "Boat ride" },
                    "startMinutes" to 60, "durationMinutes" to 120)
                output.text = "Checking the next two hours starting in one hour…"
                setStage("WAIT_WEATHER")
            }
            "OPEN_CULTURE" -> {
                FeatureState.set("culture", null)
                send(TripSensingService.LOOKUP_CULTURE, "locationCloudApproved" to true,
                    "nameHint" to taskInput.text.toString().trim())
                output.text = "Checking sourced landmark information…"
                setStage("WAIT_culture")
            }
            "OPEN_DISCOVERY" -> {
                FeatureState.set("discovery", null)
                send(TripSensingService.DISCOVER_LOCAL, "locationCloudApproved" to true,
                    "interest" to taskInput.text.toString().trim())
                output.text = "Checking sourced local experiences…"
                setStage("WAIT_discovery")
            }
            "OPEN_BOOKING" -> checkOrSaveBooking()
        }
    }

    private fun checkOrSaveBooking() {
        val saved = TripMemoryStore(host).use { memory ->
            PhysicalContextEngine.situation.value.tripId?.let { memory.latestDestination(it) }
        }
        if (saved != null && shareConsent.isChecked) {
            FeatureState.set("destination", null)
            send(TripSensingService.CHECK_DESTINATION, "locationCloudApproved" to true)
            output.text = "Comparing your current GPS fix with saved ${saved.placeName}…"
            setStage("WAIT_destination")
            return
        }
        val name = placeName.text.toString().trim()
        val lat = placeLat.text.toString().toDoubleOrNull()
        val lon = placeLon.text.toString().toDoubleOrNull()
        val source = placeSource.text.toString().trim()
        if (name.isBlank() || lat == null || lon == null || source.isBlank()) {
            output.text = if (saved == null)
                "Tell me the booking point name, coordinates and ticket/source words in the fields above; I will save them locally before comparing GPS."
            else "Saved point: ${saved.placeName}. Approve sharing one GPS fix to check it."
            return
        }
        send(TripSensingService.SAVE_DESTINATION, "placeName" to name,
            "latitude" to lat, "longitude" to lon, "bookingExcerpt" to source)
        output.text = "Saving this traveller-entered booking point locally. Its coordinates are unverified."
        if (shareConsent.isChecked)
            main.postDelayed({ if (sessionVisible) checkOrSaveBooking() }, 700)
    }

    private fun showConversationControls() {
        if (!conversationControlsAdded) {
            conversationControlsAdded = true
            button(dynamicControls, "I speak to the local person") { startConversationTurn("TRAVELLER") }
            button(dynamicControls, "Listen to the local person") { startConversationTurn("LOCAL") }
            button(dynamicControls, "Stop selected turn and translate") {
                if (!shareConsent.isChecked || !cloud.isChecked) {
                    output.text = "Approve sending this selected spoken turn to Sarvam."
                } else {
                    send(TripSensingService.SEND_TURN, "cloudApproved" to true)
                    output.text = "Transcribing and translating this selected turn…"
                    setStage("WAIT_CONVERSATION")
                }
            }
        }
        output.text = "Choose who speaks, keep the phone near them, then stop the selected turn. Review the translation before speaking it aloud."
    }

    private fun startConversationTurn(owner: String) {
        recordOwner = owner
        val source = if (owner == "LOCAL") codes[localLanguage.selectedItemPosition]
            else codes[language.selectedItemPosition]
        val target = if (owner == "LOCAL") codes[language.selectedItemPosition]
            else codes[localLanguage.selectedItemPosition]
        send(TripSensingService.START_TURN, "owner" to owner,
            "sourceLanguage" to source, "targetLanguage" to target)
        output.text = "Listening to ${if (owner == "LOCAL") "the local person" else "you"} for up to ten seconds."
    }

    private fun showMemory() {
        dynamicControls.removeAllViews()
        val (latest, entries) = TripMemoryStore(host).use { it.latestTripId() to it.recentTimeline(40) }
        output.text = if (entries.isEmpty()) "No saved trip context yet." else
            entries.joinToString("\n\n") {
                "${formatTime(it.observedAt)} • ${it.title}\n${it.detail}\nSource: ${it.source}"
            }
        entries.filter { it.evidenceId != null }.distinctBy { it.evidenceId }.take(12)
            .forEach { item ->
                button(dynamicControls, "Delete saved evidence: ${item.title.take(35)}") {}
                    .also { control ->
                        control.setOnClickListener {
                            if (control.tag != "confirm") {
                                control.tag = "confirm"
                                control.text = "Confirm permanent deletion: ${item.title.take(25)}"
                                output.append("\nTap Confirm to remove this evidence and its derived details.")
                            } else {
                                val deleted = TripMemoryStore(host).use {
                                    it.deleteEvidenceCascade(item.tripId, requireNotNull(item.evidenceId))
                                }
                                if (deleted) showMemory()
                                else output.text = "That evidence was already removed."
                            }
                        }
                    }
            }
        if (latest != null) TripMemoryStore(host).use { memory ->
            memory.openConflicts(latest).forEach { conflict ->
                button(dynamicControls, "Review ${conflict.kind.replace('_', ' ')} disagreement") {
                    output.text = conflict.claims.joinToString("\n\n") {
                        "${it.value} • ${it.sourceTitle} • ${it.observedAt}\n${it.excerpt}"
                    }
                    conflict.claims.forEachIndexed { index, claim ->
                        button(dynamicControls, "Confirm source ${index + 1}: ${claim.value.take(28)}") {
                            TripMemoryStore(host).use {
                                it.resolveConflict(conflict.conflictId,
                                    if (index == 0) "KEEP_OLD" else "CONFIRM_NEW", claim.claimId,
                                    "Traveller confirmed in assistant bubble")
                            }
                            output.text = "Confirmed that sourced detail. The other evidence remains saved."
                        }
                    }
                }
            }
        }
    }

    private fun showOffline() {
        dynamicControls.removeAllViews()
        val phrases = OfflinePhraseStore(host).use { it.recent() }
        output.text = "Offline help\n" + OfflineHelp.contacts.joinToString("\n") {
            "${it.label}: ${it.number}"
        } + "\nSource: ${OfflineHelp.SOURCE}\n\nSaved phrases\n" +
            if (phrases.isEmpty()) "None yet" else phrases.joinToString("\n") {
                "${it.original} → ${it.translation} • ${it.reviewedAt}"
            }
        OfflineHelp.contacts.forEach { contact ->
            button(dynamicControls, "Open dialer for ${contact.label}: ${contact.number}") {
                try {
                    host.startActivity(Intent(Intent.ACTION_DIAL,
                        android.net.Uri.parse("tel:${contact.number}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) { output.append("\nDialer unavailable on this phone.") }
            }
        }
    }

    private fun showSensors() {
        output.text = "Current phone context\n${SensorState.snapshot()}"
        if (!sensorControlsAdded) {
            sensorControlsAdded = true
            button(dynamicControls, "Pause camera") { send(TripSensingService.PAUSE_CAMERA); showSensors() }
            button(dynamicControls, "Mute microphone") { send(TripSensingService.MUTE_MIC); showSensors() }
            button(dynamicControls, "Resume sensors") { send(TripSensingService.RESUME); showSensors() }
        }
        stopTrip.visibility = if (PhysicalContextEngine.situation.value.tripId != null) View.VISIBLE else View.GONE
    }

    private fun send(actionName: String, vararg extras: Pair<String, Any>) {
        val intent = Intent(host, TripSensingService::class.java).setAction(actionName)
        extras.forEach { (key, value) -> when (value) {
            is Boolean -> intent.putExtra(key, value)
            is Int -> intent.putExtra(key, value)
            is Double -> intent.putExtra(key, value)
            else -> intent.putExtra(key, value.toString())
        } }
        try { host.startService(intent) }
        catch (e: Exception) { output.text = "Task unavailable: ${e.javaClass.simpleName}" }
    }

    private fun setStage(next: String) {
        taskStage = next
        stageStartedMs = SystemClock.elapsedRealtime()
        main.removeCallbacks(pollTask)
        main.postDelayed(pollTask, 500)
    }

    private val pollTask = object : Runnable {
        override fun run() {
            if (!sessionVisible || taskStage.isBlank()) return
            if (SystemClock.elapsedRealtime() - stageStartedMs > 75_000) {
                output.text = "This check took too long. Check the phone's sensor state, then retry.\n" +
                    SensorState.get("text scan")
                taskStage = ""
                return
            }
            when (taskStage) {
                "WAIT_TRIP" -> {
                    val active = PhysicalContextEngine.situation.value.tripId != null
                    val cameraReady = planAction !in setOf("CAPTURE_SCENE", "CAPTURE_TEXT", "CAPTURE_BUS_BOARD") ||
                        PhysicalContextEngine.fresh(SensorKind.CAMERA, 15_000)
                    val locationReady = planAction !in setOf("OPEN_BOOKING", "OPEN_WEATHER", "OPEN_CULTURE", "OPEN_DISCOVERY") ||
                        PhysicalContextEngine.fresh(SensorKind.LOCATION, 120_000)
                    if (active && cameraReady && locationReady) { taskStage = ""; executeTask() }
                }
                "WAIT_SCENE" -> SceneState.current?.let { scene ->
                    taskStage = ""
                    val answerText = SceneAnswerEngine.answer(scene, SceneState.capturedAt,
                        request.text.toString()).text
                    showAnswer(answerText, "en-IN")
                    saveContext("Scene checked", answerText, "On-device Mirage; tentative visual cues")
                }
                "WAIT_OCR" -> {
                    val ocr = SensorState.getOcrText()
                    if (ocr.isNotBlank()) {
                        if (planAction == "CAPTURE_BUS_BOARD") {
                            FeatureState.set("transport", null)
                            send(TripSensingService.CHECK_TRANSPORT,
                                "cloudApproved" to true, "boardText" to ocr,
                                "destination" to destination, "routeRecords" to "[]")
                            taskStage = "WAIT_transport"
                            stageStartedMs = SystemClock.elapsedRealtime()
                        } else {
                            send(TripSensingService.INTERPRET_OCR, "cloudApproved" to true,
                                "targetLanguage" to codes[language.selectedItemPosition])
                            taskStage = "WAIT_ocr interpretation"
                            stageStartedMs = SystemClock.elapsedRealtime()
                            saveKind = "OCR"
                        }
                        output.text = "Recognized text: $ocr\nChecking its meaning…"
                    }
                }
                "WAIT_WEATHER" -> WeatherState.current?.let { weather ->
                    taskStage = ""
                    val answerText = "${weather.decision}: ${weather.reason}\n" +
                        "IMD warning: ${weather.officialWarningStatus}\n" +
                        "Forecast: ${weather.forecastSourceUrl}\nRetrieved: ${weather.retrievedAt}"
                    showAnswer(answerText, "en-IN")
                    saveContext("Weather check", answerText, weather.forecastSourceUrl)
                }
                "WAIT_CONVERSATION" -> ConversationState.current?.takeIf {
                    it.status == "READY_FOR_REVIEW" || it.status == "TRANSLATION_UNAVAILABLE"
                }?.let { turn ->
                    taskStage = ""
                    reviewLanguage = turn.targetLanguage
                    output.text = "${turn.owner} said: ${turn.original}\n" +
                        "Translation: ${turn.translated.ifBlank { "Unavailable" }}\n" +
                        "Review names, numbers and times before speaking."
                    reviewed.setText(turn.translated)
                    reviewed.visibility = View.VISIBLE
                    speechConsent.visibility = View.VISIBLE
                    speakResult.visibility = View.VISIBLE
                    saveResult.visibility = View.VISIBLE
                    saveKind = "TRANSCRIPT"
                }
                else -> if (taskStage.startsWith("WAIT_")) {
                    val kind = taskStage.removePrefix("WAIT_")
                    FeatureState.get(kind)?.let { feature ->
                        taskStage = ""
                        val answerText = if (kind == "ocr interpretation")
                            "Recognized: ${SensorState.getOcrText()}\n" + FeaturePresenter.describe(kind, feature)
                        else FeaturePresenter.describe(kind, feature)
                        if (kind == "transport") showBusAnswer(SensorState.getOcrText(), answerText)
                        else showAnswer(answerText, if (kind == "ocr interpretation")
                            codes[language.selectedItemPosition] else "en-IN")
                        if (kind == "ocr interpretation")
                            reviewed.setText(feature.optString("translated_text")
                                .ifBlank { SensorState.getOcrText() })
                        if (kind in setOf("transport", "culture", "discovery", "destination")) {
                            val source = feature.optString("source_url")
                                .ifBlank { feature.optString("official_depot_enquiry") }
                                .ifBlank { when (kind) {
                                    "transport" -> "Selected board OCR; route evidence unavailable"
                                    "destination" -> "Phone GPS and traveller-entered point"
                                    else -> "Provider result; check details before relying on them"
                                } }
                            saveContext("${kind.replaceFirstChar { it.uppercase() }} check", answerText, source)
                        }
                    }
                }
            }
            if (taskStage.isNotBlank()) main.postDelayed(this, 500)
        }
    }

    private fun showAnswer(text: String, sourceLanguage: String) {
        output.text = text
        reviewLanguage = codes[language.selectedItemPosition]
        reviewed.setText(text)
        reviewed.visibility = View.VISIBLE
        speakResult.visibility = View.VISIBLE
        speechConsent.visibility = if (PhysicalContextEngine.situation.value.tripId != null)
            View.VISIBLE else View.GONE
        saveResult.visibility = if (saveKind == "OCR") View.VISIBLE else View.GONE
        if (sourceLanguage != reviewLanguage && cloud.isChecked) {
            worker.execute {
                try {
                    val translated = CloudGateway.translateText(text.take(1800), sourceLanguage, reviewLanguage)
                    main.post {
                        if (sessionVisible) {
                            output.text = "$text\n\nIn your language: ${translated.text}"
                            reviewed.setText(translated.text)
                        }
                    }
                } catch (_: Exception) {
                    main.post { if (sessionVisible) output.text = "$text\nTranslation unavailable; review the original." }
                }
            }
        }
    }

    private fun showBusAnswer(boardText: String, routeCheck: String) {
        val answerText = "Board text: $boardText\nDestination asked: $destination\n$routeCheck"
        showAnswer(answerText, codes[language.selectedItemPosition])
        val from = codes[localLanguage.selectedItemPosition]
        val to = codes[language.selectedItemPosition]
        if (boardText.isBlank() || from == to || !cloud.isChecked) return
        worker.execute {
            try {
                val translated = CloudGateway.translateText(boardText.take(1200), from, to)
                main.post {
                    if (sessionVisible) {
                        val display = "$answerText\n\nBoard in your language: ${translated.text}"
                        output.text = display
                        reviewed.setText("Board in your language: ${translated.text}. $routeCheck")
                    }
                }
            } catch (_: Exception) {
                main.post { if (sessionVisible) output.append("\nBoard translation unavailable.") }
            }
        }
    }

    private fun saveContext(title: String, detail: String, source: String) {
        val trip = PhysicalContextEngine.situation.value.tripId ?: return
        try { TripMemoryStore(host).use { it.saveContextNote(trip, title, detail, source) } }
        catch (_: Exception) { output.append("\nThis result could not be added to trip memory.") }
    }

    private fun speakReviewed() {
        val text = reviewed.text.toString().trim()
        if (text.isBlank()) { output.text = "Review an answer first."; return }
        if (PhysicalContextEngine.situation.value.tripId != null) {
            if (!speechConsent.isChecked) {
                output.text = "Approve sending this reviewed answer for Sarvam speech first."
                return
            }
            send(TripSensingService.SPEAK_REVIEWED_TEXT, "cloudApproved" to true,
                "approvedText" to text, "language" to reviewLanguage)
            output.text = "Speaking the reviewed answer aloud.\n$text"
        } else {
            val locale = Locale.forLanguageTag(reviewLanguage)
            if ((tts?.isLanguageAvailable(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED) >= TextToSpeech.LANG_AVAILABLE) {
                tts?.language = locale
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "assistant-answer")
            } else output.text = "Local speech is unavailable for this language. The text remains visible."
        }
    }

    private fun saveCurrentEvidence() {
        when (saveKind) {
            "OCR" -> send(TripSensingService.SAVE_OCR, "userApproved" to true)
            "TRANSCRIPT" -> send(TripSensingService.SAVE_TRANSCRIPT, "userApproved" to true)
        }
        output.text = "Saving this reviewed text with its source in trip memory."
        saveResult.visibility = View.GONE
        saveKind = ""
    }

    private fun edit(parent: LinearLayout, hintText: String): EditText = EditText(host).apply {
        hint = hintText
        setTextColor(host.getColor(R.color.yl_ivory))
        setHintTextColor(host.getColor(R.color.yl_mist))
        parent.addView(this)
    }

    override fun onDestroy() {
        sessionVisible = false
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

    private fun formatTime(value: String): String = try {
        DateTimeFormatter.ofPattern("d MMM, h:mm a").withZone(ZoneId.systemDefault())
            .format(Instant.parse(value))
    } catch (_: Exception) { value }

    private fun languageListener(key: String) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            host.getSharedPreferences("assistant_languages", 0).edit().putInt(key, position).apply()
        }
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }
}
