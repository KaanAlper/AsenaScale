package dev.mobileclaude.shot

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import androidx.core.content.FileProvider
import dev.mobileclaude.ssh.SshConnection
import java.io.File

data class ShotTarget(val kind: String, val id: String, val label: String)

class ShotList(val desktop: String, val targets: List<ShotTarget>)

/**
 * Screenshots of the PC, taken over the already-open SSH session. Nothing
 * has to be installed on the PC: a helper script is piped to the shell.
 *  - Linux/macOS: assets/mcshot.sh via `sh -s`
 *  - Windows: assets/mcshot.ps1 via Windows PowerShell
 */
object Screenshots {
    private val scripts = HashMap<String, ByteArray>()

    private fun script(context: Context, name: String) =
        scripts.getOrPut(name) { context.assets.open(name).use { it.readBytes() } }

    /** Reads the whole script from stdin; works whether sshd's shell is cmd or PowerShell. */
    private const val WINDOWS_CMD =
        "powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command \"iex ([Console]::In.ReadToEnd())\""

    private fun checkId(s: String): String {
        require(Regex("[A-Za-z0-9_.:,x-]*").matches(s)) { "geçersiz hedef: $s" }
        return s
    }

    /** Single-quoted: safe in sh, bash and fish. */
    private fun q(s: String) = "'${checkId(s)}'"

    private fun windowsScript(context: Context, mode: String, kind: String, target: String): ByteArray {
        val header = "\$Mode='$mode';\$Kind='${checkId(kind)}';\$Target='${checkId(target)}'\r\n"
        return header.toByteArray() + script(context, "mcshot.ps1")
    }

    /** "windows" or "posix"; asked once per connection. */
    private fun os(conn: SshConnection): String = conn.remoteOs ?: run {
        val r = runCatching { conn.exec("uname -s", timeoutMs = 10_000) }.getOrNull()
        val name = r?.stdout?.toString(Charsets.UTF_8)?.trim().orEmpty()
        val posix = r != null && r.exitCode == 0 && name.isNotEmpty() &&
            listOf("MINGW", "MSYS", "CYGWIN").none { name.uppercase().startsWith(it) }
        (if (posix) "posix" else "windows").also { conn.remoteOs = it }
    }

    fun list(context: Context, conn: SshConnection): ShotList {
        val windows = os(conn) == "windows"
        val r = if (windows) {
            conn.exec(WINDOWS_CMD, windowsScript(context, "list", "", ""), timeoutMs = 45_000)
        } else {
            conn.exec("sh -s list", script(context, "mcshot.sh"))
        }
        if (r.stdout.isEmpty()) error(friendly(r.stderr, "Liste alınamadı"))
        var desktop = ""
        val lines = r.stdout.toString(Charsets.UTF_8).lines().flatMap { line ->
            // Windows sends the window list base64-encoded to survive the console code page.
            if (line.startsWith("b64\t")) {
                String(Base64.decode(line.substringAfter('\t').trim(), Base64.DEFAULT), Charsets.UTF_8).lines()
            } else listOf(line)
        }
        val targets = lines.mapNotNull { line ->
            val parts = line.trimEnd('\r').split('\t', limit = 3)
            if (parts.size < 3) return@mapNotNull null
            when (parts[0]) {
                "de" -> { desktop = parts[1]; null }
                "screen" -> ShotTarget("screen", parts[1], "Tüm ekran")
                "active" -> ShotTarget("active", parts[1], "Aktif pencere")
                else -> ShotTarget(parts[0], parts[1], parts[2])
            }
        }
        return ShotList(desktop, targets)
    }

    /** Captures [target] and returns the PNG, cached in the app's cache dir. */
    fun take(context: Context, conn: SshConnection, target: ShotTarget): File {
        val png = if (os(conn) == "windows") {
            val r = conn.exec(WINDOWS_CMD, windowsScript(context, "shot", target.kind, target.id), timeoutMs = 60_000)
            val text = r.stdout.toString(Charsets.US_ASCII).trim()
            runCatching { Base64.decode(text, Base64.DEFAULT) }.getOrNull()
                ?.takeIf { isPng(it) } ?: error(friendly(r.stderr, "Ekran görüntüsü alınamadı"))
        } else {
            val r = conn.exec("sh -s shot ${q(target.kind)} ${q(target.id)}", script(context, "mcshot.sh"), timeoutMs = 45_000)
            r.stdout.takeIf { isPng(it) } ?: error(friendly(r.stderr, "Ekran görüntüsü alınamadı"))
        }
        val dir = File(context.cacheDir, "shots").apply { mkdirs() }
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(10)?.forEach { it.delete() }
        return File(dir, "pc-${System.currentTimeMillis()}.png").apply { writeBytes(png) }
    }

    private fun isPng(b: ByteArray) =
        b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte()

    /** Turns the helper scripts' error output into something readable. */
    private fun friendly(stderr: String, fallback: String): String {
        val m = stderr.trim()
        return when {
            m.isEmpty() -> fallback
            "E_NOSESSION" in m -> "Windows'ta oturum açık değil. Ekran görüntüsü için PC'de oturumun açık olmalı."
            "E_NOWINDOW" in m -> "Pencere bulunamadı (kapanmış olabilir). Listeyi yenile."
            "handle is invalid" in m.lowercase() || "tanıtıcı geçersiz" in m.lowercase() ->
                "PC'nin ekranı kilitli görünüyor. Kilidi açıkken dene."
            "is not recognized" in m || "tanınmıyor" in m -> "PC'de PowerShell bulunamadı."
            else -> m.lines().first { it.isNotBlank() }.take(300)
        }
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
