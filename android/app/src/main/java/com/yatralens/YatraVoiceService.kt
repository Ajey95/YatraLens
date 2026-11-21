package com.yatralens

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.util.concurrent.atomic.AtomicInteger

/** Registered as an optional system assistant; the OS owns selection and invocation. */
class YatraVoiceService : VoiceInteractionService() {
    companion object {
        const val WAKE_PREFS = "local_wake"
        const val WAKE_ENABLED = "enabled"
        const val WAKE_PHRASE = "hello assistant"
        @Volatile var active: YatraVoiceService? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private val wakeEpoch = AtomicInteger(0)
    private val wakeFailures = AtomicInteger(0)
    @Volatile private var recorder: AudioRecord? = null
    private val wakeWorkers = mutableSetOf<Thread>()
    @Volatile private var pausedForSession = false
    @Volatile private var pausedForTrip = false
    @Volatile private var destroyed = false
    private var model: Model? = null
    private var lastWakeMs = 0L
    private lateinit var power: PowerManager
    private lateinit var notifications: NotificationManager
    private var thermalSuspended = false
    private var thermalRegistered = false
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        if (status >= PowerManager.THERMAL_STATUS_CRITICAL) {
            thermalSuspended = true
            pauseWake()
        } else if (thermalSuspended) {
            thermalSuspended = false
            if (!pausedForSession) resumeWake()
        }
    }

    override fun onReady() {
        super.onReady()
        if (destroyed) return
        active = this
        setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST or
            VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
        power = getSystemService(POWER_SERVICE) as PowerManager
        notifications = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notifications.createNotificationChannel(NotificationChannel("local_wake",
            "Local assistant wake", NotificationManager.IMPORTANCE_LOW))
        if (!thermalRegistered) {
            power.addThermalStatusListener(thermalListener)
            thermalRegistered = true
        }
        resumeWake()
    }

    fun updateWakeSetting() {
        if (getSharedPreferences(WAKE_PREFS, MODE_PRIVATE).getBoolean(WAKE_ENABLED, false)) resumeWake()
        else pauseWake()
    }

    fun pauseForSession() {
        pausedForSession = true
        pauseWake()
    }

    fun resumeAfterSession() {
        pausedForSession = false
        resumeWake()
    }

    fun pauseForTrip() {
        pausedForTrip = true
        pauseWake()
    }

    fun resumeAfterTrip() {
        pausedForTrip = false
        resumeWake()
    }

    private fun pauseWake() {
        wakeEpoch.incrementAndGet()
        try { recorder?.stop() } catch (_: Exception) {}
        if (::notifications.isInitialized) notifications.cancel(102)
    }

    private fun resumeWake() {
        if (destroyed || pausedForSession || pausedForTrip ||
            PhysicalContextEngine.situation.value.tripId != null ||
            !getSharedPreferences(WAKE_PREFS, MODE_PRIVATE).getBoolean(WAKE_ENABLED, false) ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) ||
            power.currentThermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL) return
        try { recorder?.stop() } catch (_: Exception) {}
        val epoch = wakeEpoch.incrementAndGet()
        notifications.notify(102, Notification.Builder(this, "local_wake")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("YatraLens local wake is on")
            .setContentText("Say ‘Hello assistant’. Audio stays on this phone.")
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 102,
                Intent(this, HomeActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build())
        val worker = Thread({ listenForWake(epoch) }, "YatraLocalWake")
        synchronized(wakeWorkers) { wakeWorkers.add(worker) }
        worker.start()
    }

    private fun listenForWake(epoch: Int) {
        var localRecorder: AudioRecord? = null
        try {
            val loaded = synchronized(this) {
                model ?: Model(WakeModelStore.modelPath(this).absolutePath).also { model = it }
            }
            if (epoch != wakeEpoch.get() || destroyed) return
            Recognizer(loaded, 16_000f, "[\"hello assistant\",\"hey assistant\",\"[unk]\"]").use { recognizer ->
                val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0) { "Microphone sample rate unavailable" }
                val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minimum * 2, 4096))
                localRecorder = audio
                recorder = audio
                check(audio.state == AudioRecord.STATE_INITIALIZED) { "Wake microphone unavailable" }
                audio.startRecording()
                val samples = ByteArray(3200)
                var successfulReads = 0
                while (epoch == wakeEpoch.get() && !destroyed) {
                    val count = audio.read(samples, 0, samples.size)
                    if (count <= 0) break
                    if (++successfulReads == 10) wakeFailures.set(0)
                    if (recognizer.acceptWaveForm(samples, count)) {
                        val heard = JSONObject(recognizer.result).optString("text").trim().lowercase()
                        if (heard == WAKE_PHRASE || heard == "hey assistant") {
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastWakeMs > 12_000) {
                                lastWakeMs = now
                                main.post {
                                    if (epoch == wakeEpoch.get() && !destroyed) {
                                        pauseForSession()
                                        showSession(Bundle(), 0)
                                    }
                                }
                            }
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("YatraLocalWake", "Wake listening stopped: ${e.javaClass.simpleName}")
        } finally {
            try { localRecorder?.stop() } catch (_: Exception) {}
            localRecorder?.release()
            if (recorder === localRecorder) recorder = null
            if (epoch == wakeEpoch.get()) main.post {
                notifications.cancel(102)
                val failures = wakeFailures.incrementAndGet().coerceAtMost(5)
                val delayMs = (1000L shl failures).coerceAtMost(30_000L)
                main.postDelayed({ if (epoch == wakeEpoch.get() && !destroyed) resumeWake() }, delayMs)
            }
            synchronized(wakeWorkers) { wakeWorkers.remove(Thread.currentThread()) }
        }
    }

    override fun onDestroy() {
        destroyed = true
        pauseWake()
        if (::power.isInitialized && thermalRegistered) {
            power.removeThermalStatusListener(thermalListener)
            thermalRegistered = false
        }
        val workers = synchronized(wakeWorkers) { wakeWorkers.toList() }
        Thread({
            workers.forEach { it.join() }
            synchronized(this) { model?.close(); model = null }
        }, "YatraWakeCleanup").start()
        active = null
        super.onDestroy()
    }
}

class YatraVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = YatraVoiceSession(this)
}
