package com.github.libretube.ui.behaviors

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.AccelerateDecelerateInterpolator
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

        isScrollingUp(offset)?.also { scrollingUp ->
            if (scrollingUp == wasScrollingUp) return@also

            if (scrollingUp) onVisibleListener?.invoke()
            wasScrollingUp = scrollingUp

            val targetAlpha = if (scrollingUp) 1f else 0f
            val startAlpha = abs(targetAlpha - 1f)
            child.alpha = startAlpha
            child.animate()
                .alpha(targetAlpha)
                .setDuration(150L)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        }

        return true
    }

    fun setOnVisibleListener(onVisibleListener: () -> Unit) {
        this.onVisibleListener = onVisibleListener
    }

    private fun isScrollingUp(currentOffset: Int): Boolean? {
        val deltaOffset = currentOffset - prevOffset
        if (abs(deltaOffset) < touchSlop) return null
        prevOffset = currentOffset

        return deltaOffset > 0
    }
}