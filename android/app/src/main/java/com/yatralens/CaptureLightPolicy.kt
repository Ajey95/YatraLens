package com.yatralens

/** Camera auto-exposure can lift a dim sign above very low luminance values. */
object CaptureLightPolicy {
    const val DIM_LUMA_BELOW = 100

    fun isDim(averageLuma: Int?, freshFrame: Boolean): Boolean =
        freshFrame && averageLuma != null && averageLuma in 0 until DIM_LUMA_BELOW
}
