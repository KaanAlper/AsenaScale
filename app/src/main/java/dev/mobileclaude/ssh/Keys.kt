package dev.mobileclaude.ssh

import android.content.Context
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream
import java.io.File

/** The app's own SSH key (ECDSA P-256, generated on first use). */
object Keys {
    private const val NAME = "id_ecdsa"

    fun privateKeyFile(context: Context): File {
        val f = File(context.filesDir, NAME)
        if (!f.exists()) generate(context)
        return f
    }

    fun publicKey(context: Context): String {
        privateKeyFile(context)
        return File(context.filesDir, "$NAME.pub").readText().trim()
    }

    @Synchronized
    private fun generate(context: Context) {
        val priv = File(context.filesDir, NAME)
        if (priv.exists()) return
        val kp = KeyPair.genKeyPair(JSch(), KeyPair.ECDSA, 256)
        val pubOut = ByteArrayOutputStream()
        kp.writePublicKey(pubOut, "mobile-claude")
        File(context.filesDir, "$NAME.pub").writeBytes(pubOut.toByteArray())
        val tmp = File(context.filesDir, "$NAME.tmp")
        tmp.outputStream().use { kp.writePrivateKey(it) }
        tmp.renameTo(priv)
        kp.dispose()
    }
}
