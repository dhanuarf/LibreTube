package com.github.libretube.ui.dialogs

import android.app.Dialog
import android.os.Bundle
import android.text.format.DateUtils
import android.util.Log
import android.widget.Toast
import androidx.collection.LongLongPair
import androidx.core.view.isGone
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.RetrofitInstance
import com.github.libretube.api.obj.Segment
import com.github.libretube.constants.IntentData
import com.github.libretube.databinding.DialogSubmitSegmentBinding
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.util.TextUtils
import com.github.libretube.util.TextUtils.formatMillisecondsToString
import com.github.libretube.util.TextUtils.parseDurationString
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SubmitSegmentDialog : DialogFragment() {
    private var videoId: String = ""
    private var currentPosition: Long = 0
    private var duration: Long? = null

    private var startAndEndTime: LongLongPair? = null

    private var _binding: DialogSubmitSegmentBinding? = null
    private val binding: DialogSubmitSegmentBinding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            videoId = it.getString(IntentData.videoId)!!
            currentPosition = it.getLong(IntentData.currentPosition)
            duration = it.getLong(IntentData.duration)

            val longArray = it.getLongArray(IntentData.newSegmentsStartAndEndTime)!!
            startAndEndTime = LongLongPair(longArray[0], longArray[1])
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogSubmitSegmentBinding.inflate(layoutInflater)

        binding.createSegment.setOnClickListener {
            lifecycleScope.launch { createSegment() }
        }
        binding.startTime.setText(startAndEndTime!!.first.formatMillisecondsToString(false))
        binding.endTime.setText(startAndEndTime!!.second.formatMillisecondsToString(false))

        binding.segmentCategory.items = resources.getStringArray(R.array.sponsorBlockSegmentNames).toList()

        binding.swapTimestamps.setOnClickListener {
            val temp = binding.startTime.text
            binding.startTime.text = binding.endTime.text
            binding.endTime.text = temp
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setView(binding.root)
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private suspend fun createSegment() {
        val context = requireContext().applicationContext
        val binding = _binding ?: return

        requireDialog().hide()

        var startTime = binding.startTime.text.toString().parseDurationString()
        var endTime = binding.endTime.text.toString().parseDurationString()

        if (endTime == null || startTime == null || startTime > endTime) {
            context.toastFromMainDispatcher(R.string.sb_invalid_segment)
            return
        }

        startTime = maxOf(startTime, 0f)
        if (duration != null) {
            // the end time can't be greater than the video duration
            endTime = minOf(endTime, duration!!.toFloat())
        }

        val categories = resources.getStringArray(R.array.sponsorBlockSegments)
        val category = categories[binding.segmentCategory.selectedItemPosition]
        val userAgent = TextUtils.getUserAgent(context)
        val uuid = PreferenceHelper.getSponsorBlockUserID()
        val duration = duration?.let { it.toFloat() / 1000 }

        try {
            withContext(Dispatchers.IO) {
                RetrofitInstance.externalApi
                    .submitSegment(videoId, uuid, userAgent, startTime, endTime, category, duration)
            }
            context.toastFromMainDispatcher(R.string.segment_submitted)
        } catch (e: Exception) {
            Log.e(TAG(), e.toString())
            context.toastFromMainDispatcher(e.localizedMessage.orEmpty())
        }

        requireDialog().dismiss()
    }
}
