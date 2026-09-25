package dev.asenascale

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import dev.asenascale.data.HostStore
import dev.asenascale.data.SessionIds
import dev.asenascale.files.Transfers
import dev.asenascale.ssh.Sessions
import dev.asenascale.tailnet.Tailnet

class App : Application() {
    lateinit var tailnet: Tailnet
        private set
    lateinit var hosts: HostStore
        private set
    lateinit var sessions: Sessions
        private set
    lateinit var sessionIds: SessionIds
        private set
    lateinit var transfers: Transfers
        private set
    /** Labels of PC sessions joined from the launcher, by tool id. */
    val joinedNames = HashMap<String, String>()
    /** Last folder open in the PC file browser, per PC. */
    val lastPcFolder = HashMap<String, String>()

    override fun onCreate() {
        super.onCreate()
        instance = this
        tailnet = Tailnet(this)
        hosts = HostStore(this)
        sessionIds = SessionIds(this)
        sessions = Sessions(this)
        transfers = Transfers(this)
        tailnet.startIfEnabled()
    }

    fun copyToClipboard(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    fun clipboardText(): String? =
        getSystemService(ClipboardManager::class.java).primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()

    companion object {
        lateinit var instance: App
            private set
    }
}
