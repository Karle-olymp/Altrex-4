package com.google.ai.edge.gallery.ui.github

/** A GitHub repository named by the user ("owner/name" or a github.com link). */
data class RepoRef(val owner: String, val name: String) {
  val fullName: String
    get() = "$owner/$name"
}

/**
 * [ALTREX] Pure rules of the GitHub import (no Android), tested on their own: what the user typed,
 * and which paths of the downloaded archive may be written into the project folder.
 */
object GitHubPaths {
  private val OWNER = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})")
  private val NAME = Regex("[A-Za-z0-9._-]{1,100}")
  private val BRANCH = Regex("[A-Za-z0-9._/-]{1,200}")

  /** "owner/repo", "github.com/owner/repo", "https://github.com/owner/repo(.git)(/tree/...)". */
  fun parseRepo(input: String): RepoRef? {
    var s = input.trim().removeSuffix("/")
    s = s.removePrefix("https://").removePrefix("http://").removePrefix("www.")
    if (s.startsWith("github.com/", ignoreCase = true)) s = s.substring("github.com/".length)
    val parts = s.split('/')
    if (parts.size < 2) return null
    val owner = parts[0]
    val name = parts[1].removeSuffix(".git")
    if (!OWNER.matches(owner) || !NAME.matches(name) || name == "." || name == "..") return null
    // Only "owner/repo" or a link to a page of the repo: never a path we would not understand.
    if (parts.size > 2 && parts[2] !in setOf("tree", "blob", "")) return null
    return RepoRef(owner, name)
  }

  /** A branch, tag or commit typed by the user; null when blank, invalid when unsafe. */
  fun isValidRef(ref: String): Boolean =
    BRANCH.matches(ref) && ".." !in ref && !ref.startsWith("/") && !ref.endsWith("/")

  /**
   * Path inside the project of an entry of GitHub's zip archive. Every entry starts with one folder
   * ("owner-repo-sha/"), which is dropped. Null for that folder itself and for anything that could
   * leave the target folder or that a file system would not accept.
   */
  fun entryPath(entryName: String): String? {
    if ('\\' in entryName || '\u0000' in entryName) return null
    val segments = entryName.split('/').filter { it.isNotEmpty() }
    if (segments.size < 2) return null
    val inner = segments.drop(1)
    for (seg in inner) {
      if (seg == "." || seg == ".." || seg.length > 255) return null
      if (seg.any { it < ' ' || it in "<>:\"|?*" }) return null
    }
    return inner.joinToString("/")
  }

  /** Name of the project folder that receives the repository: "name", "name-2"... if taken. */
  fun folderName(repo: RepoRef, taken: Set<String>): String {
    val base = repo.name.trimStart('.').ifEmpty { "depot" }
    if (base !in taken) return base
    var i = 2
    while ("$base-$i" in taken) i++
    return "$base-$i"
  }

  /** True when the project [path] is inside one of the [imported] folders. */
  fun isInside(path: String, imported: Collection<String>): Boolean {
    val p = path.trim('/')
    return imported.any { folder -> p == folder || p.startsWith("$folder/") }
  }
}
