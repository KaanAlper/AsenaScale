package dev.mobileclaude.shot

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import dev.mobileclaude.ssh.SshConnection
import java.io.File

data class ShotTarget(val kind: String, val id: String, val label: String)

class ShotList(val desktop: String, val targets: List<ShotTarget>)

/**
 * Screenshots of the PC, taken over the already-open SSH session. A small
 * shell script (assets/mcshot.sh) is piped to `sh -s`, so nothing has to be
 * installed on the PC beyond the desktop's usual screenshot tool.
 */
object Screenshots {
    private var script: ByteArray? = null

    private fun script(context: Context) =
        script ?: context.assets.open("mcshot.sh").use { it.readBytes() }.also { script = it }

    /** Single-quoted, safe in sh, bash and fish. Ids are simple tokens anyway. */
    private fun q(s: String): String {
        require(Regex("[A-Za-z0-9_.:,x-]+").matches(s)) { "geçersiz hedef: $s" }
        return "'$s'"
    }

    fun list(context: Context, conn: SshConnection): ShotList {
        val r = conn.exec("sh -s list", script(context))
        if (r.exitCode != 0 && r.stdout.isEmpty()) error(r.stderr.trim().ifEmpty { "Liste alınamadı" })
        var desktop = ""
        val targets = r.stdout.toString(Charsets.UTF_8).lineSequence().mapNotNull { line ->
            val parts = line.split('\t', limit = 3)
            if (parts.size < 3) return@mapNotNull null
            if (parts[0] == "de") {
                desktop = parts[1]; null
            } else ShotTarget(parts[0], parts[1], parts[2])
        }.toList()
        return ShotList(desktop, targets)
    }

    /** Captures [target] and returns the PNG, cached in the app's cache dir. */
    fun take(context: Context, conn: SshConnection, target: ShotTarget): File {
        val r = conn.exec("sh -s shot ${q(target.kind)} ${q(target.id)}", script(context), timeoutMs = 45_000)
        val png = r.stdout
        val isPng = png.size > 8 && png[1] == 'P'.code.toByte() && png[2] == 'N'.code.toByte()
        if (!isPng) error(r.stderr.trim().ifEmpty { "Ekran görüntüsü alınamadı" })
        val dir = File(context.cacheDir, "shots").apply { mkdirs() }
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(10)?.forEach { it.delete() }
        return File(dir, "pc-${System.currentTimeMillis()}.png").apply { writeBytes(png) }
    }

    fun saveToGallery(context: Context, file: File) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Mobile Claude")
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Kaydedilemedi")
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
