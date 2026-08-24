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
    private val touchSlopPx: Int = ViewConfiguration.get(context).scaledTouchSlop
    private val thumbXPosition = AtomicInteger(0)
    
    private var shouldPlayerSeek: Boolean = true
    private var scrubTriggered: Boolean = false
    private var initialX: Float = 0f
    private var initialY: Float = 0f
    private var timeDistancePerPixel: Float = 0f
    private var scrubTriggerStartDx: Float = 0f
    private var boundLeft: Int = -1
    private var boundRight: Int = -1
    private var currentWidth: Int = -1
    private var currentDuration: Long = -1L

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
                        scrubTriggerStartDx = dx
                        // Begin scrubbing now by synthesizing a DOWN at the current progress X
                        val fakeDown = MotionEvent.obtain(event).apply {
                            action = MotionEvent.ACTION_DOWN
                            setLocation(thumbXPosition.get() + dx - scrubTriggerStartDx, event.y)
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
                        setLocation(thumbXPosition.get() + dx - scrubTriggerStartDx, event.y)
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
                scrubTriggerStartDx = 0f
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
    
    private fun calculateTimeDistancePerPx(duration: Long): Float {
        return duration/(boundRight - boundLeft).toFloat()
    }
    
    private fun areBoundsInitialized(): Boolean {
        return boundLeft != -1 && boundRight != -1
    }
    
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val width = right - left
        if (!areBoundsInitialized() || currentWidth != width) {
            boundLeft = getPaddingLeft()
            boundRight = width - getPaddingRight()
            currentWidth = width
            if (currentDuration != -1L) {
                timeDistancePerPixel = calculateTimeDistancePerPx(currentDuration)
            }
        }
    }
    
    override fun setDuration(duration: Long) {
        super.setDuration(duration)
        currentDuration = duration;
        if (!areBoundsInitialized()) return
        
        timeDistancePerPixel = calculateTimeDistancePerPx(duration)
    }
    
    override fun setPosition(position: Long) {
        super.setPosition(position)
        if (!areBoundsInitialized() || timeDistancePerPixel == 0f) return;
        
        // Coerce the max value. the DefaultTimeBar has bounds check and the right side bound is
        // exclusive, hence `boundRight -1`
        val timeRelativePosX = (boundLeft + (position / timeDistancePerPixel).toInt())
                .coerceAtMost(boundRight - 1)
        thumbXPosition.set(timeRelativePosX)
    }

    companion object {
        private const val TOUCH_SEEK_LIMIT_ABOVE = -70f
        private const val TOUCH_SEEK_LIMIT_BELOW = 200f
    }
}