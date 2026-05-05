package com.freeflowtv.app

data class ProgramSlot(
  val startMillis: Long,
  val endMillis: Long,
  val title: String,
  val description: String,
)

data class Channel(
  val id: String,
  val name: String,
  val group: String,
  val region: String,
  val sourceUrl: String,
  val sourceType: String,
  val epgId: String,
  val epgName: String,
  val logo: String,
  val programs: List<ProgramSlot> = emptyList(),
  val health: String = "unknown",
)

data class RemoteConfig(
  val title: String?,
  val playlistUrl: String,
  val epgUrl: String?,
  val favoriteNames: Set<String> = emptySet(),
  val hiddenNames: Set<String> = emptySet(),
)
