package com.google.ai.edge.gallery.skills

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeScriptNameTest {
  @Test
  fun plainRelativeNamesPass() {
    assertTrue(isSafeScriptName("index.html"))
    assertTrue(isSafeScriptName("sub/page.html"))
  }

  @Test
  fun climbingOrAbsoluteNamesAreRefused() {
    for (n in listOf("../../datastore/user_data.pb", "a/../../b", "/etc/passwd", "..\\x", "%2e%2e/x", "a%2Fb", "", "  "))
      assertFalse(n, isSafeScriptName(n))
  }
}
