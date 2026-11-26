#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_DIR="${ANDROID_HOME:-$HOME/Android/Sdk}"
NDK_DIR="${ANDROID_NDK_HOME:-$SDK_DIR/ndk/28.2.13676358}"
CMAKE_BIN="$SDK_DIR/cmake/4.1.2/bin/cmake"
NINJA_BIN="$SDK_DIR/cmake/4.1.2/bin/ninja"

ABI="arm64-v8a"
API=29
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64"
SYSROOT="$TOOLCHAIN/sysroot"
CC="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang"
CXX="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang++"
AR="$TOOLCHAIN/bin/llvm-ar"
NM="$TOOLCHAIN/bin/llvm-nm"
RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
STRIP="$TOOLCHAIN/bin/llvm-strip"

DEPS_SRC="$ROOT_DIR/native/deps/src"
DEPS_BUILD="$ROOT_DIR/native/deps/build/$ABI"
PREFIX="$ROOT_DIR/native/deps/install/$ABI"
JOBS="$(nproc)"

mkdir -p "$DEPS_SRC" "$DEPS_BUILD" "$PREFIX/include" "$PREFIX/lib/pkgconfig"

export PATH="$SDK_DIR/cmake/4.1.2/bin:$TOOLCHAIN/bin:$PATH"
export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"
export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig"

echo "=== Building Phase 0/1 deps for Android $ABI (API $API) ==="
echo "PREFIX=$PREFIX"

# 1. SIMDe (header-only)
if [ ! -f "$PREFIX/include/simde/x86/sse2.h" ]; then
    echo ">>> [1/5] Installing SIMDe..."
    if [ ! -d "$DEPS_SRC/simde" ]; then
        git clone --depth 1 --branch v0.8.2 https://github.com/simd-everywhere/simde.git "$DEPS_SRC/simde"
    fi
    cp -r "$DEPS_SRC/simde/simde" "$PREFIX/include/"
    cat > "$PREFIX/lib/pkgconfig/simde.pc" <<EOF
prefix=$PREFIX
includedir=\${prefix}/include

Name: SIMDe
Description: SIMD Everywhere
Version: 0.8.2
Cflags: -I\${includedir}
EOF
fi

# 2. uthash (header-only)
if [ ! -f "$PREFIX/include/uthash.h" ]; then
    echo ">>> [2/5] Installing uthash..."
    if [ ! -d "$DEPS_SRC/uthash" ]; then
        git clone --depth 1 --branch v2.3.0 https://github.com/troydhanson/uthash.git "$DEPS_SRC/uthash"
    fi
    cp "$DEPS_SRC/uthash/src/"*.h "$PREFIX/include/"
fi

# 3. jansson
if [ ! -f "$PREFIX/lib/libjansson.a" ]; then
    echo ">>> [3/5] Building jansson v2.15.1..."
    if [ ! -d "$DEPS_SRC/jansson" ]; then
        git clone --depth 1 --branch v2.15.1 https://github.com/akheron/jansson.git "$DEPS_SRC/jansson"
    fi
    "$CMAKE_BIN" -G Ninja \
        -S "$DEPS_SRC/jansson" \
        -B "$DEPS_BUILD/jansson" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK_DIR/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM="android-$API" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="$PREFIX" \
        -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
        -DJANSSON_BUILD_DOCS=OFF \
        -DJANSSON_BUILD_TESTS=OFF \
        -DJANSSON_EXAMPLES=OFF \
        -DJANSSON_WITHOUT_TESTS=ON \
        -DJANSSON_BUILD_SHARED_LIBS=OFF \
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON
    "$CMAKE_BIN" --build "$DEPS_BUILD/jansson" -j "$JOBS"
    "$CMAKE_BIN" --install "$DEPS_BUILD/jansson"
fi

# 4. mbedTLS (for obs-outputs RTMP/RTMPS)
if [ ! -f "$PREFIX/lib/libmbedtls.a" ]; then
    echo ">>> [4/5] Building mbedTLS v3.6.8..."
    if [ ! -d "$DEPS_SRC/mbedtls" ]; then
        git clone --depth 1 --branch v3.6.8 https://github.com/Mbed-TLS/mbedtls.git "$DEPS_SRC/mbedtls"
        git -C "$DEPS_SRC/mbedtls" submodule update --init --depth 1
    fi
    "$CMAKE_BIN" -G Ninja \
        -S "$DEPS_SRC/mbedtls" \
        -B "$DEPS_BUILD/mbedtls" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK_DIR/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM="android-$API" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="$PREFIX" \
        -DENABLE_TESTING=OFF \
        -DENABLE_PROGRAMS=OFF \
        -DUSE_SHARED_MBEDTLS_LIBRARY=OFF \
        -DUSE_STATIC_MBEDTLS_LIBRARY=ON \
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON
    "$CMAKE_BIN" --build "$DEPS_BUILD/mbedtls" -j "$JOBS"
    "$CMAKE_BIN" --install "$DEPS_BUILD/mbedtls"
fi

# 5. FFmpeg 8.1.3 (shared libs with Android MediaCodec + JNI)
if [ ! -f "$PREFIX/lib/libavcodec.so" ]; then
    echo ">>> [5/5] Building FFmpeg n8.1.3..."
    if [ ! -d "$DEPS_SRC/ffmpeg" ]; then
        git clone --depth 1 --branch n8.1.3 https://github.com/FFmpeg/FFmpeg.git "$DEPS_SRC/ffmpeg"
    fi
    mkdir -p "$DEPS_BUILD/ffmpeg"
    pushd "$DEPS_BUILD/ffmpeg" >/dev/null
    "$DEPS_SRC/ffmpeg/configure" \
        --prefix="$PREFIX" \
        --enable-cross-compile \
        --target-os=android \
        --arch=aarch64 \
        --cpu=armv8-a \
        --cc="$CC" \
        --cxx="$CXX" \
        --ar="$AR" \
        --nm="$NM" \
        --ranlib="$RANLIB" \
        --strip="$STRIP" \
        --sysroot="$SYSROOT" \
        --extra-cflags="-O3 -fPIC -DANDROID" \
        --extra-ldflags="-Wl,-z,max-page-size=16384" \
        --enable-shared \
        --disable-static \
        --enable-pic \
        --enable-neon \
        --enable-jni \
        --enable-mediacodec \
        --enable-zlib \
        --disable-programs \
        --disable-doc \
        --disable-debug \
        --disable-vulkan \
        --disable-avdevice \
        --disable-symver
    make -j "$JOBS"
    make install
    popd >/dev/null
fi

echo "=== All Phase 0/1 deps installed in $PREFIX ==="
ls -lh "$PREFIX/lib"
