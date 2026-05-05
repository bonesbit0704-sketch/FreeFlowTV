package com.freeflowtv.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.freeflowtv.app.databinding.ItemChannelBinding

class ChannelAdapter(
  private val onClick: (Int) -> Unit,
  private val onLongPress: (Int) -> Unit,
) : ListAdapter<Channel, ChannelAdapter.ChannelViewHolder>(DiffCallback()) {

  private var activeChannelId: String? = null
  private var favorites: Set<String> = emptySet()

  init {
    setHasStableIds(true)
  }

  fun submitChannels(channels: List<Channel>, activeId: String?, favoriteIds: Set<String>) {
    activeChannelId = activeId
    favorites = favoriteIds
    submitList(channels)
  }

  override fun getItemId(position: Int): Long = getItem(position).id.hashCode().toLong()

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
    val binding = ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    return ChannelViewHolder(binding)
  }

  override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
    holder.bind(getItem(position), position)
  }

  inner class ChannelViewHolder(
    private val binding: ItemChannelBinding,
  ) : RecyclerView.ViewHolder(binding.root) {

    fun bind(channel: Channel, position: Int) {
      binding.channelNumber.text = String.format("%02d", position + 1)
      binding.channelTitle.text = channel.name
      binding.channelMeta.text = "${channel.group} / ${channel.region}"
      binding.channelState.text = when {
        channel.health == "dead" -> "НЕТ"
        favorites.contains(channel.id) -> "ИЗБР"
        channel.health == "alive" -> channel.sourceType.uppercase()
        else -> "..."
      }
      binding.root.isSelected = channel.id == activeChannelId
      binding.root.setOnClickListener { onClick(bindingAdapterPosition) }
      binding.root.isLongClickable = true
      binding.root.setOnLongClickListener {
        val adapterPosition = bindingAdapterPosition
        if (adapterPosition != RecyclerView.NO_POSITION) {
          onLongPress(adapterPosition)
        }
        true
      }
      binding.root.setOnKeyListener { _, keyCode, event ->
        val isCenterKey =
          keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
            keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
        if (isCenterKey && event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount >= 1) {
          val adapterPosition = bindingAdapterPosition
          if (adapterPosition != RecyclerView.NO_POSITION) {
            onLongPress(adapterPosition)
          }
          return@setOnKeyListener true
        }
        false
      }
      binding.root.setOnFocusChangeListener { view, hasFocus -> view.isActivated = hasFocus }
    }
  }

  private class DiffCallback : DiffUtil.ItemCallback<Channel>() {
    override fun areItemsTheSame(oldItem: Channel, newItem: Channel): Boolean = oldItem.id == newItem.id

    override fun areContentsTheSame(oldItem: Channel, newItem: Channel): Boolean = oldItem == newItem
  }
}

