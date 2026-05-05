package com.freeflowtv.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SidebarLayoutTest {
  @Test
  fun sidebarUsesPictureButtonNextToDiagnosticsWithoutSeparateCheckButton() {
    val layout = File("src/main/res/layout/activity_main.xml").readText(Charsets.UTF_8)

    assertFalse(layout.contains("@+id/checkChannelsButton"))
    assertFalse(layout.contains("Проверить"))
    assertTrue(layout.contains("@+id/diagnosticsButton"))
    assertTrue(layout.contains("@+id/pictureModeButton"))
    assertTrue(layout.indexOf("@+id/pictureModeButton") > layout.indexOf("@+id/diagnosticsButton"))
  }
}
