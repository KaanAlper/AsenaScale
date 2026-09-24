# Mobile Claude screenshot helper. Piped to `sh -s` over SSH; nothing is
# installed on the PC.
#   sh -s list               -> "kind<TAB>id<TAB>label" lines
#   sh -s shot KIND ID       -> PNG on stdout
# KIND is screen | output | active | window.

mode=$1; kind=$2; target=$3

# An SSH session has none of the desktop's environment; find it.
uid=$(id -u)
rt=${XDG_RUNTIME_DIR:-/run/user/$uid}
export XDG_RUNTIME_DIR="$rt"
[ -n "$DBUS_SESSION_BUS_ADDRESS" ] || export DBUS_SESSION_BUS_ADDRESS="unix:path=$rt/bus"
if [ -z "$WAYLAND_DISPLAY" ]; then
  for s in "$rt"/wayland-*; do
    case "$s" in *.lock) continue ;; esac
    if [ -S "$s" ]; then export WAYLAND_DISPLAY="${s##*/}"; break; fi
  done
fi
if [ -z "$HYPRLAND_INSTANCE_SIGNATURE" ] && [ -d "$rt/hypr" ]; then
  HYPRLAND_INSTANCE_SIGNATURE=$(ls -t "$rt/hypr" 2>/dev/null | head -n 1)
  export HYPRLAND_INSTANCE_SIGNATURE
fi
if [ -z "$SWAYSOCK" ]; then
  for s in "$rt"/sway-ipc.*.sock; do [ -S "$s" ] && export SWAYSOCK="$s"; done
fi
if [ -z "$DISPLAY" ]; then
  for x in /tmp/.X11-unix/X*; do
    if [ -S "$x" ]; then export DISPLAY=":${x##*/X}"; break; fi
  done
fi
if [ -z "$XAUTHORITY" ]; then
  for f in "$HOME/.Xauthority" "$rt"/xauth_* "$rt"/.mutter-Xwaylandauth.* "$rt/gdm/Xauthority"; do
    if [ -f "$f" ]; then export XAUTHORITY="$f"; break; fi
  done
fi

has() { command -v "$1" >/dev/null 2>&1; }
running() { pgrep -x "$1" >/dev/null 2>&1; }
die() { echo "$*" >&2; exit 2; }

if has hyprctl && [ -n "$HYPRLAND_INSTANCE_SIGNATURE" ] && hyprctl version >/dev/null 2>&1; then de=hyprland
elif has swaymsg && [ -n "$SWAYSOCK" ]; then de=sway
elif [ -n "$WAYLAND_DISPLAY" ] && { running kwin_wayland || running plasmashell; }; then de=kde
elif [ -n "$WAYLAND_DISPLAY" ] && running gnome-shell; then de=gnome
elif [ -n "$WAYLAND_DISPLAY" ] && has grim; then de=wlroots
elif [ -n "$DISPLAY" ]; then de=x11
else die "Grafik oturumu bulunamadı. Bilgisayarda oturum açık mı?"
fi

tmpd=$(mktemp -d)
trap 'rm -rf "$tmpd"' EXIT
out="$tmpd/shot.png"

# ---- listing ---------------------------------------------------------------

hypr_windows() {
  hyprctl clients | awk '
    function flush() { if (addr != "" && mapped == 1 && hidden == 0 && title != "") printf "window\t0x%s\t%s — %s\n", addr, cls, title; addr = "" }
    /^Window / { flush(); addr = $2; mapped = 1; hidden = 0; cls = ""; title = ""; next }
    /^[ \t]*mapped:/ { mapped = $2 }
    /^[ \t]*hidden:/ { hidden = $2 }
    /^[ \t]*class:/ { sub(/^[ \t]*class: */, ""); cls = $0 }
    /^[ \t]*title:/ { sub(/^[ \t]*title: */, ""); title = $0 }
    END { flush() }'
}

x11_windows() {
  if has wmctrl; then
    wmctrl -lx | awk '$2 != "-1" { id = $1; cls = $3; sub(/^[^.]*\./, "", cls); $1 = $2 = $3 = $4 = ""; sub(/^ +/, ""); print "window\t" id "\t" cls " — " $0 }'
  elif has xdotool; then
    for w in $(xdotool search --onlyvisible --name . 2>/dev/null); do
      n=$(xdotool getwindowname "$w" 2>/dev/null)
      [ -n "$n" ] && printf 'window\t%s\t%s\n' "$w" "$n"
    done
  fi
}

list() {
  printf 'de\t%s\t%s\n' "$de" "$de"
  printf 'screen\tall\tTüm ekran\n'
  case $de in
    hyprland)
      hyprctl monitors | awk '/^Monitor /{ print "output\t" $2 "\t" $2 }'
      printf 'active\tactive\tAktif pencere\n'
      hypr_windows ;;
    sway)
      if has jq; then
        swaymsg -t get_outputs | jq -r '.[] | select(.active) | "output\t\(.name)\t\(.name)"'
        printf 'active\tactive\tAktif pencere\n'
        swaymsg -t get_tree | jq -r '.. | objects | select(.pid? != null and (.name // "") != "") | "window\t\(.id)\t\(.app_id // .window_properties.class // "?") — \(.name)"'
      fi ;;
    kde|gnome) printf 'active\tactive\tAktif pencere\n' ;;
    x11)
      printf 'active\tactive\tAktif pencere\n'
      x11_windows ;;
  esac
}

# ---- capturing -------------------------------------------------------------

grim_geo() { grim -g "$1" "$out"; }

x11_grab() { # $1 = window id or "root"
  # An empty id would make `import` wait for a mouse click on the PC.
  case "$1" in ""|0|0x0) die "Pencere bulunamadı." ;; esac
  if has import; then import -silent -window "$1" "$out"
  elif has maim; then if [ "$1" = root ]; then maim "$out"; else maim -i "$1" "$out"; fi
  elif [ "$1" = root ] && has scrot; then scrot -o "$out"
  elif has xwd && has convert; then xwd -silent $( [ "$1" = root ] && echo -root || echo -id "$1" ) | convert xwd:- "$out"
  else die "X11 için 'import' (ImageMagick), 'maim' ya da 'scrot' kur."
  fi
}

x11_focus() {
  if has xdotool; then t=""; has timeout && t="timeout 1.5"; $t xdotool windowactivate --sync "$1" 2>/dev/null
  elif has wmctrl; then wmctrl -i -a "$1"
  fi
  sleep 0.3
}

shot() {
  case $de in
    hyprland|sway|wlroots) has grim || die "Ekran görüntüsü için 'grim' kurulu olmalı." ;;
  esac
  case "$de:$kind" in
    hyprland:screen|sway:screen|wlroots:screen) grim "$out" ;;
    hyprland:output|sway:output) grim -o "$target" "$out" ;;
    hyprland:window)
      hyprctl dispatch focuswindow "address:$target" >/dev/null; sleep 0.35
      grim_geo "$(hyprctl activewindow | awk '/^[ \t]*at:/{a=$2} /^[ \t]*size:/{s=$2} END{sub(",", "x", s); print a " " s}')" ;;
    hyprland:active)
      grim_geo "$(hyprctl activewindow | awk '/^[ \t]*at:/{a=$2} /^[ \t]*size:/{s=$2} END{sub(",", "x", s); print a " " s}')" ;;
    sway:window|sway:active)
      has jq || die "Sway'de pencere görüntüsü için 'jq' gerekli."
      [ "$kind" = window ] && { swaymsg "[con_id=$target] focus" >/dev/null; sleep 0.35; }
      grim_geo "$(swaymsg -t get_tree | jq -r '.. | objects | select(.focused? == true) | .rect | "\(.x),\(.y) \(.width)x\(.height)"' | head -n 1)" ;;
    kde:*)
      has spectacle || die "KDE'de 'spectacle' gerekli."
      if [ "$kind" = active ]; then spectacle -b -n -a -o "$out"; else spectacle -b -n -f -o "$out"; fi
      i=0; while [ ! -s "$out" ] && [ $i -lt 50 ]; do sleep 0.1; i=$((i + 1)); done ;;
    gnome:*)
      if has gnome-screenshot; then
        if [ "$kind" = active ]; then gnome-screenshot -w -f "$out"; else gnome-screenshot -f "$out"; fi
      fi
      [ -s "$out" ] || gdbus call --session --dest org.gnome.Shell.Screenshot --object-path /org/gnome/Shell/Screenshot \
        --method org.gnome.Shell.Screenshot.Screenshot false false "$out" >/dev/null 2>&1
      [ -s "$out" ] || die "GNOME (Wayland) dışarıdan ekran görüntüsü almayı engelliyor. 'gnome-screenshot' kurmayı dene." ;;
    x11:screen) x11_grab root ;;
    x11:active)
      w=""
      has xdotool && w=$(xdotool getactivewindow 2>/dev/null)
      [ -z "$w" ] && has xprop && w=$(xprop -root _NET_ACTIVE_WINDOW 2>/dev/null | awk '/#/ {print $NF}')
      # Window managers without EWMH: fall back to the X input focus.
      [ -z "$w" ] && has xdotool && w=$(xdotool getwindowfocus 2>/dev/null)
      case "$w" in ""|0x0|0) die "Şu an aktif (odaklı) bir pencere yok." ;; esac
      x11_grab "$w" ;;
    x11:window) x11_focus "$target"; x11_grab "$target" ;;
    *) die "Bu masaüstünde ($de) bu seçenek desteklenmiyor." ;;
  esac
  [ -s "$out" ] || die "Ekran görüntüsü alınamadı ($de)."
  cat "$out"
}

case $mode in
  list) list ;;
  shot) shot ;;
  *) die "kullanım: list | shot KIND ID" ;;
esac
