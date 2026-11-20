package com.github.libretube.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.databinding.BottomSheetItemBinding
import com.github.libretube.obj.bottomsheetitem.BottomSheetItem
import com.github.libretube.ui.extensions.setDrawables
import com.github.libretube.ui.viewholders.BottomSheetViewHolder

class BottomSheetAdapter(
    private val listener: (index: Int) -> Unit
) : RecyclerView.Adapter<BottomSheetViewHolder>() {
    private var currentList = emptyList<BottomSheetItem>()
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BottomSheetViewHolder {
        val binding = BottomSheetItemBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return BottomSheetViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BottomSheetViewHolder, position: Int) {
        val item = currentList[position]
        holder.binding.root.apply {
            val current = item.getCurrent()
            val title = item.title.getText(context)
            text = if (current != null) "$title ($current)" else title
            isSelected = item.isSelected
            setDrawables(start = item.drawable)

            setOnClickListener {
                item.onClick.invoke()
                listener.invoke(position)
            }
        }
    }

    override fun getItemCount(): Int {
        return currentList.size
    }

    fun submitList(list: List<BottomSheetItem>) {
        currentList = list
        notifyItemRangeChanged(0, currentList.size)
    }
}
