package com.freeflowtv.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelUiToolsTest {
  private val baseChannels = listOf(
    channel("1", "Первый", "Основные", "alive"),
    channel("2", "Карусель", "Детские", "dead"),
    channel("3", "Пятница", "Развлекательные", "unknown"),
    channel("4", "Россия 24", "", "alive"),
  )

  @Test
  fun categoryNamesUseRussianDefaultAndCaseInsensitiveSort() {
    assertEquals(
      listOf("Детские", "Основные", "Развлекательные"),
      ChannelUiTools.categoryNames(baseChannels),
    )
  }

  @Test
  fun visibleChannelsApplyCategoryBeforeFavorites() {
    assertEquals(
      listOf("Карусель"),
      ChannelUiTools.visibleChannels(
        allChannels = baseChannels,
        selectedCategory = "детские",
        favoriteIds = setOf("2", "3"),
        showFavoritesOnly = true,
      ).map { it.name },
    )
  }

  @Test
  fun visibleChannelsHideDeadChannelsWhenRequested() {
    assertEquals(
      listOf("Первый", "Пятница", "Россия 24"),
      ChannelUiTools.visibleChannels(
        allChannels = baseChannels,
        selectedCategory = null,
        favoriteIds = emptySet(),
        showFavoritesOnly = false,
        hideDeadChannels = true,
        showDeadOnly = false,
      ).map { it.name },
    )
  }

  @Test
  fun visibleChannelsCanShowDeadChannelsSeparatelyWithoutDeletingThem() {
    assertEquals(
      listOf("Карусель"),
      ChannelUiTools.visibleChannels(
        allChannels = baseChannels,
        selectedCategory = null,
        favoriteIds = emptySet(),
        showFavoritesOnly = false,
        hideDeadChannels = true,
        showDeadOnly = true,
      ).map { it.name },
    )
  }

  @Test
  fun healthSummaryCountsAliveDeadAndUnknown() {
    assertEquals(
      ChannelUiTools.HealthSummary(alive = 2, dead = 1, unknown = 1),
      ChannelUiTools.healthSummary(baseChannels),
    )
  }

  @Test
  fun pictureModeLabelFallsBackToFill() {
    assertEquals("заполнить", ChannelUiTools.pictureModeLabel(0))
    assertEquals("по размеру", ChannelUiTools.pictureModeLabel(1))
    assertEquals("растянуть", ChannelUiTools.pictureModeLabel(2))
    assertEquals("заполнить", ChannelUiTools.pictureModeLabel(99))
  }

  private fun channel(
    id: String,
    name: String,
    group: String,
    health: String,
  ): Channel = Channel(
    id = id,
    name = name,
    group = group,
    region = "Россия",
    sourceUrl = "https://example.com/$id.m3u8",
    sourceType = "hls",
    epgId = id,
    epgName = name,
    logo = "",
    health = health,
  )
}
