#!/bin/sh
# AsenaScale for Linux: one-line install / update / uninstall.
#
#   curl -fsSL https://raw.githubusercontent.com/KaanAlper/AsenaScale/main/install.sh | sh
#   curl -fsSL https://raw.githubusercontent.com/KaanAlper/AsenaScale/main/install.sh | sh -s -- --uninstall
#
# Installs for the current user only (~/.local), adds a menu entry with its
# icon, starts it at sign-in, and launches it. Works on Debian/Ubuntu, Arch,
# Fedora and openSUSE (it installs the few system libraries it needs).
set -eu

REPO="${ASENASCALE_REPO:-KaanAlper/AsenaScale}"
URL="${ASENASCALE_URL:-https://github.com/$REPO/releases/latest/download}"

DIR="$HOME/.local/opt/asenascale"
APPIMAGE="$DIR/AsenaScale.AppImage"
ICON="$HOME/.local/share/icons/hicolor/256x256/apps/asenascale.png"
MENU="$HOME/.local/share/applications/asenascale.desktop"
AUTOSTART="${XDG_CONFIG_HOME:-$HOME/.config}/autostart/asenascale.desktop"
BIN="$HOME/.local/bin/asenascale"

say() { printf '\033[1;35m::\033[0m %s\n' "$*"; }
die() { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

stop_running() {
  pkill -x asenascale 2>/dev/null || true
  pkill -f "$APPIMAGE" 2>/dev/null || true
}

if [ "${1:-}" = "--uninstall" ]; then
  stop_running
  rm -f "$APPIMAGE" "$ICON" "$MENU" "$AUTOSTART" "$BIN"
  rmdir "$DIR" 2>/dev/null || true
  say "AsenaScale removed. (Settings stay in ~/.config/AsenaScale; delete that folder to forget approved phones.)"
  exit 0
fi

[ "$(uname -s)" = Linux ] || die "this installer is for Linux; on Windows use AsenaScale-Setup.exe"
[ "$(uname -m)" = x86_64 ] || die "only x86_64 builds are published for now"
command -v curl >/dev/null || die "curl is needed"

# System libraries: GTK 3 (tray menu), the tray indicator, and FUSE 2 so the
# AppImage can mount itself. Skipped when already present.
sudo_cmd=""
[ "$(id -u)" -ne 0 ] && command -v sudo >/dev/null && sudo_cmd="sudo"
if command -v apt-get >/dev/null; then
  pkgs="libgtk-3-0 libayatana-appindicator3-1"
  # Ubuntu 24.04+/Debian 13 renamed libfuse2 to libfuse2t64.
  if apt-cache show libfuse2t64 >/dev/null 2>&1; then pkgs="$pkgs libfuse2t64"; else pkgs="$pkgs libfuse2"; fi
  missing=""
  for p in $pkgs; do dpkg -s "$p" >/dev/null 2>&1 || missing="$missing $p"; done
  if [ -n "$missing" ]; then
    say "Installing:$missing"
    $sudo_cmd apt-get update -qq && $sudo_cmd apt-get install -y -qq $missing
  fi
elif command -v pacman >/dev/null; then
  missing=""
  for p in gtk3 libayatana-appindicator fuse2; do pacman -Qi "$p" >/dev/null 2>&1 || missing="$missing $p"; done
  if [ -n "$missing" ]; then
    say "Installing:$missing"
    $sudo_cmd pacman -S --needed --noconfirm $missing
  fi
elif command -v dnf >/dev/null; then
  $sudo_cmd dnf install -y -q gtk3 libayatana-appindicator-gtk3 fuse-libs
elif command -v zypper >/dev/null; then
  $sudo_cmd zypper -n -q install gtk3 libayatana-appindicator3-1 libfuse2
else
  say "Unknown distribution: make sure GTK 3, an AppIndicator library and FUSE 2 are installed."
fi

say "Downloading AsenaScale"
stop_running
mkdir -p "$DIR" "$(dirname "$ICON")" "$(dirname "$MENU")" "$(dirname "$AUTOSTART")" "$(dirname "$BIN")"
curl -fL --progress-bar -o "$APPIMAGE.new" "$URL/AsenaScale-x86_64.AppImage"
mv "$APPIMAGE.new" "$APPIMAGE"
chmod +x "$APPIMAGE"
curl -fsSL -o "$ICON" "$URL/asenascale.png" || true
# The command line: asenascale status, devices, connect, log -f, ...
ln -sf "$APPIMAGE" "$BIN"

cat > "$MENU" <<EOF
[Desktop Entry]
Type=Application
Name=AsenaScale
GenericName=Phone terminal bridge
Comment=Use this computer's terminal from your phone over Tailscale
Exec="$APPIMAGE"
Icon=asenascale
Terminal=false
Categories=Network;RemoteAccess;
StartupNotify=false
EOF
cp "$MENU" "$AUTOSTART"
echo "X-GNOME-Autostart-enabled=true" >> "$AUTOSTART"
command -v update-desktop-database >/dev/null && update-desktop-database -q "$(dirname "$MENU")" 2>/dev/null || true
command -v gtk-update-icon-cache >/dev/null && gtk-update-icon-cache -q "$HOME/.local/share/icons/hicolor" 2>/dev/null || true

say "Starting AsenaScale (look for it in the system tray)"
nohup "$APPIMAGE" >/dev/null 2>&1 &
say "Done. First run opens the Tailscale login page in your browser."
case ":$PATH:" in
  *":$HOME/.local/bin:"*) say "Command line: asenascale help" ;;
  *) say "Command line: ~/.local/bin/asenascale help (add ~/.local/bin to PATH to type just 'asenascale')" ;;
esac
