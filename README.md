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

1. APK: [Releases → nightly](../../releases/tag/nightly) → `MobileClaude.apk`
   (ya da Actions → son çalışma → `MobileClaude-apk`).
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

Her push'ta GitHub Actions APK'yı derler ve `nightly` sürümüne yükler. APK repodaki
sabit `app/debug.keystore` ile imzalanır, böylece yeni sürüm eskisinin üzerine kurulur.
