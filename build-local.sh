#!/bin/zsh
# Local convenience wrapper: loads an Android SDK environment if one is configured, then builds.
# Anyone else can just run ./gradlew assembleDebug (or assembleRelease).
set -eu
cd "${0:A:h}"
[[ -n "${ANDROID_ENV:-}" && -f "$ANDROID_ENV" ]] && source "$ANDROID_ENV"
[[ -f "$HOME/Developer/AndroidDev/env.sh" ]] && source "$HOME/Developer/AndroidDev/env.sh"
exec ./gradlew --no-daemon --console=plain "${@:-assembleDebug}"
