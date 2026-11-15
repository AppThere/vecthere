#!/usr/bin/env sh
# Build libloki_ffi for the host (JVM desktop).
#   scripts/build-desktop.sh        build only; run later with: gradle :app:run
#   scripts/build-desktop.sh run    build, then launch the app
#
# On Linux the launch defaults to Skia's CPU renderer (SKIKO_RENDER_API=SOFTWARE_FAST); set
# SKIKO_RENDER_API=OPENGL to use the GPU where it works. Use Gradle 8.14 or 9.1 (see Loki Text's playbook).
set -eu
cd "$(dirname "$0")/.."
cargo build --release -p loki-ffi
if [ "${1:-}" = run ]; then
    if [ "$(uname -s)" = Linux ]; then
        export SKIKO_RENDER_API="${SKIKO_RENDER_API:-SOFTWARE_FAST}"
    fi
    if [ -x ./gradlew ]; then exec ./gradlew :app:run; else exec gradle :app:run; fi
fi
