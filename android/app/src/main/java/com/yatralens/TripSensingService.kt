package com.yatralens

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.Camera
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.TorchState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

/** User-started foreground collector. Frames and PCM are ephemeral; no raw media is stored. */
class TripSensingService : LifecycleService(), SensorEventListener, LocationListener {
    companion object {
        const val START = "com.yatralens.START"
        const val PAUSE_CAMERA = "com.yatralens.PAUSE_CAMERA"
        const val MUTE_MIC = "com.yatralens.MUTE_MIC"
        const val RESUME_MIC = "com.yatralens.RESUME_MIC"
        const val RESUME_CAMERA = "com.yatralens.RESUME_CAMERA"
        const val RESUME = "com.yatralens.RESUME"
        const val CAPTURE_TEXT = "com.yatralens.CAPTURE_TEXT"
        const val CAPTURE_SCENE = "com.yatralens.CAPTURE_SCENE"
        const val SAVE_OCR = "com.yatralens.SAVE_OCR"
        const val START_TURN = "com.yatralens.START_TURN"
        const val SEND_TURN = "com.yatralens.SEND_TURN"
        const val SAVE_TRANSCRIPT = "com.yatralens.SAVE_TRANSCRIPT"
        const val SPEAK_TRANSLATION = "com.yatralens.SPEAK_TRANSLATION"
        const val CHECK_WEATHER = "com.yatralens.CHECK_WEATHER"
        const val INTERPRET_OCR = "com.yatralens.INTERPRET_OCR"
        const val PROPOSE_AGREEMENT = "com.yatralens.PROPOSE_AGREEMENT"
        const val LOOKUP_CULTURE = "com.yatralens.LOOKUP_CULTURE"
        const val DISCOVER_LOCAL = "com.yatralens.DISCOVER_LOCAL"
        const val CHECK_TRANSPORT = "com.yatralens.CHECK_TRANSPORT"
        const val CHECK_DESTINATION = "com.yatralens.CHECK_DESTINATION"
        const val SAVE_DESTINATION = "com.yatralens.SAVE_DESTINATION"
        const val END = "com.yatralens.END"
        private const val CHANNEL = "trip_sensing"
        private const val NOTIFICATION_ID = 101
        private val SUPPORTED_TURN_LANGUAGES = setOf("en-IN", "ml-IN", "te-IN", "hi-IN")
    }

    private lateinit var store: EventStore
    private lateinit var memory: TripMemoryStore
    private lateinit var sensors: SensorManager
    private lateinit var locations: LocationManager
    private lateinit var connectivity: ConnectivityManager
    private lateinit var power: PowerManager
    private lateinit var audioManager: AudioManager
    private val main = Handler(Looper.getMainLooper())
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var boundCamera: Camera? = null
    private var imageCapture: ImageCapture? = null
    @Volatile private var captureBusy = false
    @Volatile private var autoTorchOwned = false
    private var cameraStarting = false
    private var pendingOcrCapture = false
    private var cameraWanted = false
    private var cameraAllowedForTrip = false
    private var micWanted = false
    @Volatile private var activeTrip = false
    @Volatile private var tripId: String? = null
    private var cameraLastPacketMs = 0L
    private var micLastPacketMs = 0L
    private var locationLastMs = 0L
    private var lastCameraSampleMs = 0L
    private var lastMicEventMs = 0L
    private var lastMotionEventMs = 0L
    private var lastHeadingEventMs = 0L
    private var lastGyroEventMs = 0L
    private var frameCount = 0L
    private var audioPacketCount = 0L
    @Volatile private var micRunning = false
    private var audioRecord: AudioRecord? = null
    private var micThread: Thread? = null
    private val turnLock = Any()
    private var turnBuffer: ByteArrayOutputStream? = null
    private var turnReady: ByteArray? = null
    private var turnStartedMs = 0L
    private var turnOwner = "TRAVELLER"
    private var turnSourceLanguage = "te-IN"
    private var turnTargetLanguage = "ml-IN"
    private var turnId = 0L
    @Volatile private var lastTranscript: CloudGateway.Transcript? = null
    @Volatile private var lastTranscriptDurationMs = 0L
    @Volatile private var lastOcr: CloudGateway.OcrResult? = null
    private var player: MediaPlayer? = null
    private var playbackFile: File? = null
    private var focusRequest: AudioFocusRequest? = null
    private var resumeMicAfterPlayback = false
    private var speechPreparing = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            SensorState.set("battery", if (level >= 0 && scale > 0) "${level * 100 / scale}%" else "unknown")
            SensorState.set("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
            PhysicalContextEngine.accept(ContextEvent.Battery(
                if (level >= 0 && scale > 0) level * 100 / scale else null,
                status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
                Instant.now()
            ))
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { updateNetwork() }
        override fun onLost(network: Network) { updateNetwork() }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { updateNetwork() }
    }

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        SensorState.set("thermal status", status)
        PhysicalContextEngine.accept(ContextEvent.Thermal(status, Instant.now()))
        store.record("THERMAL_STATUS", status.toString())
        if (status >= PowerManager.THERMAL_STATUS_SEVERE) {
            if (cameraProvider != null) stopCamera("THROTTLED_THERMAL")
        } else if (activeTrip && cameraWanted && cameraProvider == null) {
            startCamera()
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = EventStore(this)
        memory = TripMemoryStore(this)
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        locations = getSystemService(LOCATION_SERVICE) as LocationManager
        connectivity = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        power = getSystemService(POWER_SERVICE) as PowerManager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        cacheDir.listFiles()?.filter { it.name.startsWith("yatralens-speech-") && it.extension == "wav" }
            ?.forEach { it.delete() }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL, "Active YatraLens trip", NotificationManager.IMPORTANCE_LOW)
        )
        SensorState.set("trip", "stopped")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            START -> startTrip(if (intent.getStringExtra("mode") == "CONVERSATION") "CONVERSATION" else "CONTINUOUS")
            PAUSE_CAMERA -> { cameraWanted = false; stopCamera("PAUSED_BY_USER") }
            MUTE_MIC -> { resumeMicAfterPlayback = false; micWanted = false; stopMic("MUTED_BY_USER") }
            RESUME_MIC -> if (activeTrip && !speechPreparing && player == null) { micWanted = true; startMic() }
            RESUME_CAMERA -> if (activeTrip && cameraAllowedForTrip) { cameraWanted = true; startCamera() }
            RESUME -> if (activeTrip) {
                if (cameraAllowedForTrip) { cameraWanted = true; startCamera() }
                if (!speechPreparing && player == null) { micWanted = true; startMic() }
            }
            CAPTURE_TEXT -> captureText(intent.getBooleanExtra("cloudApproved", false))
            CAPTURE_SCENE -> captureScene()
            SAVE_OCR -> saveOcr(intent.getBooleanExtra("userApproved", false))
            START_TURN -> startTurn(intent)
            SEND_TURN -> sendTurn(intent.getBooleanExtra("cloudApproved", false))
            SAVE_TRANSCRIPT -> saveTranscript(intent.getBooleanExtra("userApproved", false))
            SPEAK_TRANSLATION -> speakTranslation(intent)
            CHECK_WEATHER -> checkWeather(intent)
            INTERPRET_OCR -> interpretOcr(intent)
            PROPOSE_AGREEMENT -> proposeAgreement(intent)
            LOOKUP_CULTURE -> lookupCulture(intent)
            DISCOVER_LOCAL -> discoverLocal(intent)
            CHECK_TRANSPORT -> checkTransport(intent)
            CHECK_DESTINATION -> checkDestination(intent)
            SAVE_DESTINATION -> saveDestination(intent)
            END -> endTrip()
            else -> if (!activeTrip) stopSelf()
        }
        if (activeTrip) updateNotification() else stopSelf()
        return START_NOT_STICKY
    }

    private fun has(permission: String): Boolean = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun startTrip(mode: String) {
        if (activeTrip) return
        var types = 0
        if (mode == "CONTINUOUS" && has(Manifest.permission.CAMERA))
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (has(Manifest.permission.RECORD_AUDIO)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION))
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (types == 0) { SensorState.set("trip", "permission required"); stopSelf(); return }
        try {
            startForeground(NOTIFICATION_ID, notification(), types)
        } catch (e: Exception) {
            SensorState.set("trip", "foreground start failed: ${e.javaClass.simpleName}")
            store.record("SERVICE_FAILED", e.javaClass.simpleName)
            stopSelf()
            return
        }
        activeTrip = true
        WeatherState.set(null)
        SceneState.set(null)
        FeatureState.clear()
        locationLastMs = 0L
        cameraAllowedForTrip = mode == "CONTINUOUS"
        val previousTripId = memory.interruptedTripId(mode)
        tripId = previousTripId ?: UUID.randomUUID().toString()
        PhysicalContextEngine.accept(ContextEvent.TripStarted(tripId!!, Instant.now()))
        if (previousTripId == null) memory.startTrip(tripId!!, mode)
        SensorState.set("trip ID", tripId)
        cameraWanted = cameraAllowedForTrip && has(Manifest.permission.CAMERA)
        micWanted = has(Manifest.permission.RECORD_AUDIO)
        SensorState.set("trip", "${if (previousTripId == null) "active" else "resumed after interruption"} since ${Instant.now()}")
        SensorState.set("camera", if (cameraWanted) "initializing" else "off in conversation mode")
        SensorState.set("microphone", "initializing")
        PhysicalContextEngine.accept(ContextEvent.Health(SensorKind.CAMERA,
            if (cameraWanted) CaptureState.INITIALIZING else CaptureState.DISABLED))
        PhysicalContextEngine.accept(ContextEvent.Health(SensorKind.MICROPHONE, CaptureState.INITIALIZING))
        store.record(if (previousTripId == null) "TRIP_STARTED" else "TRIP_RESUMED")
        startSystemInputs()
        startLocation()
        if (cameraWanted) startCamera()
        startMic()
        main.post(healthTick)
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pauseCamera = actionIntent(PAUSE_CAMERA, 2)
        val muteMic = actionIntent(MUTE_MIC, 3)
        val end = actionIntent(END, 4)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("YatraLens trip active")
            .setContentText("Camera ${sensorLabel(SensorKind.CAMERA, 15_000)} • Mic ${sensorLabel(SensorKind.MICROPHONE, 7_000)} • Location ${sensorLabel(SensorKind.LOCATION, 120_000)}")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Pause camera", pauseCamera)
            .addAction(android.R.drawable.ic_lock_silent_mode, "Mute mic", muteMic)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "End trip", end)
            .build()
    }

    private fun sensorLabel(kind: SensorKind, maxAgeMs: Long): String {
        val state = PhysicalContextEngine.situation.value.sensorStates[kind]?.state
        return when {
            PhysicalContextEngine.fresh(kind, maxAgeMs) -> "ON"
            state == CaptureState.THROTTLED -> "THROTTLED"
            state == CaptureState.INITIALIZING -> "STARTING"
            state == CaptureState.INTERRUPTED || state == CaptureState.FAILED -> "UNAVAILABLE"
            else -> "OFF"
        }
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this, requestCode, Intent(this, TripSensingService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun updateNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification())
    }

    private fun startCamera() {
        if (!activeTrip || !cameraWanted || !has(Manifest.permission.CAMERA)) {
            SensorState.set("camera", "permission required or off")
            PhysicalContextEngine.accept(ContextEvent.Health(SensorKind.CAMERA, CaptureState.DISABLED))
            return
        }
        if (power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            SensorState.set("camera", "THROTTLED_THERMAL")
            PhysicalContextEngine.accept(ContextEvent.Health(SensorKind.CAMERA, CaptureState.THROTTLED, "thermal"))
            store.record("CAMERA_THROTTLED", "thermal=${power.currentThermalStatus}")
            return
        }
        if (cameraProvider != null || cameraStarting) return
        cameraStarting = true
        SensorState.set("camera", "initializing")
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraStarting = false
            if (!activeTrip || !cameraWanted || power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
                SensorState.set("camera", if (cameraWanted) "THROTTLED_THERMAL" else "PAUSED_BY_USER")
                return@addListener
            }
            try {
                val provider = future.get()
                provider.unbindAll()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(cameraExecutor) { image ->
                    try {
                        val now = SystemClock.elapsedRealtime()
                        if (activeTrip && cameraWanted && now - lastCameraSampleMs >= 5000) {
                            lastCameraSampleMs = now
                            cameraLastPacketMs = now
                            frameCount++
                            SensorState.set("camera", "active; last frame ${Instant.now()}")
                            val luma = averageLuma(image)
                            SensorState.set("scene luminance", luma?.let {
                                "$it/255; ${if (it < 30) "too dark for reliable text capture" else "brightness sampled"}"
                            } ?: "unavailable")
                            PhysicalContextEngine.accept(ContextEvent.Scene(SceneObservation(
                                image.width, image.height, luma, Instant.now())))
                            SensorState.set("camera frames accepted", frameCount)
                            store.record("CAMERA_FRAME_ACCEPTED", "${image.width}x${image.height}")
                            main.post { updateNotification() }
                        }
                    } finally { image.close() }
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setJpegQuality(75)
                    .build()
                boundCamera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis, capture)
                cameraProvider = provider
                imageCapture = capture
                store.record("CAMERA_OPENED")
                if (pendingOcrCapture) captureText(true)
            } catch (e: Exception) {
                SensorState.set("camera", "interrupted: ${e.javaClass.simpleName}")
                PhysicalContextEngine.accept(ContextEvent.Health(SensorKind.CAMERA, CaptureState.INTERRUPTED, e.javaClass.simpleName))
                store.record("CAMERA_INTERRUPTED", e.javaClass.simpleName)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun averageLuma(image: ImageProxy): Int? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        var total = 0L
        var count = 0
        for (y in 0 until image.height step maxOf(1, image.height / 12)) {
            for (x in 0 until image.width step maxOf(1, image.width / 12)) {
                val index = y * plane.rowStride + x * plane.pixelStride
                if (index in 0 until buffer.limit()) {
                    total += buffer.get(index).toInt() and 0xff
                    count++
                }
            }
        }
        return if (count == 0) null else (total / count).toInt()
    }

    private fun stopCamera(reason: String) {
        finishCaptureLight()
        if (pendingOcrCapture) {
            pendingOcrCapture = false
            SensorState.set("text scan", "Capture interrupted: $reason; retry when camera is available")
        }
        cameraProvider?.unbindAll()
        cameraProvider = null
        boundCamera = null
        imageCapture = null
        cameraLastPacketMs = 0
        SensorState.set("camera", reason)
        PhysicalContextEngine.accept(ContextEvent.Health(SensorKind.CAMERA,
            if (reason == "THROTTLED_THERMAL") CaptureState.THROTTLED else CaptureState.DISABLED, reason))
        store.record("CAMERA_STOPPED", reason)
    }

    private fun withCaptureLight(kind: String, capture: ImageCapture, takePicture: () -> Unit) {
        if (captureBusy) { SensorState.set(kind, "Another capture is in progress"); return }
        captureBusy = true
        val scene = PhysicalContextEngine.situation.value.scene
        val camera = boundCamera
        val dim = PhysicalContextEngine.fresh(SensorKind.CAMERA, 15_000) &&
            scene?.averageLuma?.let { it < 45 } == true
        if (!dim || camera?.cameraInfo?.hasFlashUnit() != true ||
            camera.cameraInfo.torchState.value == TorchState.ON) {
            SensorState.set("capture light", if (dim) "Torch unavailable or already on" else "Ambient light sufficient")
            takePicture()
            return
        }
        val torchRequest = camera.cameraControl.enableTorch(true)
        torchRequest.addListener({
            try {
                torchRequest.get()
                autoTorchOwned = true
                SensorState.set("capture light", "Torch on for selected capture")
            } catch (_: Exception) {
                SensorState.set("capture light", "Torch unavailable; capturing without it")
            }
            main.postDelayed({
                if (activeTrip && imageCapture === capture &&
                    power.currentThermalStatus < PowerManager.THERMAL_STATUS_SEVERE) takePicture()
                else finishCaptureLight()
            }, 500)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun finishCaptureLight() {
        if (autoTorchOwned) {
            boundCamera?.cameraControl?.enableTorch(false)
            autoTorchOwned = false
            SensorState.set("capture light", "Torch off")
        }
        captureBusy = false
    }

    private fun captureText(approved: Boolean) {
        if (!approved) {
            SensorState.set("text scan", "Separate cloud approval required")
            return
        }
        val capture = imageCapture
        if (activeTrip && cameraWanted && capture == null && cameraStarting &&
            power.currentThermalStatus < PowerManager.THERMAL_STATUS_SEVERE) {
            pendingOcrCapture = true
            SensorState.set("text scan", "Waiting for camera to open for selected crop")
            return
        }
        if (!activeTrip || capture == null || SensorState.get("camera").startsWith("THROTTLED")) {
            SensorState.set("text scan", "Camera unavailable; cool phone or resume camera")
            return
        }
        pendingOcrCapture = false
        SensorState.set("text scan", "Capturing center text crop")
        SensorState.setOcrText("")
        lastOcr = null
        withCaptureLight("text scan", capture) { capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val plane = image.planes.firstOrNull() ?: error("No image bytes")
                    val buffer = plane.buffer
                    val jpeg = ByteArray(buffer.remaining())
                    buffer.get(jpeg)
                    val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: error("Could not decode capture")
                    jpeg.fill(0)
                    val x = bitmap.width / 10
                    val y = bitmap.height / 5
                    val cropped = Bitmap.createBitmap(bitmap, x, y, bitmap.width - 2 * x, bitmap.height - 2 * y)
                    val output = ByteArrayOutputStream()
                    require(cropped.compress(Bitmap.CompressFormat.JPEG, 75, output)) { "Could not encode crop" }
                    val cropBytes = output.toByteArray()
                    cropped.recycle()
                    bitmap.recycle()
                    if (cropBytes.size > 8_000_000) error("Crop is too large")
                    SensorState.set("text scan", "Sending selected crop to Sarvam Document AI")
                    Thread({
                        try {
                            val result = CloudGateway.ocrSelectedCrop(cropBytes)
                            if (result.status == "COMPLETED" && result.text.isNotBlank()) lastOcr = result
                            SensorState.setOcrText(result.text)
                            SensorState.set("text scan", if (result.text.isBlank())
                                "${result.status}: no text detected; retake or type text"
                                else "${result.status}; ${result.text.length} characters; review OCR text below")
                            store.record("TEXT_CROP_OCR", "status=${result.status};job=${result.jobId}")
                        } catch (e: Exception) {
                            SensorState.set("text scan", "OCR unavailable: ${e.message ?: e.javaClass.simpleName}; retake or type text")
                            store.record("TEXT_CROP_OCR_FAILED", e.javaClass.simpleName)
                        } finally { cropBytes.fill(0) }
                    }, "YatraLensSelectedOcr").start()
                } catch (e: Exception) {
                    SensorState.set("text scan", "Capture failed: ${e.javaClass.simpleName}")
                    store.record("TEXT_CROP_CAPTURE_FAILED", e.javaClass.simpleName)
                } finally { image.close(); finishCaptureLight() }
            }

            override fun onError(exception: ImageCaptureException) {
                SensorState.set("text scan", "Capture failed: ${exception.javaClass.simpleName}")
                store.record("TEXT_CROP_CAPTURE_FAILED", exception.javaClass.simpleName)
                finishCaptureLight()
            }
        }) }
    }

    private fun captureScene() {
        val capture = imageCapture
        if (!activeTrip || capture == null || power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            SensorState.set("scene scan", "Camera unavailable; start a camera trip or let the phone cool")
            return
        }
        SceneState.set(null)
        SensorState.set("scene scan", "Analyzing selected scene on this phone")
        withCaptureLight("scene scan", capture) { capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val plane = image.planes.firstOrNull() ?: error("No image bytes")
                    val buffer = plane.buffer
                    val jpeg = ByteArray(buffer.remaining())
                    buffer.get(jpeg)
                    val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                        ?: error("Could not decode capture")
                    jpeg.fill(0)
                    Thread({
                        try {
                            val result = MirageNative.describe(this@TripSensingService, bitmap)
                            SceneState.set(result)
                            SensorState.set("scene scan", if (result.optString("status").endsWith("FAILED"))
                                "Scene model unavailable: ${result.optString("status")}" else
                                "On-device scene result ready; confirm any boarding details with staff")
                            store.record("SCENE_ANALYZED", "model=Mirage 4.0.0;status=${result.optString("status", "OK")}")
                        } catch (e: Throwable) {
                            SensorState.set("scene scan", "Scene model unavailable: ${e.javaClass.simpleName}")
                            store.record("SCENE_FAILED", e.javaClass.simpleName)
                        } finally { bitmap.recycle() }
                    }, "YatraLensMirage").start()
                } catch (e: Exception) {
                    SensorState.set("scene scan", "Scene capture failed: ${e.javaClass.simpleName}")
                } finally { image.close(); finishCaptureLight() }
            }

            override fun onError(exception: ImageCaptureException) {
                SensorState.set("scene scan", "Scene capture failed: ${exception.javaClass.simpleName}")
                finishCaptureLight()
            }
        }) }
    }

    private fun saveOcr(approved: Boolean) {
        val result = lastOcr
        val id = tripId
        if (!approved || result == null || id == null) {
            SensorState.set("text scan", "No reviewed OCR text to save")
            return
        }
        try {
            val evidenceId = memory.saveOcrText(id, result.text, result.imageSha256, result.jobId)
            SensorState.set("text scan", "Saved reviewed OCR text as evidence $evidenceId")
            store.record("OCR_TEXT_SAVED", "evidence_id=$evidenceId")
            lastOcr = null
        } catch (e: Exception) {
            SensorState.set("text scan", "Could not save OCR text: ${e.javaClass.simpleName}")
        }
    }

    private fun startMic() {
        if (!activeTrip || !micWanted || !has(Manifest.permission.RECORD_AUDIO) || micRunning) return
        try {
            val rate = 16000
            val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            require(minimum > 0) { "AudioRecord unavailable" }
            val recorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum * 2, 4096))
                .build()
            require(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialize" }
            recorder.startRecording()
            require(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord failed to start" }
            audioRecord = recorder
            micRunning = true
            SensorState.set("microphone", "initializing; awaiting packet")
            PhysicalContextEngine.accept(ContextEvent.Health(SensorKind.MICROPHONE, CaptureState.INITIALIZING))
            store.record("MIC_OPENED")
            micThread = Thread({ audioLoop(recorder) }, "YatraLensAudio").also { it.start() }
        } catch (e: Exception) {
            SensorState.set("microphone", "interrupted: ${e.javaClass.simpleName}")
            PhysicalContextEngine.accept(ContextEvent.Health(SensorKind.MICROPHONE, CaptureState.INTERRUPTED, e.javaClass.simpleName))
            store.record("MIC_INTERRUPTED", e.javaClass.simpleName)
        }
    }

    private fun audioLoop(recorder: AudioRecord) {
        val samples = ShortArray(1600)
        while (micRunning) {
            val read = recorder.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
            if (read <= 0) {
                if (micRunning) {
                    SensorState.set("microphone", "interrupted: read $read")
                    PhysicalContextEngine.accept(ContextEvent.Health(SensorKind.MICROPHONE, CaptureState.INTERRUPTED, "read $read"))
                    store.record("MIC_READ_ERROR", read.toString())
                }
                break
            }
            if (!micRunning) break
            val now = SystemClock.elapsedRealtime()
            synchronized(turnLock) {
                val buffer = turnBuffer
                if (buffer != null) {
                    if (now - turnStartedMs < 10_000 && buffer.size() + read * 2 <= 320_000) {
                        val bytes = ByteArray(read * 2)
                        for (i in 0 until read) {
                            bytes[i * 2] = (samples[i].toInt() and 0xff).toByte()
                            bytes[i * 2 + 1] = ((samples[i].toInt() ushr 8) and 0xff).toByte()
                        }
                        buffer.write(bytes)
                    } else finishTurnLocked()
                }
            }
            micLastPacketMs = now
            audioPacketCount++
            if (now - lastMicEventMs >= 5000) {
                lastMicEventMs = now
                val rms = sqrt(samples.take(read).sumOf { it.toDouble() * it.toDouble() } / read)
                SensorState.set("microphone", "active; last packet ${Instant.now()}")
                PhysicalContextEngine.accept(ContextEvent.Audio(AudioObservation(rms.toInt(), Instant.now())))
                SensorState.set("audio packets", audioPacketCount)
                SensorState.set("audio RMS", rms.toInt())
                store.record("AUDIO_WINDOW_ACCEPTED", "rms=${rms.toInt()}")
                main.post { updateNotification() }
            }
        }
        if (micRunning && audioRecord === recorder) {
            micRunning = false
            try { recorder.stop() } catch (_: Exception) { }
            recorder.release()
            audioRecord = null
        }
    }

    private fun stopMic(reason: String) {
        synchronized(turnLock) {
            turnBuffer?.reset(); turnBuffer = null
            turnReady?.fill(0); turnReady = null
        }
        micRunning = false
        try { audioRecord?.stop() } catch (_: Exception) { }
        try { micThread?.join(500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        micThread = null
        audioRecord?.release()
        audioRecord = null
        micLastPacketMs = 0
        SensorState.set("microphone", reason)
        PhysicalContextEngine.accept(ContextEvent.Health(SensorKind.MICROPHONE, CaptureState.DISABLED, reason))
        store.record("MIC_STOPPED", reason)
    }

    private fun startTurn(intent: Intent) {
        if (speechPreparing || player != null) {
            SensorState.set("spoken turn", "Wait for speech playback to finish")
            return
        }
        if (!activeTrip || !micRunning) {
            SensorState.set("spoken turn", "microphone unavailable; resume it from the visible app")
            return
        }
        turnOwner = if (intent.getStringExtra("owner") == "LOCAL") "LOCAL" else "TRAVELLER"
        turnSourceLanguage = intent.getStringExtra("sourceLanguage") ?: "te-IN"
        turnTargetLanguage = intent.getStringExtra("targetLanguage") ?: "ml-IN"
        turnId = SystemClock.elapsedRealtimeNanos()
        synchronized(turnLock) {
            turnReady?.fill(0)
            turnReady = null
            turnBuffer = ByteArrayOutputStream(320_000)
            turnStartedMs = SystemClock.elapsedRealtime()
        }
        lastTranscript = null
        ConversationState.set(ConversationTurn(turnId, turnOwner, "", "",
            turnSourceLanguage, turnTargetLanguage, "RECORDING"))
        SensorState.set("spoken turn", "recording selected turn; maximum 10 seconds")
        store.record("TURN_STARTED", "owner=$turnOwner")
    }

    private fun finishTurnLocked() {
        val buffer = turnBuffer ?: return
        turnReady = buffer.toByteArray()
        buffer.reset()
        turnBuffer = null
        SensorState.set("spoken turn", "captured ${turnReady!!.size / 32000.0} seconds; awaiting explicit cloud send")
        store.record("TURN_CAPTURED", "bytes=${turnReady!!.size}")
    }

    private fun sendTurn(approved: Boolean) {
        if (!approved) {
            SensorState.set("spoken turn", "cloud send requires separate user consent")
            return
        }
        val pcm = synchronized(turnLock) {
            finishTurnLocked()
            val ready = turnReady
            turnReady = null
            ready
        }
        if (pcm == null || pcm.size < 3200) {
            SensorState.set("spoken turn", "no usable selected speech; record again")
            return
        }
        val owner = turnOwner
        val source = turnSourceLanguage
        val target = turnTargetLanguage
        val selectedTurnId = turnId
        SensorState.set("spoken turn", "transcribing selected turn via Sarvam")
        Thread({
            try {
                val result = CloudGateway.transcribePcm16(pcm)
                lastTranscript = result
                lastTranscriptDurationMs = pcm.size / 32L
                SensorState.set("spoken turn", "${result.language ?: "unknown language"}: ${result.text}")
                store.record("TURN_TRANSCRIBED", "model=${result.model};language=${result.language};owner=$owner")
                val detected = result.language?.takeIf { it in SUPPORTED_TURN_LANGUAGES } ?: source
                ConversationState.set(ConversationTurn(selectedTurnId, owner, result.text, "",
                    detected, target, "TRANSLATING"))
                try {
                    val translation = CloudGateway.translateText(result.text, detected, target)
                    ConversationState.set(ConversationTurn(selectedTurnId, owner, result.text,
                        translation.text, detected, target, "READY_FOR_REVIEW", translation.reviewFlags))
                    store.record("TURN_TRANSLATED", "owner=$owner;source=$detected;target=$target")
                } catch (e: Exception) {
                    ConversationState.set(ConversationTurn(selectedTurnId, owner, result.text, "",
                        detected, target, "TRANSLATION_UNAVAILABLE"))
                    SensorState.set("spoken turn", "Transcript ready; translation unavailable: ${e.message ?: e.javaClass.simpleName}")
                    store.record("TURN_TRANSLATION_FAILED", e.javaClass.simpleName)
                }
            } catch (e: Exception) {
                SensorState.set("spoken turn", "transcription unavailable: ${e.message ?: e.javaClass.simpleName}")
                store.record("TURN_TRANSCRIPTION_FAILED", e.javaClass.simpleName)
            } finally { pcm.fill(0) }
        }, "YatraLensCloudTurn").start()
    }

    private fun saveTranscript(approved: Boolean) {
        val transcript = lastTranscript
        val id = tripId
        if (!approved || transcript == null || id == null) {
            SensorState.set("spoken turn", "No reviewed transcript to save")
            return
        }
        try {
            val evidenceId = memory.saveTranscript(id, transcript.text, transcript.language,
                lastTranscriptDurationMs, owner = turnOwner)
            SensorState.set("spoken turn", "Saved reviewed transcript as evidence $evidenceId")
            store.record("TRANSCRIPT_SAVED", "evidence_id=$evidenceId")
            lastTranscript = null
        } catch (e: Exception) {
            SensorState.set("spoken turn", "Could not save transcript: ${e.javaClass.simpleName}")
        }
    }

    private fun speakTranslation(intent: Intent) {
        val turn = ConversationState.current
        val approvedText = intent.getStringExtra("approvedText")?.trim().orEmpty()
        if (!activeTrip || !intent.getBooleanExtra("cloudApproved", false) ||
            turn == null || turn.status != "READY_FOR_REVIEW" ||
            turn.id != intent.getLongExtra("turnId", -1L) || approvedText.isBlank() ||
            approvedText.length > 2500 || player != null || speechPreparing) {
            SensorState.set("speech playback", "A reviewed current turn and cloud approval are required")
            return
        }
        val shouldResume = micWanted && micRunning
        speechPreparing = true
        resumeMicAfterPlayback = shouldResume
        micWanted = false
        stopMic("MUTED_FOR_PLAYBACK")
        SensorState.set("speech playback", "Generating Sarvam voice for reviewed text")
        store.record("SPEECH_APPROVED", "owner=${turn.owner};language=${turn.targetLanguage}")
        Thread({
            try {
                val audio = CloudGateway.synthesizeSpeech(approvedText, turn.targetLanguage)
                val file = File.createTempFile("yatralens-speech-", ".wav", cacheDir)
                try { file.writeBytes(audio) } finally { audio.fill(0) }
                main.post {
                    if (!activeTrip) { file.delete(); return@post }
                    speechPreparing = false
                    if (ConversationState.current?.id != turn.id) {
                        file.delete()
                        restoreMicAfterPlayback()
                        return@post
                    }
                    playAudio(file, turn.targetLanguage)
                }
            } catch (e: Exception) {
                main.post {
                    speechPreparing = false
                    SensorState.set("speech playback", "Unavailable: ${e.message ?: e.javaClass.simpleName}")
                    store.record("SPEECH_FAILED", e.javaClass.simpleName)
                    restoreMicAfterPlayback()
                }
            }
        }, "YatraLensSarvamSpeech").start()
    }

    private fun playAudio(file: File, language: String) {
        try {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS) main.post { finishPlayback("Audio focus lost") }
                }.build()
            require(audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                "Audio focus unavailable"
            }
            focusRequest = request
            playbackFile = file
            val currentPlayer = MediaPlayer()
            player = currentPlayer
            currentPlayer.setAudioAttributes(attributes)
            currentPlayer.setDataSource(file.absolutePath)
            currentPlayer.setOnCompletionListener { finishPlayback("Finished speaking $language") }
            currentPlayer.setOnErrorListener { _, _, _ -> finishPlayback("Playback failed"); true }
            currentPlayer.prepare()
            currentPlayer.start()
            SensorState.set("speech playback", "Speaking approved $language text; microphone paused")
            store.record("SPEECH_PLAYING", "language=$language")
        } catch (e: Exception) {
            file.delete()
            SensorState.set("speech playback", "Unavailable: ${e.message ?: e.javaClass.simpleName}")
            store.record("SPEECH_FAILED", e.javaClass.simpleName)
            finishPlayback("Playback unavailable")
        }
    }

    private fun restoreMicAfterPlayback() {
        val shouldResume = resumeMicAfterPlayback
        resumeMicAfterPlayback = false
        if (activeTrip && shouldResume) { micWanted = true; startMic() }
    }

    private fun finishPlayback(status: String) {
        player?.setOnCompletionListener(null)
        player?.setOnErrorListener(null)
        player?.release()
        player = null
        playbackFile?.delete()
        playbackFile = null
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        SensorState.set("speech playback", status)
        restoreMicAfterPlayback()
    }

    private fun checkWeather(intent: Intent) {
        if (!intent.getBooleanExtra("locationCloudApproved", false)) {
            SensorState.set("weather", "Approve sharing current location for this forecast")
            return
        }
        if (!activeTrip || !PhysicalContextEngine.fresh(SensorKind.LOCATION, 120_000)) {
            SensorState.set("weather", "Fresh GPS fix unavailable; choose a place manually")
            return
        }
        if (PhysicalContextEngine.situation.value.device.networkAvailable != true) {
            SensorState.set("weather", "Offline; live forecast unavailable")
            return
        }
        val location = PhysicalContextEngine.situation.value.location ?: return
        if (location.accuracyM > 100f) {
            SensorState.set("weather", "GPS accuracy too low; choose a place manually")
            return
        }
        val startMinutes = intent.getIntExtra("startMinutes", 0)
        val durationMinutes = intent.getIntExtra("durationMinutes", 120)
        val activity = intent.getStringExtra("activity")?.trim().orEmpty()
        if (startMinutes !in 0..2880 || durationMinutes !in 1..720 || activity.isBlank()) {
            SensorState.set("weather", "Enter a valid activity, start offset and duration")
            return
        }
        val start = Instant.now().plusSeconds(startMinutes * 60L)
        val end = start.plusSeconds(durationMinutes * 60L)
        val selectedTripId = tripId
        SensorState.set("weather", "Checking forecast overlap for $activity")
        WeatherState.set(null)
        SceneState.set(null)
        FeatureState.clear()
        Thread({
            try {
                val result = CloudGateway.assessWeather(location.lat, location.lon, start, end, activity)
                if (!activeTrip || tripId != selectedTripId) return@Thread
                WeatherState.set(result)
                PhysicalContextEngine.accept(ContextEvent.Forecast(ForecastObservation(
                    result.decision, result.sourceUrl, Instant.parse(result.retrievedAt),
                    activity, start, end)))
                SensorState.set("weather", "${result.decision}: ${result.reason}")
                store.record("FORECAST_ASSESSED", "decision=${result.decision}")
            } catch (e: Exception) {
                SensorState.set("weather", "Forecast unavailable: ${e.message ?: e.javaClass.simpleName}")
                store.record("FORECAST_FAILED", e.javaClass.simpleName)
            }
        }, "YatraLensWeather").start()
    }

    private fun runCloudFeature(kind: String, approved: Boolean, work: () -> JSONObject) {
        if (!approved) { SensorState.set(kind, "Approve this cloud request first"); return }
        if (!activeTrip) { SensorState.set(kind, "Start a trip first"); return }
        if (PhysicalContextEngine.situation.value.device.networkAvailable != true) {
            SensorState.set(kind, "Offline; live check unavailable")
            return
        }
        val selectedTripId = tripId
        FeatureState.set(kind, null)
        SensorState.set(kind, "Checking")
        Thread({
            try {
                val result = work()
                if (activeTrip && tripId == selectedTripId) {
                    FeatureState.set(kind, result)
                    SensorState.set(kind, result.optString("status", "Ready"))
                    store.record("FEATURE_CHECKED", kind)
                }
            } catch (e: Exception) {
                SensorState.set(kind, "Unavailable: ${e.message ?: e.javaClass.simpleName}")
                store.record("FEATURE_FAILED", "$kind ${e.javaClass.simpleName}")
            }
        }, "YatraLens-$kind").start()
    }

    private fun freshAccurateLocation(kind: String): GeoObservation? {
        val location = PhysicalContextEngine.situation.value.location
        if (!PhysicalContextEngine.fresh(SensorKind.LOCATION, 120_000) || location == null ||
            location.accuracyM > 100f) {
            SensorState.set(kind, "Fresh, accurate location unavailable")
            return null
        }
        return location
    }

    private fun interpretOcr(intent: Intent) {
        val ocr = lastOcr
        if (ocr?.imageSha256.isNullOrBlank() || ocr?.text.isNullOrBlank()) {
            SensorState.set("ocr interpretation", "Capture readable text first")
            return
        }
        val language = intent.getStringExtra("targetLanguage") ?: "te-IN"
        runCloudFeature("ocr interpretation", intent.getBooleanExtra("cloudApproved", false)) {
            CloudGateway.interpretOcr(ocr!!.text, ocr.imageSha256!!, language)
        }
    }

    private fun proposeAgreement(intent: Intent) {
        val turn = ConversationState.current
        if (turn == null || turn.original.isBlank()) {
            SensorState.set("agreement", "Transcribe a spoken turn first")
            return
        }
        runCloudFeature("agreement", intent.getBooleanExtra("cloudApproved", false)) {
            CloudGateway.proposeAgreement(turn.original, turn.owner)
        }
    }

    private fun lookupCulture(intent: Intent) {
        val location = freshAccurateLocation("culture") ?: return
        val hint = intent.getStringExtra("nameHint")?.trim().orEmpty()
        if (hint.isBlank()) { SensorState.set("culture", "Enter a place name or sign text"); return }
        runCloudFeature("culture", intent.getBooleanExtra("locationCloudApproved", false)) {
            CloudGateway.cultureLookup(location.lat, location.lon, hint)
        }
    }

    private fun discoverLocal(intent: Intent) {
        val location = freshAccurateLocation("discovery") ?: return
        val interest = intent.getStringExtra("interest")?.trim().orEmpty()
        if (interest.isBlank()) { SensorState.set("discovery", "Enter a craft or interest"); return }
        runCloudFeature("discovery", intent.getBooleanExtra("locationCloudApproved", false)) {
            CloudGateway.discoverLocal(location.lat, location.lon, interest)
        }
    }

    private fun checkTransport(intent: Intent) {
        val board = intent.getStringExtra("boardText")?.trim().orEmpty()
        val destination = intent.getStringExtra("destination")?.trim().orEmpty()
        val routeJson = intent.getStringExtra("routeRecords") ?: "[]"
        if (board.isBlank() || destination.isBlank()) {
            SensorState.set("transport", "Enter board text and destination")
            return
        }
        runCloudFeature("transport", intent.getBooleanExtra("cloudApproved", false)) {
            CloudGateway.checkTransport(board, destination, JSONArray(routeJson))
        }
    }

    private fun checkDestination(intent: Intent) {
        val location = freshAccurateLocation("destination") ?: return
        val saved = tripId?.let { memory.latestDestination(it) }
        if (saved == null) {
            SensorState.set("destination", "Save sourced destination coordinates first")
            return
        }
        runCloudFeature("destination", intent.getBooleanExtra("locationCloudApproved", false)) {
            CloudGateway.checkDestination(location.lat, location.lon, location.accuracyM,
                location.observedAt, saved.latitude, saved.longitude, saved.sourceId)
        }
    }

    private fun saveDestination(intent: Intent) {
        val id = tripId
        val name = intent.getStringExtra("placeName")?.trim().orEmpty()
        val excerpt = intent.getStringExtra("bookingExcerpt")?.trim().orEmpty()
        val latitude = intent.getDoubleExtra("latitude", Double.NaN)
        val longitude = intent.getDoubleExtra("longitude", Double.NaN)
        if (!activeTrip || id == null || name.isBlank() || excerpt.isBlank() ||
            !latitude.isFinite() || !longitude.isFinite()) {
            SensorState.set("destination", "Enter the booking name, coordinates and source text")
            return
        }
        try {
            val saved = memory.saveDestination(id, name, latitude, longitude, excerpt)
            SensorState.set("destination", "Saved ${saved.placeName} as traveller-entered destination")
            store.record("DESTINATION_SAVED", "event=${saved.eventId}")
        } catch (e: Exception) {
            SensorState.set("destination", "Could not save destination: ${e.javaClass.simpleName}")
        }
    }

    private fun startLocation() {
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION) && !has(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            SensorState.set("location", "permission required")
            PhysicalContextEngine.accept(ContextEvent.Health(SensorKind.LOCATION, CaptureState.DISABLED, "permission required"))
            return
        }
        var requested = false
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (locations.isProviderEnabled(provider)) {
                    locations.requestLocationUpdates(provider, 10000L, 0f, this, Looper.getMainLooper())
                    requested = true
                }
            } catch (e: Exception) { store.record("LOCATION_PROVIDER_ERROR", "$provider ${e.javaClass.simpleName}") }
        }
        SensorState.set("location", if (requested) "awaiting fix" else "provider unavailable")
        PhysicalContextEngine.accept(ContextEvent.Health(SensorKind.LOCATION,
            if (requested) CaptureState.INITIALIZING else CaptureState.INTERRUPTED))
    }

    override fun onLocationChanged(location: Location) {
        if (!location.hasAccuracy()) {
            store.record("LOCATION_IGNORED", "accuracy unavailable")
            return
        }
        val nowMs = SystemClock.elapsedRealtime()
        val previous = PhysicalContextEngine.situation.value.location
        if (previous != null && nowMs - locationLastMs in 0..120_000 &&
            location.accuracy > previous.accuracyM) {
            store.record("LOCATION_IGNORED", "less accurate ${location.provider};accuracy_m=${location.accuracy.toInt()}")
            return
        }
        locationLastMs = nowMs
        SensorState.set("location", "active ${location.latitude}, ${location.longitude} ±${location.accuracy.toInt()} m; ${Instant.now()}")
        PhysicalContextEngine.accept(ContextEvent.Location(GeoObservation(
            location.latitude, location.longitude, location.accuracy, location.provider ?: "unknown", Instant.now())))
        SensorState.set("location provider", location.provider)
        store.record("LOCATION_UPDATED", "provider=${location.provider};accuracy_m=${location.accuracy.toInt()}")
        updateNotification()
    }

    private fun startSystemInputs() {
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        SensorState.set("thermal status", power.currentThermalStatus)
        PhysicalContextEngine.accept(ContextEvent.Thermal(power.currentThermalStatus, Instant.now()))
        power.addThermalStatusListener({ command -> main.post(command) }, thermalListener)
        try { connectivity.registerDefaultNetworkCallback(networkCallback) } catch (_: Exception) { }
        updateNetwork()
        for ((kind, type) in listOf(
            "rotation vector" to Sensor.TYPE_ROTATION_VECTOR,
            "accelerometer" to Sensor.TYPE_ACCELEROMETER,
            "gyroscope" to Sensor.TYPE_GYROSCOPE
        )) {
            val sensor = sensors.getDefaultSensor(type)
            if (sensor == null) SensorState.set(kind, "unavailable")
            else { sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL); SensorState.set(kind, "awaiting event") }
        }
    }

    private fun updateNetwork() {
        val network = connectivity.activeNetwork
        val caps = network?.let { connectivity.getNetworkCapabilities(it) }
        SensorState.set("network", if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) "available" else "offline")
        PhysicalContextEngine.accept(ContextEvent.Network(
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true, Instant.now()))
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rotation = FloatArray(9)
                val orientation = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                SensorManager.getOrientation(rotation, orientation)
                val heading = ((Math.toDegrees(orientation[0].toDouble()) + 360) % 360).toInt()
                SensorState.set("rotation vector", "active; heading ~$heading°")
                if (SystemClock.elapsedRealtime() - lastHeadingEventMs >= 2000) {
                    lastHeadingEventMs = SystemClock.elapsedRealtime()
                    PhysicalContextEngine.accept(ContextEvent.Heading(heading, Instant.now()))
                }
            }
            Sensor.TYPE_ACCELEROMETER -> SensorState.set("accelerometer", "active; ${event.values.take(3).joinToString { "%.1f".format(it) }} m/s²")
            Sensor.TYPE_GYROSCOPE -> {
                SensorState.set("gyroscope", "active; ${event.values.take(3).joinToString { "%.2f".format(it) }} rad/s")
                if (SystemClock.elapsedRealtime() - lastGyroEventMs >= 2000) {
                    lastGyroEventMs = SystemClock.elapsedRealtime()
                    PhysicalContextEngine.accept(ContextEvent.Gyroscope(GyroscopeObservation(
                        event.values[0], event.values[1], event.values[2], Instant.now())))
                }
            }
        }
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER && SystemClock.elapsedRealtime() - lastMotionEventMs >= 2000) {
            lastMotionEventMs = SystemClock.elapsedRealtime()
            PhysicalContextEngine.accept(ContextEvent.Movement(MovementObservation(
                event.values[0], event.values[1], event.values[2], Instant.now())))
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private val healthTick = object : Runnable {
        override fun run() {
            if (!activeTrip) return
            val now = SystemClock.elapsedRealtime()
            val thermalNow = power.currentThermalStatus
            SensorState.set("thermal status", thermalNow)
            PhysicalContextEngine.accept(ContextEvent.Thermal(thermalNow, Instant.now()))
            if (cameraWanted && cameraProvider == null && SensorState.get("camera") == "THROTTLED_THERMAL" &&
                thermalNow < PowerManager.THERMAL_STATUS_SEVERE) startCamera()
            if (cameraWanted && cameraProvider == null && SensorState.get("camera").startsWith("interrupted") &&
                thermalNow < PowerManager.THERMAL_STATUS_SEVERE) startCamera()
            if (cameraProvider != null && thermalNow >= PowerManager.THERMAL_STATUS_SEVERE)
                stopCamera("THROTTLED_THERMAL")
            SensorState.set("clock UTC", Instant.now())
            SensorState.set("elapsed realtime ms", now)
            if (cameraWanted && cameraLastPacketMs > 0 && now - cameraLastPacketMs > 15000) {
                SensorState.set("camera", "interrupted: no recent frames")
                PhysicalContextEngine.accept(ContextEvent.Health(SensorKind.CAMERA, CaptureState.INTERRUPTED, "no recent frames"))
                store.record("CAMERA_INTERRUPTED", "no recent frames")
                cameraLastPacketMs = 0
            }
            if (micWanted && micLastPacketMs > 0 && now - micLastPacketMs > 5000) {
                SensorState.set("microphone", "interrupted: no recent packets")
                PhysicalContextEngine.accept(ContextEvent.Health(SensorKind.MICROPHONE, CaptureState.INTERRUPTED, "no recent packets"))
                store.record("MIC_INTERRUPTED", "no recent packets")
                micLastPacketMs = 0
            }
            if (locationLastMs > 0 && now - locationLastMs > 120000) {
                SensorState.set("location", "stale: no recent fix")
                PhysicalContextEngine.accept(ContextEvent.Health(SensorKind.LOCATION, CaptureState.INTERRUPTED, "stale fix"))
            }
            updateNotification()
            main.postDelayed(this, 5000)
        }
    }

    private fun endTrip() {
        if (!activeTrip) { stopSelf(); return }
        activeTrip = false
        speechPreparing = false
        resumeMicAfterPlayback = false
        finishPlayback("off")
        WeatherState.set(null)
        cameraWanted = false
        cameraAllowedForTrip = false
        micWanted = false
        main.removeCallbacks(healthTick)
        stopCamera("off")
        stopMic("off")
        locations.removeUpdates(this)
        sensors.unregisterListener(this)
        try { connectivity.unregisterNetworkCallback(networkCallback) } catch (_: Exception) { }
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) { }
        power.removeThermalStatusListener(thermalListener)
        SensorState.set("location", "off")
        SensorState.set("trip", "stopped at ${Instant.now()}")
        lastTranscript = null
        lastOcr = null
        ConversationState.set(null)
        tripId?.let { memory.endTrip(it) }
        PhysicalContextEngine.accept(ContextEvent.TripEnded(Instant.now()))
        tripId = null
        store.record("TRIP_ENDED")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (activeTrip) endTrip()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

}

