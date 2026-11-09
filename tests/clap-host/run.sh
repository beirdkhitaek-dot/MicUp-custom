#!/usr/bin/env bash
# Builds the Null Commander plugin and the real app/src/main/cpp/ClapHost.cpp on this machine
# (Android's log.h is stubbed) and runs audio through them under ASan/UBSan.
# Needs: g++, a JDK (for jni.h), git. Usage: tests/clap-host/run.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/../.." && pwd)"; OUT="${TMPDIR:-/tmp}/clap-host-test"
mkdir -p "$OUT"
[ -d "$OUT/clap" ] || git clone --depth 1 --branch 1.2.1 https://github.com/free-audio/clap.git "$OUT/clap"
JH="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
g++ -std=c++17 -O2 -Wall -Wextra -Wno-unused-parameter -fPIC -shared -fvisibility=hidden -I"$OUT/clap/include" \
    "$ROOT/plugins/null-commander/null_commander.cpp" -o "$OUT/NullCommander.so"
g++ -std=c++17 -O1 -g -fsanitize=address,undefined -Wall -Wno-unused-parameter -I"$HERE/stub" -I"$JH/include" -I"$JH/include/linux" \
    -I"$OUT/clap/include" -I"$ROOT/app/src/main/cpp" "$HERE/test.cpp" "$ROOT/app/src/main/cpp/ClapHost.cpp" -o "$OUT/hosttest" -ldl
"$OUT/hosttest" "$OUT/NullCommander.so"
