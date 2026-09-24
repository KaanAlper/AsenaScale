#!/usr/bin/env bash
# Builds app/libs/tsbridge.aar (embedded Tailscale) with gomobile.
# Needs: Go (the toolchain in tsbridge/go.mod is fetched automatically),
# the Android SDK and NDK (ANDROID_HOME / ANDROID_NDK_HOME).
set -euo pipefail
cd "$(dirname "$0")/../tsbridge"
export GOTOOLCHAIN=auto
go install golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind
PATH="$(go env GOPATH)/bin:$PATH"
gomobile init
mkdir -p ../app/libs
gomobile bind -target=android/arm64,android/arm,android/amd64,android/386 -androidapi 26 \
  -trimpath -ldflags "-s -w -extldflags=-Wl,-z,max-page-size=16384" \
  -o ../app/libs/tsbridge.aar .
echo "built app/libs/tsbridge.aar"
