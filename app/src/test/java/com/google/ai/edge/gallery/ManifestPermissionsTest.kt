package com.google.ai.edge.gallery

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * [ALTREX] The app's own manifest asks only for what Altrex uses, and strips the advertising and
 * storage permissions that libraries merge in. Firebase (analytics, messaging) is gone.
 */
class ManifestPermissionsTest {
  private val androidNs = "http://schemas.android.com/apk/res/android"
  private val toolsNs = "http://schemas.android.com/tools"

  private fun manifest(): Element {
    val file =
      listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
        .first { it.exists() }
    val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
    return factory.newDocumentBuilder().parse(file).documentElement
  }

  /** Permission name to its tools:node value ("" when the permission is really requested). */
  private fun permissions(): Map<String, String> {
    val nodes = manifest().getElementsByTagName("uses-permission")
    return (0 until nodes.length).associate { i ->
      val e = nodes.item(i) as Element
      e.getAttributeNS(androidNs, "name") to e.getAttributeNS(toolsNs, "node")
    }
  }

  @Test
  fun unusedPermissions_areNotRequested() {
    val requested = permissions().filterValues { it.isEmpty() }.keys
    val unwanted =
      listOf(
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.ACCESS_MEDIA_LOCATION",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.GET_ACCOUNTS",
        "android.permission.READ_PHONE_STATE",
        "com.google.android.c2dm.permission.RECEIVE",
        "com.google.android.providers.gsf.permission.READ_GSERVICES",
        "com.google.android.gms.permission.AD_ID",
      )
    for (p in unwanted) assertFalse("$p must not be requested", p in requested)
  }

  @Test
  fun libraryPermissions_areStrippedFromTheMergedManifest() {
    val all = permissions()
    for (p in
      listOf(
        "com.google.android.gms.permission.AD_ID",
        "android.permission.ACCESS_ADSERVICES_AD_ID",
        "android.permission.ACCESS_ADSERVICES_ATTRIBUTION",
        "android.permission.READ_PHONE_STATE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
      )) {
      assertEquals("$p needs tools:node=\"remove\"", "remove", all[p])
    }
  }

  @Test
  fun usedPermissions_stay() {
    val requested = permissions().filterValues { it.isEmpty() }.keys
    for (p in
      listOf(
        "android.permission.INTERNET",
        "android.permission.CAMERA",
        "android.permission.RECORD_AUDIO",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.READ_CALENDAR",
        "com.android.alarm.permission.SET_ALARM",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
        // BootReceiver sets the scheduled reminders again after a reboot.
        "android.permission.RECEIVE_BOOT_COMPLETED",
      )) {
      assertTrue("$p is used and must stay", p in requested)
    }
  }

  @Test
  fun noFirebaseComponents() {
    val text =
      listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
        .first { it.exists() }
        .readText()
    assertFalse(text.contains("firebase", ignoreCase = true))
    assertFalse(text.contains("com.google.android.gms.measurement"))
  }
}
