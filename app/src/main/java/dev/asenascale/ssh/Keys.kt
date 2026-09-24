package dev.asenascale.ssh

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

    /**
     * One paste into an *administrator* PowerShell on the Windows PC: installs
     * and starts OpenSSH Server, makes PowerShell the SSH shell, opens the
     * firewall, and authorizes this phone's key. ASCII only, and SIDs instead
     * of group names so it also works on non-English Windows.
     */
    fun windowsSetupScript(context: Context): String {
        val key = publicKey(context)
        return """
            |# AsenaScale: Windows setup (paste into PowerShell opened as administrator)
            |${'$'}ErrorActionPreference = 'Stop'
            |if (-not (Get-Service sshd -ErrorAction SilentlyContinue)) { Add-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0 | Out-Null }
            |Set-Service sshd -StartupType Automatic; Start-Service sshd
            |New-ItemProperty -Path HKLM:\SOFTWARE\OpenSSH -Name DefaultShell -Value "${'$'}env:WINDIR\System32\WindowsPowerShell\v1.0\powershell.exe" -PropertyType String -Force | Out-Null
            |if (-not (Get-NetFirewallRule -Name OpenSSH-Server-In-TCP -ErrorAction SilentlyContinue)) { New-NetFirewallRule -Name OpenSSH-Server-In-TCP -DisplayName 'OpenSSH Server (sshd)' -Enabled True -Direction Inbound -Protocol TCP -Action Allow -LocalPort 22 | Out-Null }
            |Set-NetFirewallRule -Name OpenSSH-Server-In-TCP -Enabled True -Profile Any -Action Allow
            |${'$'}key = '$key'
            |${'$'}admin = "${'$'}env:ProgramData\ssh\administrators_authorized_keys"
            |if (-not (Test-Path ${'$'}admin) -or -not (Select-String -Path ${'$'}admin -SimpleMatch ${'$'}key -Quiet)) { Add-Content -Path ${'$'}admin -Value ${'$'}key }
            |icacls ${'$'}admin /inheritance:r /grant '*S-1-5-32-544:F' /grant '*S-1-5-18:F' | Out-Null
            |New-Item -ItemType Directory -Force "${'$'}env:USERPROFILE\.ssh" | Out-Null
            |${'$'}user = "${'$'}env:USERPROFILE\.ssh\authorized_keys"
            |if (-not (Test-Path ${'$'}user) -or -not (Select-String -Path ${'$'}user -SimpleMatch ${'$'}key -Quiet)) { Add-Content -Path ${'$'}user -Value ${'$'}key }
            |${'$'}ok = (Get-Service sshd).Status -eq 'Running' -and (Test-NetConnection 127.0.0.1 -Port 22 -WarningAction SilentlyContinue).TcpTestSucceeded
            |if (${'$'}ok) { Write-Host "Ready. User name for the app: ${'$'}env:USERNAME" -ForegroundColor Green } else { Write-Host "The SSH server is not running: Get-Service sshd" -ForegroundColor Red }
        """.trimMargin()
    }

    @Synchronized
    private fun generate(context: Context) {
        val priv = File(context.filesDir, NAME)
        if (priv.exists()) return
        val kp = KeyPair.genKeyPair(JSch(), KeyPair.ECDSA, 256)
        val pubOut = ByteArrayOutputStream()
        kp.writePublicKey(pubOut, "asenascale")
        File(context.filesDir, "$NAME.pub").writeBytes(pubOut.toByteArray())
        val tmp = File(context.filesDir, "$NAME.tmp")
        tmp.outputStream().use { kp.writePrivateKey(it) }
        tmp.renameTo(priv)
        kp.dispose()
    }
}
