package dev.asenascale.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import dev.asenascale.ssh.SshConnection

/**
 * Sends files from the phone to the PC's Downloads/AsenaScale folder and
 * returns their path on the PC, ready to hand to Claude.
 */
object Uploads {
    private const val MAX_BYTES = 512L * 1024 * 1024

    fun send(context: Context, conn: SshConnection, uri: Uri): String {
        val name = displayName(context, uri)
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val size = runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: -1
            if (size > MAX_BYTES) error("Dosya çok büyük (en fazla 512 MB)")
            input.readBytes()
        } ?: error("Dosya okunamadı")

        return if (conn.isHostApp) {
            // Base64url keeps spaces and Turkish letters intact through the command line.
            val encoded = Base64.encodeToString(name.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            val r = conn.exec("mc put $encoded", bytes, timeoutMs = 10 * 60_000)
            r.stdout.toString(Charsets.UTF_8).trim().ifEmpty { error(r.stderr.trim().ifEmpty { "Gönderilemedi" }) }
        } else {
            conn.sftpPut(name, bytes)
        }
    }

    /** How to type [path] into a shell prompt or Claude's input. */
    fun quoted(path: String) = if (path.any { it.isWhitespace() || it in "'\"()&;" }) "\"$path\"" else path

    private fun displayName(context: Context, uri: Uri): String {
        val raw = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "dosya"
        val clean = raw.substringAfterLast('/').map { if (it in "\\/:*?\"<>|" || it.isISOControl()) '_' else it }
            .joinToString("").trim().trim('.')
        return clean.ifEmpty { "dosya" }
    }
}
