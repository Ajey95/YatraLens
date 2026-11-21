package com.yatralens

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Home is a read-only trip brief and wake setup; travel tasks live in the assistant bubble. */
class HomeActivity : Activity() {
    private lateinit var summary: TextView
    private lateinit var timeline: LinearLayout
    private lateinit var wake: CheckBox
    private lateinit var setupStatus: TextView
    private var pendingWake = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = ScrollView(this).apply { setBackgroundColor(getColor(R.color.yl_night)) }
        val stack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(32), dp(22), dp(32))
        }
        page.addView(stack)
        stack.addView(label("YatraLens", 29f, R.color.yl_ivory))
        stack.addView(label("Your trip at a glance", 17f, R.color.yl_sand))
        summary = label("Loading trip summary…", 17f, R.color.yl_ivory)
        summary.setPadding(dp(16), dp(14), dp(16), dp(14))
        summary.background = GradientDrawable().apply {
            setColor(getColor(R.color.yl_panel))
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), getColor(R.color.yl_sage))
        }
        stack.addView(summary)
        stack.addView(label("Context memory • newest first", 21f, R.color.yl_ivory))
        timeline = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        stack.addView(timeline)
        button(stack, "Refresh memory") { refreshSummary() }
        setupStatus = label("", 15f, R.color.yl_mist)
        stack.addView(setupStatus)
        button(stack, "Call the assistant bubble") {
            val assistant = YatraVoiceService.active
            if (assistant == null) setupStatus.text = "Select YatraLens as phone assistant first."
            else assistant.showSession(Bundle(), 0)
        }
        button(stack, "Set YatraLens as phone assistant") {
            val roles = getSystemService(ROLE_SERVICE) as RoleManager
            if (roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT))
                startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT), 42)
            else setupStatus.text = "This phone does not support changing the assistant."
        }
        button(stack, "Grant travel sensor permissions") {
            val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            requestPermissions(permissions.toTypedArray(), 41)
        }
        wake = CheckBox(this).apply {
            text = "Listen locally for ‘Hello assistant’"
            setTextColor(getColor(R.color.yl_ivory))
            textSize = 16f
            isChecked = getSharedPreferences(YatraVoiceService.WAKE_PREFS, MODE_PRIVATE)
                .getBoolean(YatraVoiceService.WAKE_ENABLED, false)
            setOnCheckedChangeListener { _, enabled ->
                if (enabled && !wakePermissionsGranted()) {
                    pendingWake = true
                    val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
                    requestPermissions(needed.toTypedArray(), 43)
                } else setWakeEnabled(enabled)
            }
        }
        stack.addView(wake)
        stack.addView(label("Wake audio stays on this phone. The microphone notification stays visible while listening. The assistant pauses on severe heat and during a trip.",
            14f, R.color.yl_mist))
        button(stack, "End active trip and stop sensors") {
            startService(Intent(this, TripSensingService::class.java).setAction(TripSensingService.END))
            setupStatus.text = "Stopping trip sensors."
        }
        setContentView(page)
        refreshSummary()
    }

    override fun onResume() { super.onResume(); if (::timeline.isInitialized) refreshSummary() }

    private fun refreshSummary() {
        val active = PhysicalContextEngine.situation.value.tripId != null
        val entries = try { TripMemoryStore(this).use { it.recentTimeline(60) } }
            catch (_: Exception) { emptyList() }
        summary.text = buildString {
            append(if (active) "Trip active" else "No active trip")
            append(" • ${entries.count { it.evidenceId != null }} saved details")
            append("\nCamera ${SensorState.get("camera")} • Mic ${SensorState.get("microphone")}")
            val facts = entries.filterNot { it.title == "Trip started" || it.title == "Trip ended" }
            append("\n\nRecent context")
            if (facts.isEmpty()) append("\nNothing saved yet. Ask the bubble to read or remember something.")
            else facts.take(3).forEach {
                append("\n• ${it.title}: ${it.detail.take(100)} (${formatTime(it.observedAt)})")
            }
        }
        timeline.removeAllViews()
        if (entries.isEmpty()) timeline.addView(label("No saved context yet.", 16f, R.color.yl_mist))
        entries.forEach { item ->
            timeline.addView(label("${formatTime(item.observedAt)}  ${item.title}\n" +
                "${item.detail.take(220)}\nSource: ${item.source}", 15f, R.color.yl_ivory).apply {
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(getColor(R.color.yl_panel))
                setTextIsSelectable(true)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
    }

    private fun wakePermissionsGranted(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            (Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    private fun setWakeEnabled(enabled: Boolean) {
        getSharedPreferences(YatraVoiceService.WAKE_PREFS, MODE_PRIVATE).edit()
            .putBoolean(YatraVoiceService.WAKE_ENABLED, enabled).apply()
        YatraVoiceService.active?.updateWakeSetting()
        setupStatus.text = if (enabled) "Local wake is on when YatraLens is your phone assistant."
            else "Local wake is off."
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 43 && pendingWake) {
            pendingWake = false
            if (wakePermissionsGranted()) setWakeEnabled(true)
            else { wake.isChecked = false; setupStatus.text = "Microphone and notification access are needed for local wake." }
        } else if (code == 41) setupStatus.text = "Permissions updated. Call the bubble to choose a travel task."
    }

    override fun onActivityResult(code: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(code, resultCode, data)
        if (code == 42) setupStatus.text = if (resultCode == RESULT_OK)
            "YatraLens is the phone assistant. Say ‘Hello assistant’ if local wake is on, or use the assistant gesture."
        else "The phone assistant was not changed."
    }

    private fun label(value: String, size: Float, color: Int): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(getColor(color))
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun button(parent: LinearLayout, value: String, click: () -> Unit) {
        parent.addView(Button(this).apply {
            text = value
            isAllCaps = false
            setTextColor(getColor(R.color.yl_ivory))
            backgroundTintList = ColorStateList.valueOf(getColor(R.color.yl_sage))
            setOnClickListener { click() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
    }

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    private fun formatTime(value: String): String = try {
        DateTimeFormatter.ofPattern("d MMM, h:mm a 'IST'").withZone(ZoneId.of("Asia/Kolkata"))
            .format(Instant.parse(value))
    } catch (_: Exception) { value }
}
