package dev.asenascale.ui

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
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import dev.asenascale.tailnet.HOST_APP_PORT
import androidx.compose.material3.TextButton
import dev.asenascale.ssh.Keys
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
import dev.asenascale.App
import dev.asenascale.data.AuthMode
import dev.asenascale.data.Host

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

    // Is the AsenaScale PC app running there? Then nothing else is needed.
    var hostApp by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(address, ts.running) {
        hostApp = null
        if (address.isBlank() || !ts.running) return@LaunchedEffect
        delay(400) // typing
        hostApp = withContext(Dispatchers.IO) { app.tailnet.isHostApp(app.tailnet.resolve(address)) }
    }
    val viaHostApp = hostApp == true
    val valid = address.isNotBlank() && (viaHostApp || user.isNotBlank()) && port.toIntOrNull() in 1..65535
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
            IconButton(onClick = onDone) { Icon(AsIcons.Back, "Geri") }
            Text(if (host == null) "Bilgisayar ekle" else "Düzenle", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (host != null) {
                IconButton(onClick = { app.sessions.disconnectHost(host.id); app.hosts.delete(host.id); onDone() }) {
                    Icon(AsIcons.Trash, "Sil", tint = Pal.red)
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
            if (viaHostApp) {
                HostAppFoundCard()
            } else {
                if (isWindows) {
                    WindowsSetupCard(
                        onCopyLink = { app.copyToClipboard(HOST_APP_DOWNLOAD) },
                        onCopyScript = { app.copyToClipboard(Keys.windowsSetupScript(context)) },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Field(
                        "Kullanıcı", user, { user = it.trim() }, Modifier.weight(1f), mono = true,
                        placeholder = if (isWindows) "Windows kullanıcı adın" else "",
                    )
                    Field(
                        "Port", port, { port = it.filter(Char::isDigit).take(5) }, Modifier.width(96.dp),
                        mono = true, keyboard = KeyboardType.Number,
                    )
                }

                Text("Giriş", fontSize = 13.sp, color = Pal.subtext)
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
                                activeContainerColor = Pal.mauve.copy(alpha = 0.16f),
                                activeContentColor = Pal.mauve,
                                inactiveContainerColor = MaterialTheme.colorScheme.background,
                                activeBorderColor = Pal.surface1,
                                inactiveBorderColor = Pal.surface1,
                            ),
                            icon = {},
                        ) { Text(label, fontSize = 13.sp) }
                    }
                }
                Text(
                    when (auth) {
                        AuthMode.KEY -> if (isWindows) "OpenSSH kurulum komutu bu telefonun anahtarını da ekler."
                        else "Uygulamanın anahtarını (ana ekrandaki anahtar simgesi) bilgisayardaki ~/.ssh/authorized_keys dosyasına ekle."
                        AuthMode.TAILSCALE -> "Bilgisayarda `sudo tailscale set --ssh` açıksa şifresiz bağlanır."
                        AuthMode.PASSWORD -> "Şifre bu telefonda uygulamanın özel alanında saklanır."
                    },
                    fontSize = 12.sp,
                    color = Pal.overlay0,
                )
                if (auth == AuthMode.PASSWORD) {
                    Field("Şifre", password, { password = it }, password = true)
                }
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
                color = Pal.overlay0,
            )
            Spacer(Modifier.height(8.dp))
        }

        Button(
            onClick = {
                app.hosts.save(
                    base.copy(
                        name = name.trim(),
                        address = address.trim().trimEnd('.'),
                        user = if (viaHostApp) user.ifBlank { "pc" } else user,
                        port = if (viaHostApp) HOST_APP_PORT else port.toIntOrNull() ?: 22,
                        auth = if (viaHostApp) AuthMode.KEY else auth,
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
            labelColor = Pal.subtext,
            selectedContainerColor = Pal.mauve.copy(alpha = 0.16f),
            selectedLabelColor = Pal.mauve,
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
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder, color = Pal.overlay0) },
        singleLine = true,
        textStyle = if (mono) MonoSmall.copy(fontSize = 15.sp) else MaterialTheme.typography.bodyLarge,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else keyboard,
            autoCorrectEnabled = false,
        ),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = Pal.surface0,
            focusedBorderColor = Pal.mauve,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = modifier,
    )
}

/** Where the PC app is downloaded from. */
const val HOST_APP_DOWNLOAD = "https://github.com/KaanAlper/Mobile-Claude/releases/latest"

@Composable
private fun HostAppFoundCard() {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Pal.green.copy(alpha = 0.10f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AsIcons.Check, null, tint = Pal.green, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("PC'de AsenaScale bulundu", fontWeight = FontWeight.Medium, color = Pal.green)
        }
        Text(
            "Kurulum gerekmiyor. İlk bağlantıda PC'de bir izin penceresi çıkar, \"Evet\"e basman yeterli.",
            fontSize = 13.sp,
            color = Pal.subtext,
        )
    }
}

@Composable
private fun WindowsSetupCard(onCopyLink: () -> Unit, onCopyScript: () -> Unit) {
    var copied by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Windows'a bağlanmak için", fontWeight = FontWeight.Medium)
        Text(
            "Önerilen: PC'ye AsenaScale'u kur. Bağlantıyı PC'de aç, " +
                "AsenaScale-windows-x64.exe dosyasını indirip çalıştır. Sistem tepsisine yerleşir; " +
                "komut ya da yönetici izni gerekmez. Kurunca bu ekran onu kendiliğinden bulur.",
            fontSize = 13.sp,
            color = Pal.subtext,
            lineHeight = 19.sp,
        )
        Button(
            onClick = { onCopyLink(); copied = "link" },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (copied == "link") "Bağlantı kopyalandı" else "İndirme bağlantısını kopyala") }
        TextButton(onClick = { onCopyScript(); copied = "script" }, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (copied == "script") "Komut kopyalandı" else "Alternatif: Windows OpenSSH kurulum komutu",
                fontSize = 13.sp,
                color = Pal.overlay0,
            )
        }
    }
}
