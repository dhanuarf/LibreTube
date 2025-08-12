package com.github.libretube.ui.views

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.util.AttributeSet
import android.view.Window
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.collection.FloatFloatPair
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
import com.github.libretube.api.obj.Segment
import com.github.libretube.constants.IntentData
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.enums.PlayerCommand
import com.github.libretube.extensions.seekBy
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.obj.BottomSheetItem
import com.github.libretube.services.AbstractPlayerService
import com.github.libretube.ui.base.BaseActivity
import com.github.libretube.ui.dialogs.SubmitDeArrowDialog
import com.github.libretube.ui.dialogs.SubmitSegmentDialog
import com.github.libretube.ui.interfaces.OnlinePlayerOptions
import com.github.libretube.ui.models.CommonPlayerViewModel
import com.github.libretube.ui.models.PlayerViewModel
import com.github.libretube.util.PlayingQueue
import com.github.libretube.util.TextUtils
import com.github.libretube.util.TextUtils.formatMillisecondsToString
import java.lang.StringBuilder
import java.util.concurrent.TimeUnit

@UnstableApi
class OnlinePlayerView(
    context: Context,
    attributeSet: AttributeSet? = null
) : CustomExoPlayerView(context, attributeSet) {
    private var playerOptions: OnlinePlayerOptions? = null
    private var playerViewModel: PlayerViewModel? = null
    private var commonPlayerViewModel: CommonPlayerViewModel? = null
    private var viewLifecycleOwner: LifecycleOwner? = null

    private val handler = Handler(Looper.getMainLooper())

    private val playerCurrentPosition: Long
        get() = player?.currentPosition?.takeIf { it != C.TIME_UNSET } ?: 0

    private val playerDuration: Long
        get() = player?.duration?.takeIf { it != C.TIME_UNSET } ?: 0

    /**
     * The window that needs to be addressed for showing and hiding the system bars
     * If null, the activity's default/main window will be used
     */
    var currentWindow: Window? = null

    var selectedResolution: Int? = null
    var sponsorBlockAutoSkip = true
    var newSegmentStartAndEndTime = LongLongPair(Long.MAX_VALUE, Long.MIN_VALUE)
    val isSponsorBlockSubmissionMenuOpened: Boolean
        get() = !backgroundBinding.createSegmentContainer.isGone

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

    fun openSponsorBlockSubmissionMenu(open: Boolean) {
        backgroundBinding.createSegmentContainer.isGone = !open
        if (open) hideController()
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
        with(backgroundBinding) {
            // set all segment creation menu buttons click event and dont register the click if
            // the player's controller is open
            sbSegmentStartTimeText.setOnClickListener {
                if (isControllerFullyVisible) return@setOnClickListener

                var playerPosition = playerCurrentPosition
                // make sure the start time is not more than the end time
                if (newSegmentStartAndEndTime.second != Long.MIN_VALUE) {
                    playerPosition.coerceAtMost(newSegmentStartAndEndTime.second)
                }

                // remove leading hours if video duration is less than an hour
                sbSegmentStartTimeText.text =
                    playerPosition.formatMillisecondsToString(
                        playerDuration < DateUtils.HOUR_IN_MILLIS
                    )

                newSegmentStartAndEndTime = LongLongPair(
                    playerPosition,
                    newSegmentStartAndEndTime.second
                )
            }
            sbSegmentEndTimeText.setOnClickListener {
                if (isControllerFullyVisible) return@setOnClickListener

                var playerPosition = playerCurrentPosition

                // make sure the end time is not less than the start time
                if (newSegmentStartAndEndTime.second != Long.MIN_VALUE) {
                    playerPosition.coerceAtMost(newSegmentStartAndEndTime.second)
                }

                // remove leading hours if video duration is less than an hour
                sbSegmentEndTimeText.text =
                    playerPosition.formatMillisecondsToString(
                        playerDuration < DateUtils.HOUR_IN_MILLIS
                    )

                newSegmentStartAndEndTime = LongLongPair(
                    newSegmentStartAndEndTime.first,
                    playerPosition
                )
            }
            sbSegmentFineBackwardButton.setOnClickListener {
                if (isControllerFullyVisible) return@setOnClickListener

                player?.seekBy(-100)
            }
            sbSegmentFineForwardButton.setOnClickListener {
                if (isControllerFullyVisible) return@setOnClickListener

                player?.seekBy(100)
            }
            sbSegmentSubmitButton.setOnClickListener {
                if (isControllerFullyVisible) return@setOnClickListener

                val submitSegmentDialog = SubmitSegmentDialog()
                submitSegmentDialog.arguments = buildSbBundleArgs(false) ?: return@setOnClickListener
                submitSegmentDialog.show((context as BaseActivity).supportFragmentManager, null)
            }
            sbSegmentCloseButton.setOnClickListener {
                if (isControllerFullyVisible) return@setOnClickListener

                openSponsorBlockSubmissionMenu(false)
            }
        }
        syncQueueButtons()

        binding.sbSubmit.isVisible =
            PreferenceHelper.getBoolean(PreferenceKeys.CONTRIBUTE_TO_SB, false)
        binding.sbSubmit.setOnClickListener {
            openSponsorBlockSubmissionMenu(!isSponsorBlockSubmissionMenuOpened)
        }

        binding.dearrowSubmit.isVisible =
            PreferenceHelper.getBoolean(PreferenceKeys.CONTRIBUTE_TO_DEARROW, false)
        binding.dearrowSubmit.setOnClickListener {
            val submitDialog = SubmitDeArrowDialog()
            submitDialog.arguments = buildSbBundleArgs(true) ?: return@setOnClickListener
            submitDialog.show((context as BaseActivity).supportFragmentManager, null)
        }
    }

    private fun buildSbBundleArgs(isDeArrow: Boolean): Bundle? {
        val currentPosition = player?.currentPosition?.takeIf { it != C.TIME_UNSET } ?: 0
        val duration = player?.duration?.takeIf { it != C.TIME_UNSET }
        val videoId = PlayingQueue.getCurrent()?.url?.toID() ?: return null

        if (isDeArrow) {
            return bundleOf(
                IntentData.currentPosition to currentPosition,
                IntentData.duration to duration,
                IntentData.videoId to videoId,
            )
        }

        var startAndEndTime: List<Long> = emptyList()
        // check if both value is initialized with valid value
        if (newSegmentStartAndEndTime.first < newSegmentStartAndEndTime.second) {
            startAndEndTime = listOf(
                newSegmentStartAndEndTime.first,
                newSegmentStartAndEndTime.second
            )
        }
        if (startAndEndTime.isEmpty()) return null

        return bundleOf(
            IntentData.currentPosition to currentPosition,
            IntentData.duration to duration,
            IntentData.videoId to videoId,
            IntentData.newSegmentsStartAndEndTime to startAndEndTime.toLongArray()
        )
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

    override fun getWindow(): Window = currentWindow ?: activity.window

    override fun hideController() {
        super.hideController()

        if (commonPlayerViewModel?.isFullscreen?.value == true) {
            toggleSystemBars(false)
        }

        updateTopBarMargin()

        backgroundBinding.createSegmentContainer.alpha = 1f
        backgroundBinding.createSegmentContainer.isEnabled = true

    }

    override fun showController() {
        super.showController()

        if (commonPlayerViewModel?.isFullscreen?.value == true && !isPlayerLocked) {
            toggleSystemBars(true)
        }

        backgroundBinding.createSegmentContainer.alpha = 0.5f
        backgroundBinding.createSegmentContainer.isEnabled = false

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
}
