package com.yatralens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

/** A compact traveller screen with diagnostics available on demand. */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var tripSummary: TextView
    private lateinit var notice: TextView
    private lateinit var savedEvidence: TextView
    private lateinit var ocrResult: TextView
    private lateinit var sceneResult: TextView
    private lateinit var sceneQuestion: EditText
    private lateinit var sceneAnswer: TextView
    private lateinit var myLanguage: Spinner
    private lateinit var localLanguage: Spinner
    private lateinit var originalTurn: TextView
    private lateinit var translationHeading: TextView
    private lateinit var reviewNotice: TextView
    private lateinit var translatedTurn: EditText
    private lateinit var weatherConsent: CheckBox
    private lateinit var weatherActivity: EditText
    private lateinit var weatherStart: EditText
    private lateinit var weatherDuration: EditText
    private lateinit var weatherResult: TextView
    private var displayedTurnId = 0L
    private var displayedTranslation = ""
    private val languageNames = listOf("Telugu", "Malayalam", "English", "Hindi")
    private val languageCodes = listOf("te-IN", "ml-IN", "en-IN", "hi-IN")
    private lateinit var optIn: CheckBox
    private lateinit var cloudConsent: CheckBox
    private var pendingStart = false
    private var pendingMode = "CONTINUOUS"
    private fun requiredPermissions(mode: String): Array<String> =
        (if (mode == "CONTINUOUS") listOf(Manifest.permission.CAMERA) else emptyList())
            .plus(listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)).toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val outer = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        outer.addView(layout)
        layout.addView(TextView(this).apply {
            text = "YatraLens"
            textSize = 27f
        })
        tripSummary = TextView(this).apply { textSize = 16f }
        layout.addView(tripSummary)
        notice = TextView(this).apply { textSize = 16f }
        layout.addView(notice)
        optIn = CheckBox(this).apply {
            text = "Allow trip sensing while this trip is active"
            textSize = 16f
        }
        layout.addView(optIn)
        cloudConsent = CheckBox(this).apply {
            text = "Allow selected speech and text to use Sarvam"
            textSize = 16f
        }
        layout.addView(cloudConsent)
        layout.addView(TextView(this).apply { text = "My language"; textSize = 16f })
        myLanguage = languageSpinner(layout, 0)
        layout.addView(TextView(this).apply { text = "Local person's language"; textSize = 16f })
        localLanguage = languageSpinner(layout, 1)
        addButton(layout, "Start trip • camera, talk and location") {
            requestStart("CONTINUOUS")
        }
        addButton(layout, "Start trip • talk and location only") {
            requestStart("CONVERSATION")
        }
        addButton(layout, "End trip") { send(TripSensingService.END) }

        val talk = section(layout, "Talk & Listen", true)
        addButton(talk, "Speak to local person") {
            startTurn("TRAVELLER", selectedCode(myLanguage), selectedCode(localLanguage))
        }
        addButton(talk, "Listen to local person") {
            startTurn("LOCAL", selectedCode(localLanguage), selectedCode(myLanguage))
        }
        addButton(talk, "Stop and translate") {
            if (cloudConsent.isChecked) send(TripSensingService.SEND_TURN, true)
            else notice.text = "Allow selected speech to use Sarvam first."
        }
        originalTurn = TextView(this).apply { textSize = 17f; setTextIsSelectable(true) }
        talk.addView(originalTurn)
        translationHeading = TextView(this).apply {
            text = "Translation to review or edit before speaking:"
            textSize = 16f
        }
        talk.addView(translationHeading)
        translatedTurn = EditText(this).apply {
            textSize = 18f
            minLines = 2
            maxLines = 5
            hint = "Translated text appears here"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        talk.addView(translatedTurn)
        reviewNotice = TextView(this).apply { textSize = 15f }
        talk.addView(reviewNotice)
        addButton(talk, "Speak reviewed translation aloud") {
            val turn = ConversationState.current
            val reviewedText = translatedTurn.text.toString().trim()
            if (!cloudConsent.isChecked || turn?.status != "READY_FOR_REVIEW" || reviewedText.isBlank()) {
                notice.text = "Review a translated turn and allow cloud speech first."
            } else {
                startService(Intent(this, TripSensingService::class.java)
                    .setAction(TripSensingService.SPEAK_TRANSLATION)
                    .putExtra("cloudApproved", true)
                    .putExtra("turnId", turn.id)
                    .putExtra("approvedText", reviewedText))
            }
        }
        addButton(talk, "Save reviewed transcript") {
            startService(Intent(this, TripSensingService::class.java)
                .setAction(TripSensingService.SAVE_TRANSCRIPT).putExtra("userApproved", true))
        }

        val read = section(layout, "Read a sign", false)
        addButton(read, "Capture scene on this phone") {
            sceneAnswer.text = "Analyzing selected photo…"
            send(TripSensingService.CAPTURE_SCENE)
        }
        sceneQuestion = EditText(this).apply {
            hint = "Ask about this photo, e.g. where do I buy tickets?"
            setText("What can you see?")
            textSize = 16f
        }
        read.addView(sceneQuestion)
        addButton(read, "Ask about photo") {
            val answer = SceneAnswerEngine.answer(SceneState.current, SceneState.capturedAt,
                sceneQuestion.text.toString())
            sceneAnswer.text = "${answer.text}\nSource: selected photo, ${answer.model}" +
                if (answer.cues.isEmpty()) "" else "\nCues: ${answer.cues.joinToString()}"
        }
        sceneAnswer = TextView(this).apply { textSize = 17f; setTextIsSelectable(true) }
        read.addView(sceneAnswer)
        sceneResult = TextView(this).apply { textSize = 16f; setTextIsSelectable(true) }
        read.addView(sceneResult)
        addButton(read, "Capture text with camera") {
            if (cloudConsent.isChecked) send(TripSensingService.CAPTURE_TEXT, true)
            else notice.text = "Allow selected text to use Sarvam first."
        }
        ocrResult = TextView(this).apply {
            textSize = 16f
            maxLines = 6
            setTextIsSelectable(true)
            setOnClickListener { maxLines = if (maxLines == 6) Int.MAX_VALUE else 6 }
        }
        read.addView(ocrResult)
        addButton(read, "Save reviewed text") {
            startService(Intent(this, TripSensingService::class.java)
                .setAction(TripSensingService.SAVE_OCR).putExtra("userApproved", true))
        }

        val weatherSection = section(layout, "Weather for my plan", false)
        weatherActivity = EditText(this).apply { hint = "Activity"; setText("Boat ride"); textSize = 16f }
        weatherSection.addView(weatherActivity)
        weatherStart = EditText(this).apply {
            hint = "Starts in minutes"; setText("60"); inputType = InputType.TYPE_CLASS_NUMBER
        }
        weatherSection.addView(weatherStart)
        weatherDuration = EditText(this).apply {
            hint = "Duration in minutes"; setText("60"); inputType = InputType.TYPE_CLASS_NUMBER
        }
        weatherSection.addView(weatherDuration)
        weatherConsent = CheckBox(this).apply {
            text = "Send my current location for this forecast check"
            textSize = 16f
        }
        weatherSection.addView(weatherConsent)
        addButton(weatherSection, "Check forecast") {
            val start = weatherStart.text.toString().toIntOrNull()
            val duration = weatherDuration.text.toString().toIntOrNull()
            if (!weatherConsent.isChecked || start == null || duration == null) {
                notice.text = "Allow this location check and enter start/duration in minutes."
            } else {
                startService(Intent(this, TripSensingService::class.java)
                    .setAction(TripSensingService.CHECK_WEATHER)
                    .putExtra("locationCloudApproved", true)
                    .putExtra("activity", weatherActivity.text.toString())
                    .putExtra("startMinutes", start)
                    .putExtra("durationMinutes", duration))
            }
        }
        weatherResult = TextView(this).apply { textSize = 15f; setTextIsSelectable(true) }
        weatherSection.addView(weatherResult)

        val evidence = section(layout, "Trip memory", false)
        addButton(evidence, "View saved evidence") {
            val memory = TripMemoryStore(this)
            try {
                val tripId = memory.latestTripId()
                savedEvidence.text = if (tripId == null) "No trip memory yet" else {
                    val entries = memory.recentEvidence(tripId)
                    "Open conflicts: ${memory.openConflictCount(tripId)}\n" +
                        if (entries.isEmpty()) "No saved evidence yet" else entries.joinToString("\n\n")
                }
            } finally { memory.close() }
        }
        savedEvidence = TextView(this).apply { textSize = 15f; setTextIsSelectable(true) }
        evidence.addView(savedEvidence)

        val diagnostics = section(layout, "Sensor controls & diagnostics", false)
        diagnostics.addView(TextView(this).apply {
            text = "Continuous mode samples the camera during an active trip. Raw frames and audio are discarded. The notification shows which sensors are running."
            textSize = 15f
        })
        addButton(diagnostics, "Pause camera") { send(TripSensingService.PAUSE_CAMERA) }
        addButton(diagnostics, "Mute microphone") { send(TripSensingService.MUTE_MIC) }
        addButton(diagnostics, "Resume microphone") { send(TripSensingService.RESUME_MIC) }
        addButton(diagnostics, "Resume camera") { send(TripSensingService.RESUME_CAMERA) }
        addButton(diagnostics, "Resume sensors") { send(TripSensingService.RESUME) }
        status = TextView(this).apply { textSize = 15f; setTextIsSelectable(true) }
        diagnostics.addView(status)
        setContentView(outer)
        handler.post(refresh)
    }

    private fun section(parent: LinearLayout, title: String, initiallyOpen: Boolean): LinearLayout {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (initiallyOpen) View.VISIBLE else View.GONE
        }
        val heading = Button(this).apply {
            text = (if (initiallyOpen) "▾ " else "▸ ") + title
            setOnClickListener {
                val open = body.visibility != View.VISIBLE
                body.visibility = if (open) View.VISIBLE else View.GONE
                text = (if (open) "▾ " else "▸ ") + title
            }
        }
        parent.addView(heading)
        parent.addView(body)
        return body
    }

    private fun requestStart(mode: String) {
            if (!optIn.isChecked) {
                notice.text = "Allow trip sensing first."
                return
            }
            val missing = requiredPermissions(mode).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) {
                pendingStart = true
                pendingMode = mode
                requestPermissions(missing.toTypedArray(), 41)
            } else startTrip(mode)
    }

    private fun addButton(layout: LinearLayout, label: String, action: () -> Unit) {
        layout.addView(Button(this).apply {
            text = label
            minHeight = 56
            setOnClickListener { action() }
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun languageSpinner(layout: LinearLayout, selectedIndex: Int): Spinner = Spinner(this).also { spinner ->
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languageNames)
        spinner.setSelection(selectedIndex)
        layout.addView(spinner)
    }

    private fun selectedCode(spinner: Spinner): String = languageCodes[spinner.selectedItemPosition]

    private fun startTurn(owner: String, source: String, target: String) {
        startService(Intent(this, TripSensingService::class.java)
            .setAction(TripSensingService.START_TURN)
            .putExtra("owner", owner)
            .putExtra("sourceLanguage", source)
            .putExtra("targetLanguage", target))
    }

    private fun startTrip(mode: String) {
        val intent = Intent(this, TripSensingService::class.java).setAction(TripSensingService.START)
            .putExtra("mode", mode)
        startForegroundService(intent)
    }

    private fun send(action: String, cloudApproved: Boolean = false) {
        startService(Intent(this, TripSensingService::class.java).setAction(action)
            .putExtra("cloudApproved", cloudApproved))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41 && pendingStart) {
            pendingStart = false
            if ((pendingMode != "CONTINUOUS" || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            ) startTrip(pendingMode) else notice.text = "Grant the selected sensor permissions to start."
        }
    }

    private val refresh = object : Runnable {
        override fun run() {
            val turn = ConversationState.current
            if (turn != null) {
                originalTurn.text = "${if (turn.owner == "LOCAL") "Local person" else "Traveller"} (${turn.sourceLanguage}): ${turn.original.ifBlank { turn.status }}"
                translationHeading.text = "Translation for ${if (turn.owner == "LOCAL") "traveller" else "local person"} (${turn.targetLanguage}); review or edit before speaking:"
                reviewNotice.text = if (turn.reviewFlags.isNotEmpty())
                    "Check the fare and time carefully: numbers or time format changed in translation."
                    else if (turn.status == "TRANSLATION_UNAVAILABLE")
                        "Translation unavailable. Record again or use typed text."
                    else ""
                if (turn.id != displayedTurnId || turn.translated != displayedTranslation) {
                    displayedTurnId = turn.id
                    displayedTranslation = turn.translated
                    translatedTurn.setText(turn.translated)
                }
            } else if (displayedTurnId != 0L) {
                displayedTurnId = 0L
                displayedTranslation = ""
                originalTurn.text = ""
                reviewNotice.text = ""
                translatedTurn.setText("")
            }
            val ocr = SensorState.getOcrText()
            val scene = SceneState.current
            sceneResult.text = scene?.let {
                val objects = it.optJSONArray("objects")?.length() ?: 0
                "Photo analyzed locally at ${SceneState.capturedAt}; $objects possible objects. Model output is tentative."
            } ?: SensorState.get("scene scan").let {
                if (it == "not started") "" else it
            }
            val scanStatus = SensorState.get("text scan")
            val shown = if (ocr.isBlank()) {
                if (scanStatus == "not started") "" else scanStatus
            } else "OCR text (tap to expand):\n$ocr"
            if (ocrResult.text.toString() != shown) ocrResult.text = shown
            val weather = WeatherState.current
            weatherResult.text = if (weather == null) {
                val state = SensorState.get("weather")
                if (state == "not started") "" else state
            } else
                "${weather.decision}: ${weather.reason}\nRain probability up to ${weather.rainPercent ?: "unknown"}% • Gusts up to ${weather.gustKmh ?: "unknown"} km/h\nForecast checked ${weather.retrievedAt}\nSource: ${weather.sourceUrl}"
            tripSummary.text = "Trip: ${SensorState.get("trip")}\nCamera: ${SensorState.get("camera")} • Mic: ${SensorState.get("microphone")}\nLocation: ${SensorState.get("location")}"
            status.text = SensorState.snapshot() + "\nPersisted health events: " + EventStore(this@MainActivity).count()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        super.onDestroy()
    }
}

