package dev.asenascale

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import dev.asenascale.data.HostStore
import dev.asenascale.data.SessionIds
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

    override fun onCreate() {
        super.onCreate()
        instance = this
        tailnet = Tailnet(this)
        hosts = HostStore(this)
        sessionIds = SessionIds(this)
        sessions = Sessions(this)
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
