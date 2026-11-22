package com.yatralens

import org.json.JSONObject
import org.json.JSONArray
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant

/** Debug phone-to-local-backend adapter. The Sarvam key never enters the APK. */
object CloudGateway {
    data class Transcript(val text: String, val language: String?, val model: String)
    data class OcrResult(val status: String, val text: String, val jobId: String?,
                         val imageSha256: String?, val language: String, val spans: JSONArray)
    data class Translation(val text: String, val sourceLanguage: String, val targetLanguage: String,
                           val reviewFlags: List<String>)
    data class WeatherAssessment(val decision: String, val reason: String, val sourceUrl: String,
                                 val retrievedAt: String, val rainPercent: Double?, val gustKmh: Double?,
                                 val forecastSourceUrl: String, val officialWarningStatus: String,
                                 val officialWarningIssueDate: String?)

    fun planAssistant(utterance: String, followUpAnswer: String?, language: String): JSONObject {
        require(utterance.isNotBlank() && utterance.length <= 1000)
        require(language in setOf("en-IN", "ml-IN", "te-IN", "hi-IN"))
        return postJson("/v1/assistant/plan", JSONObject()
            .put("utterance", utterance)
            .put("follow_up_answer", followUpAnswer)
            .put("language_code", language))
    }

    fun assessWeather(lat: Double, lon: Double, start: Instant, end: Instant,
                      activity: String): WeatherAssessment {
        require(activity.isNotBlank() && end > start)
        val json = postJson("/v1/weather/assess", JSONObject()
            .put("lat", lat).put("lon", lon)
            .put("event_start", start.toString()).put("event_end", end.toString())
            .put("activity_label", activity))
        val forecastSource = json.optString("forecast_source_url")
        val officialSource = json.optString("alert_source_url")
        return WeatherAssessment(json.optString("decision"), json.optString("reason"),
            officialSource.ifBlank { forecastSource }, json.optString("forecast_retrieved_at"),
            if (json.isNull("max_precipitation_probability_percent")) null
                else json.optDouble("max_precipitation_probability_percent"),
            if (json.isNull("max_wind_gust_kmh")) null else json.optDouble("max_wind_gust_kmh"),
            forecastSource, json.optString("official_warning_status"),
            json.optString("official_warning_issue_date").takeIf { it.isNotBlank() })
    }

    fun currentWeather(lat: Double, lon: Double): JSONObject =
        postJson("/v1/weather/current", JSONObject().put("lat", lat).put("lon", lon))

    fun interpretOcr(text: String, imageSha256: String, sourceLanguage: String,
                     targetLanguage: String): JSONObject =
        postJson("/v1/ocr/interpret", JSONObject()
            .put("recognized_text", text.take(2000))
            .put("image_sha256", imageSha256)
            .put("source_language_code", sourceLanguage)
            .put("target_language_code", targetLanguage))

    fun proposeAgreement(transcript: String, owner: String): JSONObject =
        postJson("/v1/memory/propose-agreement", JSONObject()
            .put("transcript", transcript.take(2000)).put("turn_owner", owner))

    fun cultureLookup(lat: Double?, lon: Double?, hint: String): JSONObject {
        val body = JSONObject().put("name_hint", hint)
        if (lat != null && lon != null) body.put("lat", lat).put("lon", lon)
        return postJson("/v1/culture/lookup", body)
    }

    fun discoverLocal(lat: Double, lon: Double, interest: String): JSONObject =
        postJson("/v1/discovery/search", JSONObject()
            .put("lat", lat).put("lon", lon).put("interest", interest))

    fun checkTransport(boardText: String, destination: String, routeRecords: org.json.JSONArray): JSONObject =
        postJson("/v1/transport/check", JSONObject()
            .put("board_text", boardText).put("destination", destination)
            .put("route_records", routeRecords))

    fun transportMap(destination: String, requestText: String): JSONObject =
        postJson("/v1/transport/map", JSONObject()
            .put("destination", destination).put("request_text", requestText))

    fun checkDestination(lat: Double, lon: Double, accuracyM: Float, fixAt: Instant,
                         destinationLat: Double, destinationLon: Double,
                         destinationSourceId: String): JSONObject =
        postJson("/v1/geo/check", JSONObject()
            .put("current_lat", lat).put("current_lon", lon)
            .put("accuracy_m", accuracyM.toDouble()).put("fix_at", fixAt.toString())
            .put("destination_lat", destinationLat).put("destination_lon", destinationLon)
            .put("destination_source_id", destinationSourceId))

    fun translateText(text: String, sourceLanguage: String, targetLanguage: String): Translation {
        require(text.isNotBlank() && text.length <= 2000)
        if (sourceLanguage == targetLanguage) return Translation(text, sourceLanguage, targetLanguage, emptyList())
        val body = JSONObject().put("text", text).put("source_language_code", sourceLanguage)
            .put("target_language_code", targetLanguage)
        if (targetLanguage == "te-IN") body.put("output_script", "roman")
        val json = postJson("/v1/text/translate", body)
        val translated = json.optString("translated_text")
        require(translated.isNotBlank()) { "Translation returned no text" }
        val flags = json.optJSONArray("review_flags")?.let { list ->
            (0 until list.length()).map { list.optString(it) }
        } ?: emptyList()
        return Translation(translated, sourceLanguage, targetLanguage, flags)
    }

    fun synthesizeSpeech(text: String, language: String): ByteArray {
        require(text.isNotBlank() && text.length <= 2500)
        val json = postJson("/v1/speech/synthesize", JSONObject().put("text", text)
            .put("language_code", language))
        val encoded = json.optString("audio_base64")
        require(encoded.isNotBlank()) { "Speech provider returned no audio" }
        val wav = Base64.decode(encoded, Base64.DEFAULT)
        require(wav.isNotEmpty() && wav.size <= 8_000_000) { "Speech audio is too large" }
        return wav
    }

    private fun postJson(path: String, body: JSONObject): JSONObject {
        require(BuildConfig.DEBUG && BuildConfig.DEV_TOKEN.isNotBlank()) { "Debug backend token unavailable" }
        val connection = URL("http://127.0.0.1:8000$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10000
            connection.readTimeout = 50000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer ${BuildConfig.DEV_TOKEN}")
            connection.setRequestProperty("X-YatraLens-Cloud-Consent", "approved-current-trip")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val content = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (status !in 200..299) error("Backend returned HTTP $status")
            return JSONObject(content)
        } finally { connection.disconnect() }
    }

    /** Sends only the image the user explicitly captured and approved for OCR. */
    fun ocrSelectedCrop(jpeg: ByteArray, language: String): OcrResult {
        require(BuildConfig.DEBUG && BuildConfig.DEV_TOKEN.isNotBlank()) { "Debug backend token unavailable" }
        require(language in setOf("en-IN", "ml-IN", "te-IN", "hi-IN"))
        require(jpeg.size in 1..8_000_000 && jpeg.size >= 3 &&
            jpeg[0] == 0xff.toByte() && jpeg[1] == 0xd8.toByte() && jpeg[2] == 0xff.toByte()) {
            "Selected crop must be a JPEG under 8 MB"
        }
        val boundary = "YatraLensImage${System.currentTimeMillis()}"
        val connection = URL("http://127.0.0.1:8000/v1/ocr/indic").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10000
            connection.readTimeout = 90000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer ${BuildConfig.DEV_TOKEN}")
            connection.setRequestProperty("X-YatraLens-Cloud-Consent", "approved-current-trip")
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.outputStream.use { output ->
                output.write("--$boundary\r\nContent-Disposition: form-data; name=\"language\"\r\n\r\n$language\r\n".toByteArray())
                output.write("--$boundary\r\nContent-Disposition: form-data; name=\"purpose\"\r\n\r\nselected_text_crop\r\n".toByteArray())
                output.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"selected-crop.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
                output.write(jpeg)
                output.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val status = connection.responseCode
            val content = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (status !in 200..299) error("Backend returned HTTP $status")
            val json = JSONObject(content)
            return OcrResult(json.optString("status"), json.optString("text"),
                json.optString("job_id"), json.optString("image_sha256"),
                json.optString("language", language), json.optJSONArray("spans") ?: JSONArray())
        } finally { connection.disconnect() }
    }

    /** Sends one user-approved phone frame to the backend's primary visual model. */
    fun analyzeSelectedFrame(jpeg: ByteArray, task: String, question: String): JSONObject {
        require(BuildConfig.DEBUG && BuildConfig.DEV_TOKEN.isNotBlank())
        require(task in setOf("scene", "culture", "text", "bus_board"))
        require(jpeg.size in 3..8_000_000 && jpeg[0] == 0xff.toByte() &&
            jpeg[1] == 0xd8.toByte() && jpeg[2] == 0xff.toByte())
        val boundary = "YatraLensVision${System.currentTimeMillis()}"
        val connection = URL("http://127.0.0.1:8000/v1/vision/analyze").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10000
            connection.readTimeout = 50000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer ${BuildConfig.DEV_TOKEN}")
            connection.setRequestProperty("X-YatraLens-Cloud-Consent", "approved-current-trip")
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.outputStream.use { output ->
                output.write("--$boundary\r\nContent-Disposition: form-data; name=\"task\"\r\n\r\n$task\r\n".toByteArray())
                output.write("--$boundary\r\nContent-Disposition: form-data; name=\"question\"\r\n\r\n${question.take(500)}\r\n".toByteArray())
                output.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"selected-frame.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
                output.write(jpeg)
                output.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val status = connection.responseCode
            val content = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (status !in 200..299) error("Backend returned HTTP $status")
            return JSONObject(content)
        } finally { connection.disconnect() }
    }

    fun transcribePcm16(pcm: ByteArray): Transcript {
        require(BuildConfig.DEBUG && BuildConfig.DEV_TOKEN.isNotBlank()) { "Debug backend token unavailable" }
        require(pcm.isNotEmpty() && pcm.size <= 960_000) { "Turn must be 0–30 seconds" }
        val wav = wav(pcm)
        val boundary = "YatraLensBoundary${System.currentTimeMillis()}"
        val connection = URL("http://127.0.0.1:8000/v1/speech/transcribe").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10000
            connection.readTimeout = 50000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer ${BuildConfig.DEV_TOKEN}")
            connection.setRequestProperty("X-YatraLens-Cloud-Consent", "approved-current-trip")
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.outputStream.use { output ->
                output.write("--$boundary\r\nContent-Disposition: form-data; name=\"mode\"\r\n\r\ncodemix\r\n".toByteArray())
                output.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"turn.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
                output.write(wav)
                output.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val status = connection.responseCode
            val content = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (status !in 200..299) error("Backend returned HTTP $status")
            val json = JSONObject(content)
            val transcript = json.optString("transcript", "")
            if (json.optString("status") == "NO_SPEECH" || transcript.isBlank())
                error("No speech recognized; please record again")
            val language = if (json.isNull("language_code")) null else json.optString("language_code")
            return Transcript(transcript, language, json.optString("model"))
        } finally { connection.disconnect() }
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcm.size)
        header.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(16000)
        header.putInt(32000)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)
        return ByteArrayOutputStream(44 + pcm.size).apply {
            write(header.array()); write(pcm)
        }.toByteArray()
    }
}

