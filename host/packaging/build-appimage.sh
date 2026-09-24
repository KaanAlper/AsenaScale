#!/usr/bin/env bash
# Packs the Linux binary into AsenaScale-x86_64.AppImage (runs on Debian,
# Ubuntu, Arch, Fedora... alike).
#   packaging/build-appimage.sh path/to/asenascale out/AsenaScale-x86_64.AppImage
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
bin="$1"; out="$2"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
app="$work/AsenaScale.AppDir"

install -Dm755 "$bin" "$app/usr/bin/asenascale"
install -Dm644 "$here/asenascale.desktop" "$app/usr/share/applications/asenascale.desktop"
install -Dm644 "$here/../../assets/asenascale.png" "$app/usr/share/icons/hicolor/256x256/apps/asenascale.png"
cp "$here/asenascale.desktop" "$app/asenascale.desktop"
cp "$here/../../assets/asenascale.png" "$app/asenascale.png"
cp "$here/../../assets/asenascale.png" "$app/.DirIcon"
ln -s usr/bin/asenascale "$app/AppRun"

tool="$work/appimagetool"
curl -fsSL -o "$tool" https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage
chmod +x "$tool"
mkdir -p "$(dirname "$out")"
ARCH=x86_64 "$tool" --appimage-extract-and-run --no-appstream "$app" "$out"
echo "built $out"
