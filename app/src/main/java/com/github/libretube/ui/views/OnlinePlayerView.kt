package com.github.libretube.ui.views

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ColorFilter
import android.graphics.PorterDuff
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.util.AttributeSet
import android.view.Window
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.collection.LongLongPair
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import com.github.libretube.R
import com.github.libretube.constants.IntentData
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.enums.PlayerCommand
import com.github.libretube.extensions.seekBy
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.ThemeHelper
import com.github.libretube.obj.BottomSheetItem
import com.github.libretube.services.AbstractPlayerService
import com.github.libretube.ui.base.BaseActivity
import com.github.libretube.ui.dialogs.SubmitDeArrowDialog
import com.github.libretube.ui.dialogs.SubmitSegmentDialog
import com.github.libretube.ui.dialogs.SubmitSegmentVoteDialog
import com.github.libretube.ui.interfaces.OnlinePlayerOptions
import com.github.libretube.ui.models.CommonPlayerViewModel
import com.github.libretube.ui.models.PlayerViewModel
import com.github.libretube.util.PlayingQueue
import com.github.libretube.util.TextUtils.formatMillisecondsToString

@UnstableApi
class OnlinePlayerView(
    context: Context,
    attributeSet: AttributeSet? = null
) : CustomExoPlayerView(context, attributeSet) {
    private var playerOptions: OnlinePlayerOptions? = null
    private var playerViewModel: PlayerViewModel? = null
    private var commonPlayerViewModel: CommonPlayerViewModel? = null
    private var viewLifecycleOwner: LifecycleOwner? = null
    private var newSegmentStartAndEndTime = LongLongPair(Long.MAX_VALUE, Long.MIN_VALUE)

    private val handler = Handler(Looper.getMainLooper())

    private val playerCurrentPosition: Long
        get() = player?.currentPosition?.takeIf { it != C.TIME_UNSET } ?: 0

    private val playerDuration: Long
        get() = player?.duration?.takeIf { it != C.TIME_UNSET } ?: 0

    private val currentVideoId: String?
        get() = PlayingQueue.getCurrent()?.url?.toID()

    private val isSbCreateSegmentMenuOpened: Boolean
        get() = !backgroundBinding.createSegmentContainer.isGone

    private val isLayoutDirectionRTL : Boolean
        get() = context.resources.configuration.layoutDirection == LAYOUT_DIRECTION_RTL

    /**
     * Check if both value is initialized with valid value, end time must be higher than start time
     */
    private val isNewSegmentStartAndEndTimeValid: Boolean
        get() = newSegmentStartAndEndTime.first < newSegmentStartAndEndTime.second

    /**
     * The window that needs to be addressed for showing and hiding the system bars
     * If null, the activity's default/main window will be used
     */
    var currentWindow: Window? = null

    var selectedResolution: Int? = null
    var sponsorBlockAutoSkip = true
    var isOnNewSbSegmentPreviewMode = false


    @OptIn(UnstableApi::class)
    override fun getOptionsMenuItems(): List<BottomSheetItem> {
        return super.getOptionsMenuItems() +
                listOf(
                    BottomSheetItem(
                        context.getString(R.string.quality),
                        R.drawable.ic_hd,
                        this::getCurrentResolutionSummary
                    ) {
                        playerOptions?.onQualityClicked()
                    },
                    BottomSheetItem(
                        context.getString(R.string.audio_track),
                        R.drawable.ic_audio,
                        this::getCurrentAudioTrackTitle
                    ) {
                        playerOptions?.onAudioStreamClicked()
                    },
                    BottomSheetItem(
                        context.getString(R.string.captions),
                        R.drawable.ic_caption,
                        {
                            player?.let { PlayerHelper.getCurrentPlayedCaptionFormat(it)?.language }
                                ?: context.getString(R.string.none)
                        }
                    ) {
                        playerOptions?.onCaptionsClicked()
                    },
                    BottomSheetItem(
                        context.getString(R.string.stats_for_nerds),
                        R.drawable.ic_info
                    ) {
                        playerOptions?.onStatsClicked()
                    }
                )
    }

    private fun resetCreatedSbSegment(){
        newSegmentStartAndEndTime = LongLongPair(Long.MAX_VALUE, Long.MIN_VALUE)
        backgroundBinding.sbSegmentStartTimeBtn.text = "?"
        backgroundBinding.sbSegmentEndTimeBtn.text = "?"
    }

    private fun toggleSbCreateSegmentMenu(open: Boolean? = null) {
        backgroundBinding.createSegmentContainer.isGone =
            if (open != null) !open else isSbCreateSegmentMenuOpened

        if (isSbCreateSegmentMenuOpened) hideController()
    }

    private fun toggleSbOptions(){
        binding.sbButtonsContainer.isVisible = !binding.sbButtonsContainer.isVisible
    }

    private fun enterNewSegmentPreviewMode() {
        if (!isNewSegmentStartAndEndTimeValid) return

        playerViewModel?.previewSbSegmentStartAndEndTime = newSegmentStartAndEndTime

        val tintColor = ThemeHelper.getThemeColor(
            this.context, androidx.appcompat.R.attr.colorPrimary
        )
        val originalColorFilter = backgroundBinding.sbSegmentPreviewButton.colorFilter
        backgroundBinding.sbSegmentPreviewButton.setColorFilter(tintColor, PorterDuff.Mode.SRC_IN)

        isOnNewSbSegmentPreviewMode = true

        // rewind to [NEW_SEGMENT_PREVIEW_ROOM_MS] before the start of the segment
        player?.seekTo(newSegmentStartAndEndTime.first - NEW_SEGMENT_PREVIEW_ROOM_MS)
        player?.play()
        //toggleSbCreateSegmentMenu(false)

        // reset value [NEW_SEGMENT_PREVIEW_ROOM_MS] after the end of the segment
        handler.postDelayed({
            //toggleSbCreateSegmentMenu(true)
            backgroundBinding.sbSegmentPreviewButton.colorFilter = originalColorFilter
            isOnNewSbSegmentPreviewMode = false
            playerViewModel?.previewSbSegmentStartAndEndTime = null
        }, NEW_SEGMENT_PREVIEW_ROOM_MS * 2)
    }

    @OptIn(UnstableApi::class)
    private fun getCurrentResolutionSummary(): String {
        val currentQuality = player?.videoSize?.height ?: 0
        var summary = "${currentQuality}p"
        if (selectedResolution == null) {
            summary += " - ${context.getString(R.string.auto)}"
        } else if ((selectedResolution ?: 0) > currentQuality) {
            summary += " - ${context.getString(R.string.resolution_limited)}"
        }
        return summary
    }

    private fun getCurrentAudioTrackTitle(): String {
        if (player == null) {
            return context.getString(R.string.unknown_or_no_audio)
        }

        // The player reference should be not changed between the null check
        // and its access, so a non null assertion should be safe here
        val selectedAudioLanguagesAndRoleFlags =
            PlayerHelper.getAudioLanguagesAndRoleFlagsFromTrackGroups(
                player!!.currentTracks.groups,
                true
            )

        if (selectedAudioLanguagesAndRoleFlags.isEmpty()) {
            return context.getString(R.string.unknown_or_no_audio)
        }

        // At most one audio track should be selected regardless of audio
        // format or quality
        val firstSelectedAudioFormat = selectedAudioLanguagesAndRoleFlags[0]

        if (selectedAudioLanguagesAndRoleFlags.size == 1 &&
            firstSelectedAudioFormat.first == null &&
            !PlayerHelper.haveAudioTrackRoleFlagSet(
                firstSelectedAudioFormat.second
            )
        ) {
            // Regardless of audio format or quality, if there is only one
            // audio stream which has no language and no role flags, it
            // should mean that there is only a single audio track which
            // has no language or track type set in the video played
            // Consider it as the default audio track (or unknown)
            return context.getString(R.string.default_or_unknown_audio_track)
        }

        return PlayerHelper.getAudioTrackNameFromFormat(
            context,
            firstSelectedAudioFormat
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    fun initPlayerOptions(
        playerViewModel: PlayerViewModel,
        commonPlayerViewModel: CommonPlayerViewModel,
        viewLifecycleOwner: LifecycleOwner,
        playerOptions: OnlinePlayerOptions
    ) {
        this.playerViewModel = playerViewModel
        this.commonPlayerViewModel = commonPlayerViewModel
        this.viewLifecycleOwner = viewLifecycleOwner
        this.playerOptions = playerOptions

        commonPlayerViewModel.isFullscreen.observe(viewLifecycleOwner) { isFullscreen ->
            updateTopBarMargin()

            binding.fullscreen.isInvisible = PlayerHelper.autoFullscreenEnabled
            val fullscreenDrawable =
                if (isFullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen
            binding.fullscreen.setImageResource(fullscreenDrawable)

            binding.exoTitle.isInvisible = !isFullscreen
        }

        val updateSbImageResource = {
            binding.sbToggle.setImageResource(
                if (sponsorBlockAutoSkip) R.drawable.ic_sb_enabled else R.drawable.ic_sb_disabled
            )
        }
        updateSbImageResource()
        binding.sbToggle.setOnClickListener {
            sponsorBlockAutoSkip = !sponsorBlockAutoSkip
            (player as? MediaController)?.sendCustomCommand(
                AbstractPlayerService.runPlayerActionCommand, bundleOf(
                    PlayerCommand.SET_SB_AUTO_SKIP_ENABLED.name to sponsorBlockAutoSkip
                )
            )
            updateSbImageResource()
        }

        binding.sbOptionsToggle.setOnClickListener {
            toggleSbOptions()
        }

        syncQueueButtons()

        binding.sbCreate.isVisible =
            PreferenceHelper.getBoolean(PreferenceKeys.CONTRIBUTE_TO_SB, false)
        binding.sbCreate.setOnClickListener {
            toggleSbCreateSegmentMenu()
        }

        binding.sbVote.setOnClickListener {
            val submitSegmentVoteDialog = SubmitSegmentVoteDialog()
            submitSegmentVoteDialog.arguments = bundleOf(IntentData.videoId to currentVideoId)
            submitSegmentVoteDialog.show((context as BaseActivity).supportFragmentManager, null)
        }

        binding.dearrowSubmit.isVisible =
            PreferenceHelper.getBoolean(PreferenceKeys.CONTRIBUTE_TO_DEARROW, false)
        binding.dearrowSubmit.setOnClickListener {
            val bundleArgs = bundleOf(
                IntentData.videoId to currentVideoId,
                IntentData.currentPosition to playerCurrentPosition
            )

            val submitDialog = SubmitDeArrowDialog()
            submitDialog.arguments = bundleArgs
            submitDialog.show((context as BaseActivity).supportFragmentManager, null)
        }

        resetCreatedSbSegment()

        with(backgroundBinding) {
            sbSegmentStartTimeBtn.setOnClickListener {
                val playerPosition = playerCurrentPosition
                var resetEndTime = false

                // make sure the start time is not more than or equal to the end time. If it is, reset the
                // end time
                if (newSegmentStartAndEndTime.second != Long.MIN_VALUE &&
                    playerPosition >= newSegmentStartAndEndTime.second
                ) {
                    sbSegmentEndTimeBtn.text = "?"
                    resetEndTime = true
                }

                // remove leading hours if video duration is less than an hour
                sbSegmentStartTimeBtn.text =
                    playerPosition.formatMillisecondsToString(
                        playerDuration < DateUtils.HOUR_IN_MILLIS
                    )

                newSegmentStartAndEndTime = LongLongPair(
                    playerPosition,
                    if(resetEndTime) Long.MIN_VALUE else newSegmentStartAndEndTime.second
                )
            }
            sbSegmentEndTimeBtn.setOnClickListener {
                var playerPosition = playerCurrentPosition
                var resetStartTime = false

                // make sure the end time is not less than or equal to the start time. If it is, reset the
                // start time
                if (newSegmentStartAndEndTime.first != Long.MAX_VALUE &&
                    playerPosition <= newSegmentStartAndEndTime.first
                ) {
                    sbSegmentStartTimeBtn.text = "?"
                    resetStartTime = true
                }

                // remove leading hours if video duration is less than an hour
                sbSegmentEndTimeBtn.text =
                    playerPosition.formatMillisecondsToString(
                        playerDuration < DateUtils.HOUR_IN_MILLIS
                    )

                newSegmentStartAndEndTime = LongLongPair(
                    if(resetStartTime) Long.MAX_VALUE else newSegmentStartAndEndTime.first,
                    playerPosition
                )
            }

            sbSegmentFineBackwardButton.setOnClickListener { player?.seekBy(-100) }
            sbSegmentFineForwardButton.setOnClickListener { player?.seekBy(100) }

            sbSegmentPreviewButton.setOnClickListener {
                enterNewSegmentPreviewMode()
            }
            sbSegmentSubmitButton.setOnClickListener {
                if (!isNewSegmentStartAndEndTimeValid){
                    Toast.makeText(context, R.string.sb_invalid_segment, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val startAndEndTime = listOf(
                    newSegmentStartAndEndTime.first, newSegmentStartAndEndTime.second
                )

                val bundleArgs = bundleOf(
                    IntentData.currentPosition to playerCurrentPosition,
                    IntentData.duration to playerDuration,
                    IntentData.videoId to currentVideoId,
                    IntentData.newSegmentsStartAndEndTime to startAndEndTime.toLongArray()
                )

                val submitSegmentDialog = SubmitSegmentDialog()
                submitSegmentDialog.arguments = bundleArgs
                submitSegmentDialog.show((context as BaseActivity).supportFragmentManager, null)
            }

            sbSegmentCloseButton.setOnClickListener {
                if (isControllerFullyVisible) return@setOnClickListener

                toggleSbCreateSegmentMenu()
            }
        }
    }

    private fun syncQueueButtons() {
        if (!PlayerHelper.skipButtonsEnabled) return

        // toggle the visibility of next and prev buttons based on queue and whether the player view is locked
        binding.skipPrev.isInvisible = !PlayingQueue.hasPrev() || isPlayerLocked
        binding.skipNext.isInvisible = !PlayingQueue.hasNext() || isPlayerLocked

        handler.postDelayed(this::syncQueueButtons, 100)
    }

    /**
     * Update the displayed duration of the video
     */
    private fun updateDisplayedDuration() {
        if (isLive) return

        val duration = player?.duration?.div(1000) ?: return
        if (duration < 0) return

        val durationWithoutSegments = duration - playerViewModel?.segments?.value.orEmpty().sumOf {
            val (start, end) = it.segmentStartAndEnd
            end.toDouble() - start.toDouble()
        }.toLong()
        val durationString = DateUtils.formatElapsedTime(duration)

        binding.duration.text = if (durationWithoutSegments < duration) {
            "$durationString (${DateUtils.formatElapsedTime(durationWithoutSegments)})"
        } else {
            durationString
        }
    }

    /**
     * Animate away sponsorblock create segment menu to the side of the screen.
     * @param animateAway set to `true` to animate away, set to `false` to animate it to
     * the original position
     */
    private fun animateAwaySbCreateSegmentMenu(animateAway: Boolean){
        if (isSbCreateSegmentMenuOpened) {
            val viewWidth = backgroundBinding.createSegmentContainer.width
            var translationXTarget = 0.0f

            if(animateAway){
                translationXTarget = viewWidth * 85 / 100.0f
                // inverse direction if on RTL mode
                if (!isLayoutDirectionRTL) translationXTarget *= -1.0f
            }
            // no need to animate if it's already on target position
            if (backgroundBinding.createSegmentContainer.translationX == translationXTarget) {
                return
            }

            backgroundBinding.createSegmentContainer.animate().apply {
                translationX(translationXTarget)
                setDuration(TRANSLATION_ANIM_DURATION_MS)
                start()
            }
        }
    }

    override fun getWindow(): Window = currentWindow ?: activity.window

    override fun hideController() {
        super.hideController()

        if (commonPlayerViewModel?.isFullscreen?.value == true) {
            toggleSystemBars(false)
        }

        updateTopBarMargin()

        // collapse sponsorblock buttons
        if (binding.sbButtonsContainer.isVisible) toggleSbOptions()
        animateAwaySbCreateSegmentMenu(false)

    }

    override fun showController() {
        super.showController()

        if (commonPlayerViewModel?.isFullscreen?.value == true && !isPlayerLocked) {
            toggleSystemBars(true)
        }

        // only animate away the view on portrait mode
        if (!isFullscreen()) animateAwaySbCreateSegmentMenu(true)

    }

    override fun isFullscreen(): Boolean {
        return commonPlayerViewModel?.isFullscreen?.value ?: super.isFullscreen()
    }

    override fun minimizeOrExitPlayer() {
        playerOptions?.exitFullscreen()
    }

    override fun onPlaybackEvents(player: Player, events: Player.Events) {
        super.onPlaybackEvents(player, events)
        updateDisplayedDuration()
    }

    companion object {
        private const val TRANSLATION_ANIM_DURATION_MS = 120L
        private const val NEW_SEGMENT_PREVIEW_ROOM_MS = 2 * 1000L
    }
}
