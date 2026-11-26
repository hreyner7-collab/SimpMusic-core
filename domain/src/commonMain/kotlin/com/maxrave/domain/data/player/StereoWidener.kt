package com.maxrave.domain.data.player

/**
 * The stereo widener's one definition of what a slider position means, shared by both backends so
 * Windows and Android sound the same.
 *
 * A mid/side widener keeps the centre (mid) and scales the difference between the channels (side).
 * A width of 1.0 is the recording as it is; 2.0 doubles the side signal.
 */
object StereoWidener {
    const val MIN_WIDTH = 1.0f
    const val MAX_WIDTH = 2.5f

    /** Slider `0f..1f` to a width factor between [MIN_WIDTH] and [MAX_WIDTH]. */
    fun widthFor(amount: Float): Float {
        val a = if (amount.isNaN()) 0f else amount.coerceIn(0f, 1f)
        return MIN_WIDTH + a * (MAX_WIDTH - MIN_WIDTH)
    }
}
