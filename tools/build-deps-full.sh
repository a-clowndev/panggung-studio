#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_DIR="${ANDROID_HOME:-$HOME/Android/Sdk}"
NDK_DIR="${ANDROID_NDK_HOME:-$SDK_DIR/ndk/28.2.13676358}"
CMAKE_BIN="$SDK_DIR/cmake/4.1.2/bin/cmake"
[ -x "$CMAKE_BIN" ] || CMAKE_BIN="$(command -v cmake)"

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

CMAKE_COMMON=(
    -G Ninja
    -DCMAKE_TOOLCHAIN_FILE="$NDK_DIR/build/cmake/android.toolchain.cmake"
    -DANDROID_ABI="$ABI"
    -DANDROID_PLATFORM="android-$API"
    -DCMAKE_BUILD_TYPE=Release
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5
    -DCMAKE_INSTALL_PREFIX="$PREFIX"
    -DCMAKE_PREFIX_PATH="$PREFIX"
    -DCMAKE_FIND_ROOT_PATH="$PREFIX"
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON
    -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384"
)

echo "=== T-D01: Cross-Compiling Full Android Native Deps ($ABI, API $API) ==="

# Ensure base deps (SIMDe, uthash, jansson, mbedTLS, FFmpeg src) & Phase 1 deps (FreeType, x264) exist first
FFMPEG_SKIP_BUILD=1 "$ROOT_DIR/tools/build-deps.sh"
"$ROOT_DIR/tools/build-deps-p1.sh"

# 1. libjpeg-turbo 3.1.0 (UVC MJPEG NEON acceleration)
if [ ! -f "$PREFIX/lib/libturbojpeg.a" ]; then
    echo ">>> [1/7] Building libjpeg-turbo 3.1.0..."
    [ -d "$DEPS_SRC/libjpeg-turbo" ] || git clone --depth 1 --branch 3.1.0 https://github.com/libjpeg-turbo/libjpeg-turbo.git "$DEPS_SRC/libjpeg-turbo"
    "$CMAKE_BIN" "${CMAKE_COMMON[@]}" \
        -S "$DEPS_SRC/libjpeg-turbo" -B "$DEPS_BUILD/libjpeg-turbo" \
        -DENABLE_SHARED=OFF -DENABLE_STATIC=ON -DWITH_TURBOJPEG=ON -DWITH_SIMD=ON
    "$CMAKE_BIN" --build "$DEPS_BUILD/libjpeg-turbo" -j "$JOBS"
    "$CMAKE_BIN" --install "$DEPS_BUILD/libjpeg-turbo"
fi

# 2. libxml2 v2.13.5 (FFmpeg DASH demuxer)
if [ ! -f "$PREFIX/lib/libxml2.a" ]; then
    echo ">>> [2/7] Building libxml2 v2.13.5..."
    [ -d "$DEPS_SRC/libxml2" ] || git clone --depth 1 --branch v2.13.5 https://gitlab.gnome.org/GNOME/libxml2.git "$DEPS_SRC/libxml2"
    "$CMAKE_BIN" "${CMAKE_COMMON[@]}" \
        -S "$DEPS_SRC/libxml2" -B "$DEPS_BUILD/libxml2" \
        -DBUILD_SHARED_LIBS=OFF \
        -DLIBXML2_WITH_PYTHON=OFF -DLIBXML2_WITH_TESTS=OFF -DLIBXML2_WITH_PROGRAMS=OFF \
        -DLIBXML2_WITH_LZMA=OFF -DLIBXML2_WITH_ICONV=OFF -DLIBXML2_WITH_ZLIB=ON
    "$CMAKE_BIN" --build "$DEPS_BUILD/libxml2" -j "$JOBS"
    "$CMAKE_BIN" --install "$DEPS_BUILD/libxml2"
fi

# 3. libsrt v1.5.4 (SRT streaming protocol, using mbedTLS)
if [ ! -f "$PREFIX/lib/libsrt.a" ]; then
    echo ">>> [3/7] Building libsrt v1.5.4 (mbedTLS)..."
    [ -d "$DEPS_SRC/srt" ] || git clone --depth 1 --branch v1.5.4 https://github.com/Haivision/srt.git "$DEPS_SRC/srt"
    "$CMAKE_BIN" "${CMAKE_COMMON[@]}" \
        -S "$DEPS_SRC/srt" -B "$DEPS_BUILD/srt" \
        -DUSE_ENCLIB=mbedtls \
        -DMBEDTLS_PREFIX="$PREFIX" \
        -DMBEDTLS_INCLUDE_DIR="$PREFIX/include" \
        -DMBEDTLS_INCLUDE_DIRS="$PREFIX/include" \
        -DMBEDTLS_LIBRARY="$PREFIX/lib/libmbedtls.a" \
        -DMBEDCRYPTO_LIBRARY="$PREFIX/lib/libmbedcrypto.a" \
        -DMBEDX509_LIBRARY="$PREFIX/lib/libmbedx509.a" \
        -DMBEDTLS_LIBRARIES="$PREFIX/lib/libmbedtls.a;$PREFIX/lib/libmbedx509.a;$PREFIX/lib/libmbedcrypto.a" \
        -DENABLE_SHARED=OFF -DENABLE_STATIC=ON -DENABLE_APPS=OFF \
        -DENABLE_TESTING=OFF -DENABLE_UNITTESTS=OFF -DENABLE_ENCRYPTION=ON \
        -DENABLE_STDCXX_SYNC=ON
    "$CMAKE_BIN" --build "$DEPS_BUILD/srt" -j "$JOBS"
    "$CMAKE_BIN" --install "$DEPS_BUILD/srt"
    # Ensure static pkg-config links mbedtls & c++_static cleanly
    sed -i 's/^Libs.private:.*/Libs.private: -lmbedtls -lmbedx509 -lmbedcrypto -lc++_static -lc++abi/' "$PREFIX/lib/pkgconfig/srt.pc" || true
fi

# 4. x265 3.6 (Software HEVC 8-bit/10-bit)
if [ ! -f "$PREFIX/lib/libx265.a" ]; then
    echo ">>> [4/7] Building x265 3.6..."
    [ -d "$DEPS_SRC/x265" ] || git clone --depth 1 --branch 3.6 https://bitbucket.org/multicoreware/x265_git.git "$DEPS_SRC/x265"
    sed -i 's/CMP0025 OLD/CMP0025 NEW/g; s/CMP0054 OLD/CMP0054 NEW/g' "$DEPS_SRC/x265/source/CMakeLists.txt"
    "$CMAKE_BIN" "${CMAKE_COMMON[@]}" \
        -S "$DEPS_SRC/x265/source" -B "$DEPS_BUILD/x265" \
        -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
        -DENABLE_SHARED=OFF -DENABLE_CLI=OFF -DENABLE_ASSEMBLY=OFF \
        -DENABLE_PIC=ON -DCROSS_COMPILE_ARM=OFF
    "$CMAKE_BIN" --build "$DEPS_BUILD/x265" -j "$JOBS"
    "$CMAKE_BIN" --install "$DEPS_BUILD/x265"
    sed -i 's/^Libs.private:.*/Libs.private: -lc++_static -lc++abi -lm -ldl/' "$PREFIX/lib/pkgconfig/x265.pc" || true
fi

# 5. libaom v3.10.0 (AV1 software encoder/decoder)
if [ ! -f "$PREFIX/lib/libaom.a" ]; then
    echo ">>> [5/7] Building libaom v3.10.0..."
    [ -d "$DEPS_SRC/aom" ] || git clone --depth 1 --branch v3.10.0 https://aomedia.googlesource.com/aom "$DEPS_SRC/aom"
    "$CMAKE_BIN" "${CMAKE_COMMON[@]}" \
        -S "$DEPS_SRC/aom" -B "$DEPS_BUILD/aom" \
        -DBUILD_SHARED_LIBS=OFF \
        -DENABLE_EXAMPLES=OFF -DENABLE_TESTS=OFF -DENABLE_TOOLS=OFF -DENABLE_DOCS=OFF \
        -DAOM_TARGET_CPU=arm64 -DCONFIG_RUNTIME_CPU_DETECT=0
    "$CMAKE_BIN" --build "$DEPS_BUILD/aom" -j "$JOBS"
    "$CMAKE_BIN" --install "$DEPS_BUILD/aom"
fi

# 6. speexdsp 1.2.1 (OBS Audio Noise Suppression)
if [ ! -f "$PREFIX/lib/libspeexdsp.a" ]; then
    echo ">>> [6/7] Building speexdsp 1.2.1..."
    [ -d "$DEPS_SRC/speexdsp" ] || git clone --depth 1 --branch SpeexDSP-1.2.1 https://github.com/xiph/speexdsp.git "$DEPS_SRC/speexdsp"
    mkdir -p "$DEPS_BUILD/speexdsp"
    pushd "$DEPS_SRC/speexdsp" >/dev/null
    [ -f configure ] || ./autogen.sh
    popd >/dev/null
    pushd "$DEPS_BUILD/speexdsp" >/dev/null
    CC="$CC" AR="$AR" RANLIB="$RANLIB" CFLAGS="-O3 -fPIC -DANDROID" \
        "$DEPS_SRC/speexdsp/configure" \
        --host=aarch64-linux-android --prefix="$PREFIX" \
        --enable-static --disable-shared --disable-examples
    make -j "$JOBS"
    make install
    popd >/dev/null
fi

# 7. curl 8.11.1 (with mbedTLS)
if [ ! -f "$PREFIX/lib/libcurl.a" ]; then
    echo ">>> [7/7] Building curl 8.11.1 (mbedTLS)..."
    [ -d "$DEPS_SRC/curl" ] || git clone --depth 1 --branch curl-8_11_1 https://github.com/curl/curl.git "$DEPS_SRC/curl"
    "$CMAKE_BIN" "${CMAKE_COMMON[@]}" \
        -S "$DEPS_SRC/curl" -B "$DEPS_BUILD/curl" \
        -DBUILD_SHARED_LIBS=OFF -DBUILD_CURL_EXE=OFF -DBUILD_TESTING=OFF \
        -DCURL_USE_MBEDTLS=ON -DCURL_USE_OPENSSL=OFF -DCURL_USE_LIBPSL=OFF \
        -DMBEDTLS_INCLUDE_DIRS="$PREFIX/include" \
        -DMBEDTLS_LIBRARY="$PREFIX/lib/libmbedtls.a" \
        -DMBEDX509_LIBRARY="$PREFIX/lib/libmbedx509.a" \
        -DMBEDCRYPTO_LIBRARY="$PREFIX/lib/libmbedcrypto.a" \
        -DENABLE_ARES=OFF -DCURL_DISABLE_LDAP=ON -DHTTP_ONLY=ON
    "$CMAKE_BIN" --build "$DEPS_BUILD/curl" -j "$JOBS"
    "$CMAKE_BIN" --install "$DEPS_BUILD/curl"
fi

echo "=== Rebuilding FFmpeg 8.1.3 with SRT + XML2(DASH) + x265 + AOM ==="
rm -rf "$DEPS_BUILD/ffmpeg-full"
mkdir -p "$DEPS_BUILD/ffmpeg-full"
pushd "$DEPS_BUILD/ffmpeg-full" >/dev/null
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
    --pkg-config=pkg-config \
    --pkg-config-flags="--static" \
    --extra-cflags="-O3 -fPIC -DANDROID -I$PREFIX/include" \
    --extra-ldflags="-L$PREFIX/lib -Wl,-z,max-page-size=16384" \
    --extra-libs="-lmbedtls -lmbedx509 -lmbedcrypto -lc++_static -lc++abi -lm" \
    --enable-gpl \
    --enable-shared \
    --disable-static \
    --enable-pic \
    --enable-neon \
    --enable-jni \
    --enable-mediacodec \
    --enable-zlib \
    --enable-libsrt \
    --enable-libxml2 \
    --enable-libx265 \
    --enable-libaom \
    --disable-programs \
    --disable-doc \
    --disable-debug \
    --disable-vulkan \
    --disable-avdevice \
    --disable-symver
make -j "$JOBS"
make install
popd >/dev/null

echo "=== T-D01 COMPLETE! Installed libs: ==="
ls -lh "$PREFIX/lib"
