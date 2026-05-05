package com.freeflowtv.app

import java.util.Locale

object ChannelUiTools {
  data class HealthSummary(
    val alive: Int,
    val dead: Int,
    val unknown: Int,
  )

  fun categoryNames(channels: List<Channel>): List<String> {
    val unique = linkedMapOf<String, String>()
    channels.forEach { channel ->
      val title = channel.group.ifBlank { DEFAULT_CATEGORY }
      unique.putIfAbsent(title.normalizeCategory(), title)
    }
    return unique.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
  }

  fun visibleChannels(
    allChannels: List<Channel>,
    selectedCategory: String?,
    favoriteIds: Set<String>,
    showFavoritesOnly: Boolean,
    hideDeadChannels: Boolean = false,
    showDeadOnly: Boolean = false,
  ): List<Channel> {
    if (showDeadOnly) {
      return allChannels.filter { it.health == "dead" }
    }

    val filteredByCategory = selectedCategory?.let { category ->
      allChannels.filter { channel ->
        channel.group.ifBlank { DEFAULT_CATEGORY }.equals(category, ignoreCase = true)
      }
    } ?: allChannels
    val filteredByHealth = if (hideDeadChannels) {
      filteredByCategory.filterNot { it.health == "dead" }
    } else {
      filteredByCategory
    }

    return if (showFavoritesOnly) {
      filteredByHealth.filter { favoriteIds.contains(it.id) }
    } else {
      filteredByHealth
    }
  }

  fun healthSummary(channels: List<Channel>): HealthSummary {
    var alive = 0
    var dead = 0
    var unknown = 0
    channels.forEach { channel ->
      when (channel.health) {
        "alive" -> alive += 1
        "dead" -> dead += 1
        else -> unknown += 1
      }
    }
    return HealthSummary(alive = alive, dead = dead, unknown = unknown)
  }

  fun pictureModeLabel(index: Int): String = when (index) {
    1 -> "по размеру"
    2 -> "растянуть"
    else -> "заполнить"
  }

  private fun String.normalizeCategory(): String =
    trim().lowercase(Locale.getDefault())

  private const val DEFAULT_CATEGORY = "Основные"
}
