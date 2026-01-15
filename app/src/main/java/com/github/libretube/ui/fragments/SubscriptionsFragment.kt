package com.github.libretube.ui.fragments

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.core.os.bundleOf
import androidx.core.view.children
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.R
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.IntentData
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.databinding.FragmentSubscriptionsBinding
import com.github.libretube.databinding.ViewSubscriptionToolbarBinding
import com.github.libretube.db.DatabaseHelper
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.db.obj.SubscriptionGroup
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.obj.SelectableOption
import com.github.libretube.ui.adapters.VideoCardsAdapter
import com.github.libretube.ui.base.DynamicLayoutManagerFragment
import com.github.libretube.ui.models.EditChannelGroupsModel
import com.github.libretube.ui.models.SubscriptionsViewModel
import com.github.libretube.ui.sheets.ChannelGroupsSheet
import com.github.libretube.ui.sheets.EditChannelGroupSheet
import com.github.libretube.ui.sheets.FilterSortBottomSheet
import com.github.libretube.ui.sheets.FilterSortBottomSheet.Companion.FILTER_SORT_REQUEST_KEY
import com.github.libretube.ui.sheets.SubscriptionsBottomSheet
import com.github.libretube.util.PlayingQueue
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SubscriptionsFragment : DynamicLayoutManagerFragment(R.layout.fragment_subscriptions) {
    private var _binding: FragmentSubscriptionsBinding? = null
    private var _toolbarBinding: ViewSubscriptionToolbarBinding? = null
    private val binding get() = _binding!!
    private val toolbarBinding get() = _toolbarBinding!!

    var selectedFilterGroup: Int
        set(value) {
            viewModel.updateSelectedChannelGroup(value)
        }
        get() = viewModel.selectedChannelGroup

    private val viewModel: SubscriptionsViewModel by activityViewModels()
    private val channelGroupsModel: EditChannelGroupsModel by activityViewModels()

    private var isAppBarFullyExpanded = true

    private var feedAdapter = VideoCardsAdapter()
    private var selectedSortOrder = PreferenceHelper.getInt(PreferenceKeys.FEED_SORT_ORDER, 0)
        set(value) {
            PreferenceHelper.putInt(PreferenceKeys.FEED_SORT_ORDER, value)
            field = value
        }

    private var hideWatched =
        PreferenceHelper.getBoolean(PreferenceKeys.HIDE_WATCHED_FROM_FEED, false)
        set(value) {
            PreferenceHelper.putBoolean(PreferenceKeys.HIDE_WATCHED_FROM_FEED, value)
            field = value
        }

    private var showUpcoming =
        PreferenceHelper.getBoolean(PreferenceKeys.SHOW_UPCOMING_IN_FEED, true)
        set(value) {
            PreferenceHelper.putBoolean(PreferenceKeys.SHOW_UPCOMING_IN_FEED, value)
            field = value
        }

    override fun setLayoutManagers(gridItems: Int) {
        _binding?.subFeed?.layoutManager = GridLayoutManager(context, gridItems)
    }

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentSubscriptionsBinding.bind(view)
        _toolbarBinding = ViewSubscriptionToolbarBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        setupSortAndFilter()

        binding.subFeed.adapter = feedAdapter

        // Check if the AppBarLayout is fully expanded
        binding.subscriptionsAppBar.addOnOffsetChangedListener { _, verticalOffset ->
            isAppBarFullyExpanded = verticalOffset == 0
        }

        // Determine if the child can scroll up
        binding.subRefresh.setOnChildScrollUpCallback { _, _ ->
            !isAppBarFullyExpanded || binding.subFeed.canScrollVertically(-1)
        }

        binding.subRefresh.isEnabled = true
        binding.subProgress.isVisible = true

        if (viewModel.videoFeed.value == null) {
            viewModel.fetchFeed(requireContext(), forceRefresh = false)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.fetchSubscriptions(requireContext())
            }
        }

        // only restore the previous state (i.e. scroll position) the first time the feed is shown
        // any other feed updates are caused by manual refreshing and thus should reset the scroll
        // position to zero
        var alreadyShowedFeedOnce = false
        viewModel.videoFeed.observe(viewLifecycleOwner) { feed ->
            if (feed != null) {
                lifecycleScope.launch {
                    showFeed(!alreadyShowedFeedOnce)
                }
                alreadyShowedFeedOnce = true
            }

           feed?.firstOrNull { !it.isUpcoming }?.uploaded?.let {
                PreferenceHelper.updateLastFeedWatchedTime(it, true)
            }
        }

        viewModel.feedProgress.observe(viewLifecycleOwner) { progress ->
            if (progress == null || progress.currentProgress == progress.total) {
                toolbarBinding.feedProgressContainer.isGone = true
            } else {
                toolbarBinding.feedProgressContainer.isVisible = true
                toolbarBinding.feedProgressText.text = "${progress.currentProgress}/${progress.total}"
                toolbarBinding.feedProgressBar.max = progress.total
                toolbarBinding.feedProgressBar.progress = progress.currentProgress
            }
        }

        binding.subRefresh.setOnRefreshListener {
            viewModel.fetchSubscriptions(requireContext())
            viewModel.fetchFeed(requireContext(), forceRefresh = true)
        }

        toolbarBinding.toggleSubs.setOnClickListener {
            SubscriptionsBottomSheet()
                .show(childFragmentManager)
        }

        toolbarBinding.channelGroups.setOnCheckedStateChangeListener { group, _ ->
            selectedFilterGroup =
                group.children.indexOfFirst { it.id == group.checkedChipId }

            lifecycleScope.launch {
                showFeed(restoreScrollState = false)
            }
        }

        channelGroupsModel.groups.observe(viewLifecycleOwner) {
            lifecycleScope.launch { initChannelGroups() }
        }

        toolbarBinding.editGroups.setOnClickListener {
            ChannelGroupsSheet().show(childFragmentManager, null)
        }

        toolbarBinding.createGroupButton.setOnClickListener {
            channelGroupsModel.groupToEdit = SubscriptionGroup("", mutableListOf(), 0)
            EditChannelGroupSheet().show(parentFragmentManager, null)
        }

        binding.subFeed.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                viewModel.subFeedRecyclerViewState =
                    recyclerView.layoutManager?.onSaveInstanceState()?.takeIf {
                        recyclerView.computeVerticalScrollOffset() != 0
                    }
            }
        })

        lifecycleScope.launch(Dispatchers.IO) {
            val groups = DatabaseHolder.Database.subscriptionGroupsDao().getAll()
                .sortedBy { it.index }
            channelGroupsModel.groups.postValue(groups)
        }
    }

    private fun setupSortAndFilter() {
        toolbarBinding.filterSort.setOnClickListener {
            childFragmentManager.setFragmentResultListener(
                FILTER_SORT_REQUEST_KEY,
                viewLifecycleOwner
            ) { _, resultBundle ->
                selectedSortOrder = resultBundle.getInt(IntentData.sortOptions)
                hideWatched = resultBundle.getBoolean(IntentData.hideWatched)
                showUpcoming = resultBundle.getBoolean(IntentData.showUpcoming)
                lifecycleScope.launch { showFeed() }
            }

            FilterSortBottomSheet()
                .apply {
                    arguments = bundleOf(
                        IntentData.sortOptions to fetchSortOptions(),
                        IntentData.hideWatched to hideWatched,
                        IntentData.showUpcoming to showUpcoming,
                    )
                }
                .show(childFragmentManager)
        }
    }

    private fun fetchSortOptions(): List<SelectableOption> {
        return resources.getStringArray(R.array.sortOptions)
            .mapIndexed { index, option ->
                SelectableOption(isSelected = index == selectedSortOrder, name = option)
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private suspend fun playByGroup(groupIndex: Int) {
        val streams = viewModel.videoFeed.value.orEmpty()
            .filterByGroup(groupIndex)
            .let {
                DatabaseHelper.filterByStreamTypeAndWatchPosition(it, hideWatched, showUpcoming)
            }
            .sortedBySelectedOrder()

        if (streams.isEmpty()) return

        PlayingQueue.setStreams(streams)

        NavigationHelper.navigateVideo(
            requireContext(),
            videoId = streams.first().url,
            keepQueue = true
        )
    }

    @SuppressLint("InflateParams")
    private fun initChannelGroups() {
        val toolbarBinding = _toolbarBinding ?: return

        toolbarBinding.chipAll.isChecked = viewModel.selectedChannelGroup == 0
        toolbarBinding.chipAll.setOnLongClickListener {
            lifecycleScope.launch { playByGroup(0) }
            true
        }

        toolbarBinding.channelGroups.removeAllViews()
        toolbarBinding.channelGroups.addView(toolbarBinding.chipAll)

        val anyChannelGroups = !channelGroupsModel.groups.value.isNullOrEmpty()
        with(toolbarBinding) {
            channelGroupsContainer.isVisible = anyChannelGroups
            createGroupButton.isGone = anyChannelGroups
            toggleSubs.text = if (anyChannelGroups) null else getString(R.string.subscriptions)
            toggleSubs.updateLayoutParams<LinearLayout.LayoutParams> {
                width = if (anyChannelGroups) LinearLayout.LayoutParams.WRAP_CONTENT else 0
                weight = if (anyChannelGroups) 0f else 1f
            }
            toolbarButtonGroup.updateLayoutParams {
                width =
                    if (anyChannelGroups) LinearLayout.LayoutParams.WRAP_CONTENT
                    else LinearLayout.LayoutParams.MATCH_PARENT
            }
        }

        channelGroupsModel.groups.value?.forEachIndexed { index, group ->
            val chip = layoutInflater.inflate(R.layout.filter_chip, null) as Chip
            chip.apply {
                id = View.generateViewId()
                isCheckable = true
                text = group.name
                setOnLongClickListener {
                    // the index must be increased by one to skip the "all channels" group button
                    lifecycleScope.launch { playByGroup(index + 1) }
                    true
                }
            }
            toolbarBinding.channelGroups.addView(chip)

            if (index + 1 == viewModel.selectedChannelGroup){
                toolbarBinding.channelGroups.check(chip.id)
            }
        }
    }

    private fun List<StreamItem>.filterByGroup(groupIndex: Int): List<StreamItem> {
        if (groupIndex == 0) return this

        val group = channelGroupsModel.groups.value?.getOrNull(groupIndex - 1)
        return filter {
            val channelId = it.uploaderUrl.orEmpty().toID()
            group?.channels?.contains(channelId) != false
        }
    }

    private fun List<StreamItem>.sortedBySelectedOrder() = when (selectedSortOrder) {
        0 -> this
        1 -> this.reversed()
        2 -> this.sortedBy { it.views }.reversed()
        3 -> this.sortedBy { it.views }
        4 -> this.sortedBy { it.uploaderName }
        5 -> this.sortedBy { it.uploaderName }.reversed()
        else -> this
    }

    private suspend fun showFeed(restoreScrollState: Boolean = true) {
        val binding = _binding ?: return
        val videoFeed = viewModel.videoFeed.value ?: return

        val feed = videoFeed
            .filterByGroup(viewModel.selectedChannelGroup)
            .let {
                DatabaseHelper.filterByStreamTypeAndWatchPosition(it, hideWatched, showUpcoming)
            }

        val sortedFeed = feed
            .sortedBySelectedOrder()
            .toMutableList()

        // add an "all caught up item"
        if (selectedSortOrder == 0) {
            val lastCheckedFeedTime = PreferenceHelper.getLastCheckedFeedTime(seenByUser = true)
            val caughtUpIndex =
                feed.indexOfFirst { it.uploaded <= lastCheckedFeedTime && !it.isUpcoming }
            if (caughtUpIndex > 0 && !feed[caughtUpIndex - 1].isUpcoming) {
                sortedFeed.add(
                    caughtUpIndex,
                    StreamItem(type = VideoCardsAdapter.CAUGHT_UP_STREAM_TYPE)
                )
            }
        }

        binding.subProgress.isGone = true

        val notLoaded = viewModel.videoFeed.value.isNullOrEmpty()
        binding.subFeed.isGone = notLoaded
        binding.emptyFeed.isVisible = notLoaded
        binding.subRefresh.isRefreshing = false

        feedAdapter.submitList(sortedFeed) {
            if (restoreScrollState) {
                // manually restore the previous feed state
                binding.subFeed.layoutManager?.onRestoreInstanceState(viewModel.subFeedRecyclerViewState)
            } else {
                binding.subFeed.scrollToPosition(0)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // manually restore the recyclerview state after rotation due to https://github.com/material-components/material-components-android/issues/3473
        binding.subFeed.layoutManager?.onRestoreInstanceState(viewModel.subFeedRecyclerViewState)
    }

    fun removeItem(videoId: String) {
        feedAdapter.removeItemById(videoId)
    }
}
