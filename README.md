# AsenaScale

Telefondan PC'deki Claude Code'u rahatça kullanmak için native Android uygulaması.
Uzak masaüstü değil: **Tailscale uygulamanın içine gömülü**, PC'ye doğrudan SSH ile
bağlanıyor, terminali telefonda native olarak çiziyor. Yazdığın her tuş anında gider.

- **Tailscale gömülü** (Go `tsnet`): ayrı bir Tailscale uygulaması ya da VPN izni gerekmez.
  Giriş, aç/kapat, cihazlar ve çevrimiçi durumları.
- **Hızlı terminal**: Termux'un emülatör çekirdeği + Canvas'a doğrudan çizim,
  Catppuccin Mocha renkleri, JetBrains Mono, kıstırarak yakınlaştırma, xterm-256color / truecolor.
- **Klavye üstü tuş çubuğu**: `esc` `tab` `ctrl` `alt` `⇧tab` oklar (basılı tutunca tekrarlar)
  ve `/ \ | - _ ~ * = + " ' $ & ; : { } [ ] ( ) < > # ! ? % ^ @` gibi semboller.
- **📸 PC ekran görüntüsü**: tüm ekran, tek monitör, aktif pencere ya da listeden
  seçtiğin bir pencere. Yakınlaştır, galeriye kaydet, paylaş.
- **Arka planda bağlı kalır**: açık terminal varken küçük bir bildirimle oturum yaşar.

## Kurulum

### Telefon (Android)
[Releases](../../releases) → en yeni sürüm → **`AsenaScale-Mobile-arm64-v8a.apk`** (çoğu telefon),
emin değilsen `AsenaScale-Mobile-universal.apk`. Tailscale anahtarını aç → **Giriş yap**.

### PC — Windows
**`AsenaScale-Setup.exe`**'yi indirip çalıştır. Yönetici izni istemez, Windows'un diline göre
Türkçe/İngilizce dahil 31 dilde açılır, oturum açınca başlar. (Kurulum istemezsen:
`AsenaScale-windows-x64-portable.exe`.)

### PC — Linux (Debian, Ubuntu, Arch, Fedora, openSUSE)
Tek satır; gerekli kütüphaneleri kurar, menüye ikonuyla ekler, oturum açınca başlatır:

```sh
curl -fsSL https://raw.githubusercontent.com/KaanAlper/AsenaScale/main/install.sh | sh
```

Kaldırmak için: `curl -fsSL https://raw.githubusercontent.com/KaanAlper/AsenaScale/main/install.sh | sh -s -- --uninstall`
(ya da elle: `AsenaScale-x86_64.AppImage`).

### Komut satırı (Windows ve Linux)
Kurulumdan sonra terminalde `asenascale` komutu var (Windows'ta cmd/PowerShell, Linux'ta
`~/.local/bin`). Çalışan tepsi uygulamasıyla konuşur:

```text
asenascale status            Tailscale durumu, bu PC'nin adı ve IP'leri, oturumlar, telefonlar
asenascale devices           tailnet cihazları: ad, IPv4, OS, çevrimiçi/dışı, direkt/relay, IPv6
asenascale ip [-4|-6]        bu PC'nin Tailscale IP'si
asenascale connect           tailnet'e bağlan (gerekirse giriş linki verir)
asenascale disconnect        tailnet'ten çık (telefon connect'e kadar ulaşamaz)
asenascale ping CİHAZ        Tailscale üzerinden gecikme
asenascale claude             bu klasörde Claude'u ortak oturumda aç: telefon canlı katılır
asenascale new [KOMUT]        aynısı, herhangi bir komutla (boş: kabuk)
asenascale attach [ID]        çalışan oturuma katıl (telefonun başlattığına da); Ctrl+] ayrılır
asenascale sessions | kill ID|all
asenascale phones | revoke N|AD|all
asenascale log [-f] [-n N]   günlük (-f: canlı izle; telefon bağlandı/ayrıldı dahil)
asenascale start | quit | login | logout | version
```
`status`, `devices` ve `sessions` için `--json` eklenebilir.

**PC'de açık Claude'a telefondan bağlanmak:** Windows başka bir programın sıradan bir terminal
penceresine girmesine izin vermediği için Claude'u PC'de `asenascale claude` ile aç (normal
`claude` gibi, o klasörde çalışır). Telefonda PC'ye dokununca başlatıcıda *PC'de çalışanlar*
altında görünür; dokun, aynı terminale katıl. İkisi aynı anda yazabilir, ikisi de aynı ekranı
görür. Tersi: telefonda başlattığın oturuma PC'den `asenascale attach` ile gir.

### İlk bağlantı
PC uygulaması ilk açılışta tarayıcıda Tailscale girişini açar (telefondakiyle **aynı hesap**).
PC, telefonun ana ekranında kendiliğinden belirir. Dokun, bir araç seç (Claude Code, Codex,
Gemini, Grok, kabuk), ilk seferde PC'de çıkan izin penceresinde *Evet*'e bas. Bu kadar.

### Dil
Telefon uygulaması ve PC uygulaması sistem dilini izler; o dil yoksa İngilizce açılır.
Desteklenen diller: Türkçe, English, Deutsch, Français, Español, Italiano, Português,
Nederlands, Polski, Русский, Українська, العربية, فارسی, हिन्दी, Bahasa Indonesia,
日本語, 한국어, 简体中文, 繁體中文, Tiếng Việt, ไทย. Android 13+ üzerinde uygulamaya özel
dil *Ayarlar → Uygulamalar → AsenaScale Mobile → Dil*'den seçilebilir.
Çeviriler `i18n/` klasöründe JSON olarak durur; düzenledikten sonra `python3 i18n/gen.py`.

## Kullanım ipuçları

- **Terminal hareketleri:** dokun → klavye, kaydır → geçmiş (fırlatınca akar),
  iki parmak → yazı boyutu (hatırlanır), uzun bas → metin seç / linke dokun.
- **Claude kısayolları:** `esc` (durdur), `⇧tab` (mod değiştir), `ctrl` sonra `c` (iptal),
  oklar basılı tutunca tekrarlar. Çok satırlı yapıştırma Claude'a tek parça gider.
- **Ağ değişimi:** Wi-Fi ↔ mobil veri geçişinde SSH kopmaz; tünel yeni ağa kendiliğinden geçer.
- **Arka plan:** terminal açıkken bildirimdeki "terminal açık" oturumu canlı tutar.
  Terminalden çıkınca oturum kapanmaz; ana ekranda "açık" etiketiyle görünür.
- **Dosya ekleme (ataş):** *Telefondan* (fotoğraf/video, dosya) PC'ye gönderir, ya da
  *PC'den* dosya seçtirir; iki durumda da dosyanın PC'deki yolu terminale yazılır, Claude'a
  "şu resme bak" demek için hazır.
- **Dosyalar (⋮ → Dosyalar):** PC'nin dosyalarında gez; dosyayı telefona indir, yolunu
  terminale yaz ya da kopyala, açık klasöre telefondan yükle. Aktarımlar IDM gibi parçalı ve
  paralel (2 MB parçalar, 4 işçi, 2 bağlantı), kesilirse parça yeniden denenir; hız ve ilerleme
  görünür. İnenler telefonda *İndirilenler/AsenaScale*'e düşer.
- **Ekran paylaşımı (ekran simgesi):** PC ekranını canlı gör ve kullan. Dokun = tıkla, çift
  dokun = çift tık, basılı tut = sağ tık, sürükle = kaydır, iki parmak = yakınlaştır. Klavye
  düğmesi telefon klavyesini ve esc/tab/oklar/ctrl+c… satırını açar; birden çok monitörde
  1/2 ile seç. Sadece değişen bölgeler gider: sabit ekran veri harcamaz, *Tasarruf*/*Net*
  arasında geçilebilir.

## Sorun giderme

| Belirti | Çözüm |
|---|---|
| "Kimlik doğrulama başarısız" | 🔑 anahtarını PC'de `~/.ssh/authorized_keys`'e ekle ya da Tailscale SSH seç |
| "Bağlantı reddedildi" | PC'de SSH sunucusu çalışmıyor. Windows: kurulum komutunu çalıştır ya da `Start-Service sshd`; Linux: `sudo systemctl enable --now sshd` |
| Windows'ta ekran görüntüsü "oturum açık değil" | PC'de kullanıcı oturumu açık olmalı (kilit ekranında görüntü alınamaz) |
| "Zaman aşımı" | PC uykuda ya da Tailscale'i kapalı; `tailscale status` ile kontrol et |
| Ekran görüntüsünde araç hatası | Aşağıdaki tablodaki aracı kur (ör. Hyprland için `grim`) |

## Ekran görüntüsü

PC'ye bir şey kurmak gerekmez; uygulama açık SSH oturumu üzerinden küçük bir betik çalıştırır
(Windows: `mcshot.ps1`, Linux: `mcshot.sh`). Windows'ta SSH oturumu masaüstünü göremediği için
görüntü, senin oturumunda çalışan geçici bir zamanlanmış görevle alınır (ekranda pencere açılmaz).

| Masaüstü | Gerekli araç | Pencere seçme |
|---|---|---|
| **Windows 10/11** | hiçbiri (PowerShell ile) | ✓ (arkada kalan pencereler dahil) |
| Hyprland | `grim` | ✓ |
| Sway | `grim`, `jq` | ✓ |
| Diğer wlroots | `grim` | sadece tüm ekran |
| KDE Plasma | `spectacle` | aktif pencere |
| GNOME | `gnome-screenshot` | aktif pencere |
| X11 | `import` (ImageMagick), `maim` veya `scrot`; liste için `wmctrl`/`xdotool` | ✓ |

## Geliştirme

```
tsbridge/        Go: gömülü Tailscale (tsnet) + 127.0.0.1 → tailnet TCP yönlendirme
host/            Rust: AsenaScale PC tepsi uygulaması (SSH sunucusu, izin, ekran görüntüsü)
terminal/        Termux terminal emülatörü (Apache-2.0, bkz. terminal/NOTICE)
app/             Kotlin + Jetpack Compose arayüzü, SSH (JSch), terminal görünümü
```

Yerelde derlemek için Android SDK + NDK ve Go gerekir:

```sh
./scripts/build-tsbridge.sh   # app/libs/tsbridge.aar
./gradlew assembleRelease
```

Cihazsız test araçları:

```sh
cd tsbridge && go run ./cmd/tstry      # gömülü Tailscale: başlat, durum, yönlendirme, durdur, yeniden başlat
sh app/src/main/assets/mcshot.sh list  # PC'de: ekran görüntüsü hedeflerini listele
```

Her push'ta GitHub Actions universal + ABI'ye özel APK'ları (arm64-v8a, armeabi-v7a,
x86_64, x86) derler ve `nightly` sürümüne yükler. Yeni sürüm çıkarmak için: Actions → **Build** →
**Run workflow** → `release` alanına `0.2.0` yaz (ya da `v0.2.0` etiketi push et). APK repodaki
sabit `app/debug.keystore` ile imzalanır, böylece yeni sürüm eskisinin üzerine kurulur.
