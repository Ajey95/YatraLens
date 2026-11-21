package com.yatralens

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

/** Typed observations are distinct from verified claims and from desired sensor toggles. */
enum class SensorKind { CAMERA, MICROPHONE, LOCATION, ROTATION, ACCELEROMETER, GYROSCOPE, BATTERY, THERMAL, NETWORK }
enum class CaptureState { DISABLED, INITIALIZING, ACTIVE, THROTTLED, INTERRUPTED, FAILED }

data class SensorHealth(val state: CaptureState, val observedAt: Instant, val reason: String? = null,
                        val observedElapsedMs: Long = SystemClock.elapsedRealtime())
data class GeoObservation(val lat: Double, val lon: Double, val accuracyM: Float, val provider: String, val observedAt: Instant)
data class SceneObservation(val width: Int, val height: Int, val averageLuma: Int?, val observedAt: Instant)
data class AudioObservation(val rms: Int, val observedAt: Instant)
data class MovementObservation(val x: Float, val y: Float, val z: Float, val observedAt: Instant)
data class GyroscopeObservation(val x: Float, val y: Float, val z: Float, val observedAt: Instant)
data class DeviceObservation(val batteryPercent: Int?, val charging: Boolean?, val thermalStatus: Int?, val networkAvailable: Boolean?)
data class ForecastObservation(val decision: String, val sourceUrl: String, val retrievedAt: Instant,
                               val activity: String, val eventStart: Instant, val eventEnd: Instant)

data class TripSituation(
    val tripId: String? = null,
    val startedAt: Instant? = null,
    val lastUpdatedAt: Instant = Instant.now(),
    val sensorStates: Map<SensorKind, SensorHealth> = emptyMap(),
    val location: GeoObservation? = null,
    val scene: SceneObservation? = null,
    val audio: AudioObservation? = null,
    val movement: MovementObservation? = null,
    val gyroscope: GyroscopeObservation? = null,
    val headingDegrees: Int? = null,
    val forecast: ForecastObservation? = null,
    val device: DeviceObservation = DeviceObservation(null, null, null, null)
)

sealed interface ContextEvent {
    val observedAt: Instant
    data class TripStarted(val id: String, override val observedAt: Instant) : ContextEvent
    data class TripEnded(override val observedAt: Instant) : ContextEvent
    data class Health(val kind: SensorKind, val state: CaptureState, val reason: String? = null,
                      override val observedAt: Instant = Instant.now()) : ContextEvent
    data class Location(val value: GeoObservation, override val observedAt: Instant = value.observedAt) : ContextEvent
    data class Scene(val value: SceneObservation, override val observedAt: Instant = value.observedAt) : ContextEvent
    data class Audio(val value: AudioObservation, override val observedAt: Instant = value.observedAt) : ContextEvent
    data class Movement(val value: MovementObservation, override val observedAt: Instant = value.observedAt) : ContextEvent
    data class Gyroscope(val value: GyroscopeObservation, override val observedAt: Instant = value.observedAt) : ContextEvent
    data class Heading(val value: Int, override val observedAt: Instant) : ContextEvent
    data class Battery(val percent: Int?, val charging: Boolean?, override val observedAt: Instant) : ContextEvent
    data class Thermal(val status: Int, override val observedAt: Instant) : ContextEvent
    data class Network(val available: Boolean, override val observedAt: Instant) : ContextEvent
    data class Forecast(val value: ForecastObservation, override val observedAt: Instant = value.retrievedAt) : ContextEvent
}

/** Single authoritative in-process state; persistence/replay lives in TripMemoryStore. */
object PhysicalContextEngine {
    private val mutable = MutableStateFlow(TripSituation())
    val situation: StateFlow<TripSituation> = mutable

    @Synchronized fun accept(event: ContextEvent) {
        val current = mutable.value
        val next = when (event) {
            is ContextEvent.TripStarted -> TripSituation(tripId = event.id, startedAt = event.observedAt)
            is ContextEvent.TripEnded -> current.copy(tripId = null, startedAt = null,
                sensorStates = current.sensorStates.mapValues { SensorHealth(CaptureState.DISABLED, event.observedAt) })
            is ContextEvent.Health -> current.copy(sensorStates = current.sensorStates +
                (event.kind to SensorHealth(event.state, event.observedAt, event.reason)))
            is ContextEvent.Location -> current.copy(location = event.value,
                sensorStates = current.sensorStates + (SensorKind.LOCATION to SensorHealth(CaptureState.ACTIVE, event.observedAt)))
            is ContextEvent.Scene -> current.copy(scene = event.value,
                sensorStates = current.sensorStates + (SensorKind.CAMERA to SensorHealth(CaptureState.ACTIVE, event.observedAt)))
            is ContextEvent.Audio -> current.copy(audio = event.value,
                sensorStates = current.sensorStates + (SensorKind.MICROPHONE to SensorHealth(CaptureState.ACTIVE, event.observedAt)))
            is ContextEvent.Movement -> current.copy(movement = event.value,
                sensorStates = current.sensorStates + (SensorKind.ACCELEROMETER to SensorHealth(CaptureState.ACTIVE, event.observedAt)))
            is ContextEvent.Gyroscope -> current.copy(gyroscope = event.value,
                sensorStates = current.sensorStates + (SensorKind.GYROSCOPE to SensorHealth(CaptureState.ACTIVE, event.observedAt)))
            is ContextEvent.Heading -> current.copy(headingDegrees = event.value,
                sensorStates = current.sensorStates + (SensorKind.ROTATION to SensorHealth(CaptureState.ACTIVE, event.observedAt)))
            is ContextEvent.Battery -> current.copy(device = current.device.copy(batteryPercent = event.percent, charging = event.charging))
            is ContextEvent.Thermal -> current.copy(device = current.device.copy(thermalStatus = event.status))
            is ContextEvent.Network -> current.copy(device = current.device.copy(networkAvailable = event.available))
            is ContextEvent.Forecast -> current.copy(forecast = event.value)
        }
        mutable.value = next.copy(lastUpdatedAt = event.observedAt)
    }

    /** An old observation must never be described as currently live. */
    fun fresh(kind: SensorKind, maxAgeMs: Long, elapsedNowMs: Long = SystemClock.elapsedRealtime()): Boolean {
        val health = mutable.value.sensorStates[kind] ?: return false
        return health.state == CaptureState.ACTIVE &&
            elapsedNowMs - health.observedElapsedMs in 0..maxAgeMs
    }
}

