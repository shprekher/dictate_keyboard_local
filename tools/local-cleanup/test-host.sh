#!/usr/bin/env bash
set -euo pipefail
# Builds and tests the JNI bridge locally. Requires already downloaded llama.cpp sources and GGUFs.
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
WORK="$HERE/build"
SRC="${LLAMA_SOURCE_DIR:-$WORK/src}"
QUALITY="${1:?Usage: bash test-host.sh QUALITY_GGUF FAST_GGUF}"
FAST="${2:?Usage: bash test-host.sh QUALITY_GGUF FAST_GGUF}"
CMAKE_VERSION="$(sed -n 's/^cmake = "\(.*\)"/\1/p' "$ROOT/gradle/tools.versions.toml")"
CMAKE="${CMAKE:-${ANDROID_HOME:?Set ANDROID_HOME}/cmake/$CMAKE_VERSION/bin/cmake}"
test -f "$SRC/include/llama.h"
test -f "$QUALITY"
test -f "$FAST"
if [[ "$(uname -s)" == Darwin ]]; then
    export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home)}"
    LIB="$WORK/host/libdictate_cleanup.dylib"
else
    LIB="$WORK/host/libdictate_cleanup.so"
fi
"$CMAKE" -S "$HERE" -B "$WORK/host" -DLLAMA_SOURCE_DIR="$SRC" -DCMAKE_BUILD_TYPE=Release
"$CMAKE" --build "$WORK/host" --target dictate_cleanup --parallel 6
mkdir -p "$WORK/host-test"
# Export the exact Kotlin instruction instead of maintaining a second prompt in the test harness.
python3 - "$ROOT" "$WORK/host-test/cleanup-instruction.txt" <<'PY'
from pathlib import Path
import re
import sys
import textwrap

source = (Path(sys.argv[1]) / "app/src/main/kotlin/dev/patrickgold/florisboard/dictate/cleanup/LocalCleanupText.kt").read_text()
match = re.search(r'val CLEANUP_INSTRUCTION = """(.*?)"""\.trimIndent\(\)', source, re.S)
if not match:
    raise SystemExit("Cannot extract the Kotlin cleanup instruction")
Path(sys.argv[2]).write_text(textwrap.dedent(match.group(1)).strip())
PY
javac -d "$WORK/host-test" "$HERE/LocalCleanupNative.java"
java -cp "$WORK/host-test" dev.patrickgold.florisboard.dictate.cleanup.LocalCleanupNative \
    "$LIB" "$FAST" "$WORK/host-test/cleanup-instruction.txt" "$QUALITY"
java -cp "$WORK/host-test" dev.patrickgold.florisboard.dictate.cleanup.LocalCleanupNative \
    "$LIB" "$QUALITY" "$WORK/host-test/cleanup-instruction.txt" "$FAST"
