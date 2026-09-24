package dev.asenascale.files

import android.util.Base64
import dev.asenascale.ssh.SshConnection

/** A file or folder on the PC. For the starting places, [name] is the full path. */
data class PcEntry(val name: String, val path: String, val dir: Boolean, val size: Long, val mtime: Long)

/** A folder's contents; [path] is empty for the starting places (home, drives...). */
data class PcListing(val path: String, val entries: List<PcEntry>)

/**
 * Browsing the PC through the AsenaScale PC app (`mc ls`). Paths are the
 * PC's own (C:\Users\... or /home/...) and travel base64url-encoded.
 */
object PcFiles {
    fun b64(s: String): String =
        Base64.encodeToString(s.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    fun list(conn: SshConnection, path: String): PcListing {
        val r = conn.exec("mc ls ${if (path.isEmpty()) "-" else b64(path)}", timeoutMs = 20_000)
        if (r.exitCode != 0) error(r.stderr.trim().ifEmpty { "ls failed" })
        val lines = r.stdout.toString(Charsets.UTF_8).lines().filter { it.isNotEmpty() }
        val dir = lines.firstOrNull()?.takeIf { it.startsWith("path\t") }?.removePrefix("path\t").orEmpty()
        val entries = lines.drop(1).mapNotNull { line ->
            val f = line.split('\t', limit = 4)
            if (f.size < 4) return@mapNotNull null
            val full = if (dir.isEmpty()) f[3] else child(dir, f[3])
            PcEntry(f[3], full, f[0] == "d", f[1].toLongOrNull() ?: 0, f[2].toLongOrNull() ?: 0)
        }
        return PcListing(dir, entries)
    }

    private fun sep(path: String) = if ('\\' in path || Regex("^[A-Za-z]:").containsMatchIn(path)) '\\' else '/'

    fun child(dir: String, name: String): String {
        val s = sep(dir)
        return if (dir.endsWith(s)) dir + name else "$dir$s$name"
    }

    /** The folder above [path]; empty (the starting places) above a drive or "/". */
    fun parent(path: String): String {
        val s = sep(path)
        val trimmed = if (path.length > 1 && path.endsWith(s) && !isRoot(path)) path.dropLast(1) else path
        if (isRoot(trimmed)) return ""
        val i = trimmed.lastIndexOf(s)
        return when {
            i < 0 -> ""
            i == 0 -> "/"
            s == '\\' && i == 2 && trimmed[1] == ':' -> trimmed.substring(0, 3)
            else -> trimmed.substring(0, i)
        }
    }

    private fun isRoot(path: String) = path == "/" || Regex("^[A-Za-z]:\\\\?$").matches(path)

    /** Last path segment, or the whole thing for a drive / "/". */
    fun name(path: String): String {
        if (isRoot(path)) return path
        val s = sep(path)
        return path.trimEnd(s).substringAfterLast(s)
    }
}
