package com.freeflowtv.app

import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.Reader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object PlaylistParsers {
  fun parseRemoteConfig(text: String): RemoteConfig {
    val json = JSONObject(text)
    val playlistUrl = json.optString("playlistUrl")
      .ifBlank { json.optString("m3u") }
      .ifBlank { json.optString("url") }
      .trim()
    if (playlistUrl.isBlank()) {
      error("В JSON-конфиге не найдена ссылка playlistUrl.")
    }
    if (!playlistUrl.isPlayableSourceUrl()) {
      error("В JSON-конфиге неверная ссылка playlistUrl.")
    }
    return RemoteConfig(
      title = json.optString("title").takeIf { it.isNotBlank() },
      playlistUrl = playlistUrl,
      epgUrl = json.optString("epgUrl").takeIf { it.isNotBlank() },
      favoriteNames = json.optJSONArray("favoriteNames").toStringSet(),
      hiddenNames = json.optJSONArray("hiddenNames").toStringSet(),
    )
  }

  fun parseRemoteConfigOrPlaylist(configUrl: String, text: String): RemoteConfig {
    val trimmed = text.trim()

    if (trimmed.startsWith("{")) {
      return runCatching { parseRemoteConfig(trimmed) }
        .getOrElse { error("JSON-конфиг поврежден или заполнен неверно.") }
    }

    if (trimmed.startsWith("#EXTM3U", ignoreCase = true) || configUrl.isPlaylistUrl()) {
      return RemoteConfig(
        title = null,
        playlistUrl = configUrl,
        epgUrl = null,
      )
    }

    val firstUrl = trimmed.lineSequence()
      .map { it.trim() }
      .firstOrNull { it.startsWith("http://", true) || it.startsWith("https://", true) }
    if (firstUrl != null) {
      return RemoteConfig(
        title = null,
        playlistUrl = firstUrl,
        epgUrl = null,
      )
    }

    error("Укажите ссылку на M3U/M3U8 или JSON-конфиг.")
  }

  fun parseM3u(reader: Reader): Pair<List<Channel>, String?> {
    val channels = mutableListOf<Channel>()
    var pendingName = ""
    var pendingGroup = "Основные"
    var pendingLogo = ""
    var pendingEpgId = ""
    var pendingEpgName = ""
    var pendingRegion = "Россия"
    var playlistEpgUrl: String? = null
    var index = 0

    reader.buffered().forEachLine { rawLine ->
      index += 1
      val line = rawLine.replace("\uFEFF", "").trim()
      if (line.isBlank()) return@forEachLine

      if (playlistEpgUrl == null && index <= 5) {
        Regex("""(?:x-tvg-url|url-tvg|tvg-url)="([^"]+)"""", RegexOption.IGNORE_CASE)
          .find(line)
          ?.groupValues
          ?.getOrNull(1)
          ?.let { playlistEpgUrl = it }
      }

      if (line.startsWith("#EXTINF", ignoreCase = true)) {
        pendingName = line.substringAfter(",", "Канал $index").trim()
        pendingGroup = line.findAttr("group-title").ifBlank { "Основные" }
        pendingLogo = line.findAttr("tvg-logo")
        pendingEpgId = line.findAttr("tvg-id")
        pendingEpgName = line.findAttr("tvg-name")
        pendingRegion = line.findAttr("tvg-country").ifBlank { "Россия" }
        return@forEachLine
      }

      if (line.startsWith("#")) return@forEachLine
      if (!line.isPlayableSourceUrl()) return@forEachLine

      val channelName = pendingName.ifBlank { "Канал ${channels.size + 1}" }
      channels += Channel(
        id = buildStableId(channelName, line),
        name = channelName,
        group = pendingGroup,
        region = pendingRegion,
        sourceUrl = line,
        sourceType = when {
          line.contains(".m3u8", true) -> "hls"
          line.contains(".mp4", true) -> "mp4"
          else -> "auto"
        },
        epgId = pendingEpgId,
        epgName = pendingEpgName.ifBlank { channelName },
        logo = pendingLogo,
      )

      pendingName = ""
      pendingGroup = "Основные"
      pendingLogo = ""
      pendingEpgId = ""
      pendingEpgName = ""
      pendingRegion = "Россия"
    }

    return channels to playlistEpgUrl
  }

  fun applyXmltv(channels: List<Channel>, reader: Reader): List<Channel> {
    val parser = Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    parser.setInput(reader.buffered())

    val programsByKey = mutableMapOf<String, MutableList<ProgramSlot>>()
    val aliases = mutableMapOf<String, String>()

    var eventType = parser.eventType
    var currentChannelId = ""
    var currentProgrammeChannel = ""
    var programmeTitle = ""
    var programmeDesc = ""
    var programmeStart = ""
    var programmeStop = ""

    while (eventType != XmlPullParser.END_DOCUMENT) {
      when (eventType) {
        XmlPullParser.START_TAG -> when (parser.name) {
          "channel" -> currentChannelId = parser.getAttributeValue(null, "id").normalizeEpgKey()
          "display-name" -> if (currentChannelId.isNotBlank()) {
            val displayName = parser.nextText().normalizeEpgKey()
            if (displayName.isNotBlank()) aliases[displayName] = currentChannelId
          }
          "programme" -> {
            currentProgrammeChannel = parser.getAttributeValue(null, "channel").normalizeEpgKey()
            programmeStart = parser.getAttributeValue(null, "start").orEmpty()
            programmeStop = parser.getAttributeValue(null, "stop").orEmpty()
            programmeTitle = ""
            programmeDesc = ""
          }
          "title" -> if (currentProgrammeChannel.isNotBlank()) programmeTitle = parser.nextText().trim()
          "desc" -> if (currentProgrammeChannel.isNotBlank()) programmeDesc = parser.nextText().trim()
        }

        XmlPullParser.END_TAG -> when (parser.name) {
          "channel" -> {
            if (currentChannelId.isNotBlank()) aliases[currentChannelId] = currentChannelId
            currentChannelId = ""
          }
          "programme" -> {
            val resolved = aliases[currentProgrammeChannel] ?: currentProgrammeChannel
            val start = programmeStart.parseXmltvMillis()
            val end = programmeStop.parseXmltvMillis()
            if (resolved.isNotBlank() && start > 0L && end > start) {
              programsByKey.getOrPut(resolved) { mutableListOf() } += ProgramSlot(
                startMillis = start,
                endMillis = end,
                title = programmeTitle.ifBlank { "Без названия" },
                description = programmeDesc.ifBlank { "Описание отсутствует." },
              )
            }
            currentProgrammeChannel = ""
          }
        }
      }
      eventType = parser.next()
    }

    return channels.map { channel ->
      val keys = listOf(channel.epgId, channel.epgName, channel.name)
        .map { it.normalizeEpgKey() }
        .filter { it.isNotBlank() }
      val programs = keys.firstNotNullOfOrNull { key ->
        val resolved = aliases[key] ?: key
        programsByKey[resolved]
      }?.sortedBy { it.startMillis }.orEmpty()
      channel.copy(programs = programs)
    }
  }

  private fun buildStableId(name: String, url: String): String {
    val seed = "$name|$url"
      .lowercase(Locale.getDefault())
      .replace(Regex("[^\\p{L}\\p{N}]+"), "_")
      .trim('_')
    return seed.take(120)
  }

  private fun String.findAttr(name: String): String =
    Regex("""$name="([^"]+)"""", RegexOption.IGNORE_CASE)
      .find(this)
      ?.groupValues
      ?.getOrNull(1)
      .orEmpty()

  private fun String.normalizeEpgKey(): String =
    trim().lowercase(Locale.getDefault()).replace(Regex("[^\\p{L}\\p{N}]+"), "")

  private fun String.isPlaylistUrl(): Boolean {
    val value = trim().lowercase(Locale.getDefault())
    return value.endsWith(".m3u") || value.endsWith(".m3u8") || value.contains(".m3u?") || value.contains(".m3u8?")
  }

  private fun String.isPlayableSourceUrl(): Boolean {
    val value = trim()
    return value.startsWith("http://", ignoreCase = true) ||
      value.startsWith("https://", ignoreCase = true) ||
      value.startsWith("rtmp://", ignoreCase = true) ||
      value.startsWith("rtsp://", ignoreCase = true)
  }

  private fun String.parseXmltvMillis(): Long {
    val normalized = trim()
    val patterns = listOf(
      SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US),
      SimpleDateFormat("yyyyMMddHHmmss", Locale.US),
    )
    patterns.forEach { formatter ->
      formatter.timeZone = TimeZone.getDefault()
      runCatching { return formatter.parse(normalized)?.time ?: 0L }
    }
    return 0L
  }

  private fun org.json.JSONArray?.toStringSet(): Set<String> {
    if (this == null) return emptySet()
    val values = mutableSetOf<String>()
    for (index in 0 until length()) {
      optString(index).trim().takeIf { it.isNotBlank() }?.let(values::add)
    }
    return values
  }
}

