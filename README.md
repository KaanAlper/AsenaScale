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
3. **Cihazlar** listesinde PC'ne dokun → kullanıcı adını yaz → kaydet.
4. Giriş yöntemi:
   - **Anahtar** (varsayılan): ana ekrandaki 🔑 simgesinden anahtarı kopyala ve PC'de
     `~/.ssh/authorized_keys` dosyasına ekle. PC'de `sshd` açık olmalı.
   - **Tailscale SSH**: PC'de `sudo tailscale set --ssh` yeterli, anahtar/şifre gerekmez.
   - **Şifre**.
5. "Bağlanınca çalıştır" için `claude` ya da `tmux new -As claude claude` seç.
   tmux ile bağlantı koparsa Claude PC'de çalışmaya devam eder, tekrar bağlanınca kaldığın yerden sürer.

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
| "Bağlantı reddedildi" | PC'de `sshd` çalışmıyor: `sudo systemctl enable --now sshd` |
| "Zaman aşımı" | PC uykuda ya da Tailscale'i kapalı; `tailscale status` ile kontrol et |
| Ekran görüntüsünde araç hatası | Aşağıdaki tablodaki aracı kur (ör. Hyprland için `grim`) |

## Ekran görüntüsü

PC'ye bir şey kurmak gerekmez; uygulama açık SSH oturumu üzerinden küçük bir betik
(`app/src/main/assets/mcshot.sh`) çalıştırır ve masaüstüne göre doğru aracı seçer:

| Masaüstü | Gerekli araç | Pencere seçme |
|---|---|---|
| Hyprland | `grim` | ✓ |
| Sway | `grim`, `jq` | ✓ |
| Diğer wlroots | `grim` | sadece tüm ekran |
| KDE Plasma | `spectacle` | aktif pencere |
| GNOME | `gnome-screenshot` | aktif pencere |
| X11 | `import` (ImageMagick), `maim` veya `scrot`; liste için `wmctrl`/`xdotool` | ✓ |

## Geliştirme

```
tsbridge/        Go: gömülü Tailscale (tsnet) + 127.0.0.1 → tailnet TCP yönlendirme
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
x86_64, x86) derler ve `nightly` sürümüne yükler; `v*` etiketi gerçek bir release oluşturur. APK repodaki
sabit `app/debug.keystore` ile imzalanır, böylece yeni sürüm eskisinin üzerine kurulur.
