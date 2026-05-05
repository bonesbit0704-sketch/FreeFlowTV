package com.freeflowtv.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LoadingOverlayLayoutTest {
  @Test
  fun activityLayoutContainsStartupAndChannelLoadingOverlays() {
    val layout = File("src/main/res/layout/activity_main.xml").readText(Charsets.UTF_8)

    assertTrue(layout.contains("@+id/startupLoadingOverlay"))
    assertTrue(layout.contains("@+id/startupLoadingStatus"))
    assertTrue(layout.contains("@+id/channelLoadingOverlay"))
    assertTrue(layout.contains("@+id/channelLoadingName"))
    assertTrue(layout.contains("@+id/channelLoadingProgress"))
  }
}
