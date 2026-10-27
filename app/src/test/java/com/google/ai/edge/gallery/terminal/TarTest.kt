package com.google.ai.edge.gallery.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TarTest {
  @Test
  fun parsePax_readsPathAndLink() {
    val path = "path=usr/lib/a-very-long-name.so\n"
    val link = "linkpath=../../lib/libc.musl-aarch64.so.1\n"
    fun record(s: String): String {
      // "<length> <key>=<value>\n", the length counting itself.
      var len = s.length + 3
      if ((len.toString().length + 1 + s.length) != len) len = len.toString().length + 1 + s.length
      return "$len $s"
    }
    val pax = Tar.parsePax(record(path) + record(link))
    assertEquals("usr/lib/a-very-long-name.so", pax["path"])
    assertEquals("../../lib/libc.musl-aarch64.so.1", pax["linkpath"])
  }

  @Test
  fun parseOctal_readsTarNumbers() {
    val field = "0000644\u0000".toByteArray()
    assertEquals(420L, Tar.parseOctal(field, 0, 8))
    val size = "00000012345 ".toByteArray()
    assertEquals(5349L, Tar.parseOctal(size, 0, 12))
  }
}
