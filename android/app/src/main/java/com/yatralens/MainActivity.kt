package com.yatralens

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.ViewGroup
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale

/** A compact traveller screen with diagnostics available on demand. */
class MainActivity : Activity() {
    companion object {
        const val EXTRA_ASSISTANT_REQUEST = "com.yatralens.assistant.REQUEST"
        const val EXTRA_ASSISTANT_FOLLOW_UP = "com.yatralens.assistant.FOLLOW_UP"
        const val EXTRA_ASSISTANT_ACTION = "com.yatralens.assistant.ACTION"
        const val EXTRA_ASSISTANT_DESTINATION = "com.yatralens.assistant.DESTINATION"
    }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var startCameraButton: Button
    private lateinit var startTalkButton: Button
    private lateinit var endTripButton: Button
    private lateinit var tripSummary: TextView
    private lateinit var offlineNotice: TextView
    private lateinit var notice: TextView
    private lateinit var savedEvidence: TextView
    private lateinit var timelinePreview: TextView
    private lateinit var timelineItems: LinearLayout
    private lateinit var phraseItems: LinearLayout
    private lateinit var evidenceItems: LinearLayout
    private lateinit var conflictItems: LinearLayout
    private lateinit var agreementPanel: LinearLayout
    private lateinit var agreementSubject: EditText
    private var displayedAgreementKey = ""
    private lateinit var ocrResult: TextView
    private lateinit var ocrTranslation: EditText
    private var displayedOcrTranslation = ""
    private val featureOutputs = mutableMapOf<String, TextView>()
    private lateinit var sceneResult: TextView
    private lateinit var sceneQuestion: EditText
    private lateinit var sceneAnswer: TextView
    private lateinit var sceneTranslation: EditText
    private var latestSceneAnswerText = ""
    private var displayedSceneTranslation = ""
    private lateinit var myLanguage: Spinner
    private lateinit var localLanguage: Spinner
    private lateinit var originalTurn: TextView
    private lateinit var translationHeading: TextView
    private lateinit var reviewNotice: TextView
    private lateinit var translatedTurn: EditText
    private lateinit var typedInput: EditText
    private lateinit var typedTranslation: EditText
    private var displayedTypedTranslation = ""
    private lateinit var conductorTranslation: EditText
    private var displayedConductorTranslation = ""
    private lateinit var weatherConsent: CheckBox
    private lateinit var weatherActivity: EditText
    private lateinit var weatherStart: EditText
    private lateinit var weatherDuration: EditText
    private lateinit var weatherResult: TextView
    private lateinit var pageScroll: ScrollView
    private lateinit var assistantPanel: LinearLayout
    private lateinit var assistantPrompt: TextView
    private lateinit var assistantRequest: EditText
    private lateinit var assistantFollowUp: EditText
    private lateinit var assistantAction: Button
    private lateinit var assistantSpeak: Button
    private lateinit var assistantReviewedResponse: EditText
    private var displayedAssistantTranslation = ""
    private var lastTimelineRefreshMs = 0L
    private lateinit var boardTextInput: EditText
    private lateinit var busDestinationInput: EditText
    private val sectionBodies = mutableMapOf<String, Pair<Button, LinearLayout>>()
    private var assistantPlanKey = ""
    private var assistantTranscriptKey = ""
    private var pendingAssistantBusDestination: String? = null
    private var assistantBusCheckSent = false
    private var pendingAssistantSceneQuestion: String? = null
    private var pendingAssistantText = false
    private var assistantTextInterpretSent = false
    private var localTts: TextToSpeech? = null
    private var localTtsReady = false
    private var displayedTurnId = 0L
    private var displayedTranslation = ""
    private val tripScopedConsents = mutableListOf<CheckBox>()
    private var wasTripActive = false
    private val languageNames = listOf("Telugu", "Malayalam", "English", "Hindi")
    private val languageCodes = listOf("te-IN", "ml-IN", "en-IN", "hi-IN")
    private lateinit var optIn: CheckBox
    private lateinit var cloudConsent: CheckBox
    private var pendingStart = false
    private var pendingMode = "CONTINUOUS"
    private fun requiredPermissions(mode: String): Array<String> =
        (if (mode == "CONTINUOUS") listOf(Manifest.permission.CAMERA) else emptyList())
            .plus(listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION))
            .plus(if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS)
                else emptyList()).toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val outer = ScrollView(this)
        pageScroll = outer
        outer.setBackgroundColor(getColor(R.color.yl_night))
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        outer.setOnApplyWindowInsetsListener { _, insets ->
            val top = if (Build.VERSION.SDK_INT >= 30)
                insets.getInsets(WindowInsets.Type.systemBars()).top else insets.systemWindowInsetTop
            val bottom = if (Build.VERSION.SDK_INT >= 30)
                insets.getInsets(WindowInsets.Type.systemBars()).bottom else insets.systemWindowInsetBottom
            layout.setPadding(32, top + 24, 32, bottom + 32)
            insets
        }
        outer.addView(layout)
        layout.addView(TextView(this).apply {
            text = "YatraLens"
            textSize = 27f
            setTextColor(getColor(R.color.yl_ivory))
        })
        layout.addView(TextView(this).apply {
            text = "Start a trip, then choose what you need. End trip stops camera, mic and location."
            textSize = 15f
            setTextColor(getColor(R.color.yl_sand))
        })
        tripSummary = TextView(this).apply {
            textSize = 16f
            setTextColor(getColor(R.color.yl_ivory))
            background = panelBackground(false)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        layout.addView(tripSummary)
        timelinePreview = TextView(this).apply {
            textSize = 15f
            setTextColor(getColor(R.color.yl_mist))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        layout.addView(timelinePreview)
        offlineNotice = TextView(this).apply {
            textSize = 16f
            setTextColor(getColor(R.color.yl_mist))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        layout.addView(offlineNotice)
        notice = TextView(this).apply {
            textSize = 16f
            setTextColor(getColor(R.color.yl_sand))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        layout.addView(notice)
        addButton(layout, "Use assistant here") { wakeAssistant() }
        addButton(layout, "Set YatraLens as phone assistant") {
            val roles = getSystemService(ROLE_SERVICE) as RoleManager
            if (roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT))
                startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT), 42)
            else notice.text = "This phone does not offer the Android assistant role."
        }
        layout.addView(TextView(this).apply {
            text = "After setup, the phone's assistant gesture opens the bubble while this app is closed. A spoken YatraLens wake phrase is not available yet."
            textSize = 14f
            setTextColor(getColor(R.color.yl_mist))
        })
        assistantPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = panelBackground(false)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        layout.addView(assistantPanel)
        assistantPrompt = TextView(this).apply {
            textSize = 20f
            setTextColor(getColor(R.color.yl_ivory))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setTextIsSelectable(true)
        }
        assistantPanel.addView(assistantPrompt)
        assistantRequest = input(assistantPanel, "Tell YatraLens what you need")
        assistantFollowUp = input(assistantPanel, "Answer the follow-up question").apply {
            visibility = View.GONE
        }
        addButton(assistantPanel, "Speak my request or follow-up") { speakToAssistant() }
        addButton(assistantPanel, "Stop and understand my speech") {
            if (cloudConsent.isChecked) send(TripSensingService.SEND_TURN, true)
            else assistantPrompt.text = "Allow selected speech to use Sarvam first."
        }
        addButton(assistantPanel, "Send typed request or follow-up") { sendAssistantTyped() }
        assistantAction = addButton(assistantPanel, "Do suggested action") { doAssistantAction() }
        assistantAction.visibility = View.GONE
        assistantReviewedResponse = input(assistantPanel, "Review the answer in your language before speaking")
        assistantReviewedResponse.visibility = View.GONE
        assistantSpeak = addButton(assistantPanel, "Speak reviewed answer aloud") {
            val result = FeatureState.get("assistant response")
            val reviewed = assistantReviewedResponse.text.toString().trim()
            if (!cloudConsent.isChecked || result == null || reviewed.isBlank())
                assistantPrompt.text = "Review an answer and allow selected speech to use Sarvam first."
            else startService(Intent(this, TripSensingService::class.java)
                .setAction(TripSensingService.SPEAK_REVIEWED_TEXT)
                .putExtra("cloudApproved", true).putExtra("approvedText", reviewed)
                .putExtra("language", result.optString("target_language")))
        }
        assistantSpeak.visibility = View.GONE
        optIn = CheckBox(this).apply {
            text = "Allow trip sensing while this trip is active"
            textSize = 16f
        }
        layout.addView(optIn)
        tripScopedConsents.add(optIn)
        cloudConsent = CheckBox(this).apply {
            text = "Allow selected speech and text to use Sarvam"
            textSize = 16f
        }
        layout.addView(cloudConsent)
        tripScopedConsents.add(cloudConsent)
        layout.addView(TextView(this).apply { text = "My language"; textSize = 16f })
        myLanguage = languageSpinner(layout, 0)
        layout.addView(TextView(this).apply { text = "Local person's language"; textSize = 16f })
        localLanguage = languageSpinner(layout, 1)
        startCameraButton = addButton(layout, "Start trip with camera") {
            requestStart("CONTINUOUS")
        }
        startTalkButton = addButton(layout, "Start trip for conversation only") {
            requestStart("CONVERSATION")
        }
        endTripButton = addButton(layout, "End trip and stop sensors") {
            send(TripSensingService.END)
            clearTripConsents()
        }

        val talk = section(layout, "Talk & Listen", false)
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
        addButton(talk, "Extract fare and time for review") {
            if (cloudConsent.isChecked) send(TripSensingService.PROPOSE_AGREEMENT, true)
            else notice.text = "Allow selected transcript to use Sarvam first."
        }
        featureOutput(talk, "agreement")
        agreementSubject = input(talk, "This quote concerns which trip or booking?").apply {
            setText("boat trip")
        }
        agreementPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        talk.addView(agreementPanel)
        addButton(talk, "Save reviewed translation for offline use") {
            val turn = ConversationState.current
            val reviewed = translatedTurn.text.toString().trim()
            if (turn?.status != "READY_FOR_REVIEW" || reviewed.isBlank()) {
                notice.text = "Review a translated turn first."
            } else {
                OfflinePhraseStore(this).use { it.saveReviewed(turn.original, reviewed,
                    turn.sourceLanguage, turn.targetLanguage) }
                notice.text = "Reviewed phrase saved on this phone for offline use."
            }
        }
        typedInput = input(talk, "Type a message if speaking is difficult")
        val typedFromLocal = CheckBox(this).apply {
            text = "This text is from the local person"
            textSize = 16f
        }
        talk.addView(typedFromLocal)
        addButton(talk, "Translate typed message") {
            val from = if (typedFromLocal.isChecked) selectedCode(localLanguage) else selectedCode(myLanguage)
            val to = if (typedFromLocal.isChecked) selectedCode(myLanguage) else selectedCode(localLanguage)
            if (!cloudConsent.isChecked || typedInput.text.isBlank())
                notice.text = "Enter a message and allow selected text to use Sarvam."
            else {
                typedTranslation.setText("")
                displayedTypedTranslation = ""
                startService(Intent(this, TripSensingService::class.java)
                .setAction(TripSensingService.TRANSLATE_TYPED).putExtra("cloudApproved", true)
                .putExtra("text", typedInput.text.toString())
                .putExtra("sourceLanguage", from).putExtra("targetLanguage", to))
            }
        }
        featureOutput(talk, "typed translation")
        typedTranslation = input(talk, "Review or edit typed translation")
        addButton(talk, "Speak reviewed typed translation") {
            val result = FeatureState.get("typed translation")
            if (!cloudConsent.isChecked || result == null || typedTranslation.text.isBlank())
                notice.text = "Translate and review the typed message first."
            else startService(Intent(this, TripSensingService::class.java)
                .setAction(TripSensingService.SPEAK_REVIEWED_TEXT)
                .putExtra("cloudApproved", true)
                .putExtra("approvedText", typedTranslation.text.toString())
                .putExtra("language", result.optString("target_language")))
        }
        addButton(talk, "Save reviewed typed phrase offline") {
            val result = FeatureState.get("typed translation")
            val reviewed = typedTranslation.text.toString().trim()
            if (result == null || reviewed.isBlank()) notice.text = "Translate and review a typed message first."
            else {
                OfflinePhraseStore(this).use { it.saveReviewed(result.optString("original_text"),
                    reviewed, result.optString("source_language"), result.optString("target_language")) }
                notice.text = "Reviewed phrase saved offline."
            }
        }

        val read = section(layout, "Read a sign", false)
        addButton(read, "Capture scene on this phone") {
            sceneAnswer.text = "Analyzing selected photo…"
            latestSceneAnswerText = ""
            displayedSceneTranslation = ""
            sceneTranslation.setText("")
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
            latestSceneAnswerText = answer.text
            sceneAnswer.text = "${answer.text}\nSource: selected photo, ${answer.model}" +
                if (answer.cues.isEmpty()) "" else "\nCues: ${answer.cues.joinToString()}"
        }
        sceneAnswer = TextView(this).apply { textSize = 17f; setTextIsSelectable(true) }
        read.addView(sceneAnswer)
        addButton(read, "Translate scene answer for me") {
            if (!cloudConsent.isChecked || latestSceneAnswerText.isBlank())
                notice.text = "Ask about a photo and allow selected text to use Sarvam first."
            else startService(Intent(this, TripSensingService::class.java)
                .setAction(TripSensingService.TRANSLATE_TYPED).putExtra("cloudApproved", true)
                .putExtra("kind", "scene translation").putExtra("text", latestSceneAnswerText)
                .putExtra("sourceLanguage", "en-IN")
                .putExtra("targetLanguage", selectedCode(myLanguage)))
        }
        featureOutput(read, "scene translation")
        sceneTranslation = input(read, "Review or edit scene answer")
        addButton(read, "Speak reviewed scene answer") {
            val result = FeatureState.get("scene translation")
            if (!cloudConsent.isChecked || result == null || sceneTranslation.text.isBlank())
                notice.text = "Translate and review the scene answer first."
            else startService(Intent(this, TripSensingService::class.java)
                .setAction(TripSensingService.SPEAK_REVIEWED_TEXT)
                .putExtra("cloudApproved", true)
                .putExtra("approvedText", sceneTranslation.text.toString())
                .putExtra("language", result.optString("target_language")))
        }
        sceneResult = TextView(this).apply { textSize = 16f; setTextIsSelectable(true) }
        read.addView(sceneResult)
        addButton(read, "Capture text with camera") {
            if (cloudConsent.isChecked) {
                ocrTranslation.setText("")
                displayedOcrTranslation = ""
                startService(Intent(this, TripSensingService::class.java)
                    .setAction(TripSensingService.CAPTURE_TEXT)
                    .putExtra("cloudApproved", true)
                    .putExtra("language", selectedCode(localLanguage)))
            }
            else notice.text = "Allow selected text to use Sarvam first."
        }
        ocrResult = TextView(this).apply {
            textSize = 16f
            maxLines = 6
            setTextIsSelectable(true)
            setOnClickListener { maxLines = if (maxLines == 6) Int.MAX_VALUE else 6 }
        }
        read.addView(ocrResult)
        addButton(read, "Translate captured text for review") {
            if (cloudConsent.isChecked) startService(Intent(this, TripSensingService::class.java)
                .setAction(TripSensingService.INTERPRET_OCR).putExtra("cloudApproved", true)
                .putExtra("targetLanguage", selectedCode(myLanguage)))
            else notice.text = "Allow selected text to use Sarvam first."
        }
        featureOutput(read, "ocr interpretation")
        ocrTranslation = EditText(this).apply {
            hint = "Reviewed translation appears here"
            minLines = 2
            textSize = 17f
        }
        read.addView(ocrTranslation)
        addButton(read, "Speak reviewed sign translation") {
            val text = ocrTranslation.text.toString().trim()
            if (!cloudConsent.isChecked || text.isBlank() || FeatureState.get("ocr interpretation") == null)
                notice.text = "Translate the latest photo, review the result and allow cloud speech first."
            else startService(Intent(this, TripSensingService::class.java)
                .setAction(TripSensingService.SPEAK_REVIEWED_TEXT)
                .putExtra("cloudApproved", true).putExtra("approvedText", text)
                .putExtra("language", selectedCode(myLanguage)))
        }
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
        tripScopedConsents.add(weatherConsent)
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
        weatherResult = TextView(this).apply {
            textSize = 15f
            autoLinkMask = Linkify.WEB_URLS
            movementMethod = LinkMovementMethod.getInstance()
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        weatherSection.addView(weatherResult)
        addButton(weatherSection, "Open official IMD Kerala warnings") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://mausam.imd.gov.in/thiruvananthapuram/")))
            } catch (e: Exception) {
                notice.text = "A browser is needed to view the official warning page."
            }
        }

        val booking = section(layout, "Booking point", false)
        booking.addView(TextView(this).apply {
            text = "Saved booking points and live GPS are approximate. Confirm the name on a sign."
            textSize = 15f
        })
        val locationConsent = CheckBox(this).apply {
            text = "Send my current GPS fix for the selected place check"
            textSize = 16f
        }
        booking.addView(locationConsent)
        tripScopedConsents.add(locationConsent)
        val destinationName = input(booking, "Booked place name")
        val destinationLat = input(booking, "Booking latitude (decimal)").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        val destinationLon = input(booking, "Booking longitude (decimal)").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        val destinationSource = input(booking, "Booking excerpt or source text")
        addButton(booking, "Save booking point on this phone") {
            val lat = destinationLat.text.toString().toDoubleOrNull()
            val lon = destinationLon.text.toString().toDoubleOrNull()
            if (lat == null || lon == null || destinationName.text.isBlank() || destinationSource.text.isBlank())
                notice.text = "Enter the place name, coordinates and booking text."
            else startService(Intent(this, TripSensingService::class.java)
                .setAction(TripSensingService.SAVE_DESTINATION)
                .putExtra("placeName", destinationName.text.toString())
                .putExtra("latitude", lat).putExtra("longitude", lon)
                .putExtra("bookingExcerpt", destinationSource.text.toString()))
        }
        addButton(booking, "Compare current GPS with booking point") {
            if (locationConsent.isChecked) startService(Intent(this, TripSensingService::class.java)
                .setAction(TripSensingService.CHECK_DESTINATION).putExtra("locationCloudApproved", true))
            else notice.text = "Allow this GPS check first."
        }
        featureOutput(booking, "destination")
        addButton(booking, "Open saved point in a map app") {
            val saved = TripMemoryStore(this).use { memory ->
                memory.latestTripId()?.let { memory.latestDestination(it) }
            }
            if (saved == null) notice.text = "Save a booking point first."
            else try {
                val label = Uri.encode(saved.placeName)
                val geo = Uri.parse("geo:${saved.latitude},${saved.longitude}?q=${saved.latitude},${saved.longitude}($label)")
                startActivity(Intent(Intent.ACTION_VIEW, geo))
            } catch (e: Exception) {
                notice.text = "No map app is available for this point."
            }
        }
        val transit = section(layout, "Bus board", false)
        boardTextInput = input(transit, "Bus board text from sign or OCR")
        addButton(transit, "Use last scanned sign text") {
            val scanned = SensorState.getOcrText()
            if (scanned.isBlank()) notice.text = "Scan the bus board in Read a sign first."
            else boardTextInput.setText(scanned)
        }
        busDestinationInput = input(transit, "Where you want to go")
        addButton(transit, "Check bus board") {
            if (cloudConsent.isChecked) startService(Intent(this, TripSensingService::class.java)
                .setAction(TripSensingService.CHECK_TRANSPORT).putExtra("cloudApproved", true)
                .putExtra("boardText", boardTextInput.text.toString())
                .putExtra("destination", busDestinationInput.text.toString()))
            else notice.text = "Allow selected text to use the service first."
        }
        featureOutput(transit, "transport")
        val conductorQuestion = input(transit, "Write a question for the conductor in your language")
        addButton(transit, "Translate question for conductor") {
            if (!cloudConsent.isChecked || conductorQuestion.text.isBlank())
                notice.text = "Write your question and allow selected text to use Sarvam."
            else {
                conductorTranslation.setText("")
                displayedConductorTranslation = ""
                FeatureState.set("transport question", null)
                startService(Intent(this, TripSensingService::class.java)
                    .setAction(TripSensingService.TRANSLATE_TYPED).putExtra("cloudApproved", true)
                    .putExtra("kind", "transport question")
                    .putExtra("text", conductorQuestion.text.toString())
                    .putExtra("sourceLanguage", selectedCode(myLanguage))
                    .putExtra("targetLanguage", selectedCode(localLanguage)))
            }
        }
        featureOutput(transit, "transport question")
        conductorTranslation = input(transit, "Review the local-language question")
        addButton(transit, "Speak reviewed question to conductor") {
            val result = FeatureState.get("transport question")
            if (!cloudConsent.isChecked || result == null || conductorTranslation.text.isBlank())
                notice.text = "Translate and review your conductor question first."
            else startService(Intent(this, TripSensingService::class.java)
                .setAction(TripSensingService.SPEAK_REVIEWED_TEXT)
                .putExtra("cloudApproved", true)
                .putExtra("approvedText", conductorTranslation.text.toString())
                .putExtra("language", result.optString("target_language")))
        }
        val explore = section(layout, "Culture & local craft", false)
        val exploreConsent = CheckBox(this).apply {
            text = "Send my current GPS fix for this lookup"
            textSize = 16f
        }
        explore.addView(exploreConsent)
        tripScopedConsents.add(exploreConsent)
        val landmark = input(explore, "Temple or landmark name from a sign")
        addButton(explore, "Use last scanned sign as name hint") {
            val scanned = SensorState.getOcrText()
            if (scanned.isBlank()) notice.text = "Scan a landmark sign first."
            else landmark.setText(scanned.take(120))
        }
        addButton(explore, "Look up landmark") {
            if (exploreConsent.isChecked) startService(Intent(this, TripSensingService::class.java)
                .setAction(TripSensingService.LOOKUP_CULTURE).putExtra("locationCloudApproved", true)
                .putExtra("nameHint", landmark.text.toString()))
            else notice.text = "Allow this GPS check first."
        }
        featureOutput(explore, "culture")
        val interest = input(explore, "Local craft or interest, e.g. coir")
        addButton(explore, "Find sourced local experience") {
            if (exploreConsent.isChecked) startService(Intent(this, TripSensingService::class.java)
                .setAction(TripSensingService.DISCOVER_LOCAL).putExtra("locationCloudApproved", true)
                .putExtra("interest", interest.text.toString()))
            else notice.text = "Allow this GPS check first."
        }
        featureOutput(explore, "discovery")

        val evidence = section(layout, "Trip memory", false)
        evidence.addView(TextView(this).apply {
            text = "Context over time • newest first"
            textSize = 17f
        })
        timelineItems = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        evidence.addView(timelineItems)
        addButton(evidence, "View saved evidence and conflicts") { showTripMemory() }
        savedEvidence = TextView(this).apply { textSize = 15f; setTextIsSelectable(true) }
        evidence.addView(savedEvidence)
        evidenceItems = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        evidence.addView(evidenceItems)
        conflictItems = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        evidence.addView(conflictItems)
        addButton(evidence, "Show offline phrases") { showOfflinePhrases() }
        phraseItems = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        evidence.addView(phraseItems)

        val offlineHelp = section(layout, "Offline help", false)
        offlineHelp.addView(TextView(this).apply {
            text = "Saved emergency references for Kerala. Confirm the number shown in the dialer before calling. Last checked ${OfflineHelp.REVIEWED_ON}.\nSource: ${OfflineHelp.SOURCE}"
            textSize = 15f
            autoLinkMask = Linkify.WEB_URLS
            movementMethod = LinkMovementMethod.getInstance()
        })
        for (contact in OfflineHelp.contacts) {
            addButton(offlineHelp, "Dial ${contact.label}: ${contact.number}") {
                try {
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", contact.number, null)))
                } catch (e: Exception) {
                    notice.text = "A phone dialer is unavailable on this device."
                }
            }
        }

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
        handleAssistantLaunch(intent)
        localTts = TextToSpeech(this) { status -> localTtsReady = status == TextToSpeech.SUCCESS }
        outer.post {
            if (Build.VERSION.SDK_INT >= 30) {
                outer.windowInsetsController?.setSystemBarsAppearance(
                    0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = 0
            }
        }
        handler.post(refresh)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAssistantLaunch(intent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42) notice.text = if (resultCode == RESULT_OK)
            "YatraLens is selected as the phone assistant. Use the phone's assistant gesture to summon the bubble."
        else "YatraLens was not selected as the phone assistant."
    }

    private fun handleAssistantLaunch(launch: Intent?) {
        val requestText = launch?.getStringExtra(EXTRA_ASSISTANT_REQUEST).orEmpty()
        if (requestText.isBlank()) return
        wakeAssistant()
        assistantRequest.setText(requestText)
        assistantFollowUp.setText(launch?.getStringExtra(EXTRA_ASSISTANT_FOLLOW_UP).orEmpty())
        val proposed = launch?.getStringExtra(EXTRA_ASSISTANT_ACTION).orEmpty()
        if (proposed.isNotBlank()) {
            FeatureState.set("assistant plan", JSONObject()
                .put("status", "READY").put("action", proposed)
                .put("destination", launch?.getStringExtra(EXTRA_ASSISTANT_DESTINATION).orEmpty()))
            assistantPrompt.text = "Review the suggested task. Start a trip and approve any required sensor or cloud use."
            assistantAction.visibility = View.VISIBLE
        }
        launch?.removeExtra(EXTRA_ASSISTANT_REQUEST)
    }

    private fun section(parent: LinearLayout, title: String, initiallyOpen: Boolean): LinearLayout {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (initiallyOpen) View.VISIBLE else View.GONE
        }
        val heading = Button(this).apply {
            text = (if (initiallyOpen) "▾ " else "▸ ") + title
            isAllCaps = false
            textSize = 16f
            setTextColor(getColor(R.color.yl_ivory))
            background = panelBackground(false)
            minHeight = dp(54)
            setOnClickListener {
                val open = body.visibility != View.VISIBLE
                body.visibility = if (open) View.VISIBLE else View.GONE
                text = (if (open) "▾ " else "▸ ") + title
            }
        }
        parent.addView(heading, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(6)
        })
        parent.addView(body)
        sectionBodies[title] = heading to body
        return body
    }

    private fun speakLocalPrompt(message: String) {
        val speech = localTts ?: return
        if (!localTtsReady) return
        val locale = Locale.forLanguageTag(selectedCode(myLanguage))
        if (speech.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) {
            speech.language = locale
            speech.speak(message, TextToSpeech.QUEUE_FLUSH, null, "yatralens-prompt")
        }
    }

    private fun wakeAssistant() {
        assistantPanel.visibility = View.VISIBLE
        assistantPrompt.text = "What do you need help with? Speak a short request or type it below."
        assistantRequest.setText("")
        assistantFollowUp.setText("")
        assistantFollowUp.visibility = View.GONE
        assistantAction.visibility = View.GONE
        assistantReviewedResponse.visibility = View.GONE
        assistantReviewedResponse.setText("")
        displayedAssistantTranslation = ""
        assistantSpeak.visibility = View.GONE
        FeatureState.set("assistant plan", null)
        FeatureState.set("assistant transcript", null)
        FeatureState.set("assistant response", null)
        assistantPlanKey = ""
        assistantTranscriptKey = ""
        speakLocalPrompt("What do you need help with?")
    }

    private fun speakToAssistant() {
        if (!cloudConsent.isChecked) {
            assistantPrompt.text = "Allow selected speech and text to use Sarvam first."
            return
        }
        if (PhysicalContextEngine.situation.value.tripId == null) {
            assistantPrompt.text = "Start a trip first, then speak your request."
            return
        }
        localTts?.stop()
        assistantPlanKey = ""
        assistantAction.visibility = View.GONE
        val origin = if (assistantFollowUp.visibility == View.VISIBLE)
            assistantRequest.text.toString().trim() else ""
        startService(Intent(this, TripSensingService::class.java)
            .setAction(TripSensingService.START_TURN)
            .putExtra("assistant", true).putExtra("owner", "TRAVELLER")
            .putExtra("originalRequest", origin)
            .putExtra("sourceLanguage", selectedCode(myLanguage))
            .putExtra("targetLanguage", selectedCode(myLanguage)))
        assistantPrompt.text = "Listening. Describe the issue, then tap Stop and understand my speech."
    }

    private fun sendAssistantTyped() {
        val request = assistantRequest.text.toString().trim()
        val answer = assistantFollowUp.text.toString().trim()
        if (request.isBlank() || !cloudConsent.isChecked) {
            assistantPrompt.text = "Enter your request and allow selected text to use Sarvam."
            return
        }
        if (PhysicalContextEngine.situation.value.tripId == null) {
            assistantPrompt.text = "Start a trip first."
            return
        }
        assistantPlanKey = ""
        assistantAction.visibility = View.GONE
        assistantPrompt.text = "Working out the next step…"
        startService(Intent(this, TripSensingService::class.java)
            .setAction(TripSensingService.PLAN_ASSISTANT_TEXT)
            .putExtra("cloudApproved", true)
            .putExtra("utterance", request)
            .putExtra("followUpAnswer", answer)
            .putExtra("language", selectedCode(myLanguage)))
    }

    private fun openSection(title: String) {
        val (heading, body) = sectionBodies[title] ?: return
        body.visibility = View.VISIBLE
        heading.text = "▾ $title"
        pageScroll.post { pageScroll.smoothScrollTo(0, heading.top) }
    }

    private fun requestAssistantTranslation(text: String) {
        if (text.isBlank() || !cloudConsent.isChecked) return
        FeatureState.set("assistant response", null)
        assistantReviewedResponse.setText("")
        displayedAssistantTranslation = ""
        startService(Intent(this, TripSensingService::class.java)
            .setAction(TripSensingService.TRANSLATE_TYPED)
            .putExtra("cloudApproved", true).putExtra("kind", "assistant response")
            .putExtra("text", text.take(2000)).putExtra("sourceLanguage", "en-IN")
            .putExtra("targetLanguage", selectedCode(myLanguage)))
    }

    private fun doAssistantAction() {
        val plan = FeatureState.get("assistant plan") ?: return
        if (plan.optString("status") != "READY") return
        val active = PhysicalContextEngine.situation.value.tripId != null
        if (!active) { assistantPrompt.text = "Start a trip first."; return }
        val action = plan.optString("action")
        when (action) {
            "CAPTURE_BUS_BOARD" -> {
                val destination = plan.optString("destination")
                if (!cloudConsent.isChecked || destination.isBlank() ||
                    !PhysicalContextEngine.fresh(SensorKind.CAMERA, 15_000)) {
                    assistantPrompt.text = "Start a camera trip, point at the bus board, and allow selected text to use Sarvam."
                    return
                }
                pendingAssistantBusDestination = destination
                assistantBusCheckSent = false
                busDestinationInput.setText(destination)
                SensorState.setOcrText("")
                FeatureState.set("transport", null)
                assistantPrompt.text = "Reading the pointed bus board. Keep it steady."
                startService(Intent(this, TripSensingService::class.java)
                    .setAction(TripSensingService.CAPTURE_TEXT)
                    .putExtra("cloudApproved", true)
                    .putExtra("language", selectedCode(localLanguage)))
            }
            "CAPTURE_SCENE" -> {
                if (!PhysicalContextEngine.fresh(SensorKind.CAMERA, 15_000)) {
                    assistantPrompt.text = "Start a camera trip and point at the scene first."
                    return
                }
                pendingAssistantSceneQuestion = assistantRequest.text.toString().trim()
                SceneState.set(null)
                assistantPrompt.text = "Analyzing the selected scene on this phone."
                send(TripSensingService.CAPTURE_SCENE)
            }
            "CAPTURE_TEXT" -> {
                if (!cloudConsent.isChecked || !PhysicalContextEngine.fresh(SensorKind.CAMERA, 15_000)) {
                    assistantPrompt.text = "Start a camera trip, point at the text, and allow selected text to use Sarvam."
                    return
                }
                pendingAssistantText = true
                assistantTextInterpretSent = false
                SensorState.setOcrText("")
                FeatureState.set("ocr interpretation", null)
                assistantPrompt.text = "Reading selected text."
                startService(Intent(this, TripSensingService::class.java)
                    .setAction(TripSensingService.CAPTURE_TEXT)
                    .putExtra("cloudApproved", true)
                    .putExtra("language", selectedCode(localLanguage)))
            }
            "OPEN_TALK" -> { openSection("Talk & Listen"); assistantPrompt.text = "Choose whose turn to record, then review the translation before speaking it." }
            "OPEN_BOOKING" -> { openSection("Booking point"); assistantPrompt.text = "Review the saved booking point and its source before comparing GPS." }
            "OPEN_WEATHER" -> { openSection("Weather for my plan"); assistantPrompt.text = "Enter the activity and time, approve this location check, then check the forecast and IMD warning." }
            "OPEN_CULTURE", "OPEN_DISCOVERY" -> { openSection("Culture & local craft"); assistantPrompt.text = "Give a landmark or craft hint and approve this location check before looking it up." }
            "OPEN_MEMORY" -> { openSection("Trip memory"); showTripMemory(); assistantPrompt.text = "Here are your saved sources and any disagreements. Review the original words before relying on a detail." }
            "OPEN_OFFLINE" -> { openSection("Offline help"); assistantPrompt.text = "Saved evidence, phrases, booking points and emergency references remain available offline." }
            "OPEN_ACCESSIBILITY" -> { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); assistantPrompt.text = "Android accessibility settings are open. Return here to continue." }
            "OPEN_SENSOR_CONTROLS" -> { openSection("Sensor controls & diagnostics"); assistantPrompt.text = "Current sensor states and controls are shown below." }
            else -> assistantPrompt.text = "Please describe one travel task, such as reading a sign or checking a bus."
        }
        assistantAction.visibility = View.GONE
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun panelBackground(primary: Boolean): GradientDrawable = GradientDrawable().apply {
        setColor(getColor(if (primary) R.color.yl_mist else R.color.yl_panel))
        cornerRadius = dp(12).toFloat()
        setStroke(dp(1), getColor(if (primary) R.color.yl_mist else R.color.yl_line))
    }

    private fun addButton(layout: LinearLayout, label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            val primary = label == "Wake YatraLens" || label == "Do suggested action"
            background = panelBackground(primary)
            setTextColor(getColor(if (primary) R.color.yl_night else R.color.yl_ivory))
            textSize = 16f
            minHeight = dp(56)
            setOnClickListener { action() }
        }.also { layout.addView(it, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
    }

    private fun input(layout: LinearLayout, hint: String): EditText = EditText(this).also {
        it.hint = hint
        it.textSize = 16f
        it.setTextColor(getColor(R.color.yl_ivory))
        it.setHintTextColor(getColor(R.color.yl_mist))
        it.backgroundTintList = ColorStateList.valueOf(getColor(R.color.yl_sage))
        layout.addView(it)
    }

    private fun clearTripConsents() {
        tripScopedConsents.forEach { it.isChecked = false }
    }

    private fun featureOutput(layout: LinearLayout, kind: String) {
        val output = TextView(this).apply {
            textSize = 16f
            autoLinkMask = Linkify.WEB_URLS
            movementMethod = LinkMovementMethod.getInstance()
        }
        featureOutputs[kind] = output
        layout.addView(output)
    }

    private fun renderAgreementProposals(result: JSONObject?) {
        val key = result?.toString().orEmpty()
        if (key == displayedAgreementKey) return
        displayedAgreementKey = key
        agreementPanel.removeAllViews()
        if (result == null) return
        val turnId = result.optLong("turn_id", -1L)
        val proposals = result.optJSONArray("proposals") ?: return
        for (index in 0 until proposals.length()) {
            val proposal = proposals.optJSONObject(index) ?: continue
            val kind = proposal.optString("kind")
            val excerpt = proposal.optString("raw_excerpt")
            val value = input(agreementPanel, "Review $kind from '$excerpt'").apply {
                setText(proposal.optString("normalized_candidate"))
            }
            addButton(agreementPanel, "Save this as an unconfirmed $kind") {
                val evidenceId = ReviewedEvidenceState.evidenceId
                val tripId = TripMemoryStore(this).use { it.latestTripId() }
                val subject = agreementSubject.text.toString().trim()
                if (turnId != ReviewedEvidenceState.turnId || evidenceId == null || tripId == null) {
                    notice.text = "Save the reviewed transcript for this turn first."
                    return@addButton
                }
                if (subject.isBlank() || value.text.isBlank()) {
                    notice.text = "Enter the trip name and review this value."
                    return@addButton
                }
                val predicate = when (kind) {
                    "fare_inr" -> "price"
                    "return_time_text" -> "arrival_time"
                    else -> kind
                }
                try {
                    val saved = TripMemoryStore(this).use { it.claimFromEvidence(tripId, evidenceId,
                        subject, predicate, excerpt, value.text.toString().trim()) }
                    notice.text = if (saved.conflictId == null) "Saved as an unconfirmed, sourced detail."
                        else "Saved. Two sources disagree; review the conflict in Trip memory."
                } catch (e: Exception) {
                    notice.text = "Could not save this detail: ${e.javaClass.simpleName}"
                }
            }
        }
    }

    private fun showTripMemory() {
        timelineItems.removeAllViews()
        evidenceItems.removeAllViews()
        conflictItems.removeAllViews()
        val timelineEntries = TripMemoryStore(this).use { it.recentTimeline(50) }
        val (tripId, entries, conflicts) = TripMemoryStore(this).use { memory ->
            val id = memory.latestTripId()
            Triple(id, if (id == null) emptyList() else memory.recentEvidenceItems(id),
                if (id == null) emptyList() else memory.openConflicts(id))
        }
        for (item in timelineEntries) timelineItems.addView(TextView(this).apply {
            text = "${item.observedAt} • ${item.title}\n${item.detail}\nSource: ${item.source}"
            textSize = 15f
            setTextColor(getColor(R.color.yl_ivory))
            setTextIsSelectable(true)
            background = panelBackground(false)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        })
        val savedDestination = tripId?.let { id -> TripMemoryStore(this).use { it.latestDestination(id) } }
        savedEvidence.text = if (tripId == null) "No trip memory yet" else
            "${entries.size} recent evidence items • ${conflicts.size} unresolved conflicts. Saved text is available offline." +
                (savedDestination?.let {
                    "\nBooking point: ${it.placeName} (${it.latitude}, ${it.longitude}); user entered, unverified."
                } ?: "")
        for (entry in entries) {
            evidenceItems.addView(TextView(this).apply {
                text = "${entry.title} • ${entry.observedAt}\n${entry.excerpt}\nSource: ${entry.provider}"
                textSize = 15f
                setTextIsSelectable(true)
            })
            addButton(evidenceItems, "Delete this evidence and derived facts") {
                AlertDialog.Builder(this)
                    .setTitle("Delete saved evidence?")
                    .setMessage("This also removes facts and conflict decisions derived from this item.")
                    .setNegativeButton("Keep", null)
                    .setPositiveButton("Delete") { _, _ ->
                        TripMemoryStore(this).use { it.deleteEvidenceCascade(tripId!!, entry.id) }
                        showTripMemory()
                    }.show()
            }
        }
        for (card in conflicts) {
            conflictItems.addView(TextView(this).apply {
                text = "Conflict: ${card.kind.replace('_', ' ')}. Review both sources before a reminder."
                textSize = 17f
            })
            card.claims.forEachIndexed { index, claim ->
                conflictItems.addView(TextView(this).apply {
                    text = "${claim.value}\n${claim.sourceTitle} • ${claim.observedAt}\n${claim.excerpt}"
                    textSize = 15f
                    setTextIsSelectable(true)
                })
                addButton(conflictItems, "I confirmed this source") {
                    AlertDialog.Builder(this)
                        .setTitle("Confirm this trip detail?")
                        .setMessage("Use '${claim.value}' from ${claim.sourceTitle}? Keep the other evidence for the audit trail.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Confirm") { _, _ ->
                            TripMemoryStore(this).use { it.resolveConflict(card.conflictId,
                                if (index == 0) "KEEP_OLD" else "CONFIRM_NEW", claim.claimId,
                                "Traveller confirmed selected source in the app") }
                            showTripMemory()
                        }.show()
                }
            }
            addButton(conflictItems, "Ask operator; leave unresolved") {
                TripMemoryStore(this).use { it.resolveConflict(card.conflictId, "ASK_OPERATOR", null,
                    "Traveller chose to ask the operator before deciding") }
                notice.text = "Conflict remains open until confirmed."
            }
        }
    }

    private fun showOfflinePhrases() {
        phraseItems.removeAllViews()
        val phrases = OfflinePhraseStore(this).use { it.recent() }
        if (phrases.isEmpty()) {
            phraseItems.addView(TextView(this).apply { text = "No reviewed phrases saved yet"; textSize = 16f })
            return
        }
        for (phrase in phrases) {
            phraseItems.addView(TextView(this).apply {
                text = "${phrase.original}\n→ ${phrase.translation}\nSaved ${phrase.reviewedAt}"
                textSize = 16f
                setTextIsSelectable(true)
            })
            addButton(phraseItems, "Delete this offline phrase") {
                OfflinePhraseStore(this).use { it.delete(phrase.id) }
                showOfflinePhrases()
            }
        }
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
            if (android.os.SystemClock.elapsedRealtime() - lastTimelineRefreshMs > 10_000) {
                lastTimelineRefreshMs = android.os.SystemClock.elapsedRealtime()
                try {
                    val recent = TripMemoryStore(this@MainActivity).use { it.recentTimeline(3) }
                    timelinePreview.text = if (recent.isEmpty()) "No saved trip context yet" else
                        "Recent context\n" + recent.joinToString("\n") {
                            "${it.observedAt.take(16).replace('T', ' ')} • ${it.title}"
                        }
                } catch (_: Exception) { timelinePreview.text = "Trip context unavailable" }
            }
            val assistantTranscript = FeatureState.get("assistant transcript")
            val transcriptKey = assistantTranscript?.toString().orEmpty()
            if (transcriptKey.isNotBlank() && transcriptKey != assistantTranscriptKey) {
                assistantTranscriptKey = transcriptKey
                val transcriptText = assistantTranscript!!.optString("text")
                if (assistantTranscript.optString("role") == "follow_up")
                    assistantFollowUp.setText(transcriptText)
                else assistantRequest.setText(transcriptText)
            }
            val plan = FeatureState.get("assistant plan")
            val planKey = plan?.toString().orEmpty()
            if (planKey.isNotBlank() && planKey != assistantPlanKey) {
                assistantPlanKey = planKey
                assistantPanel.visibility = View.VISIBLE
                if (plan!!.optString("status") == "NEEDS_FOLLOW_UP") {
                    val question = plan.optString("follow_up_question")
                    assistantPrompt.text = question
                    assistantFollowUp.visibility = View.VISIBLE
                    assistantAction.visibility = View.GONE
                    speakLocalPrompt(question)
                } else {
                    val next = when (plan.optString("action")) {
                        "CAPTURE_BUS_BOARD" -> "Point the camera at the bus board. I can read it and check the available route evidence."
                        "CAPTURE_SCENE" -> "Point the camera at the scene. I can analyze the selected photo."
                        "CAPTURE_TEXT" -> "Point the camera at the text. I can read and translate it."
                        else -> "I found the relevant task. Review the suggested action below."
                    }
                    assistantPrompt.text = next
                    assistantFollowUp.visibility = View.GONE
                    assistantAction.visibility = View.VISIBLE
                }
            }
            val assistantTranslation = FeatureState.get("assistant response")
            if (assistantTranslation != null) {
                val latest = assistantTranslation.optString("translated_text")
                if (latest.isNotBlank() && latest != displayedAssistantTranslation) {
                    displayedAssistantTranslation = latest
                    assistantReviewedResponse.visibility = View.VISIBLE
                    assistantSpeak.visibility = View.VISIBLE
                    assistantReviewedResponse.setText(latest)
                }
            }
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
            val pendingBus = pendingAssistantBusDestination
            if (pendingBus != null) {
                if (!assistantBusCheckSent && ocr.isNotBlank()) {
                    assistantBusCheckSent = true
                    boardTextInput.setText(ocr)
                    assistantPrompt.text = "Checking the recognized board against available route evidence."
                    startService(Intent(this@MainActivity, TripSensingService::class.java)
                        .setAction(TripSensingService.CHECK_TRANSPORT)
                        .putExtra("cloudApproved", cloudConsent.isChecked)
                        .putExtra("boardText", ocr).putExtra("destination", pendingBus))
                } else if (assistantBusCheckSent) {
                    FeatureState.get("transport")?.let { result ->
                        val explanation = FeaturePresenter.describe("transport", result)
                        assistantPrompt.text = explanation
                        requestAssistantTranslation(explanation)
                        pendingAssistantBusDestination = null
                        openSection("Bus board")
                    }
                }
            }
            val pendingScene = pendingAssistantSceneQuestion
            if (pendingScene != null) {
                SceneState.current?.let { scene ->
                    val answer = SceneAnswerEngine.answer(scene, SceneState.capturedAt, pendingScene)
                    latestSceneAnswerText = answer.text
                    sceneAnswer.text = "${answer.text}\nSource: selected photo, ${answer.model}"
                    assistantPrompt.text = sceneAnswer.text
                    requestAssistantTranslation(answer.text)
                    pendingAssistantSceneQuestion = null
                    openSection("Read a sign")
                }
            }
            if (pendingAssistantText) {
                if (!assistantTextInterpretSent && ocr.isNotBlank()) {
                    assistantTextInterpretSent = true
                    assistantPrompt.text = "Translating the recognized text for review."
                    startService(Intent(this@MainActivity, TripSensingService::class.java)
                        .setAction(TripSensingService.INTERPRET_OCR)
                        .putExtra("cloudApproved", cloudConsent.isChecked)
                        .putExtra("targetLanguage", selectedCode(myLanguage)))
                } else if (assistantTextInterpretSent) {
                    FeatureState.get("ocr interpretation")?.let { result ->
                        assistantPrompt.text = FeaturePresenter.describe("ocr interpretation", result)
                        val translated = result.optString("translated_text")
                        if (translated.isNotBlank()) {
                            FeatureState.set("assistant response", JSONObject()
                                .put("translated_text", translated)
                                .put("target_language", selectedCode(myLanguage)))
                        }
                        pendingAssistantText = false
                        openSection("Read a sign")
                    }
                }
            }
            val interpretation = FeatureState.get("ocr interpretation")
            val typed = FeatureState.get("typed translation")
            if (typed != null) {
                val latest = typed.optString("translated_text")
                if (latest.isNotBlank() && latest != displayedTypedTranslation) {
                    displayedTypedTranslation = latest
                    typedTranslation.setText(latest)
                }
            }
            val sceneTranslated = FeatureState.get("scene translation")
            if (sceneTranslated != null) {
                val latest = sceneTranslated.optString("translated_text")
                if (latest.isNotBlank() && latest != displayedSceneTranslation) {
                    displayedSceneTranslation = latest
                    sceneTranslation.setText(latest)
                }
            }
            val conductorTranslated = FeatureState.get("transport question")
            if (conductorTranslated != null) {
                val latest = conductorTranslated.optString("translated_text")
                if (latest.isNotBlank() && latest != displayedConductorTranslation) {
                    displayedConductorTranslation = latest
                    conductorTranslation.setText(latest)
                }
            }
            if (interpretation != null) {
                val latestTranslation = interpretation.optString("translated_text")
                if (latestTranslation.isNotBlank() && latestTranslation != displayedOcrTranslation) {
                    displayedOcrTranslation = latestTranslation
                    ocrTranslation.setText(latestTranslation)
                }
            }
            for ((kind, output) in featureOutputs) {
                val result = FeatureState.get(kind)
                val rendered = result?.let { FeaturePresenter.describe(kind, it) }
                    ?: SensorState.get(kind).let { if (it == "not started") "" else it }
                if (output.text.toString() != rendered) output.text = rendered
            }
            renderAgreementProposals(FeatureState.get("agreement"))
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
            } else "OCR text (tap to expand):\n$ocr\nImage regions (normalized crop coordinates):\n${SensorState.get("ocr regions")}"
            if (ocrResult.text.toString() != shown) ocrResult.text = shown
            val weather = WeatherState.current
            val weatherText = if (weather == null) {
                val state = SensorState.get("weather")
                if (state == "not started") "" else state
            } else buildString {
                append("${weather.decision}: ${weather.reason}")
                append("\nRain probability up to ${weather.rainPercent ?: "unknown"}% • Gusts up to ${weather.gustKmh ?: "unknown"} km/h")
                append("\nForecast checked ${weather.retrievedAt}\nForecast source: ${weather.forecastSourceUrl}")
                if (weather.officialWarningStatus == "available")
                    append("\nIMD Kerala subdivision issue date: ${weather.officialWarningIssueDate}\nIMD source: https://mausam.imd.gov.in/imd_latest/contents/subdivisionwise-warning_mc.php?id=4")
                else if (weather.officialWarningStatus == "unavailable")
                    append("\nOfficial Kerala warning check unavailable; open IMD manually.")
            }
            if (weatherResult.text.toString() != weatherText) weatherResult.text = weatherText
            val tripText = "Trip: ${SensorState.get("trip")}\nCamera: ${SensorState.get("camera")} • Mic: ${SensorState.get("microphone")}\nLocation: ${SensorState.get("location")}"
            if (tripSummary.text.toString() != tripText) tripSummary.text = tripText
            val connectivity = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            val online = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            val offlineText = if (online) "" else
                "Offline: saved trip memory, phrases and emergency references are available. Live checks need a connection."
            if (offlineNotice.text.toString() != offlineText) offlineNotice.text = offlineText
            val active = PhysicalContextEngine.situation.value.tripId != null
            if (wasTripActive && !active) clearTripConsents()
            wasTripActive = active
            startCameraButton.visibility = if (active) View.GONE else View.VISIBLE
            startTalkButton.visibility = if (active) View.GONE else View.VISIBLE
            endTripButton.visibility = if (active) View.VISIBLE else View.GONE
            status.text = SensorState.snapshot() + "\nPersisted health events: " + EventStore(this@MainActivity).count()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        localTts?.stop()
        localTts?.shutdown()
        super.onDestroy()
    }
}

