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
import com.github.libretube.databinding.DialogSubmitSegmentVoteBinding
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.helpers.PreferenceHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SubmitSegmentVoteDialog: DialogFragment() {
    private var videoId: String = ""
    private var segments: List<Segment> = emptyList()

    private var _binding: DialogSubmitSegmentVoteBinding? = null
    private val binding: DialogSubmitSegmentVoteBinding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            videoId = it.getString(IntentData.videoId)!!
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogSubmitSegmentVoteBinding.inflate(layoutInflater)

        binding.voteSegment.setOnClickListener {
            lifecycleScope.launch { voteForSegment() }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            fetchSegments()
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setView(binding.root)
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private suspend fun voteForSegment() {
        val binding = _binding ?: return
        val context = requireContext().applicationContext

        val segmentID = segments.getOrNull(binding.segmentsDropdown.selectedItemPosition)
            ?.uuid ?: return

        // see https://wiki.sponsor.ajay.app/w/API_Docs#POST_/api/voteOnSponsorTime
        val score = when {
            binding.upvote.isChecked -> 1
            binding.downvote.isChecked -> 0
            else -> 20
        }

        dialog?.hide()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                RetrofitInstance.externalApi.voteOnSponsorTime(
                    uuid = segmentID,
                    userID = PreferenceHelper.getSponsorBlockUserID(),
                    score = score
                )
                context.toastFromMainDispatcher(R.string.success)
            } catch (e: Exception) {
                context.toastFromMainDispatcher(e.localizedMessage.orEmpty())
            }
            withContext(Dispatchers.Main) { dialog?.dismiss() }
        }
    }

    private suspend fun fetchSegments() {
        val categories = resources.getStringArray(R.array.sponsorBlockSegments).toList()
        segments = try {
            MediaServiceRepository.instance.getSegments(videoId, categories).segments
        } catch (e: Exception) {
            Log.e(TAG(), e.toString())
            return
        }

        withContext(Dispatchers.Main) {
            val binding = _binding ?: return@withContext

            if (segments.isEmpty()) {
                binding.voteSegmentContainer.isGone = true
                Toast.makeText(context, R.string.no_segments_found, Toast.LENGTH_SHORT).show()
                return@withContext
            }

            binding.segmentsDropdown.items = segments.map {
                val (start, end) = it.segmentStartAndEnd
                val (startStr, endStr) = DateUtils.formatElapsedTime(start.toLong()) to
                        DateUtils.formatElapsedTime(end.toLong())
                "${it.category} ($startStr - $endStr)"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}