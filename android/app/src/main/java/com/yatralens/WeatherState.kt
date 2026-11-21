package com.yatralens

/** In-memory forecast result; the source URL and retrieval time remain visible. */
object WeatherState {
    @Volatile var current: CloudGateway.WeatherAssessment? = null
        private set

    fun set(value: CloudGateway.WeatherAssessment?) { current = value }
}
