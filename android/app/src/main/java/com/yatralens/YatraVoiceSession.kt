package com.yatralens

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.provider.Settings
import android.service.voice.VoiceInteractionSession
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.media.MediaPlayer
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowInsets
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.TextView
import android.widget.ScrollView
import android.webkit.WebView
import org.json.JSONObject
import java.time.Instant
import java.time.Duration
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.io.File
import java.util.concurrent.Executors

/** System-hosted assistant surface; invoked while the main app is closed. */
@android.annotation.SuppressLint("SuspiciousIndentation")
class YatraVoiceSession(private val host: android.content.Context) : VoiceInteractionSession(host) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var speechPlayer: MediaPlayer? = null
    private var speechTemp: File? = null
    private lateinit var prompt: TextView
    private lateinit var micStatus: TextView
    private lateinit var contextPreview: TextView
    private lateinit var pulseDot: TextView
    private lateinit var request: EditText
    private lateinit var answer: EditText
    private lateinit var action: Button
    private lateinit var listenButton: Button
    private lateinit var detailsPanel: LinearLayout
    private lateinit var detailsToggle: Button
    private lateinit var fullAnswerToggle: Button
    private lateinit var cloud: CheckBox
    private lateinit var sensorConsent: CheckBox
    private lateinit var shareConsent: CheckBox
    private lateinit var speechConsent: CheckBox
    private lateinit var localLanguage: Spinner
    private lateinit var taskInput: EditText
    private lateinit var weatherStart: EditText
    private lateinit var weatherDuration: EditText
    private lateinit var placeName: EditText
    private lateinit var placeLat: EditText
    private lateinit var placeLon: EditText
    private lateinit var placeSource: EditText
    private lateinit var taskControls: LinearLayout
    private lateinit var dynamicControls: LinearLayout
    private lateinit var output: TextView
    private lateinit var resultTitle: TextView
    private lateinit var resultDetails: TextView
    private lateinit var resultSource: TextView
    private lateinit var conversationBoard: LinearLayout
    private lateinit var localTranscriptView: TextView
    private lateinit var localReplyView: TextView
    private lateinit var travellerTranscriptView: TextView
    private lateinit var travellerReplyView: TextView
    private var localReplySpeech = ""
    private var travellerReplySpeech = ""
    private lateinit var mapHolder: LinearLayout
    private var mapView: WebView? = null
    private lateinit var imageHolder: LinearLayout
    private lateinit var imagePreview: ImageView
    private lateinit var imageCaption: TextView
    private lateinit var reviewed: EditText
    private lateinit var editWording: Button
    private lateinit var speakResult: Button
    private lateinit var stopSpeech: Button
    private lateinit var saveResult: Button
    private lateinit var stopTrip: Button
    private lateinit var language: Spinner
    private var planAction = ""
    private var destination = ""
    private var lastVisionDescription = ""
    private var sessionVisible = false
    private var taskStage = ""
    private var stageStartedMs = 0L
    private var recordOwner = "TRAVELLER"
    private var sessionStartedTrip = false
    private var reviewLanguage = "en-IN"
    private var saveKind = ""
    private var conversationControlsAdded = false
    private var pendingConversationOwner: String? = null
    private var agreementControls: LinearLayout? = null
    private var sensorControlsAdded = false
    private var voiceFlow = true
    private var planInFlight = false
    private var planSerial = 0L
    private var planningUtterance = ""
    private var autoConversation = false
    private var typedConversationMode = false
    private var nextConversationOwner = "LOCAL"
    private var awaitingPlannerAnswer = false
    private var awaitingSpeechReview = false
    private var announcedTurnOwner: String? = null
    private var micPulse: ObjectAnimator? = null
    private var lastRecordingSecond = -1
    private var awaitingBookingName = false
    private var awaitingBookingHere = false
    private var bookingLocationConfirmed = false
    private var weatherIsCurrent = false
    private var speechSerial = 0L
    private var reviewedSpeechSerial = 0L
    private var listenEpoch = 0L
    private var recognitionFailures = 0
    private var usePhoneSpeechLocale = false
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
        val recordingRow = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(host.getColor(R.color.yl_night))
                cornerRadius = dp(16).toFloat()
            }
        }
        pulseDot = TextView(host).apply {
            text = "●"
            textSize = 24f
            setTextColor(host.getColor(R.color.yl_sage))
        }
        recordingRow.addView(pulseDot)
        micStatus = TextView(host).apply {
            text = "MIC OFF · Assistant waking"
            textSize = 15f
            setTextColor(host.getColor(R.color.yl_ivory))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
            setPadding(dp(10), 0, 0, 0)
        }
        recordingRow.addView(micStatus)
        card.addView(recordingRow)
        contextPreview = TextView(host).apply {
            textSize = 13f
            setTextColor(host.getColor(R.color.yl_mist))
        }
        card.addView(contextPreview)
        refreshContextPreview()
        detailsPanel = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val languageSettings = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        button(detailsPanel, "Change languages") {
            languageSettings.visibility = if (languageSettings.visibility == View.GONE) View.VISIBLE else View.GONE
        }
        detailsPanel.addView(languageSettings)
        languageSettings.addView(TextView(host).apply {
            text = "My language"
            setTextColor(host.getColor(R.color.yl_mist))
        })
        language = Spinner(host).also {
            it.adapter = object : ArrayAdapter<String>(host, android.R.layout.simple_spinner_item,
                listOf("Tengglish (Telugu + English)", "Malayalam", "English", "Hindi")) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    super.getView(position, convertView, parent).also { view ->
                        (view as? TextView)?.setTextColor(host.getColor(R.color.yl_ivory))
                    }
            }
            it.setSelection(host.getSharedPreferences("assistant_languages", 0)
                .getInt("traveller", 0).coerceIn(0, codes.lastIndex))
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
        detailsPanel.addView(request)
        answer = EditText(host).apply {
            hint = "Your answer"
            setTextColor(host.getColor(R.color.yl_ivory))
            setHintTextColor(host.getColor(R.color.yl_mist))
            visibility = View.GONE
        }
        detailsPanel.addView(answer)
        listenButton = button(card, "🎙  Talk to YatraLens") {
            recognitionFailures = 0
            listen()
        }
        cloud = CheckBox(host).apply {
            text = "Use cloud AI for selected text, translation and speech"
            setTextColor(host.getColor(R.color.yl_sand))
            setOnCheckedChangeListener { _, checked ->
                if (checked && sessionVisible && request.text.isNotBlank() && planAction.isBlank() && !planInFlight)
                    plan()
            }
        }
        card.addView(cloud)
        button(detailsPanel, "Send typed request") {
            voiceFlow = true
            typedConversationMode = true
            plan()
        }
        action = button(detailsPanel, "Continue with this task") { continueTask() }
        action.visibility = View.GONE
        stopTrip = button(card, "End trip and stop sensors") {
            send(TripSensingService.END)
            sessionStartedTrip = false
            autoConversation = false
            taskStage = ""
            main.removeCallbacks(pollTask)
            stopTrip.visibility = View.GONE
            if (output.text.isNullOrBlank()) output.text = "Trip sensors stopping."
            speakBrief("Trip sensors aagayi. Inka em help kavali?")
        }
        stopTrip.visibility = View.GONE
        taskControls = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        detailsPanel.addView(taskControls)
        taskInput = edit(taskControls, "Task detail, such as activity or landmark").apply {
            visibility = View.GONE
        }
        weatherStart = edit(taskControls, "Starts in how many minutes?").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("60")
            visibility = View.GONE
        }
        weatherDuration = edit(taskControls, "Duration in minutes?").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("120")
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
            setOnCheckedChangeListener { _, checked -> if (checked) maybeStartVoiceTask() }
        }
        card.addView(sensorConsent)
        shareConsent = CheckBox(host).apply {
            text = "Send this selected image crop or GPS fix for this task"
            setTextColor(host.getColor(R.color.yl_sand))
            visibility = View.GONE
            setOnCheckedChangeListener { _, checked -> if (checked) maybeStartVoiceTask() }
        }
        card.addView(shareConsent)
        detailsToggle = button(card, "More options") {
            detailsPanel.visibility = if (detailsPanel.visibility == View.GONE) View.VISIBLE else View.GONE
            detailsToggle.text = if (detailsPanel.visibility == View.GONE) "More options" else "Hide options"
        }
        card.addView(detailsPanel)
        dynamicControls = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        taskControls.addView(dynamicControls)
        resultTitle = TextView(host).apply {
            textSize = 13f
            setTextColor(host.getColor(R.color.yl_mist))
            visibility = View.GONE
        }
        card.addView(resultTitle)
        conversationBoard = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = GradientDrawable().apply {
                setColor(host.getColor(R.color.yl_night))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), host.getColor(R.color.yl_line))
            }
        }
        val localSide = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), 0, dp(8), 0)
        }
        conversationBoard.addView(localSide, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        localSide.addView(conversationText("LOCAL · MALAYALAM", 14f, R.color.yl_sand))
        localTranscriptView = conversationText("Waiting for local person…", 13f, R.color.yl_ivory)
        localSide.addView(localTranscriptView)
        localReplyView = conversationText("Tengglish meaning appears here.", 13f, R.color.yl_mist)
        localSide.addView(localReplyView)
        button(localSide, "Speak to me") {
            replayConversationReply(localReplySpeech, "te-IN")
        }.textSize = 12f
        conversationBoard.addView(View(host).apply {
            setBackgroundColor(host.getColor(R.color.yl_line))
        }, LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
        val travellerSide = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(6), 0)
        }
        conversationBoard.addView(travellerSide, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        travellerSide.addView(conversationText("YOU · TENGGLISH", 14f, R.color.yl_sage))
        travellerTranscriptView = conversationText("Your words appear here.", 13f, R.color.yl_ivory)
        travellerSide.addView(travellerTranscriptView)
        travellerReplyView = conversationText("Malayalam reply appears here.", 13f, R.color.yl_mist)
        travellerSide.addView(travellerReplyView)
        button(travellerSide, "Speak to local") {
            replayConversationReply(travellerReplySpeech, "ml-IN")
        }.textSize = 12f
        card.addView(conversationBoard)
        output = TextView(host).apply {
            textSize = 17f
            setTextColor(host.getColor(R.color.yl_ivory))
            setTextIsSelectable(true)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            maxLines = 8
            setLineSpacing(dp(5).toFloat(), 1f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(host.getColor(R.color.yl_night))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), host.getColor(R.color.yl_line))
            }
            visibility = View.GONE
        }
        card.addView(output)
        resultDetails = TextView(host).apply {
            textSize = 15f
            setLineSpacing(dp(4).toFloat(), 1f)
            setTextColor(host.getColor(R.color.yl_ivory))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(host.getColor(R.color.yl_panel))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), host.getColor(R.color.yl_line))
            }
            visibility = View.GONE
        }
        card.addView(resultDetails)
        resultSource = TextView(host).apply {
            textSize = 12f
            setTextColor(host.getColor(R.color.yl_mist))
            autoLinkMask = android.text.util.Linkify.WEB_URLS
            visibility = View.GONE
        }
        card.addView(resultSource)
        imageHolder = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        imagePreview = ImageView(host).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Selected camera image used for this answer"
        }
        imageHolder.addView(imagePreview, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(180)))
        imageCaption = TextView(host).apply {
            textSize = 13f
            setTextColor(host.getColor(R.color.yl_mist))
        }
        imageHolder.addView(imageCaption)
        card.addView(imageHolder)
        mapHolder = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        card.addView(mapHolder)
        fullAnswerToggle = button(card, "Read full answer") {
            output.maxLines = if (output.maxLines == 8) Int.MAX_VALUE else 8
            fullAnswerToggle.text = if (output.maxLines == 8) "Read full answer" else "Show short answer"
        }
        fullAnswerToggle.visibility = View.GONE
        output.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                output.visibility = if (s.isNullOrBlank()) View.GONE else View.VISIBLE
                fullAnswerToggle.visibility = if ((s?.length ?: 0) > 350) View.VISIBLE else View.GONE
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        reviewed = edit(detailsPanel, "Edit answer before speaking").apply { visibility = View.GONE }
        editWording = button(detailsPanel, "Edit the spoken wording") {
            reviewed.visibility = if (reviewed.visibility == View.GONE) View.VISIBLE else View.GONE
        }
        speechConsent = CheckBox(host).apply {
            text = "Send this reviewed answer to Sarvam for speech"
            setTextColor(host.getColor(R.color.yl_sand))
            visibility = View.GONE
        }
        detailsPanel.addView(speechConsent)
        speakResult = button(card, "Speak reviewed answer") {
            if (voiceFlow) speechConsent.isChecked = true
            speakReviewed()
        }
        speakResult.visibility = View.GONE
        stopSpeech = button(card, "Stop speaking") { stopReviewedSpeech() }
        stopSpeech.visibility = View.GONE
        saveResult = button(detailsPanel, "Save reviewed text to trip memory") { saveCurrentEvidence() }
        saveResult.visibility = View.GONE
        button(card, "Close") { finish() }
        inner.requestFocus()
        return root
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        host.cacheDir.listFiles()?.filter {
            it.name.startsWith("yatralens-assistant-") && it.extension == "wav"
        }?.forEach { it.delete() }
        sessionVisible = true
        planSerial++
        planInFlight = false
        planningUtterance = ""
        refreshContextPreview()
        // A new wake must not silently re-run the previous camera task.
        request.setText("")
        answer.setText("")
        voiceFlow = true
        autoConversation = false
        typedConversationMode = false
        awaitingPlannerAnswer = false
        awaitingSpeechReview = false
        awaitingBookingName = false
        awaitingBookingHere = false
        bookingLocationConfirmed = false
        weatherIsCurrent = false
        planAction = ""
        conversationBoard.visibility = View.GONE
        localReplySpeech = ""
        travellerReplySpeech = ""
        localTranscriptView.text = "Waiting for local person…"
        localReplyView.text = "Tengglish meaning appears here."
        travellerTranscriptView.text = "Your words appear here."
        travellerReplyView.text = "Malayalam reply appears here."
        cloud.isChecked = false
        sensorConsent.isChecked = false
        shareConsent.isChecked = false
        speechConsent.isChecked = false
        sensorConsent.visibility = View.GONE
        shareConsent.visibility = View.GONE
        detailsPanel.visibility = View.GONE
        dynamicControls.removeAllViews()
        conversationControlsAdded = false
        YatraVoiceService.active?.pauseForSession()
        window.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        prompt.text = "Mee trip lo em help kavali?"
        setMicStatus("MIC OFF · Assistant waking", false)
        tts = TextToSpeech(host) { status -> main.post {
            if (!sessionVisible) return@post
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.forLanguageTag("te-IN")
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == "assistant-answer") main.post {
                            if (::stopSpeech.isInitialized) stopSpeech.visibility = View.GONE
                        }
                        if (utteranceId == "assistant-turn") {
                            main.post {
                                val owner = announcedTurnOwner
                                announcedTurnOwner = null
                                if (sessionVisible && owner != null) startConversationTurn(owner)
                            }
                            return
                        }
                        if (utteranceId == "assistant-welcome" || utteranceId == "assistant-followup" ||
                            utteranceId == "assistant-result" || utteranceId == "assistant-review")
                            main.post {
                                if (sessionVisible && !(typedConversationMode && planAction.isNotBlank())) listen()
                            }
                    }
                    override fun onError(utteranceId: String?) {
                        main.post {
                            if (!sessionVisible) return@post
                            if (utteranceId == "assistant-answer" && ::stopSpeech.isInitialized)
                                stopSpeech.visibility = View.GONE
                            if (utteranceId == "assistant-turn") {
                                val owner = announcedTurnOwner
                                announcedTurnOwner = null
                                if (owner != null) startConversationTurn(owner)
                            } else if (!(typedConversationMode && planAction.isNotBlank())) listen()
                        }
                    }
                })
                if (tts?.speak("Namaskaram! Mee trip lo em help kavali?", TextToSpeech.QUEUE_FLUSH,
                        null, "assistant-welcome") == TextToSpeech.ERROR) listen()
            } else listen()
        } }
    }

    override fun onHide() {
        Log.i("YatraVoiceSession", "Bubble hidden; stage=$taskStage; conversation=$autoConversation")
        super.onHide()
        sessionVisible = false
        planSerial++
        planInFlight = false
        speechSerial++
        listenEpoch++
        taskStage = ""
        awaitingSpeechReview = false
        autoConversation = false
        announcedTurnOwner = null
        setMicStatus("MIC OFF · Bubble closed", false)
        main.removeCallbacks(pollTask)
        recognizer?.destroy()
        recognizer = null
        VisualEvidenceState.clear()
        imagePreview.setImageDrawable(null)
        imageHolder.visibility = View.GONE
        mapHolder.visibility = View.GONE
        stopSessionSpeech()
        tts?.stop()
        if (sessionStartedTrip) {
            send(TripSensingService.END)
            sessionStartedTrip = false
        }
        YatraVoiceService.active?.resumeAfterSession()
    }

    override fun onBackPressed() {
        val decor = window.window?.decorView
        if (Build.VERSION.SDK_INT >= 30 &&
            decor?.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true) {
            (host.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as
                android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(decor.windowToken, 0)
            return
        }
        if (taskStage.isNotBlank() || autoConversation || planAction.isNotBlank()) {
            prompt.text = "Task nadustondi. Close button tho session end cheyandi."
            return
        }
        super.onBackPressed()
    }

    private fun listen() {
        val thisListen = ++listenEpoch
        speechSerial++
        stopSessionSpeech()
        if (host.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            prompt.text = "Open YatraLens once to grant microphone permission."
            setMicStatus("MIC OFF · Permission needed", false)
            return
        }
        if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(host)) {
            prompt.text = "On-device recognition is unavailable here. Type the request."
            setMicStatus("MIC OFF · On-device speech unavailable", false)
            return
        }
        recognizer?.destroy()
        listenButton.text = "🎙  Listening…"
        setMicStatus("MIC STARTING · Speak after the cue", false)
        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(host).also { speech ->
            speech.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    prompt.text = "Nenu vintunnanu. Mee question cheppandi."
                    setMicStatus("● MIC ON · Listening to you", true)
                }
                override fun onBeginningOfSpeech() {
                    setMicStatus("● MIC ON · Recording your request", true)
                }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    prompt.text = "Understanding your words…"
                    setMicStatus("MIC OFF · Understanding speech", false)
                }
                override fun onError(error: Int) {
                    if (thisListen != listenEpoch) return
                    Log.w("YatraVoiceSession", "On-device recognition error=$error")
                    listenButton.text = "🎙  Talk to YatraLens"
                    if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                        error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
                        usePhoneSpeechLocale = true
                    val silence = error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    if (!silence) recognitionFailures++
                    val recoverable = error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                        error == SpeechRecognizer.ERROR_CLIENT ||
                        error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                        error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED ||
                        error == SpeechRecognizer.ERROR_AUDIO ||
                        error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                        error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                        error == SpeechRecognizer.ERROR_SERVER
                    if (recoverable && (silence || recognitionFailures <= 4) &&
                        sessionVisible && taskStage.isBlank() && !planInFlight &&
                        !autoConversation && !awaitingSpeechReview &&
                        !(typedConversationMode && planAction.isNotBlank())) {
                        prompt.text = "I'm here. Go ahead and tell me what you need."
                        setMicStatus("MIC OFF · Listening again…", false)
                        main.postDelayed({
                            if (sessionVisible && thisListen == listenEpoch &&
                                taskStage.isBlank() && !planInFlight &&
                                !autoConversation &&
                                !(typedConversationMode && planAction.isNotBlank())) listen()
                        }, if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1200 else 650)
                    } else {
                        prompt.text = "Speech recognition paused. Tap Talk or type in More options."
                        setMicStatus("MIC OFF · Tap Talk to try again", false)
                    }
                }
                override fun onResults(results: Bundle?) {
                    if (thisListen != listenEpoch) return
                    recognitionFailures = 0
                    val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    listenButton.text = "🎙  Talk to YatraLens"
                    setMicStatus("MIC OFF · Speech captured", false)
                    if (heard.isBlank()) {
                        if (sessionVisible && taskStage.isBlank() && !autoConversation &&
                            !(typedConversationMode && planAction.isNotBlank()))
                            main.postDelayed({ if (sessionVisible && thisListen == listenEpoch) listen() }, 650)
                        return
                    }
                    if (awaitingBookingName) {
                        awaitingBookingName = false
                        placeName.setText(heard.trim())
                        checkOrSaveBooking()
                        return
                    }
                    if (awaitingBookingHere) {
                        awaitingBookingHere = false
                        if (Regex("\\b(yes|here|correct|save it)\\b", RegexOption.IGNORE_CASE)
                                .containsMatchIn(heard)) {
                            bookingLocationConfirmed = true
                            checkOrSaveBooking()
                        } else {
                            prompt.text = "Open More options to enter the booking coordinates, or ask me to save your current location."
                            setMicStatus("MIC OFF · Booking point not saved", false)
                        }
                        return
                    }
                    if (awaitingSpeechReview) {
                        when {
                            Regex("\\b(speak|say it|yes|play)\\b", RegexOption.IGNORE_CASE).containsMatchIn(heard) -> {
                                awaitingSpeechReview = false
                                speakReviewed()
                            }
                            Regex("\\b(stop|cancel|end)\\b", RegexOption.IGNORE_CASE).containsMatchIn(heard) -> {
                                awaitingSpeechReview = false
                                send(TripSensingService.END)
                                sessionStartedTrip = false
                                autoConversation = false
                                prompt.text = "Conversation aagindi. Inka em help kavali?"
                                tts?.speak("Conversation aagindi. Inka em help kavali?",
                                    TextToSpeech.QUEUE_FLUSH, null, "assistant-result")
                            }
                            else -> {
                                prompt.text = "Translation vinipinchalante speak anandi, lekapothe More options lo edit cheyandi."
                                tts?.speak("Translation vinipinchalante speak anandi, lekapothe edit cheyandi.",
                                    TextToSpeech.QUEUE_FLUSH, null, "assistant-review")
                            }
                        }
                        return
                    }
                    val command = heard.trim().lowercase()
                    if (command in setOf("save this", "remember this", "save to memory") && saveKind.isNotBlank()) {
                        saveCurrentEvidence()
                        speakBrief("Ee detail save cheyamani adiganu. Trip memory lo check cheyandi.")
                        return
                    }
                    if (command in setOf("speak", "speak it", "say it", "read it aloud") &&
                        reviewed.text.isNotBlank()) {
                        speechConsent.isChecked = true
                        speakReviewed()
                        return
                    }
                    voiceFlow = true
                    typedConversationMode = false
                    if (awaitingPlannerAnswer) {
                        answer.setText(heard)
                        awaitingPlannerAnswer = false
                    } else {
                        planAction = ""
                        request.setText(heard)
                        answer.setText("")
                        rememberReportedTripContext(heard)
                    }
                    if (cloud.isChecked) plan()
                    else {
                        prompt.text = "I heard: $heard. Enable Sarvam to continue this request."
                        setMicStatus("MIC OFF · Waiting for Sarvam approval", false)
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            speech.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE,
                    if (usePhoneSpeechLocale) "en-US" else codes[language.selectedItemPosition])
            })
        }
    }

    private fun plan() {
        val spoken = request.text.toString().trim()
        if (spoken.isBlank()) { prompt.text = "Say or type a request first."; return }
        if (planInFlight && spoken == planningUtterance) return
        val requestId = ++planSerial
        planningUtterance = spoken
        planInFlight = false
        taskStage = ""
        main.removeCallbacks(pollTask)
        val lower = spoken.lowercase()
        val directSensorAction = when {
            "stop sensors" in lower || "end trip" in lower -> TripSensingService.END
            "mute microphone" in lower || "mute mic" in lower -> TripSensingService.MUTE_MIC
            "pause camera" in lower -> TripSensingService.PAUSE_CAMERA
            "resume microphone" in lower -> TripSensingService.RESUME_MIC
            "resume camera" in lower -> TripSensingService.RESUME_CAMERA
            else -> null
        }
        if (directSensorAction != null) {
            send(directSensorAction)
            if (directSensorAction == TripSensingService.END) {
                sessionStartedTrip = false
                autoConversation = false
                taskStage = ""
                main.removeCallbacks(pollTask)
                stopTrip.visibility = View.GONE
            }
            output.text = when (directSensorAction) {
                TripSensingService.END -> "Trip sensors stopped."
                TripSensingService.MUTE_MIC -> "Microphone muted."
                TripSensingService.PAUSE_CAMERA -> "Camera paused."
                TripSensingService.RESUME_MIC -> "Microphone resuming."
                else -> "Camera resuming."
            }
            speakBrief(output.text.toString(), translateEnglish = true)
            return
        }
        rememberReportedTripContext(spoken)
        val localTask = when {
            Regex("\\b(offline|emergency|helpline|no internet)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(spoken) -> "OPEN_OFFLINE"
            Regex("\\b(memory|saved context|what did i save|remembered|promised|booking time|operator time|disagree|changed boarding)\\b", RegexOption.IGNORE_CASE)
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
            else {
                action.visibility = View.GONE
                continueTask()
                if (voiceFlow) speakBrief(output.text.toString().take(240), translateEnglish = true)
            }
            return
        }
        if (!cloud.isChecked) { prompt.text = "Approve sending this selected request to Sarvam."; return }
        planInFlight = true
        prompt.text = "Working out the next step…"
        action.visibility = View.GONE
        val selectedLanguage = codes[language.selectedItemPosition]
        val followUp = answer.text.toString().trim().ifBlank { null }
        worker.execute {
            try {
                val result = CloudGateway.planAssistant(spoken, followUp, selectedLanguage)
                main.post {
                    if (requestId != planSerial || !sessionVisible) return@post
                    planInFlight = false
                    if (result.optString("status") == "NEEDS_FOLLOW_UP") {
                        val question = result.optString("follow_up_question")
                        awaitingPlannerAnswer = true
                        answer.visibility = View.VISIBLE
                        prompt.text = question
                        speakTravellerFollowup(question)
                    } else {
                        awaitingPlannerAnswer = false
                        answer.visibility = View.GONE
                        planAction = result.optString("action")
                        destination = if (result.isNull("destination")) ""
                            else result.optString("destination").takeUnless { it == "null" }.orEmpty()
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
                            if (voiceFlow) {
                                speakBrief(output.text.toString().take(240), translateEnglish = true)
                            }
                        } else if (voiceFlow) {
                            action.visibility = View.GONE
                            autoConversation = planAction == "OPEN_TALK" && !typedConversationMode
                            detailsPanel.visibility = View.GONE
                            detailsToggle.text = "More options"
                            continueTask()
                        } else {
                            action.visibility = View.VISIBLE
                        }
                    }
                }
            } catch (e: Exception) {
                main.post {
                    if (requestId != planSerial || !sessionVisible) return@post
                    planInFlight = false
                    prompt.text = "Planning unavailable. Please try speaking again."
                }
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
            "OPEN_TALK" -> if (autoConversation) {
                pendingConversationOwner = "TRAVELLER"
                ensureTrip()
            } else showConversationControls()
            "OPEN_CULTURE" -> if (cultureNeedsCamera()) ensureTrip() else lookupNamedCulture()
            "SHOW_BUS_MAP" -> showBusMap()
            "OPEN_TASKS" -> output.text = "Ask about a scene, sign, bus, conversation, booking point, weather, landmark, local craft, trip memory or phone sensors."
            else -> ensureTrip()
        }
    }

    private fun maybeStartVoiceTask() {
        if (!sessionVisible || !voiceFlow || planAction.isBlank() || taskStage.isNotBlank()) return
        if (sensorConsent.visibility == View.VISIBLE && !sensorConsent.isChecked) return
        if (shareConsent.visibility == View.VISIBLE && !shareConsent.isChecked) return
        continueTask()
    }

    private fun cultureNeedsCamera(): Boolean = Regex(
        "\\b(point|pointed|camera|photo|picture|look at|this temple|that temple|in front of me)\\b",
        RegexOption.IGNORE_CASE).containsMatchIn(request.text.toString())

    private fun prepareTask() {
        lastVisionDescription = ""
        val cameraTask = planAction in setOf("CAPTURE_SCENE", "CAPTURE_TEXT", "CAPTURE_BUS_BOARD") ||
            (planAction == "OPEN_CULTURE" && cultureNeedsCamera())
        if (sessionStartedTrip && !cameraTask) send(TripSensingService.PAUSE_CAMERA)
        if (typedConversationMode && planAction == "OPEN_TALK") {
            listenEpoch++
            recognizer?.destroy()
            recognizer = null
            listenButton.text = "🎙  Talk to YatraLens"
            setMicStatus("MIC OFF · Typed conversation", false)
        }
        dynamicControls.removeAllViews()
        conversationControlsAdded = false
        pendingConversationOwner = null
        agreementControls = null
        sensorControlsAdded = false
        saveKind = ""
        awaitingBookingName = false
        awaitingBookingHere = false
        bookingLocationConfirmed = false
        weatherIsCurrent = planAction == "OPEN_WEATHER" &&
            isCurrentWeatherRequest(request.text.toString())
        sensorConsent.isChecked = false
        shareConsent.isChecked = false
        speechConsent.isChecked = false
        taskInput.visibility = if ((planAction == "OPEN_WEATHER" && !weatherIsCurrent) ||
            planAction in setOf("OPEN_CULTURE", "OPEN_DISCOVERY"))
            View.VISIBLE else View.GONE
        taskInput.setText(when (planAction) {
            "OPEN_WEATHER" -> answer.text.toString().trim().ifBlank {
                request.text.toString().trim().take(100)
            }
            "OPEN_CULTURE", "OPEN_DISCOVERY" -> answer.text.toString().trim().ifBlank {
                request.text.toString().trim()
            }
            else -> ""
        })
        weatherStart.visibility = if (planAction == "OPEN_WEATHER" && !weatherIsCurrent)
            View.VISIBLE else View.GONE
        weatherDuration.visibility = weatherStart.visibility
        if (planAction == "OPEN_WEATHER")
            weatherStart.setText((weatherStartFromSpeech(request.text.toString()) ?: 0).toString())
        val booking = planAction == "OPEN_BOOKING"
        listOf(placeName, placeLat, placeLon, placeSource).forEach {
            it.visibility = if (booking) View.VISIBLE else View.GONE
        }
        val needsSensors = planAction in setOf("CAPTURE_SCENE", "CAPTURE_TEXT", "CAPTURE_BUS_BOARD",
            "OPEN_BOOKING", "OPEN_WEATHER", "OPEN_DISCOVERY") ||
            (planAction == "OPEN_CULTURE" && cultureNeedsCamera()) ||
            (planAction == "OPEN_TALK" && !typedConversationMode)
        sensorConsent.visibility = if (needsSensors) View.VISIBLE else View.GONE
        sensorConsent.text = if (planAction in setOf("CAPTURE_SCENE", "CAPTURE_TEXT", "CAPTURE_BUS_BOARD") ||
                (planAction == "OPEN_CULTURE" && cultureNeedsCamera()))
            "Allow live camera for this trip task" else
            if (planAction == "OPEN_TALK") "Allow microphone and available location for this conversation" else
                "Allow location for this trip check"
        val checkingSavedBooking = planAction == "OPEN_BOOKING" &&
            !request.text.toString().contains(Regex("\\b(save|remember|set)\\b", RegexOption.IGNORE_CASE)) &&
            TripMemoryStore(host).use { it.latestDestinationAcrossTrips() } != null
        shareConsent.visibility = if (planAction in setOf("CAPTURE_SCENE", "CAPTURE_TEXT", "CAPTURE_BUS_BOARD",
                "OPEN_WEATHER", "OPEN_DISCOVERY") ||
                (planAction == "OPEN_CULTURE" && cultureNeedsCamera()) ||
                (planAction == "OPEN_TALK" && !typedConversationMode) ||
                checkingSavedBooking)
            View.VISIBLE else View.GONE
        shareConsent.text = when (planAction) {
            "CAPTURE_SCENE" -> "Send one selected scene frame to OpenAI vision"
            "CAPTURE_TEXT" -> "Send one selected crop to OpenAI vision; Sarvam OCR if needed"
            "CAPTURE_BUS_BOARD" -> "Send one selected bus crop to OpenAI vision; Sarvam OCR if needed"
            "OPEN_TALK" -> "Translate and speak selected turns; save relevant local trip statements"
            "OPEN_WEATHER" -> "Send this current GPS fix for the weather check"
            "OPEN_CULTURE" -> "Send one selected frame to OpenAI vision and use a current GPS fix"
            else -> "Send this current GPS fix for the selected check"
        }
        output.text = ""
        conversationBoard.visibility = if (planAction == "OPEN_TALK") View.VISIBLE else View.GONE
        editWording.visibility = if (planAction == "OPEN_TALK") View.GONE else View.VISIBLE
        resultTitle.visibility = View.GONE
        resultDetails.visibility = View.GONE
        resultSource.visibility = View.GONE
        imageHolder.visibility = View.GONE
        mapHolder.visibility = View.GONE
        reviewed.visibility = View.GONE
        speakResult.visibility = View.GONE
        speechConsent.visibility = View.GONE
        saveResult.visibility = View.GONE
        stopTrip.visibility = if (PhysicalContextEngine.situation.value.tripId != null) View.VISIBLE else View.GONE
        action.text = "Do this in the bubble"
        if (voiceFlow && needsSensors) {
            prompt.text = if (planAction == "OPEN_WEATHER" && weatherIsCurrent)
                "Ikkada current weather cheppagalanu. Location sharing allow cheyandi."
            else if (planAction == "OPEN_WEATHER")
                "Mee planned activity weather check chestanu. Location sharing allow cheyandi."
            else "Nenu help chestanu. Ee task kosam chupinchina sensors allow cheyandi."
            tts?.speak(prompt.text.toString(), TextToSpeech.QUEUE_FLUSH, null, "assistant-consent")
            setMicStatus("MIC OFF · Waiting for task approval", false)
        } else if (planAction == "OPEN_WEATHER")
            prompt.text = "Review the activity and time window in More options."
    }

    private fun ensureTrip() {
        val needsCamera = planAction in setOf("CAPTURE_SCENE", "CAPTURE_TEXT", "CAPTURE_BUS_BOARD") ||
            (planAction == "OPEN_CULTURE" && cultureNeedsCamera())
        if (!sensorConsent.isChecked) {
            prompt.text = "Approve the selected trip sensors in this bubble first."
            return
        }
        val required = mutableListOf<String>()
        if (planAction == "OPEN_TALK") required.add(Manifest.permission.RECORD_AUDIO)
        if (needsCamera) required.add(Manifest.permission.CAMERA)
        if (planAction in setOf("OPEN_BOOKING", "OPEN_WEATHER", "OPEN_DISCOVERY"))
            required.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (required.any { host.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            prompt.text = "Grant the needed camera, microphone or location permission once from the YatraLens home setup."
            return
        }
        if (planAction in setOf("CAPTURE_SCENE", "CAPTURE_TEXT", "CAPTURE_BUS_BOARD", "OPEN_TALK", "OPEN_WEATHER",
                "OPEN_CULTURE", "OPEN_DISCOVERY") && !shareConsent.isChecked) {
            prompt.text = "Approve this selected image or GPS sharing first."
            return
        }
        if (PhysicalContextEngine.situation.value.tripId == null) {
            try {
                host.startForegroundService(Intent(host, TripSensingService::class.java)
                    .setAction(TripSensingService.START)
                    .putExtra("mode", when {
                        needsCamera -> "CAMERA_ONLY"
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
            if (needsCamera) send(TripSensingService.START, "mode" to "CAMERA_ONLY")
            else if (planAction == "OPEN_TALK")
                send(TripSensingService.START, "mode" to "CONVERSATION")
            else send(TripSensingService.START, "mode" to "LOCATION_ONLY")
            setStage("WAIT_TRIP")
        }
        stopTrip.visibility = View.VISIBLE
    }

    private fun executeTask() {
        when (planAction) {
            "CAPTURE_SCENE" -> {
                SceneState.set(null)
                VisualEvidenceState.clear()
                send(TripSensingService.ANALYZE_LIVE_SCENE)
                output.text = "Checking the selected live camera frame…"
                setStage("WAIT_SCENE")
            }
            "CAPTURE_TEXT", "CAPTURE_BUS_BOARD" -> {
                SensorState.setOcrText("")
                send(TripSensingService.CAPTURE_TEXT, "cloudApproved" to true,
                    "language" to codes[localLanguage.selectedItemPosition])
                output.text = "Reading the selected camera crop…"
                setStage("WAIT_OCR")
            }
            "OPEN_TALK" -> {
                showConversationControls()
                pendingConversationOwner?.let { owner ->
                    pendingConversationOwner = null
                    announceTurnThenRecord(owner)
                }
            }
            "OPEN_WEATHER" -> {
                if (weatherIsCurrent) {
                    FeatureState.set("current weather", null)
                    send(TripSensingService.CHECK_WEATHER,
                        "locationCloudApproved" to true, "currentOnly" to true)
                    output.text = "Checking current conditions at your location…"
                    setStage("WAIT_current weather")
                    return
                }
                val start = weatherStart.text.toString().toIntOrNull()
                val duration = weatherDuration.text.toString().toIntOrNull()
                if (start == null || start !in 0..2880 || duration == null || duration !in 1..720) {
                    output.text = "Enter a start offset from 0–2880 minutes and a duration from 1–720 minutes."
                    return
                }
                val activity = taskInput.text.toString().trim()
                if (activity.isBlank()) {
                    output.text = "Enter the activity you want a forecast for."
                    return
                }
                WeatherState.set(null)
                send(TripSensingService.CHECK_WEATHER, "locationCloudApproved" to true,
                    "activity" to activity,
                    "startMinutes" to start, "durationMinutes" to duration)
                output.text = "Checking $activity starting ${weatherWindowLabel(start)} for $duration minutes…"
                setStage("WAIT_WEATHER")
            }
            "OPEN_CULTURE" -> {
                if (cultureNeedsCamera()) {
                    SceneState.set(null)
                    VisualEvidenceState.clear()
                    send(TripSensingService.ANALYZE_LIVE_SCENE)
                    output.text = "Checking the live camera view for this place…"
                    setStage("WAIT_CULTURE_VISION_FRAME")
                } else {
                    lookupNamedCulture()
                }
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

    private fun lookupNamedCulture() {
        val hint = taskInput.text.toString().trim()
        if (hint.isBlank()) { output.text = "Tell me the landmark name first."; return }
        if (!cloud.isChecked) { output.text = "Approve sending this selected place name to Sarvam."; return }
        output.text = "Searching mapped places and sourced cultural pages…"
        worker.execute {
            try {
                val result = CloudGateway.cultureLookup(null, null, hint)
                main.post {
                    if (!sessionVisible) return@post
                    showAnswer(FeaturePresenter.describe("culture", result), "en-IN")
                    showCultureImage(result)
                    showMapPin(result.optJSONObject("map_pin"), result.optString("source_url"))
                }
            } catch (e: Exception) {
                main.post {
                    if (sessionVisible) output.text =
                        "Place lookup failed: ${e.javaClass.simpleName}. Check the connection or place name."
                }
            }
        }
    }

    private fun checkOrSaveBooking() {
        val saved = TripMemoryStore(host).use { it.latestDestinationAcrossTrips() }
        val saving = saved == null || request.text.toString().contains(
            Regex("\\b(save|remember|set)\\b", RegexOption.IGNORE_CASE))
        if (!saving && saved != null && shareConsent.isChecked) {
            FeatureState.set("destination", null)
            send(TripSensingService.CHECK_DESTINATION, "locationCloudApproved" to true)
            output.text = "Comparing your current GPS fix with saved ${saved.placeName}…"
            setStage("WAIT_destination")
            return
        }
        if (!saving) {
            output.text = "Saved point: ${saved?.placeName}. Approve sharing one GPS fix to check it."
            return
        }
        if (placeName.text.toString().trim().equals("null", ignoreCase = true))
            placeName.setText("")
        if (placeName.text.isBlank() && destination.isNotBlank())
            placeName.setText(destination)
        if (placeName.text.isBlank() && request.text.toString().contains(
                Regex("\\bboarding point\\b", RegexOption.IGNORE_CASE)))
            placeName.setText("Boarding point")
        if (placeName.text.isBlank() && voiceFlow) {
            awaitingBookingName = true
            prompt.text = "What should I call this booking point?"
            speakTravellerFollowup(prompt.text.toString())
            return
        }
        var lat = placeLat.text.toString().toDoubleOrNull()
        var lon = placeLon.text.toString().toDoubleOrNull()
        if ((lat == null || lon == null) && voiceFlow) {
            if (!bookingLocationConfirmed && !request.text.toString().contains(
                    Regex("\\b(here|this location|current location)\\b", RegexOption.IGNORE_CASE))) {
                awaitingBookingHere = true
                prompt.text = "Are you at that booking point now? Say yes to save your current GPS position."
                speakTravellerFollowup(prompt.text.toString())
                return
            }
            val fix = PhysicalContextEngine.situation.value.location
            if (fix == null || !PhysicalContextEngine.fresh(SensorKind.LOCATION, 120_000) ||
                fix.accuracyM > 100f) {
                output.text = "Current GPS is not accurate enough to save this point yet. Try again when location settles."
                return
            }
            lat = fix.lat
            lon = fix.lon
            placeLat.setText(lat.toString())
            placeLon.setText(lon.toString())
        }
        if (placeSource.text.isBlank() && voiceFlow)
            placeSource.setText("Traveller confirmed point in assistant: ${request.text.toString().trim()}")
        val name = placeName.text.toString().trim()
        val source = placeSource.text.toString().trim()
        if (name.isBlank() || lat == null || lon == null || source.isBlank()) {
            output.text = "Tell me the booking point name and coordinates in More options, or ask me to save where you are now."
            return
        }
        SensorState.set("destination", "Saving selected booking point")
        send(TripSensingService.SAVE_DESTINATION, "placeName" to name,
            "latitude" to lat, "longitude" to lon, "bookingExcerpt" to source)
        output.text = "Saving $name from the selected coordinates on this phone…"
        setStage("WAIT_SAVE_DESTINATION")
    }

    private fun showConversationControls() {
        if (!conversationControlsAdded) {
            conversationControlsAdded = true
            val typed = edit(dynamicControls, "Type Tengglish or a Malayalam reply")
            val typedActions = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            dynamicControls.addView(typedActions)
            button(typedActions, "You → local") {
                val source = codes[language.selectedItemPosition]
                val target = codes[localLanguage.selectedItemPosition]
                translateConversationText(typed.text.toString(), source, target) {}
            }.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            button(typedActions, "Local → you") {
                val source = codes[localLanguage.selectedItemPosition]
                val target = codes[language.selectedItemPosition]
                translateConversationText(typed.text.toString(), source, target) {}
            }.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (!typedConversationMode) {
                val voiceActions = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
                dynamicControls.addView(voiceActions)
                button(voiceActions, "I talk") { startConversationTurn("TRAVELLER") }
                    .layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                button(voiceActions, "Local talks") { startConversationTurn("LOCAL") }
                    .layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                button(dynamicControls, "Finish turn") {
                    if (!shareConsent.isChecked || !cloud.isChecked) {
                        output.text = "Approve sending this selected spoken turn to Sarvam."
                    } else {
                        shareConsent.isChecked = false
                        send(TripSensingService.SEND_TURN, "cloudApproved" to true)
                        output.text = "Transcribing and translating this selected turn…"
                        setStage("WAIT_CONVERSATION")
                    }
                }
                agreementControls = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
                dynamicControls.addView(agreementControls)
            }
        }
        output.text = if (typedConversationMode) "Type Tengglish or Malayalam and choose a direction."
            else "Conversation is ready. Speak naturally, or choose a side to start a turn."
    }

    private fun startConversationTurn(owner: String) {
        if (PhysicalContextEngine.situation.value.tripId == null) {
            pendingConversationOwner = owner
            ensureTrip()
            return
        }
        if (!PhysicalContextEngine.fresh(SensorKind.MICROPHONE, 15_000)) {
            output.text = "The microphone is not live in this trip. End it, then start a conversation again."
            return
        }
        saveKind = ""
        saveResult.visibility = View.GONE
        speechConsent.isChecked = false
        recordOwner = owner
        val source = if (owner == "LOCAL") codes[localLanguage.selectedItemPosition]
            else codes[language.selectedItemPosition]
        val target = if (owner == "LOCAL") codes[language.selectedItemPosition]
            else codes[localLanguage.selectedItemPosition]
        SensorState.set("spoken turn", "starting selected turn")
        send(TripSensingService.START_TURN, "owner" to owner,
            "sourceLanguage" to source, "targetLanguage" to target)
        val who = if (owner == "LOCAL") "the local person" else "you"
        output.text = "Starting the $who turn…"
        prompt.text = "Preparing to listen to $who."
        setMicStatus("MIC STARTING · $who", false)
        if (autoConversation) {
            lastRecordingSecond = -1
            setStage("WAIT_CAPTURE")
        }
    }

    private fun announceTurnThenRecord(owner: String) {
        if (!autoConversation) { startConversationTurn(owner); return }
        announcedTurnOwner = owner
        val cue = if (owner == "LOCAL") "Ippudu local person Malayalam lo matladandi."
            else "Ippudu mee question Tengglish lo cheppandi."
        prompt.text = cue
        val engine = tts
        if (engine == null || engine.speak(cue, TextToSpeech.QUEUE_FLUSH, null,
                "assistant-turn") == TextToSpeech.ERROR) {
            announcedTurnOwner = null
            startConversationTurn(owner)
        }
    }

    private fun translateConversationText(raw: String, source: String, target: String,
                                          afterSuccess: (String) -> Unit) {
        val text = raw.trim()
        if (text.isBlank() || text.length > 2000) { output.text = "Enter a short phrase first."; return }
        if (!cloud.isChecked) { output.text = "Approve sending this selected typed phrase to Sarvam."; return }
        output.text = "Translating the selected phrase…"
        worker.execute {
            try {
                val result = CloudGateway.translateText(text, source, target)
                main.post {
                    if (!sessionVisible) return@post
                    afterSuccess(text)
                    saveKind = ""
                    saveResult.visibility = View.GONE
                    reviewLanguage = target
                    output.text = (if (target == "ml-IN") "Malayalam reply ready."
                        else "Tengglish meaning ready.") +
                        if (result.reviewFlags.isNotEmpty()) " Names, amounts, times check cheyandi." else ""
                    reviewed.setText(result.text)
                    updateConversationBoard(if (target == "ml-IN") "TRAVELLER" else "LOCAL",
                        text, result.text)
                    if (source == "ml-IN" && target == "te-IN" &&
                        TripContextExtractor.materialLocalReply(text)) {
                        try {
                            val saved = TripMemoryStore(host).use {
                                it.saveTypedLocalStatement(PhysicalContextEngine.situation.value.tripId,
                                    text, source)
                            }
                            if (saved != null) {
                                output.append(" Saved to trip memory as an unverified typed reply.")
                                refreshContextPreview()
                            }
                        } catch (_: Exception) {
                            output.append(" Could not save this local reply to trip memory.")
                        }
                    }
                    reviewed.visibility = View.GONE
                    val needsReview = result.reviewFlags.isNotEmpty()
                    speechConsent.visibility = if (typedConversationMode) View.GONE else View.VISIBLE
                    speakResult.visibility = if (autoConversation || (typedConversationMode && !needsReview))
                        View.GONE else View.VISIBLE
                    editWording.visibility = if (needsReview) View.VISIBLE else View.GONE
                    if (typedConversationMode && cloud.isChecked && !needsReview) {
                        speechConsent.isChecked = true
                        main.postDelayed({
                            if (sessionVisible && typedConversationMode) speakReviewed()
                        }, 700)
                    }
                }
            } catch (_: Exception) {
                main.post { if (sessionVisible) output.text = "Translation unavailable. Keep the original wording visible." }
            }
        }
    }

    private fun showMemory() {
        dynamicControls.removeAllViews()
        val (entries, conflicts) = TripMemoryStore(host).use {
            it.recentTripContext(80) to it.recentOpenConflicts()
        }
        val asksPromise = request.text.toString().contains(
            Regex("promis|operator|fare|life jacket", RegexOption.IGNORE_CASE))
        val localTurns = entries.filter { it.title.contains("Local person's") }
        val firstConflict = conflicts.firstOrNull()
        resultTitle.text = if (firstConflict != null) "TIMES NEED CONFIRMATION" else "TRIP MEMORY"
        resultTitle.visibility = View.VISIBLE
        resultDetails.visibility = View.GONE
        resultSource.visibility = View.GONE
        output.text = when {
            firstConflict != null -> {
                val alternatives = firstConflict.claims.mapIndexed { index, claim ->
                    "${index + 1}. ${claim.value} · ${claim.sourceTitle} · ${formatTime(claim.observedAt)}"
                }.joinToString("\n")
                "Two saved sources disagree about ${firstConflict.kind.replace('_', ' ')}. " +
                    "Was the later time a confirmed change?\n$alternatives\n" +
                    "I won't treat either as a definitive reminder until you confirm."
            }
            asksPromise && localTurns.isNotEmpty() -> {
                val turn = localTurns.first()
                "The local person said:\n“${turn.detail.take(430)}”\n" +
                    "Saved ${formatTime(turn.observedAt)} · ${turn.source}. " +
                    "Please confirm amounts and commitments with them."
            }
            entries.isEmpty() -> "No saved trip context yet."
            else -> "Recent saved context\n" + entries.take(7).joinToString("\n\n") {
                "${formatTime(it.observedAt)} · ${it.title}\n${it.detail.take(180)}\nSource: ${it.source}"
            }
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
        TripMemoryStore(host).use { memory ->
            conflicts.forEach { conflict ->
                button(dynamicControls, "Review ${conflict.kind.replace('_', ' ')} disagreement") {
                    output.text = conflict.claims.joinToString("\n\n") {
                        "${it.value} • ${it.sourceTitle} • ${formatTime(it.observedAt)}\n${it.excerpt}"
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
                val diagnosticKey = when (taskStage) {
                    "WAIT_CAPTURE", "WAIT_CONVERSATION", "WAIT_NEXT_TURN" -> "spoken turn"
                    "WAIT_PLAYBACK" -> "speech playback"
                    "WAIT_TRIP" -> when (planAction) {
                        "CAPTURE_SCENE", "CAPTURE_TEXT", "CAPTURE_BUS_BOARD", "OPEN_CULTURE" -> "camera"
                        "OPEN_TALK" -> "microphone"
                        else -> "location"
                    }
                    "WAIT_WEATHER" -> "weather"
                    "WAIT_SCENE" -> "scene"
                    "WAIT_OCR" -> "text scan"
                    "WAIT_AGREEMENT" -> "agreement"
                    else -> taskStage.removePrefix("WAIT_")
                }
                val diagnostic = SensorState.get(diagnosticKey)
                output.text = if (diagnostic.startsWith("THROTTLED_THERMAL"))
                    "The phone is too warm for the camera. Let it cool, then ask me to scan again."
                else "This check took too long. Check the phone's sensor state, then retry.\n$diagnostic"
                taskStage = ""
                return
            }
            when (taskStage) {
                "WAIT_CAPTURE" -> {
                    val capture = SensorState.get("spoken turn")
                    if (capture.startsWith("microphone unavailable") || capture.startsWith("Wait for")) {
                        taskStage = ""
                        setMicStatus("MIC OFF · Turn could not start", false)
                        output.text = capture
                    } else if (capture.startsWith("recording")) {
                        val remaining = (10L - (SystemClock.elapsedRealtime() - stageStartedMs) / 1000L)
                            .coerceAtLeast(0L).toInt()
                        if (remaining != lastRecordingSecond) {
                            lastRecordingSecond = remaining
                            val who = if (recordOwner == "LOCAL") "local person" else "you"
                            setMicStatus("● MIC ON · Recording $who · ${remaining}s left", true)
                            output.text = "Listening to $who. Speak now."
                        }
                    }
                    if (capture.startsWith("captured")) {
                        setMicStatus("MIC OFF · Selected turn captured", false)
                        if (cloud.isChecked && shareConsent.isChecked) {
                            send(TripSensingService.SEND_TURN, "cloudApproved" to true)
                            output.text = "Transcribing and translating this turn…"
                            setMicStatus("MIC OFF · Translating turn", false)
                            setStage("WAIT_CONVERSATION")
                        } else {
                            taskStage = ""
                            prompt.text = "Approve sending spoken turns to Sarvam before I translate."
                        }
                    }
                }
                "WAIT_PLAYBACK" -> {
                    val playback = SensorState.get("speech playback")
                    if (playback.startsWith("Finished speaking")) {
                        setMicStatus("MIC OFF · Preparing next speaker", false)
                        send(TripSensingService.RESUME_MIC)
                        setStage("WAIT_NEXT_TURN")
                    } else if (playback.startsWith("Unavailable:") || playback == "Playback failed") {
                        taskStage = ""
                        output.text = "Speech playback failed. The reviewed translation is still visible."
                    }
                }
                "WAIT_NEXT_TURN" -> if (PhysicalContextEngine.fresh(SensorKind.MICROPHONE, 15_000) &&
                    SensorState.get("microphone").startsWith("active")) {
                    taskStage = ""
                    announceTurnThenRecord(nextConversationOwner)
                }
                "WAIT_TRIP" -> {
                    val active = PhysicalContextEngine.situation.value.tripId != null
                    val cameraReady = (planAction !in setOf("CAPTURE_SCENE", "CAPTURE_TEXT",
                        "CAPTURE_BUS_BOARD") && !(planAction == "OPEN_CULTURE" && cultureNeedsCamera())) ||
                        PhysicalContextEngine.fresh(SensorKind.CAMERA, 15_000)
                    val needsLocation = planAction in setOf("OPEN_BOOKING", "OPEN_WEATHER", "OPEN_DISCOVERY")
                    val locationReady = !needsLocation ||
                        (PhysicalContextEngine.fresh(SensorKind.LOCATION, 120_000) &&
                            (PhysicalContextEngine.situation.value.location?.accuracyM ?: Float.MAX_VALUE) <= 100f)
                    val micReady = planAction != "OPEN_TALK" ||
                        PhysicalContextEngine.fresh(SensorKind.MICROPHONE, 15_000)
                    if (active && cameraReady && locationReady && micReady) {
                        taskStage = ""
                        executeTask()
                    }
                }
                "WAIT_SCENE" -> VisualEvidenceState.copy()?.let { frame ->
                    taskStage = "WAIT_SCENE_VISION"
                    val requestId = planSerial
                    worker.execute {
                        val visual = try { CloudGateway.analyzeSelectedFrame(frame,
                            "scene", request.text.toString()) } catch (_: Exception) { null }
                        finally { frame.fill(0) }
                        main.post {
                            if (!sessionVisible || requestId != planSerial) return@post
                            val answer = visual?.optString("answer").orEmpty()
                            if (answer.isNotBlank()) {
                                taskStage = ""
                                showAnswer(answer, "en-IN")
                                resultSource.text = "Selected frame · OpenAI vision; verify before acting"
                                resultSource.visibility = View.VISIBLE
                                showSelectedImage()
                            } else setStage("WAIT_SCENE_LOCAL")
                        }
                    }
                } ?: run {
                    if (SystemClock.elapsedRealtime() - stageStartedMs > 15_000 &&
                        SceneState.current != null) setStage("WAIT_SCENE_LOCAL")
                }
                "WAIT_SCENE_LOCAL" -> SceneState.current?.let { scene ->
                    taskStage = ""
                    showAnswer(SceneAnswerEngine.answer(scene, SceneState.capturedAt,
                        request.text.toString()).text, "en-IN")
                    showSelectedImage()
                }
                "WAIT_CULTURE_VISION_FRAME" -> VisualEvidenceState.copy()?.let { frame ->
                    taskStage = "WAIT_CULTURE_VISION"
                    val requestId = planSerial
                    worker.execute {
                        val visual = try { CloudGateway.analyzeSelectedFrame(frame,
                            "culture", request.text.toString()) } catch (_: Exception) { null }
                        finally { frame.fill(0) }
                        main.post {
                            if (!sessionVisible || requestId != planSerial) return@post
                            lastVisionDescription = visual?.optString("visual_description").orEmpty()
                            val name = visual?.optString("landmark_name").orEmpty()
                            if (name.isNotBlank()) {
                                FeatureState.set("culture", null)
                                send(TripSensingService.LOOKUP_CULTURE,
                                    "locationCloudApproved" to true, "nameHint" to name)
                                output.text = "Camera read a landmark name. Checking sourced history…"
                                setStage("WAIT_culture")
                            } else if (visual != null) {
                                taskStage = ""
                                val description = visual.optString("visual_description")
                                val answer = visual.optString("answer")
                                showAnswer("Camera view: $description\n$answer\n" +
                                    "I cannot verify this site's exact name from this image. " +
                                    "Point at its entrance sign or tell me the name for sourced history.",
                                    "en-IN")
                                resultSource.text = "Selected frame · OpenAI vision; site identity unverified"
                                resultSource.visibility = View.VISIBLE
                                showSelectedImage()
                            } else setStage("WAIT_CULTURE_SCENE")
                        }
                    }
                } ?: run {
                    if (SystemClock.elapsedRealtime() - stageStartedMs > 15_000 &&
                        SceneState.current != null) setStage("WAIT_CULTURE_SCENE")
                }
                "WAIT_CULTURE_SCENE" -> SceneState.current?.let { scene ->
                    taskStage = ""
                    showAnswer("Camera view: " + SceneAnswerEngine.answer(scene,
                        SceneState.capturedAt, "what do you see").text + "\n" +
                        "I cannot verify this site's exact name from this image. " +
                        "Point at its entrance sign or tell me the name for sourced history.",
                        "en-IN")
                    resultSource.text = "On-device scene cues; site identity unverified"
                    resultSource.visibility = View.VISIBLE
                    showSelectedImage()
                }
                "WAIT_CULTURE_OCR" -> {
                    val text = SensorState.getOcrText()
                    if (text.isNotBlank()) {
                        val name = text.lineSequence().map { it.trim() }.firstOrNull {
                            it.length in 8..80 && it.split(Regex("\\s+")).size in 2..9 &&
                                !Regex("image|photo|picture|appears|looks like|might be|cannot|no text|visible|showing",
                                    RegexOption.IGNORE_CASE).containsMatchIn(it) && Regex(
                                "temple|shrine|church|mosque|ക്ഷേത്ര|പള്ളി",
                                RegexOption.IGNORE_CASE).containsMatchIn(it)
                        }
                        if (name != null) {
                            FeatureState.set("culture", null)
                            send(TripSensingService.LOOKUP_CULTURE, "locationCloudApproved" to true,
                                "nameHint" to name)
                            output.text = "Camera read a possible landmark name. Checking sourced history…"
                            setStage("WAIT_culture")
                        } else {
                            taskStage = ""
                            val visual = lastVisionDescription.ifBlank {
                                SceneAnswerEngine.answer(SceneState.current,
                                    SceneState.capturedAt, "what do you see").text
                            }
                            showAnswer("Camera view: $visual\n" +
                                "I cannot verify this temple's exact name from the image. " +
                                "Point at its entrance sign or tell me the name for its history. " +
                                "For Kerala temples generally, follow the posted dress and entry rules. " +
                                "Source: https://www.keralatourism.org/faq/do-and-donts-in-temples-in-kerala",
                                "en-IN")
                            showSelectedImage()
                        }
                    } else if (SystemClock.elapsedRealtime() - stageStartedMs > 20_000 &&
                        lastVisionDescription.isNotBlank()) {
                        taskStage = ""
                        showAnswer("Camera view: $lastVisionDescription\n" +
                            "I cannot verify this temple's exact name from the image. " +
                            "Point at its entrance sign or tell me the name for its history. " +
                            "For Kerala temples generally, follow the posted dress and entry rules. " +
                            "Source: https://www.keralatourism.org/faq/do-and-donts-in-temples-in-kerala",
                            "en-IN")
                        showSelectedImage()
                    }
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
                    val answerText = buildString {
                        append(if (weather.decision == "ACTION_RECOMMENDED")
                            "Weather may affect this activity. Confirm the service with the operator."
                        else if (weather.decision == "NO_ALERT")
                            "No forecast threshold crossed for this activity window. Confirm the service before boarding."
                        else "Forecast coverage is incomplete. Check with the operator.")
                        weather.rainPercent?.let { append("\nPeak rain chance: ${it.toInt()}%") }
                        weather.gustKmh?.let { append(" · peak wind gust: ${it.toInt()} km/h") }
                        append("\nIMD Kerala warning: ${weather.officialWarningStatus.replace('_', ' ')}")
                        append("\nChecked ${formatTime(weather.retrievedAt)}")
                    }
                    showAnswer(answerText, "en-IN")
                    showWeatherSources(JSONObject()
                        .put("forecast_source_url", weather.forecastSourceUrl)
                        .put("official_warning_source_url", weather.sourceUrl.takeIf {
                            it != weather.forecastSourceUrl
                        }), "weather")
                }
                "WAIT_SAVE_DESTINATION" -> {
                    val status = SensorState.get("destination")
                    if (status.startsWith("Saved ")) {
                        taskStage = ""
                        showAnswer("$status. I can compare a later GPS fix with this saved point when you ask.",
                            "en-IN")
                    } else if (status.startsWith("Could not") || status.startsWith("Enter ")) {
                        taskStage = ""
                        output.text = status
                    }
                }
                "WAIT_CONVERSATION" -> if (SensorState.get("spoken turn").startsWith("transcription unavailable:")) {
                    taskStage = ""
                    setMicStatus("MIC OFF · Could not transcribe", false)
                    output.text = SensorState.get("spoken turn") + "\nPlease try the turn again."
                } else ConversationState.current?.takeIf {
                    it.status == "READY_FOR_REVIEW" || it.status == "TRANSLATION_UNAVAILABLE"
                }?.let { turn ->
                    taskStage = ""
                    reviewLanguage = turn.targetLanguage
                    val travellerTurn = turn.owner == "TRAVELLER"
                    val needsReview = turn.reviewFlags.isNotEmpty() ||
                        Regex("[0-9₹]").containsMatchIn(turn.original)
                    if (needsReview) autoConversation = false
                    updateConversationBoard(turn.owner, turn.original, turn.translated)
                    resultTitle.text = if (travellerTurn) "YOU SAID · ${languageName(turn.sourceLanguage)}" else
                        "LOCAL PERSON SAID · IN YOUR LANGUAGE"
                    resultTitle.visibility = View.VISIBLE
                    output.text = if (travellerTurn) turn.original else
                        turn.translated.ifBlank { "Translation unavailable. Original speech is below." }
                    val detail = SpannableStringBuilder()
                    appendResultSection(detail, if (travellerTurn)
                        "SPEAKING TO THE LOCAL PERSON · ${languageName(turn.targetLanguage)}" else
                        "ORIGINAL · ${languageName(turn.sourceLanguage)}",
                        if (travellerTurn) turn.translated.ifBlank { "Unavailable" } else turn.original)
                    appendResultSection(detail, "CHECK BEFORE RELYING ON IT",
                        "Confirm names, amounts and times with the other person.")
                    resultDetails.text = detail
                    resultDetails.visibility = View.GONE
                    resultTitle.visibility = View.GONE
                    output.text = if (needsReview)
                        "Amount or time needs checking. Edit the wording, then tap Speak."
                    else if (travellerTurn) "Malayalam reply right side lo ready."
                    else "Local person reply left side lo ready."
                    resultSource.text = "Selected phone microphone turn · Sarvam transcription and translation"
                    resultSource.visibility = View.VISIBLE
                    reviewed.setText(turn.translated)
                    reviewed.visibility = View.GONE
                    speechConsent.visibility = View.GONE
                    speakResult.visibility = if (autoConversation) View.GONE else View.VISIBLE
                    speakResult.text = if (autoConversation) "Speak this translation" else "Speak reviewed answer"
                    editWording.visibility = if (needsReview) View.VISIBLE else View.GONE
                    saveResult.visibility = View.GONE
                    saveKind = "TRANSCRIPT"
                    setMicStatus("MIC OFF · Translation ready for review", false)
                    if (autoConversation && shareConsent.isChecked && !travellerTurn &&
                        Regex("₹|\\b\\d{1,2}:\\d{2}\\b|രൂപ|ചാർജ്|ലൈഫ്|fare|boarding|return|jacket|promise",
                            RegexOption.IGNORE_CASE).containsMatchIn(turn.original))
                        send(TripSensingService.SAVE_TRANSCRIPT, "userApproved" to true,
                            "reviewed" to false)
                    if (autoConversation && turn.status == "READY_FOR_REVIEW") {
                        send(TripSensingService.MUTE_MIC)
                        awaitingSpeechReview = false
                        prompt.text = "Translation ready. Speaking it to the other person next."
                        main.postDelayed({
                            if (sessionVisible && autoConversation && shareConsent.isChecked &&
                                taskStage.isBlank()) speakReviewed()
                        }, 1200)
                    }
                }
                "WAIT_AGREEMENT" -> FeatureState.get("agreement")?.let { proposal ->
                    taskStage = ""
                    renderAgreementProposals(proposal)
                }
                else -> if (taskStage.startsWith("WAIT_")) {
                    val kind = taskStage.removePrefix("WAIT_")
                    val failure = SensorState.get(kind)
                    if (failure.startsWith("Unavailable:") || failure.startsWith("Offline;") ||
                        failure.startsWith("Fresh GPS") || failure.startsWith("Live camera is off")) {
                        taskStage = ""
                        output.text = failure
                        return
                    }
                    if (kind == "current weather" && listOf("Current weather unavailable", "Offline;",
                            "Fresh GPS", "GPS accuracy", "Approve sharing").any {
                            SensorState.get(kind).startsWith(it)
                        }) {
                        output.text = SensorState.get(kind)
                        taskStage = ""
                        return
                    }
                    FeatureState.get(kind)?.let { feature ->
                        taskStage = ""
                        val answerText = if (kind == "ocr interpretation")
                            "Recognized: ${SensorState.getOcrText()}\n" + FeaturePresenter.describe(kind, feature)
                        else FeaturePresenter.describe(kind, feature)
                        if (kind == "transport") showBusAnswer(SensorState.getOcrText(), feature)
                        else showAnswer(answerText, if (kind == "ocr interpretation")
                            codes[language.selectedItemPosition] else "en-IN")
                        if (kind in setOf("weather", "current weather")) showWeatherSources(feature, kind)
                        if (kind in setOf("ocr interpretation", "transport", "culture")) showSelectedImage()
                        if (kind == "culture") showCultureImage(feature)
                        if (kind == "ocr interpretation")
                            reviewed.setText(feature.optString("translated_text")
                                .ifBlank { SensorState.getOcrText() })
                        if (kind == "culture") showMapPin(feature.optJSONObject("map_pin"),
                            feature.optString("source_url"))
                        if (kind == "discovery") feature.optJSONArray("results")?.optJSONObject(0)?.let {
                            showMapPin(it.optJSONObject("map_pin"), it.optString("source_url"))
                        }
                    }
                }
            }
            if (taskStage.isNotBlank()) main.postDelayed(this, 500)
        }
    }

    private fun renderAgreementProposals(result: JSONObject) {
        val container = agreementControls ?: return
        container.removeAllViews()
        val turnId = result.optLong("turn_id", -1L)
        val proposals = result.optJSONArray("proposals")
        if (proposals == null || proposals.length() == 0) {
            output.text = "No price or time was extracted from this turn. Review the original transcript yourself."
            return
        }
        output.text = "Tentative details from the selected transcript. Review the exact quote and save that transcript before saving a detail."
        val subject = edit(container, "Which trip or booking does this quote concern?")
        for (index in 0 until proposals.length()) {
            val proposal = proposals.optJSONObject(index) ?: continue
            val kind = proposal.optString("kind")
            val excerpt = proposal.optString("raw_excerpt")
            val value = edit(container, "Review $kind from ‘${excerpt.take(80)}’").apply {
                setText(proposal.optString("normalized_candidate"))
            }
            button(container, "Save reviewed $kind as an unconfirmed detail") {
                val evidenceId = ReviewedEvidenceState.evidenceId
                val tripId = PhysicalContextEngine.situation.value.tripId
                if (evidenceId == null || ReviewedEvidenceState.turnId != turnId || tripId == null) {
                    output.text = "Save the reviewed original transcript for this turn first."
                    return@button
                }
                if (subject.text.isBlank() || value.text.isBlank() || excerpt.isBlank()) {
                    output.text = "Enter the trip name and review the quoted value first."
                    return@button
                }
                val predicate = when (kind) {
                    "fare_inr" -> "price"
                    "return_time_text" -> "arrival_time"
                    else -> kind
                }
                try {
                    val saved = TripMemoryStore(host).use {
                        it.claimFromEvidence(tripId, evidenceId, subject.text.toString().trim(),
                            predicate, excerpt, value.text.toString().trim())
                    }
                    output.text = if (saved.conflictId == null)
                        "Saved as an unconfirmed sourced detail. Ask for memory to review it."
                    else "Saved. Two sources disagree; ask for memory to review the conflict."
                } catch (e: Exception) {
                    output.text = "Could not save this detail: ${e.javaClass.simpleName}"
                }
            }
        }
    }

    private fun showAnswer(text: String, sourceLanguage: String) {
        output.text = text
        resultTitle.text = when (planAction) {
            "CAPTURE_SCENE" -> "SCENE ANSWER"
            "CAPTURE_TEXT" -> "READ TEXT"
            "CAPTURE_BUS_BOARD", "SHOW_BUS_MAP" -> "BUS OPTIONS"
            "OPEN_TALK" -> "TRANSLATION"
            "OPEN_WEATHER" -> if (weatherIsCurrent) "CURRENT WEATHER" else "PLANNED WEATHER CHECK"
            "OPEN_BOOKING" -> "SAVED PLACE"
            "OPEN_CULTURE" -> "PLACE INFORMATION"
            "OPEN_DISCOVERY" -> "LOCAL DISCOVERY"
            "OPEN_MEMORY" -> "TRIP MEMORY"
            else -> "YATRALENS ANSWER"
        }
        resultTitle.visibility = View.VISIBLE
        resultDetails.visibility = View.GONE
        resultSource.visibility = View.GONE
        imageHolder.visibility = View.GONE
        mapHolder.visibility = View.GONE
        setMicStatus("MIC OFF · Answer ready", false)
        reviewLanguage = codes[language.selectedItemPosition]
        reviewed.setText(text)
        reviewed.visibility = View.GONE
        speakResult.visibility = View.VISIBLE
        speechConsent.visibility = if (voiceFlow) View.GONE else View.VISIBLE
        saveResult.visibility = if (saveKind == "OCR") View.VISIBLE else View.GONE
        if (sourceLanguage != reviewLanguage && cloud.isChecked) {
            worker.execute {
                try {
                    val wording = text.substringBefore("\nSource:").take(1800)
                    val translated = CloudGateway.translateText(wording, sourceLanguage, reviewLanguage)
                    main.post {
                        if (sessionVisible) {
                            output.text = "$text\n\nIn your language: ${translated.text}"
                            reviewed.setText(translated.text)
                            if (voiceFlow && !autoConversation) {
                                speakBrief(spokenPreview(translated.text))
                            }
                        }
                    }
                } catch (_: Exception) {
                    main.post {
                        if (sessionVisible) {
                            output.text = "$text\nTranslation unavailable; review the original."
                            if (voiceFlow && !autoConversation) {
                                speakBrief("Result vachindi, kani translation ledu. Screen lo text check cheyandi.")
                            }
                        }
                    }
                }
            }
        } else if (voiceFlow && !autoConversation) {
            speakBrief(spokenPreview(text))
        }
    }

    private fun showWeatherSources(feature: JSONObject, kind: String) {
        val links = SpannableStringBuilder("Sources: ")
        fun add(label: String, url: String) {
            if (url.isBlank() || !url.startsWith("https://")) return
            if (links.length > "Sources: ".length) links.append(" · ")
            val start = links.length
            links.append(label)
            links.setSpan(android.text.style.URLSpan(url), start, links.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        add("Open-Meteo ${if (kind == "current weather") "conditions" else "forecast"}",
            feature.optString(if (kind == "current weather") "source_url" else "forecast_source_url"))
        if (kind == "weather") add("IMD Kerala warning",
            feature.optString("official_warning_source_url"))
        if (links.length > "Sources: ".length) {
            resultSource.text = links
            resultSource.movementMethod = android.text.method.LinkMovementMethod.getInstance()
            resultSource.visibility = View.VISIBLE
        }
    }

    private fun spokenPreview(text: String): String = when {
        text.startsWith("Recognized:") -> "Point chesina text chadivanu. Screen lo translation check cheyandi."
        text.startsWith("Board text:") -> "Bus board chadivanu. Destination check chesi conductor tho confirm cheyandi."
        text.startsWith("The Alappuzha private-bus dataset") ->
            "Konni possible buses dorikayi. Screen lo numbers, map check chesi conductor tho confirm cheyandi."
        else -> text.lineSequence().firstOrNull().orEmpty().take(220)
    }

    private fun speakTravellerFollowup(englishQuestion: String) {
        worker.execute {
            val wording = try {
                CloudGateway.translateText(englishQuestion.take(240), "en-IN", "te-IN").text
            } catch (_: Exception) { "Konchem inka detail cheppandi." }
            main.post {
                if (sessionVisible) {
                    prompt.text = wording
                    tts?.speak(wording, TextToSpeech.QUEUE_FLUSH, null, "assistant-followup")
                }
            }
        }
    }

    private fun speakBrief(message: String, translateEnglish: Boolean = false) {
        if (!voiceFlow || !sessionVisible || message.isBlank()) return
        prompt.text = if (output.text.isNotBlank()) "Answer ready. Inko question adagandi leda speak anandi."
            else message.take(120)
        val serial = ++speechSerial
        val languageCode = codes[language.selectedItemPosition]
        recognizer?.destroy()
        recognizer = null
        if (cloud.isChecked) {
            setMicStatus("MIC OFF · Preparing spoken answer", false)
            worker.execute {
                try {
                    val wording = if (translateEnglish && languageCode == "te-IN")
                        try { CloudGateway.translateText(message.take(240), "en-IN", "te-IN").text }
                        catch (_: Exception) { "Result screen lo undi. Dayachesi check cheyandi." }
                    else message.take(260)
                    val audio = CloudGateway.synthesizeSpeech(wording, languageCode)
                    val file = File.createTempFile("yatralens-assistant-", ".wav", host.cacheDir)
                    file.writeBytes(audio)
                    audio.fill(0)
                    main.post {
                        if (!sessionVisible || serial != speechSerial) { file.delete(); return@post }
                        try {
                            stopSessionSpeech()
                            speechTemp = file
                            speechPlayer = MediaPlayer().apply {
                                setDataSource(file.absolutePath)
                                setOnCompletionListener {
                                    stopSessionSpeech()
                                    if (sessionVisible && serial == speechSerial) listen()
                                }
                                setOnErrorListener { _, _, _ ->
                                    stopSessionSpeech()
                                    if (sessionVisible && serial == speechSerial)
                                        speakWithPhone(wording, languageCode)
                                    true
                                }
                                prepare()
                                start()
                            }
                            setMicStatus("MIC OFF · Assistant speaking", false)
                        } catch (_: Exception) {
                            stopSessionSpeech()
                            speakWithPhone(wording, languageCode)
                        }
                    }
                } catch (_: Exception) {
                    main.post {
                        if (sessionVisible && serial == speechSerial)
                            speakWithPhone("Result screen lo undi. Dayachesi check cheyandi.", languageCode)
                    }
                }
            }
        } else speakWithPhone(if (translateEnglish && languageCode == "te-IN")
            "Result screen lo undi. Dayachesi check cheyandi." else message, languageCode)
    }

    private fun speakWithPhone(message: String, languageCode: String) {
        val engine = tts ?: run { listen(); return }
        val selected = Locale.forLanguageTag(languageCode)
        if (engine.isLanguageAvailable(selected) >= TextToSpeech.LANG_AVAILABLE)
            engine.language = selected
        setMicStatus("MIC OFF · Assistant speaking", false)
        if (engine.speak(message.take(260), TextToSpeech.QUEUE_FLUSH, null,
                "assistant-result") == TextToSpeech.ERROR) listen()
    }

    private fun setMicStatus(message: String, recording: Boolean) {
        if (!::micStatus.isInitialized) return
        micStatus.text = message
        pulseDot.setTextColor(host.getColor(if (recording) R.color.yl_sand else R.color.yl_sage))
        micPulse?.cancel()
        micPulse = null
        pulseDot.alpha = 1f
        if (recording) {
            micPulse = ObjectAnimator.ofFloat(pulseDot, View.ALPHA, 1f, 0.25f).apply {
                duration = 500L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                start()
            }
        }
    }

    private fun showBusAnswer(boardText: String, result: JSONObject) {
        if (!showTransportCandidates(result, boardText)) {
            val routeCheck = FeaturePresenter.describe("transport", result)
            showAnswer(routeCheck, "en-IN")
            resultDetails.text = "BOARD READ\n${boardText.take(240)}\n\nYOUR DESTINATION\n$destination"
            resultDetails.visibility = View.VISIBLE
        }
        showMapPin(result.optJSONObject("map_pin"), result.optString("source_url"))
    }

    private fun showBusMap() {
        if (!cloud.isChecked || destination.isBlank()) {
            prompt.text = "Tell me the destination and allow this selected request to use Sarvam."
            return
        }
        output.text = "Finding the selected place on the map…"
        worker.execute {
            try {
                val result = CloudGateway.transportMap(destination, request.text.toString())
                main.post {
                    if (!sessionVisible) return@post
                    if (!showTransportCandidates(result, null))
                        showAnswer(result.optString("reason"), "en-IN")
                    showMapPin(result.optJSONObject("map_pin"), result.optString("source_url"))
                }
            } catch (_: Exception) {
                main.post { if (sessionVisible) output.text = "Map pin unavailable. Check the connection and try again." }
            }
        }
    }

    private fun appendResultSection(builder: SpannableStringBuilder, heading: String, body: String) {
        if (body.isBlank()) return
        if (builder.isNotEmpty()) builder.append("\n")
        val start = builder.length
        builder.append(heading)
        builder.setSpan(StyleSpan(Typeface.BOLD), start, builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.append("\n").append(body)
    }

    private fun showTransportCandidates(result: JSONObject, boardText: String?): Boolean {
        val candidates = result.optJSONArray("candidates") ?: return false
        if (candidates.length() == 0) return false
        val first = candidates.optJSONObject(0)
        val listedStops = first?.optJSONArray("listed_times")?.optJSONObject(0)
            ?.optJSONArray("ordered_stops")
        val place = if (listedStops != null && listedStops.length() > 0)
            listedStops.optString(listedStops.length() - 1).ifBlank { destination }
        else result.optJSONObject("map_pin")?.optString("label")
            ?.ifBlank { destination } ?: destination
        showAnswer("${candidates.length()} possible private buses to $place. Check the listed direction below.",
            "en-IN")
        val detail = SpannableStringBuilder()
        val vehicles = (0 until candidates.length()).mapNotNull {
            candidates.optJSONObject(it)?.optString("vehicle_number")?.takeIf { number -> number.isNotBlank() }
        }
        appendResultSection(detail, "VEHICLE NUMBERS", vehicles.joinToString("\n") { "• $it" })
        val trip = first?.optJSONArray("listed_times")?.optJSONObject(0)
        val ordered = trip?.optJSONArray("ordered_stops")
        val direction = if (ordered != null && ordered.length() > 1)
            (0 until ordered.length()).joinToString(" → ") { ordered.optString(it) }
        else first?.optJSONArray("major_places")?.let { places ->
            (0 until places.length()).joinToString(", ") { places.optString(it) } +
                " (direction unconfirmed)"
        }.orEmpty()
        appendResultSection(detail, "LISTED PLACES", direction)
        if (request.text.toString().contains(Regex("\\b(time|when|schedule|arrival|departure)\\b",
                RegexOption.IGNORE_CASE)) && trip != null)
            appendResultSection(detail, "LISTED CLOCK TIME",
                "${trip.optString("listed_departure")} → ${trip.optString("listed_arrival")} · not live")
        if (!boardText.isNullOrBlank())
            appendResultSection(detail, "BOARD READ", boardText.take(180))
        appendResultSection(detail, "BEFORE BOARDING",
            "Confirm this vehicle, direction, stop and today's time with the conductor.")
        resultDetails.text = detail
        resultDetails.visibility = View.VISIBLE
        val source = result.optString("source_url")
        if (source.isNotBlank()) {
            val link = SpannableStringBuilder("Source: private bus schedule dataset")
            link.setSpan(android.text.style.URLSpan(source), 8, link.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            resultSource.text = link
            resultSource.movementMethod = android.text.method.LinkMovementMethod.getInstance()
            resultSource.visibility = View.VISIBLE
        }
        return true
    }

    private fun showSelectedImage() {
        val bytes = VisualEvidenceState.copy() ?: return
        try {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            imagePreview.setImageBitmap(bitmap)
            imagePreview.contentDescription = "Selected camera image used for this answer"
            imageCaption.text = VisualEvidenceState.caption + " · Phone capture, this session only"
            imageHolder.visibility = View.VISIBLE
        } finally { bytes.fill(0) }
    }

    private fun showCultureImage(result: JSONObject) {
        val placeImage = result.optString("place_photo_base64")
        val placeSource = result.optString("place_photo_source_url")
        if (placeImage.isNotBlank() && placeSource.startsWith("https://maps.google.com/")) {
            try {
                val bytes = android.util.Base64.decode(placeImage, android.util.Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                bytes.fill(0)
                if (bitmap != null) {
                    imagePreview.setImageBitmap(bitmap)
                    imagePreview.contentDescription = "Sourced photo of the possible landmark"
                    imageCaption.text = "Place photo · Google Maps Places · " +
                        result.optString("place_photo_attribution").ifBlank { "Photo contributor" }
                    imageHolder.visibility = View.VISIBLE
                    button(dynamicControls, "Open cultural photo source") {
                        host.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(placeSource))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    return
                }
            } catch (_: Exception) { /* Continue to the sourced encyclopedia image. */ }
        }
        val imageUrl = result.optString("image_url")
        val sourceUrl = result.optString("image_source_url")
        if (!imageUrl.startsWith("https://upload.wikimedia.org/") ||
            !sourceUrl.startsWith("https://en.wikipedia.org/wiki/")) return
        button(dynamicControls, "Open cultural photo source") {
            host.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(sourceUrl))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        worker.execute {
            try {
                val connection = java.net.URL(imageUrl).openConnection().apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                val bitmap = connection.getInputStream().use { BitmapFactory.decodeStream(it) }
                main.post {
                    if (sessionVisible && bitmap != null) {
                        imagePreview.setImageBitmap(bitmap)
                        imagePreview.contentDescription = "Sourced photo of the possible landmark"
                        imageCaption.text = "Cultural photo · Wikipedia / Wikimedia Commons\n$sourceUrl"
                        imageHolder.visibility = View.VISIBLE
                    }
                }
            } catch (_: Exception) { /* The sourced text remains visible without the image. */ }
        }
    }

    private fun showMapPin(pin: JSONObject?, mapsUrl: String = "") {
        val lat = pin?.optDouble("latitude") ?: return
        val lon = pin.optDouble("longitude")
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0)
            return
        mapHolder.removeAllViews()
        mapHolder.addView(TextView(host).apply {
            text = if (mapsUrl.startsWith("https://maps.google.com/"))
                "${pin.optString("label")} · Google Maps place pin. This is not a confirmed bus stop or route."
            else "Approximate ${pin.optString("label")} map pin · $lat, $lon\n" +
                "Town centre, not a bus stop or confirmed route · © OpenStreetMap contributors"
            textSize = 13f
            setTextColor(host.getColor(R.color.yl_mist))
        })
        val web = mapView ?: WebView(host).also {
            it.settings.javaScriptEnabled = true
            it.settings.domStorageEnabled = true
            mapView = it
        }
        web.parent?.let { (it as? ViewGroup)?.removeView(web) }
        mapHolder.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            dp(190)))
        val fallbackUrl = String.format(Locale.US,
            "https://www.openstreetmap.org/export/embed.html?bbox=%.6f%%2C%.6f%%2C%.6f%%2C%.6f&layer=mapnik&marker=%.6f%%2C%.6f",
            lon - 0.035, lat - 0.025, lon + 0.035, lat + 0.025, lat, lon)
        web.loadUrl(fallbackUrl)
        if (mapsUrl.startsWith("https://maps.google.com/"))
            button(mapHolder, "Open in Google Maps") {
                host.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(mapsUrl))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        mapHolder.visibility = View.VISIBLE
    }

    private fun saveContext(title: String, detail: String, source: String) {
        val trip = PhysicalContextEngine.situation.value.tripId ?: return
        try { TripMemoryStore(host).use { it.saveContextNote(trip, title, detail, source) } }
        catch (_: Exception) { output.append("\nThis result could not be added to trip memory.") }
    }

    private fun rememberReportedTripContext(spoken: String) {
        val reports = TripContextExtractor.reportedFacts(spoken)
        if (reports.isEmpty()) return
        try {
            TripMemoryStore(host).use { memory ->
                reports.forEach { report -> memory.saveReportedContext(
                    PhysicalContextEngine.situation.value.tripId, report.title, report.detail) }
            }
            refreshContextPreview()
        } catch (_: Exception) { /* The assistant task remains usable if local storage fails. */ }
    }

    private fun refreshContextPreview() {
        if (!::contextPreview.isInitialized) return
        val recent = try { TripMemoryStore(host).use { it.recentTripContext(3) } }
            catch (_: Exception) { emptyList() }
        contextPreview.text = if (recent.isEmpty()) "No saved trip context yet" else
            "Recent trip context\n" + recent.joinToString("\n") {
                "${formatTime(it.observedAt)} • ${it.title}"
            }
    }

    private fun speakReviewed() {
        val text = reviewed.text.toString().trim()
        if (text.isBlank()) { output.text = "Review an answer first."; return }
        if (PhysicalContextEngine.situation.value.tripId != null) {
            if (!speechConsent.isChecked && !(autoConversation && shareConsent.isChecked)) {
                output.text = "Approve sending this reviewed answer for Sarvam speech first."
                return
            }
            awaitingSpeechReview = false
            reviewedSpeechSerial++
            stopSpeech.visibility = View.VISIBLE
            recognizer?.destroy()
            recognizer = null
            SensorState.set("speech playback", "starting reviewed speech")
            send(TripSensingService.SPEAK_REVIEWED_TEXT, "cloudApproved" to true,
                "approvedText" to text, "language" to reviewLanguage)
            speechConsent.isChecked = false
            setMicStatus("MIC OFF · Speaking reviewed translation", false)
            main.removeCallbacks(speechStatusPoll)
            main.postDelayed(speechStatusPoll, 500)
            if (autoConversation) {
                nextConversationOwner = if (recordOwner == "TRAVELLER") "LOCAL" else "TRAVELLER"
                setStage("WAIT_PLAYBACK")
            }
        } else if (speechConsent.isChecked) {
            speechConsent.isChecked = false
            val playbackId = ++reviewedSpeechSerial
            stopSpeech.visibility = View.VISIBLE
            setMicStatus("MIC OFF · Preparing reviewed speech", false)
            worker.execute {
                try {
                    val audio = CloudGateway.synthesizeSpeech(text, reviewLanguage)
                    val file = File.createTempFile("yatralens-assistant-", ".wav", host.cacheDir)
                    file.writeBytes(audio)
                    main.post {
                        if (!sessionVisible || playbackId != reviewedSpeechSerial) {
                            file.delete(); return@post
                        }
                        try {
                            stopSessionSpeech()
                            speechTemp = file
                            speechPlayer = MediaPlayer().apply {
                                setDataSource(file.absolutePath)
                                setOnCompletionListener {
                                    stopSessionSpeech()
                                    stopSpeech.visibility = View.GONE
                                }
                                prepare()
                                start()
                            }
                            setMicStatus("MIC OFF · Speaking reviewed translation", false)
                        } catch (_: Exception) {
                            stopSessionSpeech()
                            stopSpeech.visibility = View.GONE
                            output.text = "Speech playback unavailable; the reviewed text remains visible."
                        }
                    }
                } catch (_: Exception) {
                    main.post { if (sessionVisible && playbackId == reviewedSpeechSerial) {
                        stopSpeech.visibility = View.GONE
                        output.text = "Sarvam speech unavailable; the reviewed text remains visible."
                    } }
                }
            }
        } else {
            val locale = Locale.forLanguageTag(reviewLanguage)
            if ((tts?.isLanguageAvailable(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED) >= TextToSpeech.LANG_AVAILABLE) {
                reviewedSpeechSerial++
                stopSpeech.visibility = View.VISIBLE
                tts?.language = locale
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "assistant-answer")
            } else output.text = "Local speech is unavailable for this language. The text remains visible."
        }
    }

    private val speechStatusPoll = object : Runnable {
        override fun run() {
            if (!sessionVisible || !::stopSpeech.isInitialized || stopSpeech.visibility != View.VISIBLE) return
            val status = SensorState.get("speech playback")
            if (status.startsWith("Finished") || status.startsWith("Stopped") ||
                status.startsWith("Unavailable") || status == "off") {
                stopSpeech.visibility = View.GONE
                return
            }
            main.postDelayed(this, 500)
        }
    }

    private fun stopReviewedSpeech() {
        reviewedSpeechSerial++
        speechSerial++
        main.removeCallbacks(speechStatusPoll)
        if (PhysicalContextEngine.situation.value.tripId != null)
            send(TripSensingService.STOP_SPEECH)
        try { tts?.stop() } catch (_: Exception) {}
        stopSessionSpeech()
        stopSpeech.visibility = View.GONE
        autoConversation = false
        nextConversationOwner = "LOCAL"
        taskStage = ""
        setMicStatus("MIC OFF · Speech stopped", false)
    }

    private fun stopSessionSpeech() {
        try { speechPlayer?.release() } catch (_: Exception) {}
        speechPlayer = null
        speechTemp?.delete()
        speechTemp = null
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

    private fun conversationText(value: String, size: Float, color: Int): TextView = TextView(host).apply {
        text = value
        textSize = size
        setTextColor(host.getColor(color))
        setPadding(0, dp(5), 0, dp(5))
        setTextIsSelectable(true)
    }

    private fun updateConversationBoard(owner: String, original: String, translation: String) {
        conversationBoard.visibility = View.VISIBLE
        if (owner == "LOCAL") {
            localTranscriptView.text = "TRANSCRIBED\n${original.take(500)}"
            localReplyView.text = "FOR YOU · TENGGLISH\n${translation.ifBlank { "Translation unavailable" }.take(500)}"
            localReplySpeech = translation
        } else {
            travellerTranscriptView.text = "MEE WORDS\n${original.take(500)}"
            travellerReplyView.text = "FOR LOCAL · MALAYALAM\n${translation.ifBlank { "Translation unavailable" }.take(500)}"
            travellerReplySpeech = translation
        }
    }

    private fun replayConversationReply(text: String, targetLanguage: String) {
        if (text.isBlank()) { output.text = "Ee side translation inka ready kaaledu."; return }
        if (taskStage.isNotBlank()) {
            output.text = "Current turn complete ayyaka Speak nokkandi."
            return
        }
        autoConversation = false
        reviewLanguage = targetLanguage
        reviewed.setText(text)
        speechConsent.isChecked = true
        speakReviewed()
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
        stopSessionSpeech()
        tts?.shutdown()
        mapView?.destroy()
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

    private fun languageName(code: String): String = when (code.substringBefore('-')) {
        "te" -> "Telugu"
        "ml" -> "Malayalam"
        "hi" -> "Hindi"
        "en" -> "English"
        else -> code
    }

    private fun isCurrentWeatherRequest(spoken: String): Boolean {
        val lower = spoken.lowercase(Locale.ROOT)
        if (Regex("\\b(current|now|right now|at the moment|temperature|raining now|ippudu|prastutam)\\b")
                .containsMatchIn(lower)) return true
        if (Regex("\\b(boat|ferry|ride|trip|sail|tomorrow|later|tonight)\\b")
                .containsMatchIn(lower) || weatherStartFromSpeech(spoken) != null) return false
        return true
    }

    private fun weatherStartFromSpeech(spoken: String): Int? {
        val match = Regex("\\b(\\d{1,2}):([0-5]\\d)\\s*(am|pm)?\\b", RegexOption.IGNORE_CASE)
            .find(spoken) ?: return null
        var hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        val period = match.groupValues[3].lowercase()
        if (hour !in 0..23 || (period.isNotBlank() && hour !in 1..12)) return null
        if (period == "pm" && hour < 12) hour += 12
        if (period == "am" && hour == 12) hour = 0
        val now = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
        var start = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!start.isAfter(now)) start = start.plusDays(1)
        return Duration.between(now, start).toMinutes().toInt().coerceIn(0, 2880)
    }

    private fun weatherWindowLabel(startMinutes: Int): String =
        DateTimeFormatter.ofPattern("d MMM, h:mm a 'IST'").format(
            ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).plusMinutes(startMinutes.toLong()))

    private fun formatTime(value: String): String = try {
        DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a 'IST'")
            .withZone(ZoneId.of("Asia/Kolkata"))
            .format(Instant.parse(value))
    } catch (_: Exception) { value }

    private fun languageListener(key: String) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            host.getSharedPreferences("assistant_languages", 0).edit().putInt(key, position).apply()
        }
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }
}
