package com.github.libretube.ui.views

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.core.view.doOnPreDraw
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.TimeBar
import androidx.media3.ui.TimeBar.OnScrubListener
import com.github.libretube.extensions.dpToPx
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.absoluteValue

@UnstableApi
open class DismissableTimeBar(
    context: Context,
    attributeSet: AttributeSet? = null
): DefaultTimeBar(context, attributeSet) {
    var exoPlayer: Player? = null

    private val listeners = mutableListOf<OnScrubListener>()

    // Drag-only seeking state
    private var initialX: Float = 0f
    private var initialY: Float = 0f
    private var shouldPlayerSeek: Boolean = true
    private var scrubTriggered: Boolean = false
    private val touchSlopPx: Int = ViewConfiguration.get(context).scaledTouchSlop
    private var timeDistancePerPixel: Float = 0f
    private val thumbXPosition = AtomicInteger(0)

    init {
        super.addListener(object : OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) {
                listeners.forEach { it.onScrubStart(timeBar, position) }
            }

            override fun onScrubMove(timeBar: TimeBar, position: Long) {
                listeners.forEach { it.onScrubMove(timeBar, position) }
            }

            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                listeners.forEach { it.onScrubStop(timeBar, position, canceled) }

                if (canceled) return
                if (shouldPlayerSeek) exoPlayer?.seekTo(position)
            }
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialX = event.x
                initialY = event.y
                scrubTriggered = false
                // Consume without forwarding to prevent tap-to-seek or thumb jump
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - initialX
                if (!scrubTriggered) {
                    // make sure that the user dragged at least touchSlopPx pixels
                    // which is the minimum amount to trigger a drag event
                    if (dx.absoluteValue > touchSlopPx) {
                        // Begin scrubbing now by synthesizing a DOWN at the current progress X
                        val fakeDown = MotionEvent.obtain(event).apply {
                            action = MotionEvent.ACTION_DOWN
                            setLocation(thumbXPosition.get() + dx, event.y)
                        }
                        val handled = super.onTouchEvent(fakeDown)
                        fakeDown.recycle()
                        scrubTriggered = true

                        return handled
                    }
                }

                else {
                    val fakeMove = MotionEvent.obtain(event).apply {
                        action = MotionEvent.ACTION_MOVE
                        setLocation(thumbXPosition.get() + dx, event.y)
                    }
                    val handled = super.onTouchEvent(fakeMove)
                    fakeMove.recycle()
                    return handled
                }

                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (scrubTriggered) {
                    shouldPlayerSeek =
                        event.y > TOUCH_SEEK_LIMIT_ABOVE.dpToPx() &&
                                event.y < TOUCH_SEEK_LIMIT_BELOW.dpToPx()

                    return super.onTouchEvent(event)
                }

                scrubTriggered = false
                performClick()

                return true
            }
        }

        return super.onTouchEvent(event)
    }

    /**
     * DO NOT CALL THIS METHOD DIRECTLY. Use [addSeekBarListener] instead!
     */
    @Deprecated("Use addSeekBarListener instead")
    override fun addListener(listener: OnScrubListener) {
        // do nothing, see below on how listeners should be set
    }

    /**
     * DO NOT CALL THIS METHOD DIRECTLY. Use [removeSeekBarListener] instead!
     */
    @Deprecated("Use removeSeekBarListener instead")
    override fun removeListener(listener: OnScrubListener) {
        // do nothing
    }

    /**
     * Wrapper to circumvent adding the listener created by [PlayerControlView]
     */
    fun addSeekBarListener(listener: OnScrubListener) {
        listeners.add(listener)
    }

    /**
     * Wrapper to circumvent removing the listener created by [PlayerControlView]
     */
    fun removeSeekBarListener(listener: OnScrubListener) {
        listeners.remove(listener)
    }

    fun setPlayer(player: Player) {
        this.exoPlayer = player
    }

    override fun setDuration(duration: Long) {
        super.setDuration(duration)
        fun calcTimeDistancePerPixel() {
            timeDistancePerPixel = duration/width.toFloat()
        }
        calcTimeDistancePerPixel()
        if (width == 0) {
            doOnPreDraw {
                calcTimeDistancePerPixel()
            }
        }
    }

    override fun setPosition(position: Long) {
        super.setPosition(position)
        thumbXPosition.set((position / timeDistancePerPixel).toInt())
    }

    companion object {
        private const val TOUCH_SEEK_LIMIT_ABOVE = -70f
        private const val TOUCH_SEEK_LIMIT_BELOW = 200f
    }
}