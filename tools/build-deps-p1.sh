#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_DIR="${ANDROID_HOME:-$HOME/Android/Sdk}"
NDK_DIR="${ANDROID_NDK_HOME:-$SDK_DIR/ndk/28.2.13676358}"
CMAKE_BIN="$SDK_DIR/cmake/4.1.2/bin/cmake"

ABI="arm64-v8a"
API=29
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64"
SYSROOT="$TOOLCHAIN/sysroot"
CC="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang"
AR="$TOOLCHAIN/bin/llvm-ar"
RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
STRIP="$TOOLCHAIN/bin/llvm-strip"

DEPS_SRC="$ROOT_DIR/native/deps/src"
DEPS_BUILD="$ROOT_DIR/native/deps/build/$ABI"
PREFIX="$ROOT_DIR/native/deps/install/$ABI"
JOBS="$(nproc)"

export PATH="$SDK_DIR/cmake/4.1.2/bin:$TOOLCHAIN/bin:$PATH"
mkdir -p "$DEPS_SRC" "$DEPS_BUILD" "$PREFIX"

# 1. FreeType 2.13.3
if [ ! -f "$PREFIX/lib/libfreetype.a" ]; then
    echo ">>> [1/2] Building FreeType 2.13.3..."
    if [ ! -d "$DEPS_SRC/freetype" ]; then
        git clone --depth 1 --branch VER-2-13-3 https://github.com/freetype/freetype.git "$DEPS_SRC/freetype"
    fi
    "$CMAKE_BIN" -G Ninja \
        -S "$DEPS_SRC/freetype" \
        -B "$DEPS_BUILD/freetype" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK_DIR/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM="android-$API" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="$PREFIX" \
        -DBUILD_SHARED_LIBS=OFF \
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
        -DFT_DISABLE_HARFBUZZ=ON \
        -DFT_DISABLE_PNG=ON \
        -DFT_DISABLE_BZIP2=ON \
        -DFT_DISABLE_BROTLI=ON
    "$CMAKE_BIN" --build "$DEPS_BUILD/freetype" -j "$JOBS"
    "$CMAKE_BIN" --install "$DEPS_BUILD/freetype"
fi

# 2. x264 (ARM64 NEON)
if [ ! -f "$PREFIX/lib/libx264.a" ]; then
    echo ">>> [2/2] Building x264..."
    if [ ! -d "$DEPS_SRC/x264" ]; then
        git clone --depth 1 https://code.videolan.org/videolan/x264.git "$DEPS_SRC/x264"
    fi
    mkdir -p "$DEPS_BUILD/x264"
    pushd "$DEPS_BUILD/x264" >/dev/null
    CC="$CC" AS="$CC" AR="$AR" RANLIB="$RANLIB" STRIP="$STRIP" \
    "$DEPS_SRC/x264/configure" \
        --prefix="$PREFIX" \
        --host=aarch64-linux-android \
        --sysroot="$SYSROOT" \
        --enable-static \
        --enable-pic \
        --disable-cli \
        --disable-opencl \
        --extra-cflags="-O3 -fPIC -DANDROID"
    make -j "$JOBS"
    make install
    popd >/dev/null
fi

echo "=== Phase 1 extra deps built: FreeType + x264 ==="
ls -lh "$PREFIX/lib/libfreetype.a" "$PREFIX/lib/libx264.a"
