# Mobile Claude

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

1. APK: [Releases](../../releases) → en yeni sürüm → **`MobileClaude-arm64-v8a.apk`**
   (çoğu telefon) ya da emin değilsen `MobileClaude-universal.apk`.
   Her push'ta güncellenen deneme sürümü: [nightly](../../releases/tag/nightly).
2. Uygulamada Tailscale anahtarını aç → **Giriş yap** → tarayıcıda onayla.
   Telefon tailnet'inde `mobile-claude-<model>` adıyla görünür.
3. **Cihazlar** listesinde PC'ne dokun.
4. PC'ye erişim:
   - **Önerilen — Mobile Claude Host (Windows/Linux):** Releases'tan `MobileClaudeHost-windows-x64.exe`'yi
     indirip çalıştır. Sistem tepsisine yerleşir, oturum açınca kendiliğinden başlar. Kurulum komutu
     ya da yönetici izni gerekmez. Telefon onu kendisi bulur; ilk bağlantıda PC'de çıkan
     **"bağlanmak istiyor — izin veriyor musun?"** penceresinde *Evet*'e basman yeterli.
     (Windows ilk açılışta güvenlik duvarı izni sorarsa ikisine de izin ver.)
   - **Alternatif — Windows OpenSSH:** 🔑 → "Windows kurulum komutunu kopyala" → Yönetici PowerShell.
   - **Linux/macOS SSH:** 🔑 anahtarını `~/.ssh/authorized_keys`'e ekle ya da `sudo tailscale set --ssh`.
5. "Bağlanınca çalıştır" için `claude` seç (Linux'ta `tmux new -As claude claude` de olur).

## Mobile Claude Host (PC uygulaması)

Rust ile yazılmış küçük (~4 MB) bir tepsi uygulaması (`host/`):

- Kendi SSH sunucusu var (port 2222) ve **sadece Tailscale ağından** (100.x) bağlantı kabul ediyor.
- Tanımadığı bir telefon bağlanınca PC'de onay penceresi açıyor. Pencerede telefonun Tailscale
  cihaz adı görünüyor. Onaylanan telefonlar hatırlanıyor; tepsi menüsünden sıfırlanabiliyor.
- Terminal gerçek bir ConPTY (Windows'ta PowerShell 7 varsa o, yoksa Windows PowerShell).
- Senin oturumunda çalıştığı için ekran/pencere görüntüsünü doğrudan alıyor (hızlı, arkadaki pencereler dahil).
- Telefondan gönderilen dosyalar `İndirilenler\Mobile Claude\` klasörüne kaydediliyor.

## Dosya ve fotoğraf gönderme

Terminalde **📎** → *Fotoğraf / video* ya da *Dosya*. Dosya PC'de `İndirilenler/Mobile Claude/`
klasörüne gider ve **PC'deki yolu terminale yazılır**; Claude'a "bu resme bak" demen yeterli.
PC uygulamasıyla ya da düz SSH sunucusuyla (SFTP) çalışır.

## Kullanım ipuçları

- **Terminal hareketleri:** dokun → klavye, kaydır → geçmiş (fırlatınca akar),
  iki parmak → yazı boyutu (hatırlanır), uzun bas → metin seç / linke dokun.
- **Claude kısayolları:** `esc` (durdur), `⇧tab` (mod değiştir), `ctrl` sonra `c` (iptal),
  oklar basılı tutunca tekrarlar. Çok satırlı yapıştırma Claude'a tek parça gider.
- **Ağ değişimi:** Wi-Fi ↔ mobil veri geçişinde SSH kopmaz; tünel yeni ağa kendiliğinden geçer.
- **Arka plan:** terminal açıkken bildirimdeki "terminal açık" oturumu canlı tutar.
  Terminalden çıkınca oturum kapanmaz; ana ekranda "açık" etiketiyle görünür.

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
host/            Rust: Mobile Claude Host PC tepsi uygulaması (SSH sunucusu, izin, ekran görüntüsü)
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
