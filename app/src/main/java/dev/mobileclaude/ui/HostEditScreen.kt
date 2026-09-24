package dev.mobileclaude.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.mobileclaude.ssh.Keys
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mobileclaude.App
import dev.mobileclaude.data.AuthMode
import dev.mobileclaude.data.Host

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostEditScreen(host: Host?, suggestedAddress: String?, onDone: () -> Unit) {
    val app = App.instance
    val ts by app.tailnet.state.collectAsState()
    val base = host ?: Host(address = suggestedAddress.orEmpty(), name = suggestedAddress.orEmpty())

    var name by remember { mutableStateOf(base.name) }
    var address by remember { mutableStateOf(base.address) }
    var user by remember { mutableStateOf(base.user) }
    var port by remember { mutableStateOf(base.port.toString()) }
    var auth by remember { mutableStateOf(base.auth) }
    var password by remember { mutableStateOf(base.password) }
    var startup by remember { mutableStateOf(base.startup) }

    val valid = address.isNotBlank() && user.isNotBlank() && port.toIntOrNull() in 1..65535
    // Tailscale SSH only exists as a server on Linux/macOS; Windows uses OpenSSH Server.
    val isWindows = ts.peers.isNotEmpty() && app.tailnet.peerFor(address)?.isWindows == true
    LaunchedEffect(isWindows) { if (isWindows && auth == AuthMode.TAILSCALE) auth = AuthMode.KEY }
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Geri") }
            Text(if (host == null) "Bilgisayar ekle" else "Düzenle", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (host != null) {
                IconButton(onClick = { app.sessions.disconnect(host.id); app.hosts.delete(host.id); onDone() }) {
                    Icon(Icons.Outlined.Delete, "Sil", tint = Mocha.red)
                }
            }
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Field("Ad", name, { name = it }, placeholder = "Masaüstü")
            Field("Adres", address, { address = it }, placeholder = "bilgisayar-adi veya 100.x.y.z", mono = true)
            val suggestions = ts.peers.filter { it.online }.map { it.shortName }.filter { it != address }
            if (suggestions.isNotEmpty() && host == null) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestions.forEach { s ->
                        Chip(s, selected = false) {
                            address = s
                            if (name.isBlank()) name = s
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("Kullanıcı", user, { user = it.trim() }, Modifier.weight(1f), mono = true,
                    placeholder = if (isWindows) "Windows kullanıcı adın" else "")
                Field("Port", port, { port = it.filter(Char::isDigit).take(5) }, Modifier.width(96.dp), mono = true, keyboard = KeyboardType.Number)
            }

            Text("Giriş", fontSize = 13.sp, color = Mocha.subtext)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val options = listOfNotNull(
                    AuthMode.KEY to "Anahtar",
                    (AuthMode.TAILSCALE to "Tailscale SSH").takeUnless { isWindows },
                    AuthMode.PASSWORD to "Şifre",
                )
                options.forEachIndexed { i, (mode, label) ->
                    SegmentedButton(
                        selected = auth == mode,
                        onClick = { auth = mode },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = Mocha.mauve.copy(alpha = 0.16f),
                            activeContentColor = Mocha.mauve,
                            inactiveContainerColor = MaterialTheme.colorScheme.background,
                            activeBorderColor = Mocha.surface1,
                            inactiveBorderColor = Mocha.surface1,
                        ),
                        icon = {},
                    ) { Text(label, fontSize = 13.sp) }
                }
            }
            if (isWindows) {
                WindowsSetupCard { app.copyToClipboard(Keys.windowsSetupScript(context)) }
            } else {
                Text(
                    when (auth) {
                        AuthMode.KEY -> "Uygulamanın anahtarını (ana ekrandaki 🔑) bilgisayardaki ~/.ssh/authorized_keys dosyasına ekle."
                        AuthMode.TAILSCALE -> "Bilgisayarda `sudo tailscale set --ssh` açıksa şifresiz bağlanır."
                        AuthMode.PASSWORD -> "Şifre bu telefonda uygulamanın özel alanında saklanır."
                    },
                    fontSize = 12.sp,
                    color = Mocha.overlay0,
                )
            }
            if (auth == AuthMode.PASSWORD) {
                Field("Şifre", password, { password = it }, password = true)
            }

            Field("Bağlanınca çalıştır", startup, { startup = it }, placeholder = "boş = sadece kabuk", mono = true)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val presets = if (isWindows) listOf("claude", "claude --continue")
                else listOf("claude", "claude --continue", "tmux new -As claude claude")
                presets.forEach { cmd ->
                    Chip(cmd, selected = startup == cmd) { startup = if (startup == cmd) "" else cmd }
                }
            }
            Text(
                if (isWindows) "İpucu: bağlantı koparsa `claude --continue` ile son sohbete kaldığın yerden dönersin."
                else "İpucu: tmux ile bağlantı koparsa Claude oturumu PC'de yaşamaya devam eder, tekrar bağlanınca kaldığın yerden sürer.",
                fontSize = 12.sp,
                color = Mocha.overlay0,
            )
            Spacer(Modifier.height(8.dp))
        }

        Button(
            onClick = {
                app.hosts.save(
                    base.copy(
                        name = name.trim(),
                        address = address.trim().trimEnd('.'),
                        user = user,
                        port = port.toIntOrNull() ?: 22,
                        auth = auth,
                        password = if (auth == AuthMode.PASSWORD) password else "",
                        startup = startup.trim(),
                    ),
                )
                onDone()
            },
            enabled = valid,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().padding(20.dp).height(50.dp),
        ) { Text("Kaydet") }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MonoSmall) },
        shape = RoundedCornerShape(10.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            labelColor = Mocha.subtext,
            selectedContainerColor = Mocha.mauve.copy(alpha = 0.16f),
            selectedLabelColor = Mocha.mauve,
        ),
        border = null,
    )
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    placeholder: String = "",
    mono: Boolean = false,
    password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder, color = Mocha.overlay0) },
        singleLine = true,
        textStyle = if (mono) MonoSmall.copy(fontSize = 15.sp) else MaterialTheme.typography.bodyLarge,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else keyboard,
            autoCorrectEnabled = false,
        ),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = Mocha.surface0,
            focusedBorderColor = Mocha.mauve,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = modifier,
    )
}

@Composable
private fun WindowsSetupCard(onCopy: () -> Unit) {
    var copied by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Windows kurulumu (bir kez)", fontWeight = FontWeight.Medium)
        Text(
            "1. Aşağıdaki komutu kopyala ve bilgisayarına gönder.\n" +
                "2. PC'de Başlat → \"PowerShell\" → Yönetici olarak çalıştır → yapıştır.\n" +
                "3. En sonda yazan kullanıcı adını buraya gir.",
            fontSize = 13.sp,
            color = Mocha.subtext,
            lineHeight = 19.sp,
        )
        Text(
            "OpenSSH Sunucusunu kurar, PowerShell'i kabuk yapar ve bu telefonun anahtarını yetkilendirir. " +
                "(Tailscale SSH, Windows'ta sunucu olarak çalışmıyor.)",
            fontSize = 12.sp,
            color = Mocha.overlay0,
        )
        Button(
            onClick = { onCopy(); copied = true },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (copied) "Kopyalandı ✓" else "Kurulum komutunu kopyala") }
    }
}
