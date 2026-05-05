package com.freeflowtv.app

import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import com.freeflowtv.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStreamReader
import java.io.Reader
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
  private lateinit var binding: ActivityMainBinding
  private lateinit var player: ExoPlayer
  private lateinit var adapter: ChannelAdapter
  private lateinit var layoutManager: LinearLayoutManager

  private val client = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(12, TimeUnit.SECONDS)
    .callTimeout(20, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .build()
  private val healthClient = OkHttpClient.Builder()
    .connectTimeout(2, TimeUnit.SECONDS)
    .readTimeout(2, TimeUnit.SECONDS)
    .callTimeout(3, TimeUnit.SECONDS)
    .build()
  private val preferences by lazy { getSharedPreferences("freeflow_tv", Context.MODE_PRIVATE) }
  private val uiHandler = Handler(Looper.getMainLooper())

  private var allChannels: List<Channel> = emptyList()
  private var channels: List<Channel> = emptyList()
  private var activeIndex = 0
  private var favoriteIds: MutableSet<String> = mutableSetOf()
  private var remoteFavoriteNames: Set<String> = emptySet()
  private var showFavoritesOnly = false
  private var selectedCategory: String? = null
  private var hideDeadChannels = false
  private var showDeadOnly = false
  private var pictureModeIndex = 0
  private var pendingChannelDigits = ""
  private var healthCheckJob: Job? = null
  private var openingChannelId: String? = null
  private var playbackConfirmedChannelId: String? = null

  private val hideZapRunnable = Runnable {
    binding.zapOverlay.visibility = View.GONE
  }

  private val applyNumberInputRunnable = Runnable {
    applyPendingChannelNumber()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    showStartupLoading("Загружаю каналы...")
    favoriteIds = preferences.getStringSet(KEY_FAVORITES, emptySet())?.toMutableSet() ?: mutableSetOf()
    hideDeadChannels = preferences.getBoolean(KEY_HIDE_DEAD_CHANNELS, false)
    ensureDefaultPlaylistConfigured()

    player = ExoPlayer.Builder(this).build().apply {
      addListener(object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
          when (playbackState) {
            Player.STATE_BUFFERING -> {
              if (openingChannelId != null) {
                binding.channelLoadingStatus.text = "Буферизация потока..."
              }
            }
            Player.STATE_READY -> {
              hideChannelLoading()
              hideStartupLoading()
              openingChannelId?.let { id ->
                playbackConfirmedChannelId = id
                updateChannelHealth(id, "alive")
              }
              openingChannelId = null
              keepScreenAwake()
            }
          }
        }

        override fun onPlayerError(error: PlaybackException) {
          val failedChannelId = openingChannelId ?: activeChannelId()
          val alreadyPlaying = failedChannelId != null && failedChannelId == playbackConfirmedChannelId
          hideChannelLoading()
          hideStartupLoading()
          if (alreadyPlaying) {
            binding.statusLabel.text = "Источник канала нестабилен"
            logEvent("Нестабильный поток после запуска: ${error.localizedMessage ?: error.errorCodeName}")
            return
          }
          openingChannelId = null
          binding.statusLabel.text = "Поток недоступен"
          updateChannelHealth(failedChannelId, "dead")
          logEvent("Ошибка запуска канала: ${error.localizedMessage ?: error.errorCodeName}")
          toast("Не удалось запустить канал")
        }
      })
    }
    binding.playerView.player = player
    binding.playerView.useController = false
    pictureModeIndex = preferences.getInt(KEY_PICTURE_MODE, 0)
    applyPictureMode(save = false)

    adapter = ChannelAdapter(
      onClick = { index ->
        openChannelAt(index)
        toggleSidebar(false)
      },
      onLongPress = { index ->
        showChannelActionsDialog(index)
      },
    )
    layoutManager = LinearLayoutManager(this)
    binding.channelList.layoutManager = layoutManager
    binding.channelList.adapter = adapter
    binding.channelList.setOnKeyListener { _, keyCode, event ->
      if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
      when (keyCode) {
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BACK -> {
          toggleSidebar(false)
          true
        }
        else -> false
      }
    }

    bindActions()
    toggleSidebar(false)
    loadBundledDemoChannels()
    restoreCachedChannels()
    autoStartLastChannel()
    maybeAutoSyncPlaylist()
  }

  private fun bindActions() {
    binding.prevChannelButton.setOnClickListener { switchChannel(-1) }
    binding.nextChannelButton.setOnClickListener { switchChannel(1) }
    binding.favoriteButton.setOnClickListener { toggleFavorite() }
    binding.guideButton.setOnClickListener { showGuideDialog() }
    binding.syncButton.setOnClickListener { syncRemoteConfig(true) }
    binding.settingsButton.setOnClickListener { showRemoteConfigDialog() }
    binding.navigatorButton.setOnClickListener { showNavigatorDialog(false) }
    binding.channelRailToggle.setOnClickListener { toggleSidebar(!binding.sidebar.isVisible) }
    binding.categoryButton.setOnClickListener { showCategoryDialog() }
    binding.diagnosticsButton.setOnClickListener { runFullChannelCheck(showDialogWhenDone = true) }
    binding.pictureModeButton.setOnClickListener { cyclePictureMode() }
    binding.allChannelsButton.setOnClickListener {
      showFavoritesOnly = false
      showDeadOnly = false
      renderChannels()
      focusActiveChannelInList()
    }
    binding.favoritesFilterButton.setOnClickListener {
      showFavoritesOnly = true
      showDeadOnly = false
      renderChannels()
      focusActiveChannelInList()
    }
  }

  private fun ensureDefaultPlaylistConfigured() {
    val current = preferences.getString(KEY_REMOTE_CONFIG_URL, "").orEmpty().trim()
    if (current.isNotBlank()) return
    preferences.edit().putString(KEY_REMOTE_CONFIG_URL, DEFAULT_PLAYLIST_URL).apply()
  }

  private fun autoStartLastChannel() {
    uiHandler.postDelayed({
      if (isFinishing || allChannels.isEmpty()) return@postDelayed
      if (player.isPlaying || player.playbackState == Player.STATE_READY) return@postDelayed
      val activeId = activeChannelId()
      val displayIndex = channels.indexOfFirst { it.id == activeId }.takeIf { it >= 0 } ?: 0
      if (channels.isNotEmpty()) {
        updateStartupLoadingStatus("Включаю последний канал...")
        openChannelAt(displayIndex)
      }
    }, 700L)
  }

  private fun maybeAutoSyncPlaylist() {
    val lastSync = preferences.getLong(KEY_LAST_SYNC_MS, 0L)
    val now = System.currentTimeMillis()
    if (lastSync > 0L && now - lastSync < AUTO_SYNC_INTERVAL_MS) return
    val delay = if (allChannels.isEmpty()) 400L else 2500L
    uiHandler.postDelayed({
      if (!isFinishing) {
        syncRemoteConfig(showToastOnSuccess = false)
      }
    }, delay)
  }

  private fun syncRemoteConfig(showToastOnSuccess: Boolean) {
    val configUrl = preferences.getString(KEY_REMOTE_CONFIG_URL, DEFAULT_PLAYLIST_URL).orEmpty().ifBlank {
      DEFAULT_PLAYLIST_URL
    }
    if (configUrl.isBlank()) {
      showRemoteConfigDialog()
      return
    }

    lifecycleScope.launch {
      binding.statusLabel.text = getString(R.string.status_syncing)
      if (allChannels.isEmpty() || binding.startupLoadingOverlay.isVisible) {
        showStartupLoading("Обновляю плейлист...")
      }

      runCatching {
        val configText = withContext(Dispatchers.IO) { httpText(configUrl, MAX_CONFIG_SIZE_BYTES) }
        val config = PlaylistParsers.parseRemoteConfigOrPlaylist(configUrl, configText)
        val parsedPlaylist = withContext(Dispatchers.IO) {
          httpReader(config.playlistUrl) { reader ->
            PlaylistParsers.parseM3u(reader)
          }
        }
        val epgUrl = config.epgUrl ?: parsedPlaylist.second
        val channelsWithEpg = if (epgUrl.isNullOrBlank()) {
          parsedPlaylist.first
        } else {
          withContext(Dispatchers.IO) {
            runCatching {
              httpReader(epgUrl) { reader ->
                PlaylistParsers.applyXmltv(parsedPlaylist.first, reader)
              }
            }.getOrElse { error ->
              logEvent("EPG не загрузился: ${error.localizedMessage ?: error.javaClass.simpleName}")
              parsedPlaylist.first
            }
          }
        }
        applyRemoteConfig(channelsWithEpg, config).also { channels ->
          if (channels.isEmpty()) error("В плейлисте не найдено каналов")
        }
      }.onSuccess { loadedChannels ->
        allChannels = loadedChannels
        seedRemoteFavorites()
        restoreLastChannel()
        renderChannels()
        if (channels.isNotEmpty()) {
          showChannelPreviewById(activeChannelId() ?: channels.first().id)
          if (binding.startupLoadingOverlay.isVisible) {
            updateStartupLoadingStatus("Включаю последний канал...")
            autoStartLastChannel()
          }
        } else {
          binding.channelTitle.text = "Нет каналов"
          binding.channelMeta.text = "Проверьте плейлист или фильтр"
          binding.currentProgram.text = "EPG не найден"
          binding.nextProgram.text = "Далее: --"
          hideStartupLoading()
        }
        binding.statusLabel.text = getString(R.string.status_ready, allChannels.size)
        preferences.edit().putLong(KEY_LAST_SYNC_MS, System.currentTimeMillis()).apply()
        if (showToastOnSuccess) {
          toast("Плейлист обновлен: ${allChannels.size} каналов")
        }
        lifecycleScope.launch(Dispatchers.IO) {
          runCatching { saveCachedChannels(allChannels) }
        }
        startHealthChecks()
      }.onFailure { error ->
        hideStartupLoading()
        binding.statusLabel.text = error.localizedMessage ?: getString(R.string.status_error)
        logEvent("Ошибка обновления плейлиста: ${error.localizedMessage ?: error.javaClass.simpleName}")
        if (showToastOnSuccess) {
          toast(error.localizedMessage ?: "Ошибка загрузки")
        }
      }
    }
  }

  private fun applyRemoteConfig(parsedChannels: List<Channel>, config: RemoteConfig): List<Channel> {
    remoteFavoriteNames = config.favoriteNames.map { it.normalizeKey() }.toSet()
    val hidden = config.hiddenNames.map { it.normalizeKey() }.toSet()
    return parsedChannels
      .filter { it.sourceUrl.isPlayableSourceUrl() }
      .filterNot { hidden.contains(it.name.normalizeKey()) }
      .distinctBy { it.id }
  }

  private fun seedRemoteFavorites() {
    if (remoteFavoriteNames.isEmpty()) return
    var changed = false
    allChannels.forEach { channel ->
      if (remoteFavoriteNames.contains(channel.name.normalizeKey()) && favoriteIds.add(channel.id)) {
        changed = true
      }
    }
    if (changed) {
      preferences.edit().putStringSet(KEY_FAVORITES, favoriteIds).apply()
    }
  }

  private fun restoreLastChannel() {
    val lastId = preferences.getString(KEY_LAST_CHANNEL_ID, "").orEmpty()
    val restoredIndex = allChannels.indexOfFirst { it.id == lastId }.takeIf { it >= 0 } ?: 0
    activeIndex = restoredIndex
  }

  private fun restoreCachedChannels() {
    updateStartupLoadingStatus("Читаю сохраненные каналы...")
    runCatching {
      val cachedChannels = loadCachedChannels()
      if (cachedChannels.isEmpty()) return
      allChannels = cachedChannels
        .filter { it.sourceUrl.isPlayableSourceUrl() }
        .distinctBy { it.id }
      if (allChannels.isEmpty()) return
      seedRemoteFavorites()
      restoreLastChannel()
      renderChannels()
      if (channels.isNotEmpty()) {
        showChannelPreviewById(activeChannelId() ?: channels.first().id)
        updateStartupLoadingStatus("Включаю последний канал...")
        binding.statusLabel.text = "Каналы загружены из памяти"
      } else {
        binding.channelTitle.text = "Нет каналов"
        binding.channelMeta.text = "Проверьте плейлист или фильтр"
        binding.currentProgram.text = "EPG не найден"
        binding.nextProgram.text = "Далее: --"
        hideStartupLoading()
      }
    }.onFailure {
      hideStartupLoading()
      logEvent("Кеш каналов поврежден: ${it.localizedMessage ?: it.javaClass.simpleName}")
      runCatching { File(filesDir, CHANNEL_CACHE_FILE).delete() }
    }
  }

  private fun loadBundledDemoChannels() {
    if (allChannels.isNotEmpty()) return
    updateStartupLoadingStatus("Готовлю стартовый плейлист...")
    runCatching {
      assets.open(BUNDLED_PLAYLIST_ASSET).use { input ->
        InputStreamReader(input).use { reader ->
          val bundledChannels = PlaylistParsers.parseM3u(reader).first
          if (bundledChannels.isEmpty()) return
          allChannels = bundledChannels
          restoreLastChannel()
          renderChannels()
          showChannelPreviewById(activeChannelId() ?: bundledChannels.first().id)
          updateStartupLoadingStatus("Включаю первый канал...")
          binding.statusLabel.text = "Демо-каналы загружены"
        }
      }
    }
  }

  private fun loadCachedChannels(): List<Channel> {
    val cacheFile = File(filesDir, CHANNEL_CACHE_FILE)
    if (!cacheFile.exists()) return emptyList()

    val root = JSONObject(cacheFile.readText(Charsets.UTF_8))
    val channelsJson = root.optJSONArray("channels") ?: return emptyList()
    val channels = mutableListOf<Channel>()

    for (index in 0 until channelsJson.length()) {
      val channelJson = channelsJson.optJSONObject(index) ?: continue
      val programsJson = channelJson.optJSONArray("programs") ?: JSONArray()
      val programs = mutableListOf<ProgramSlot>()

      for (programIndex in 0 until programsJson.length()) {
        val programJson = programsJson.optJSONObject(programIndex) ?: continue
        programs += ProgramSlot(
          startMillis = programJson.optLong("startMillis"),
          endMillis = programJson.optLong("endMillis"),
          title = programJson.optString("title"),
          description = programJson.optString("description"),
        )
      }

      channels += Channel(
        id = channelJson.optString("id"),
        name = channelJson.optString("name"),
        group = channelJson.optString("group"),
        region = channelJson.optString("region"),
        sourceUrl = channelJson.optString("sourceUrl"),
        sourceType = channelJson.optString("sourceType"),
        epgId = channelJson.optString("epgId"),
        epgName = channelJson.optString("epgName"),
        logo = channelJson.optString("logo"),
        programs = programs,
        health = channelJson.optString("health", "unknown"),
      )
    }

    return channels
  }

  private fun saveCachedChannels(channels: List<Channel>) {
    val channelsJson = JSONArray()
    channels.forEach { channel ->
      val programsJson = JSONArray()
      channel.programs.forEach { slot ->
        programsJson.put(
          JSONObject()
            .put("startMillis", slot.startMillis)
            .put("endMillis", slot.endMillis)
            .put("title", slot.title)
            .put("description", slot.description),
        )
      }

      channelsJson.put(
        JSONObject()
          .put("id", channel.id)
          .put("name", channel.name)
          .put("group", channel.group)
          .put("region", channel.region)
          .put("sourceUrl", channel.sourceUrl)
          .put("sourceType", channel.sourceType)
          .put("epgId", channel.epgId)
          .put("epgName", channel.epgName)
          .put("logo", channel.logo)
          .put("health", channel.health)
          .put("programs", programsJson),
      )
    }

    File(filesDir, CHANNEL_CACHE_FILE).writeText(
      JSONObject().put("channels", channelsJson).toString(),
      Charsets.UTF_8,
    )
  }

  private fun renderChannels() {
    channels = ChannelUiTools.visibleChannels(
      allChannels = allChannels,
      selectedCategory = selectedCategory,
      favoriteIds = favoriteIds,
      showFavoritesOnly = showFavoritesOnly,
      hideDeadChannels = hideDeadChannels,
      showDeadOnly = showDeadOnly,
    )
    updateCategoryButton()

    if (channels.isEmpty()) {
      adapter.submitChannels(emptyList(), null, favoriteIds)
      binding.channelCountLabel.text = getString(R.string.channel_position, 0, 0)
      binding.favoritesFilterButton.alpha = if (showFavoritesOnly) 1f else 0.7f
      binding.allChannelsButton.alpha = if (!showFavoritesOnly && !showDeadOnly) 1f else 0.7f
      if (hideDeadChannels && !showDeadOnly && allChannels.any { it.health == "dead" }) {
        binding.channelTitle.text = "Рабочих каналов не найдено"
        binding.channelMeta.text = "Откройте Категория -> Битые каналы или обновите плейлист"
        binding.currentProgram.text = "EPG не найден"
        binding.nextProgram.text = "Далее: --"
      }
      return
    }

    val activeId = activeChannelId()
    val displayIndex = channels.indexOfFirst { it.id == activeId }.takeIf { it >= 0 } ?: 0
    activeIndex = allChannels.indexOfFirst { it.id == channels[displayIndex].id }.coerceAtLeast(0)
    adapter.submitChannels(channels, channels[displayIndex].id, favoriteIds)
    binding.channelCountLabel.text = getString(R.string.channel_position, displayIndex + 1, channels.size)
    binding.favoritesFilterButton.alpha = if (showFavoritesOnly) 1f else 0.7f
    binding.allChannelsButton.alpha = if (!showFavoritesOnly && !showDeadOnly) 1f else 0.7f
  }

  private fun categoryNames(): List<String> =
    ChannelUiTools.categoryNames(allChannels)

  private fun updateCategoryButton() {
    binding.categoryButton.text = when {
      showDeadOnly -> "Категория: Битые"
      selectedCategory != null -> "Категория: $selectedCategory"
      else -> "Категория: Все"
    }
  }

  private fun showCategoryDialog() {
    val categories = mutableListOf("Все")
    if (allChannels.any { it.health == "dead" }) {
      categories += DEAD_CHANNELS_CATEGORY
    }
    categories += categoryNames()
    if (categories.size == 1) {
      toast("Категорий пока нет")
      return
    }
    val checkedIndex = if (showDeadOnly) {
      categories.indexOf(DEAD_CHANNELS_CATEGORY).takeIf { it >= 0 } ?: 0
    } else {
      selectedCategory?.let { selected ->
        categories.indexOfFirst { it.equals(selected, ignoreCase = true) }
      }?.takeIf { it >= 0 } ?: 0
    }

    AlertDialog.Builder(this)
      .setTitle("Категория каналов")
      .setSingleChoiceItems(categories.toTypedArray(), checkedIndex) { dialog, index ->
        val selected = categories[index]
        showDeadOnly = selected == DEAD_CHANNELS_CATEGORY
        selectedCategory = selected.takeUnless { it == "Все" || it == DEAD_CHANNELS_CATEGORY }
        showFavoritesOnly = false
        renderChannels()
        focusActiveChannelInList()
        dialog.dismiss()
      }
      .setNegativeButton("Отмена", null)
      .show()
  }

  private fun applyPictureMode(save: Boolean) {
    if (pictureModeIndex !in 0..2) {
      pictureModeIndex = 0
    }
    binding.playerView.resizeMode = when (pictureModeIndex) {
      1 -> AspectRatioFrameLayout.RESIZE_MODE_FIT
      2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
      else -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    }
    binding.pictureModeButton.text = "Картинка"
    if (save) {
      preferences.edit().putInt(KEY_PICTURE_MODE, pictureModeIndex).apply()
    }
  }

  private fun cyclePictureMode() {
    pictureModeIndex = (pictureModeIndex + 1) % 3
    applyPictureMode(save = true)
    toast("Режим картинки: ${pictureModeLabel()}")
  }

  private fun pictureModeLabel(): String =
    ChannelUiTools.pictureModeLabel(pictureModeIndex)

  private fun showDiagnosticsDialog() {
    val health = ChannelUiTools.healthSummary(allChannels)
    val source = preferences.getString(KEY_REMOTE_CONFIG_URL, DEFAULT_PLAYLIST_URL).orEmpty().ifBlank {
      DEFAULT_PLAYLIST_URL
    }
    val log = recentLogText().ifBlank { "Ошибок пока нет." }
    val message = buildString {
      appendLine("Каналов всего: ${allChannels.size}")
      appendLine("Сейчас в списке: ${channels.size}")
      appendLine("Избранных: ${favoriteIds.size}")
      appendLine("Категорий: ${categoryNames().size}")
      appendLine("Фильтр: ${if (showDeadOnly) DEAD_CHANNELS_CATEGORY else selectedCategory ?: "Все"}")
      appendLine("Картинка: ${pictureModeLabel()}")
      appendLine("Потоки: работает ${health.alive}, не отвечает ${health.dead}, не проверено ${health.unknown}")
      if (hideDeadChannels && health.dead > 0) {
        appendLine("Битые скрыты из общей ленты: ${health.dead}")
      }
      appendLine("Последняя синхронизация: ${formatLastSync()}")
      appendLine()
      appendLine("Источник:")
      appendLine(source)
      appendLine()
      appendLine("Последние события:")
      append(log)
    }

    AlertDialog.Builder(this)
      .setTitle("Диагностика")
      .setMessage(message)
      .setPositiveButton("Закрыть", null)
      .setNegativeButton("Очистить лог") { _, _ ->
        runCatching { File(filesDir, ERROR_LOG_FILE).delete() }
        toast("Лог очищен")
      }
      .setNeutralButton("Показать битые") { _, _ ->
        val deadCount = allChannels.count { it.health == "dead" }
        if (deadCount == 0) {
          toast("Битых каналов нет")
        } else {
          showDeadOnly = true
          selectedCategory = null
          showFavoritesOnly = false
          renderChannels()
          focusActiveChannelInList()
          toggleSidebar(true)
          toast("Показаны битые каналы: $deadCount")
        }
      }
      .show()
  }

  private fun runFullChannelCheck(showDialogWhenDone: Boolean = false) {
    if (allChannels.isEmpty()) {
      if (showDialogWhenDone) {
        showDiagnosticsDialog()
      } else {
        toast("Каналов пока нет")
      }
      return
    }

    healthCheckJob?.cancel()
    binding.statusLabel.text = "Проверяем каналы..."
    logEvent("Запущена полная проверка каналов: ${allChannels.size}")

    healthCheckJob = lifecycleScope.launch(Dispatchers.IO) {
      var alive = 0
      var dead = 0
      var checked = 0
      val channelsToCheck = allChannels

      channelsToCheck.chunked(HEALTH_PARALLELISM).forEach { chunk ->
        val batch = chunk
          .map { channel ->
            async { channel.id to checkChannelHealth(channel) }
          }
          .awaitAll()
          .toMap()

        batch.values.forEach { health ->
          if (health == "alive") alive += 1 else dead += 1
        }
        checked += batch.size

        withContext(Dispatchers.Main) {
          applyHealthBatch(batch)
          binding.statusLabel.text =
            "Проверено $checked/${channelsToCheck.size}: работает $alive, не отвечает $dead"
        }
      }

      withContext(Dispatchers.Main) {
        hideDeadChannels = dead > 0
        showDeadOnly = false
        preferences.edit().putBoolean(KEY_HIDE_DEAD_CHANNELS, hideDeadChannels).apply()
        renderChannels()
        val activeId = activeChannelId()
        if (channels.isNotEmpty() && (activeId == null || channels.none { it.id == activeId })) {
          showChannelPreviewById(channels.first().id)
        }
        val message = if (dead > 0) {
          "Проверка завершена: работает $alive, скрыто битых $dead"
        } else {
          "Проверка завершена: работает $alive, битых нет"
        }
        binding.statusLabel.text = message
        toast(message)
        logEvent(message)
        if (showDialogWhenDone && !isFinishing) {
          showDiagnosticsDialog()
        }
      }

      runCatching { saveCachedChannels(allChannels) }
    }
  }

  private fun openChannelAt(displayIndex: Int) {
    if (channels.isEmpty()) return

    val safeDisplayIndex = displayIndex.coerceIn(0, channels.lastIndex)
    val channel = channels[safeDisplayIndex]
    val absoluteIndex = allChannels.indexOfFirst { it.id == channel.id }.coerceAtLeast(0)

    showChannelPreviewById(channel.id)
    showZapOverlay(channel)

    if (!channel.sourceUrl.isPlayableSourceUrl()) {
      openingChannelId = null
      hideChannelLoading()
      hideStartupLoading()
      binding.statusLabel.text = "У канала неверная ссылка"
      updateChannelHealth(channel.id, "dead")
      logEvent("Канал пропущен из-за неверной ссылки: ${channel.name}")
      toast("Канал пропущен: неверная ссылка")
      return
    }

    runCatching {
      showChannelLoading(channel)
      openingChannelId = channel.id
      playbackConfirmedChannelId = null
      val mediaItem = MediaItem.Builder()
        .setUri(Uri.parse(channel.sourceUrl))
        .setMimeType(
          when (channel.sourceType.lowercase(Locale.getDefault())) {
            "hls" -> MimeTypes.APPLICATION_M3U8
            "mp4" -> MimeTypes.VIDEO_MP4
            else -> null
          },
        )
        .build()

      player.setMediaItem(mediaItem)
      player.prepare()
      player.playWhenReady = true
      setFullscreenPlayback(true)
      activeIndex = absoluteIndex
      preferences.edit().putString(KEY_LAST_CHANNEL_ID, channel.id).apply()
      binding.statusLabel.text = getString(
        R.string.status_live_source,
        channel.sourceType.uppercase(Locale.getDefault()),
      )
    }.onFailure {
      openingChannelId = null
      hideChannelLoading()
      hideStartupLoading()
      binding.statusLabel.text = "Поток недоступен"
      updateChannelHealth(channel.id, "dead")
      logEvent("Не удалось подготовить канал ${channel.name}: ${it.localizedMessage ?: it.javaClass.simpleName}")
      toast("Не удалось запустить канал")
    }
  }

  private fun showChannelPreviewById(channelId: String) {
    val channel = allChannels.firstOrNull { it.id == channelId } ?: return
    activeIndex = allChannels.indexOfFirst { it.id == channel.id }.coerceAtLeast(0)
    val now = channel.programs.firstOrNull {
      it.startMillis <= System.currentTimeMillis() && it.endMillis > System.currentTimeMillis()
    }
    val next = channel.programs.firstOrNull { it.startMillis > System.currentTimeMillis() }

    binding.channelTitle.text = channel.name
    binding.channelMeta.text = "${channel.group} / ${channel.region}"
    binding.currentProgram.text = now?.title ?: "EPG не найден"
    binding.nextProgram.text = next?.let { "Далее: ${it.title}" } ?: "Далее: --"
    binding.favoriteButton.text =
      if (favoriteIds.contains(channel.id)) "Убрать из избранного" else "В избранное"
    renderChannels()
  }

  private fun showZapOverlay(channel: Channel) {
    val displayIndex = channels.indexOfFirst { it.id == channel.id }.takeIf { it >= 0 } ?: 0
    binding.zapChannelNumber.text = String.format("%02d", displayIndex + 1)
    binding.zapChannelTitle.text = channel.name
    binding.zapChannelMeta.text = "${channel.group} / ${channel.region}"
    binding.zapOverlay.visibility = View.VISIBLE
    uiHandler.removeCallbacks(hideZapRunnable)
    uiHandler.postDelayed(hideZapRunnable, 2200L)
  }

  private fun switchChannel(step: Int) {
    if (channels.isEmpty()) return
    val activeId = activeChannelId()
    val currentDisplayIndex = channels.indexOfFirst { it.id == activeId }.takeIf { it >= 0 } ?: 0
    val nextIndex = (currentDisplayIndex + step + channels.size) % channels.size
    openChannelAt(nextIndex)
  }

  private fun toggleFavorite() {
    val channel = allChannels.getOrNull(activeIndex) ?: return
    toggleFavorite(channel)
  }

  private fun toggleFavorite(channel: Channel) {
    if (!favoriteIds.add(channel.id)) {
      favoriteIds.remove(channel.id)
    }
    preferences.edit().putStringSet(KEY_FAVORITES, favoriteIds).apply()
    binding.favoriteButton.text =
      if (favoriteIds.contains(channel.id)) "Убрать из избранного" else "В избранное"
    renderChannels()
  }

  private fun showChannelActionsDialog(displayIndex: Int) {
    if (channels.isEmpty()) return
    val safeDisplayIndex = displayIndex.coerceIn(0, channels.lastIndex)
    val channel = channels[safeDisplayIndex]
    val isFavorite = favoriteIds.contains(channel.id)
    val actionLabel =
      if (isFavorite) "Убрать из избранного" else "Добавить в избранное"

    AlertDialog.Builder(this)
      .setTitle(channel.name)
      .setItems(arrayOf(actionLabel)) { _, _ ->
        toggleFavorite(channel)
        toast(
          if (favoriteIds.contains(channel.id)) {
            "Канал добавлен в избранное"
          } else {
            "Канал убран из избранного"
          },
        )
      }
      .setNegativeButton("Отмена", null)
      .show()
  }

  private fun showGuideDialog() {
    val channel = allChannels.getOrNull(activeIndex) ?: return
    val items = if (channel.programs.isEmpty()) {
      arrayOf("EPG пока не найден для этого канала")
    } else {
      channel.programs.take(MAX_GUIDE_ITEMS).map { slot ->
        val formatter = SimpleDateFormat("HH:mm", Locale("ru"))
        val description = slot.description.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
        "${formatter.format(Date(slot.startMillis))} - ${formatter.format(Date(slot.endMillis))}\n${slot.title}$description"
      }.toTypedArray()
    }

    AlertDialog.Builder(this)
      .setTitle("Программа: ${channel.name}")
      .setItems(items, null)
      .setPositiveButton("Закрыть", null)
      .show()
  }

  private fun showRemoteConfigDialog() {
    val input = EditText(this).apply {
      setText(preferences.getString(KEY_REMOTE_CONFIG_URL, DEFAULT_PLAYLIST_URL).orEmpty().ifBlank {
        DEFAULT_PLAYLIST_URL
      })
      hint = DEFAULT_PLAYLIST_URL
    }

    AlertDialog.Builder(this)
      .setTitle("Источник каналов")
      .setMessage("Вставьте прямую ссылку на M3U/M3U8 или ссылку на JSON-конфиг админки.")
      .setView(input)
      .setPositiveButton("Сохранить") { _, _ ->
        preferences.edit().putString(KEY_REMOTE_CONFIG_URL, input.text.toString().trim()).apply()
        syncRemoteConfig(true)
      }
      .setNegativeButton("Отмена", null)
      .show()
  }

  private fun showNavigatorDialog(fromStartup: Boolean) {
    preferences.edit().putBoolean(KEY_NAVIGATOR_SEEN, true).apply()

    val message = """
      Быстрая навигация:

      OK - открыть канал или нажать выбранную кнопку.
      Долгое OK на канале - добавить или убрать из избранного.
      Вверх / вниз - переключить канал.
      Влево - открыть список каналов поверх видео.
      Вправо или Назад - закрыть список каналов.
      Цифры на пульте - быстрый переход к номеру канала.

      Обновить - заново загрузить плейлист.
      Настроить - изменить ссылку на M3U.
      Категория - быстро отфильтровать каналы или открыть битые.
      Картинка - переключить заполнение экрана.
      Диагностика - проверить все потоки и показать итог.
      Навигатор - снова открыть эту подсказку.
    """.trimIndent()

    AlertDialog.Builder(this)
      .setTitle(if (fromStartup) "Как пользоваться" else "Навигатор")
      .setMessage(message)
      .setPositiveButton("Понятно", null)
      .show()
  }

  private fun toggleSidebar(show: Boolean) {
    binding.sidebar.isVisible = show
    if (show) {
      setFullscreenPlayback(false)
      focusSidebarEntry()
    } else {
      binding.playerView.requestFocus()
      setFullscreenPlayback(true)
    }
  }

  private fun focusSidebarEntry() {
    binding.syncButton.requestFocus()
  }

  private fun focusActiveChannelInList() {
    if (channels.isEmpty()) return
    val activeId = activeChannelId()
    val displayIndex = channels.indexOfFirst { it.id == activeId }.takeIf { it >= 0 } ?: 0
    layoutManager.scrollToPositionWithOffset(displayIndex, 24)
    binding.channelList.post {
      val holder = binding.channelList.findViewHolderForAdapterPosition(displayIndex)
      holder?.itemView?.requestFocus() ?: binding.channelList.requestFocus()
    }
  }

  private fun setFullscreenPlayback(enabled: Boolean) {
    val visibility = if (enabled) View.GONE else View.VISIBLE
    binding.topInfoBar.visibility = visibility
    binding.bottomOverlay.visibility = visibility
  }

  private fun showStartupLoading(message: String) {
    binding.startupLoadingStatus.text = message
    binding.startupLoadingOverlay.visibility = View.VISIBLE
  }

  private fun updateStartupLoadingStatus(message: String) {
    if (binding.startupLoadingOverlay.isVisible) {
      binding.startupLoadingStatus.text = message
    }
  }

  private fun hideStartupLoading() {
    binding.startupLoadingOverlay.visibility = View.GONE
  }

  private fun showChannelLoading(channel: Channel) {
    hideStartupLoading()
    binding.channelLoadingName.text = channel.name
    binding.channelLoadingStatus.text = "Подключаю ${channel.sourceType.uppercase(Locale.getDefault())}..."
    binding.channelLoadingOverlay.visibility = View.VISIBLE
  }

  private fun hideChannelLoading() {
    binding.channelLoadingOverlay.visibility = View.GONE
  }

  private fun keepScreenAwake() {
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    binding.playerView.keepScreenOn = true
  }

  private fun appendChannelDigit(digit: Int) {
    if (channels.isEmpty()) return
    pendingChannelDigits = if (pendingChannelDigits.length >= 3) {
      digit.toString()
    } else {
      pendingChannelDigits + digit.toString()
    }
    binding.numberInputOverlay.text = pendingChannelDigits
    binding.numberInputOverlay.visibility = View.VISIBLE
    uiHandler.removeCallbacks(applyNumberInputRunnable)
    uiHandler.postDelayed(applyNumberInputRunnable, 1200L)
  }

  private fun applyPendingChannelNumber() {
    val number = pendingChannelDigits.toIntOrNull()
    pendingChannelDigits = ""
    binding.numberInputOverlay.visibility = View.GONE
    if (number == null || number <= 0 || number > channels.size) {
      toast("Такого канала нет")
      return
    }
    openChannelAt(number - 1)
  }

  private fun startHealthChecks() {
    healthCheckJob?.cancel()
    if (allChannels.size > MAX_HEALTH_CHECK_CHANNELS) {
      binding.statusLabel.text = "Проверка потоков ограничена для больших плейлистов"
      return
    }
    healthCheckJob = lifecycleScope.launch(Dispatchers.IO) {
      runCatching {
        val updates = mutableMapOf<String, String>()
        allChannels.forEachIndexed { index, channel ->
          updates[channel.id] = checkChannelHealth(channel)
          if (updates.size >= HEALTH_BATCH_SIZE || index == allChannels.lastIndex) {
            val batch = updates.toMap()
            updates.clear()
            withContext(Dispatchers.Main) {
              runCatching { applyHealthBatch(batch) }
            }
          }
        }
      }
    }
  }

  private fun updateChannelHealth(channelId: String?, health: String) {
    if (channelId.isNullOrBlank()) return
    applyHealthBatch(mapOf(channelId to health))
  }

  private fun applyHealthBatch(batch: Map<String, String>) {
    if (batch.isEmpty()) return
    allChannels = allChannels.map { channel ->
      batch[channel.id]?.let { nextHealth ->
        if (channel.health != nextHealth) channel.copy(health = nextHealth) else channel
      } ?: channel
    }
    renderChannels()
  }

  private fun checkChannelHealth(channel: Channel): String {
    return runCatching {
      val request = Request.Builder()
        .url(channel.sourceUrl)
        .header("Range", "bytes=0-0")
        .build()
      healthClient.newCall(request).execute().use { response ->
        if (response.isSuccessful) "alive" else "dead"
      }
    }.recoverCatching {
      val request = Request.Builder().url(channel.sourceUrl).head().build()
      healthClient.newCall(request).execute().use { response ->
        if (response.isSuccessful) "alive" else "dead"
      }
    }.getOrElse { error ->
      if (error is SocketTimeoutException) "dead" else "dead"
    }
  }

  override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
    if (event?.action != KeyEvent.ACTION_DOWN) {
      return super.onKeyDown(keyCode, event)
    }

    if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
      appendChannelDigit(keyCode - KeyEvent.KEYCODE_0)
      return true
    }

    if (!binding.sidebar.isVisible) {
      when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> {
          switchChannel(-1)
          return true
        }
        KeyEvent.KEYCODE_DPAD_DOWN -> {
          switchChannel(1)
          return true
        }
        KeyEvent.KEYCODE_DPAD_LEFT -> {
          toggleSidebar(true)
          return true
        }
      }
    } else {
      val focus = currentFocus
      val focusInsideList = focus != null && isDescendantOf(focus, binding.channelList)
      when (keyCode) {
        KeyEvent.KEYCODE_DPAD_RIGHT -> {
          if (
            focus === binding.settingsButton ||
            focus === binding.favoritesFilterButton ||
            focus === binding.pictureModeButton ||
            focus === binding.navigatorButton
          ) {
            return true
          }
          if (focusInsideList) {
            toggleSidebar(false)
            return true
          }
        }
        KeyEvent.KEYCODE_DPAD_DOWN -> {
          if (currentFocus === binding.favoritesFilterButton) {
            focusActiveChannelInList()
            return true
          }
        }
        KeyEvent.KEYCODE_BACK -> {
          toggleSidebar(false)
          return true
        }
      }
    }

    return super.onKeyDown(keyCode, event)
  }

  private fun isDescendantOf(child: View, parent: View): Boolean {
    var current: View? = child
    while (current != null) {
      if (current === parent) return true
      val p = current.parent
      current = if (p is View) p else null
    }
    return false
  }

  private fun activeChannelId(): String? = allChannels.getOrNull(activeIndex)?.id

  override fun onStop() {
    super.onStop()
    player.pause()
  }

  override fun onDestroy() {
    uiHandler.removeCallbacksAndMessages(null)
    healthCheckJob?.cancel()
    player.release()
    super.onDestroy()
  }

  private fun formatLastSync(): String {
    val timestamp = preferences.getLong(KEY_LAST_SYNC_MS, 0L)
    if (timestamp <= 0L) return "еще не было"
    return SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru")).format(Date(timestamp))
  }

  private fun recentLogText(): String {
    return runCatching {
      val logFile = File(filesDir, ERROR_LOG_FILE)
      if (!logFile.exists()) return@runCatching ""
      logFile.readText(Charsets.UTF_8)
        .lines()
        .filter { it.isNotBlank() }
        .takeLast(8)
        .joinToString("\n")
    }.getOrDefault("")
  }

  private fun logEvent(message: String) {
    runCatching {
      val logFile = File(filesDir, ERROR_LOG_FILE)
      val timestamp = SimpleDateFormat("dd.MM HH:mm:ss", Locale("ru")).format(Date())
      val previous = if (logFile.exists()) {
        logFile.readText(Charsets.UTF_8).takeLast(MAX_LOG_CHARS)
      } else {
        ""
      }
      val next = buildString {
        if (previous.isNotBlank()) {
          append(previous.trim())
          append('\n')
        }
        append('[')
        append(timestamp)
        append("] ")
        append(message)
      }.takeLast(MAX_LOG_CHARS)
      logFile.writeText(next, Charsets.UTF_8)
    }
  }

  private fun httpText(url: String, maxBytes: Long): String {
    return httpReader(url) { reader ->
      val buffer = CharArray(4096)
      val builder = StringBuilder()
      var total = 0L

      while (true) {
        val count = reader.read(buffer)
        if (count < 0) break
        total += count
        if (total > maxBytes) {
          error("Файл конфигурации слишком большой")
        }
        builder.append(buffer, 0, count)
      }

      builder.toString()
    }
  }

  private fun <T> httpReader(url: String, block: (Reader) -> T): T {
    if (!url.isPlayableSourceUrl()) {
      error("Неверная ссылка на источник")
    }
    val request = Request.Builder().url(url).build()
    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) error("HTTP ${response.code}")
      val body = response.body ?: error("Пустой ответ сервера")
      body.use {
        val reader = body.charStream().buffered()
        return block(reader)
      }
    }
  }

  private fun toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
  }

  private fun String.normalizeKey(): String =
    trim().lowercase(Locale.getDefault()).replace(Regex("[^\\p{L}\\p{N}]+"), "")

  private fun String.isPlayableSourceUrl(): Boolean {
    val value = trim()
    if (value.isBlank()) return false
    return value.startsWith("http://", ignoreCase = true) ||
      value.startsWith("https://", ignoreCase = true) ||
      value.startsWith("rtmp://", ignoreCase = true) ||
      value.startsWith("rtsp://", ignoreCase = true)
  }

  companion object {
    private const val DEFAULT_PLAYLIST_URL = "https://homtv.ru/hom.m3u"
    private const val BUNDLED_PLAYLIST_ASSET = "moderation_demo.m3u"
    private const val CHANNEL_CACHE_FILE = "channels_cache.json"
    private const val KEY_REMOTE_CONFIG_URL = "remote_config_url"
    private const val KEY_FAVORITES = "favorites"
    private const val KEY_LAST_CHANNEL_ID = "last_channel_id"
    private const val KEY_NAVIGATOR_SEEN = "navigator_seen"
    private const val KEY_LAST_SYNC_MS = "last_sync_ms"
    private const val KEY_PICTURE_MODE = "picture_mode"
    private const val KEY_HIDE_DEAD_CHANNELS = "hide_dead_channels"
    private const val DEAD_CHANNELS_CATEGORY = "Битые каналы"
    private const val ERROR_LOG_FILE = "freeflow_events.log"
    private const val MAX_CONFIG_SIZE_BYTES = 4 * 1024 * 1024L
    private const val MAX_LOG_CHARS = 32 * 1024
    private const val AUTO_SYNC_INTERVAL_MS = 12 * 60 * 60 * 1000L
    private const val MAX_HEALTH_CHECK_CHANNELS = 80
    private const val HEALTH_BATCH_SIZE = 8
    private const val HEALTH_PARALLELISM = 6
    private const val MAX_GUIDE_ITEMS = 80
  }
}

