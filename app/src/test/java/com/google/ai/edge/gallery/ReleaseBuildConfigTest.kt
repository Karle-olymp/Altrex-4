package com.google.ai.edge.gallery

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ALTREX] The release build runs R8. Nothing on the JVM can see a class R8 renamed or removed,
 * so this guards the keep rules written for native code and reflection (app/proguard-rules.pro)
 * and the release settings of app/build.gradle.kts: a rule deleted by mistake fails here instead
 * of crashing on the phone.
 */
class ReleaseBuildConfigTest {
  private fun moduleFile(name: String): File =
    listOf(File(name), File("app/$name")).first { it.exists() }

  private val rules: List<String> by lazy {
    moduleFile("proguard-rules.pro").readLines().map { it.trim() }.filter { !it.startsWith("#") }
  }

  private fun assertRule(rule: String) =
    assertTrue("proguard-rules.pro must contain: $rule", rules.any { it.startsWith(rule) })

  @Test
  fun nativeLibraries_keepTheirJavaSide() {
    // liblitertlm_jni.so, librnllama*.so (jni.cpp) and libtermux.so bind and call back by name.
    assertRule("-keep class com.google.ai.edge.litertlm.** { *; }")
    assertRule("-keep class org.nehuatl.llamacpp.** { *; }")
    assertRule("-keep class com.termux.terminal.JNI { *; }")
  }

  @Test
  fun reflectionAndSerialization_areKept() {
    // LiteRT-LM tools are described to the model from their Kotlin names (kotlin-reflect).
    assertRule("-keep class * implements com.google.ai.edge.litertlm.ToolSet { *; }")
    // DataStore protos (lite runtime reflects on field names).
    assertRule("-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }")
    // Gson models of the model and skill allowlists.
    assertRule("-keep class com.google.ai.edge.gallery.data.ModelAllowlist { *; }")
    assertRule("-keep class com.google.ai.edge.gallery.data.AllowedModel { *; }")
    assertRule("-keep class com.google.ai.edge.gallery.data.SkillAllowlist { *; }")
    assertRule("-keep enum com.google.ai.edge.gallery.data.RuntimeType { *; }")
    // WebView bridges (skills WebView, code runner).
    assertRule("@android.webkit.JavascriptInterface <methods>;")
  }

  @Test
  fun crashTraces_stayDecodable() {
    assertRule("-keepattributes SourceFile,LineNumberTable")
    assertRule("-renamesourcefileattribute SourceFile")
  }

  @Test
  fun releaseBuildType_isMinifiedAndNotDebuggable() {
    val gradle = moduleFile("build.gradle.kts").readText()
    val release = gradle.substringAfter("    release {").substringBefore("\n    }\n")
    assertTrue(release.contains("isMinifyEnabled = true"))
    assertTrue(release.contains("isShrinkResources = true"))
    assertTrue(release.contains("isDebuggable = false"))
    assertTrue(release.contains("proguard-android-optimize.txt"))
    // Signed with the shared key only when the keystore exists: a checkout without it still builds.
    assertTrue(release.contains("if (sharedKeystore.exists())"))
  }

  @Test
  fun removedLibraries_stayRemoved() {
    val gradle = moduleFile("build.gradle.kts").readText()
    for (lib in listOf("firebase", "tflite", "tasks.vision", "google.services")) {
      assertFalse("$lib is back in app/build.gradle.kts", gradle.contains(lib))
    }
  }
}
