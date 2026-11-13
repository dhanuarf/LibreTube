package com.github.libretube.ui.extensions

import android.view.View
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * This function animates a view in a downward direction by a specified amount.
 *
 * @param duration The duration of the animation in milliseconds.
 * @param dy The distance to move the view along the Y-axis.
 * @param onEnd An optional lambda function that is invoked when the animation ends.
 */
fun View.animateDown(
    duration: Long,
    dy: Float,
    onEnd: () -> Unit = { }
) {
    this
        .animate()
        .withEndAction { onEnd.invoke() }
        .y(dy)
        .setDuration(duration)
        .start()
}

/**
 * Animate view visibility using simple alpha animation
 */
fun View.animateVisibility(targetVisibility: Int, duration: Duration = 200L.milliseconds) {
    if (targetVisibility != View.VISIBLE && targetVisibility != View.INVISIBLE && targetVisibility != View.GONE) {
        error("animateVisibility(): Wrong targetVisibility value")
    }
    val isShowing = targetVisibility == View.VISIBLE
    val targetAlpha = if (isShowing) 1f else 0f
    val anim = animate()
        .alpha(targetAlpha)
        .setDuration(duration.inWholeMilliseconds)
    if (isShowing) anim.withStartAction { visibility = targetVisibility }.start()
    else anim.withEndAction { visibility = targetVisibility }.start()
}
