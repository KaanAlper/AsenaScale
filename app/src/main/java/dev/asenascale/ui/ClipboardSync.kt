package dev.asenascale.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import dev.asenascale.ssh.SshConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ClipboardSync(conn: SshConnection) {
    if (!conn.isHostApp) return
    val context = LocalContext.current
    LaunchedEffect(conn) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        var lastPcDataHash = 0
        var lastPcSetHash = 0

        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            val clip = cm.primaryClip ?: return@OnPrimaryClipChangedListener
            if (clip.itemCount == 0) return@OnPrimaryClipChangedListener
            val item = clip.getItemAt(0)
            
            val hash = item.text?.hashCode() ?: item.uri?.hashCode() ?: 0
            if (hash == lastPcSetHash) return@OnPrimaryClipChangedListener

            val text = item.text
            if (text != null) {
                launch(Dispatchers.IO) {
                    try {
                        conn.exec("mc clip write text", text.toString().toByteArray())
                    } catch (e: Exception) {}
                }
            } else {
                val uri = item.uri
                if (uri != null) {
                    launch(Dispatchers.IO) {
                        try {
                            val bytes = context.contentResolver.openInputStream(uri)?.readBytes()
                            if (bytes != null) {
                                conn.exec("mc clip write image", bytes)
                            }
                        } catch (e: Exception) {}
                    }
                }
            }
        }
        cm.addPrimaryClipChangedListener(listener)

        try {
            withContext(Dispatchers.IO) {
                while (isActive) {
                    try {
                        val res = conn.exec("mc clip read", null, 5000)
                        if (res.exitCode == 0 && res.stdout.isNotEmpty()) {
                            val out = res.stdout
                            val hash = out.contentHashCode()
                            if (hash != lastPcDataHash) {
                                lastPcDataHash = hash
                                val headerEnd = out.indexOf('\n'.code.toByte())
                                if (headerEnd != -1) {
                                    val type = String(out, 0, headerEnd)
                                    val data = out.copyOfRange(headerEnd + 1, out.size)
                                    
                                    withContext(Dispatchers.Main) {
                                        if (type == "text") {
                                            val text = String(data)
                                            lastPcSetHash = text.hashCode()
                                            cm.setPrimaryClip(ClipData.newPlainText("PC", text))
                                        } else if (type == "image") {
                                            val dir = File(context.cacheDir, "shots")
                                            dir.mkdirs()
                                            val file = File(dir, "clip.png")
                                            file.writeBytes(data)
                                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                                            lastPcSetHash = uri.hashCode()
                                            cm.setPrimaryClip(ClipData.newUri(context.contentResolver, "PC Image", uri))
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {}
                    delay(2000)
                }
            }
        } finally {
            cm.removePrimaryClipChangedListener(listener)
        }
    }
}
