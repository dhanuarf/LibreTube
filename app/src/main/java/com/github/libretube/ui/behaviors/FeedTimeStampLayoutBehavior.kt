package com.github.libretube.ui.behaviors

import android.content.Context
import android.text.format.DateUtils
import android.util.AttributeSet
import android.view.View
import android.view.ViewConfiguration
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.appbar.AppBarLayout
import kotlin.math.abs

class FeedTimeStampLayoutBehavior(context: Context, attrs: AttributeSet) :
    CoordinatorLayout.Behavior<View>(context, attrs) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var onVisibleListener: (() -> Unit)? = null
    private var prevOffset = 0
    private var wasScrollingUp = false

    override fun layoutDependsOn(
        parent: CoordinatorLayout,
        child: View,
        dependency: View
    ): Boolean {
        return dependency is AppBarLayout
    }

    override fun onDependentViewChanged(
        parent: CoordinatorLayout,
        child: View,
        dependency: View
    ): Boolean {
        val offset = (dependency as AppBarLayout).bottom
        child.translationY = offset.toFloat()

        isScrollingUp(offset)?.let { scrollingUp ->
            if (scrollingUp == wasScrollingUp) return@let
            wasScrollingUp = scrollingUp
            if (scrollingUp) onVisibleListener?.invoke()
            child.animateVisibility(scrollingUp)
        }

        return true
    }

    fun setOnVisibleListener(onVisibleListener: () -> Unit) {
        this.onVisibleListener = onVisibleListener
    }

    private fun View.animateVisibility(show: Boolean) {
        animate().cancel()
        val animator = if (show) animateShowWithAutoHide() else animateHide(false)
        animator.setDuration(ANIMATION_DURATION)
        animator.start()
    }

    private fun View.animateShowWithAutoHide() = animate()
        .alpha(1f)
        .setStartDelay(0L)
        .withEndAction { animateHide(true).start() }

    private fun View.animateHide(delayed: Boolean) = animate()
        .alpha(0f)
        .setStartDelay(if (delayed) SHOW_DURATION else 0L)

    private fun isScrollingUp(currentOffset: Int): Boolean? {
        val deltaOffset = currentOffset - prevOffset
        if (abs(deltaOffset) < touchSlop) return null
        prevOffset = currentOffset

        return deltaOffset > 0
    }

    companion object {
        private const val SHOW_DURATION = 3 * DateUtils.SECOND_IN_MILLIS
        private const val ANIMATION_DURATION = 150L
    }
}