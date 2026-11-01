#!/usr/bin/env bash
set -euo pipefail
# CPU-only, arm64; llama.cpp is statically linked behind one JNI library. No server or network code.
COMMIT=0ac1a7fd35c127a18c0cbc6c57d12e256a6a385b
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
WORK="$HERE/build"
SRC="${LLAMA_SOURCE_DIR:-$WORK/src}"
SDK="${ANDROID_HOME:?Set ANDROID_HOME}"
NDK_VERSION="$(sed -n 's/^ndk = "\(.*\)"/\1/p' "$ROOT/gradle/tools.versions.toml")"
CMAKE_VERSION="$(sed -n 's/^cmake = "\(.*\)"/\1/p' "$ROOT/gradle/tools.versions.toml")"
NDK="$SDK/ndk/$NDK_VERSION"
CMAKE="$SDK/cmake/$CMAKE_VERSION/bin/cmake"
case "$(uname -s)" in
  Darwin) HOST=darwin-x86_64 ;;
  *) HOST=linux-x86_64 ;;
esac
if [ ! -d "$SRC/.git" ]; then
  git clone --filter=blob:none --no-checkout https://github.com/ggml-org/llama.cpp.git "$SRC"
fi
if [ "$(git -C "$SRC" rev-parse HEAD 2>/dev/null || true)" != "$COMMIT" ]; then
  git -C "$SRC" fetch --depth 1 origin "$COMMIT"
  git -C "$SRC" checkout --detach "$COMMIT"
fi
"$CMAKE" -S "$HERE" -B "$WORK/android-arm64" -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$SDK/cmake/$CMAKE_VERSION/bin/ninja" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release -DLLAMA_SOURCE_DIR="$SRC" -DGGML_CPU_ARM_ARCH=armv8-a
"$CMAKE" --build "$WORK/android-arm64" --target dictate_cleanup --parallel 6
DEST="$ROOT/app/src/main/jniLibs/arm64-v8a/libdictate_cleanup.so"
mkdir -p "$(dirname "$DEST")"
"$NDK/toolchains/llvm/prebuilt/$HOST/bin/llvm-strip" --strip-unneeded \
  -o "$DEST" "$WORK/android-arm64/libdictate_cleanup.so"
echo "Built $DEST"
