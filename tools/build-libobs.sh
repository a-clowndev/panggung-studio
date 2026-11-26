#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_DIR="${ANDROID_HOME:-$HOME/Android/Sdk}"
NDK_DIR="${ANDROID_NDK_HOME:-$SDK_DIR/ndk/28.2.13676358}"
CMAKE_BIN="$SDK_DIR/cmake/4.1.2/bin/cmake"

ABI="arm64-v8a"
API=29
DEPS_PREFIX="$ROOT_DIR/native/deps/install/$ABI"
OBS_SRC="$ROOT_DIR/native/obs-studio"
OBS_BUILD="$ROOT_DIR/native/build/obs/$ABI"
OBS_INSTALL="$ROOT_DIR/native/install/$ABI"
JOBS="$(nproc)"

export PATH="$SDK_DIR/cmake/4.1.2/bin:$PATH"
export PKG_CONFIG_LIBDIR="$DEPS_PREFIX/lib/pkgconfig"
export PKG_CONFIG_PATH="$DEPS_PREFIX/lib/pkgconfig"

echo "=== Configuring libobs + core modules for Android $ABI (API $API) ==="

"$CMAKE_BIN" -G Ninja \
    -S "$OBS_SRC" \
    -B "$OBS_BUILD" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK_DIR/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API" \
    -DCMAKE_BUILD_TYPE=RelWithDebInfo \
    -DCMAKE_INSTALL_PREFIX="$OBS_INSTALL" \
    -DCMAKE_PREFIX_PATH="$DEPS_PREFIX" \
    -DCMAKE_FIND_ROOT_PATH="$DEPS_PREFIX" \
    -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=ONLY \
    -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=ONLY \
    -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=ONLY \
    -DENABLE_SPEEXDSP=ON \
    -DSpeexdsp_INCLUDE_DIR="$DEPS_PREFIX/include" \
    -DSpeexdsp_LIBRARY="$DEPS_PREFIX/lib/libspeexdsp.a" \
    -DENABLE_FRONTEND=OFF \
    -DENABLE_SCRIPTING=OFF \
    -DENABLE_CORE_MODULES=ON \
    -DENABLE_BROWSER=OFF \
    -DENABLE_WEBSOCKET=OFF \
    -DENABLE_WEBRTC=OFF \
    -DENABLE_LIBFDK=OFF \
    -DENABLE_VLC=OFF \
    -DENABLE_FREETYPE=ON \
    -DENABLE_SERVICE_UPDATES=OFF \
    -DENABLE_RTMPS=ON \
    -DENABLE_NEW_MPEGTS_OUTPUT=OFF \
    -DENABLE_X264=ON \
    -DENABLE_FFMPEG_LOGGING=ON \
    -DFREETYPE_INCLUDE_DIRS="$DEPS_PREFIX/include/freetype2" \
    -DFREETYPE_LIBRARY="$DEPS_PREFIX/lib/libfreetype.a" \
    -DLibx264_INCLUDE_DIR="$DEPS_PREFIX/include" \
    -DLibx264_LIBRARY="$DEPS_PREFIX/lib/libx264.a" \
    -DFFMPEG_AVCODEC_INCLUDE_DIR="$DEPS_PREFIX/include" \
    -DFFMPEG_AVCODEC_LIBRARY="$DEPS_PREFIX/lib/libavcodec.so" \
    -DFFMPEG_AVFORMAT_INCLUDE_DIR="$DEPS_PREFIX/include" \
    -DFFMPEG_AVFORMAT_LIBRARY="$DEPS_PREFIX/lib/libavformat.so" \
    -DFFMPEG_AVUTIL_INCLUDE_DIR="$DEPS_PREFIX/include" \
    -DFFMPEG_AVUTIL_LIBRARY="$DEPS_PREFIX/lib/libavutil.so" \
    -DFFMPEG_SWSCALE_INCLUDE_DIR="$DEPS_PREFIX/include" \
    -DFFMPEG_SWSCALE_LIBRARY="$DEPS_PREFIX/lib/libswscale.so" \
    -DFFMPEG_SWRESAMPLE_INCLUDE_DIR="$DEPS_PREFIX/include" \
    -DFFMPEG_SWRESAMPLE_LIBRARY="$DEPS_PREFIX/lib/libswresample.so" \
    -DSIMDe_INCLUDE_DIR="$DEPS_PREFIX/include" \
    -DUthash_INCLUDE_DIR="$DEPS_PREFIX/include" \
    -Djansson_INCLUDE_DIR="$DEPS_PREFIX/include" \
    -Djansson_LIBRARY="$DEPS_PREFIX/lib/libjansson.a" \
    -DMbedTLS_INCLUDE_DIR="$DEPS_PREFIX/include" \
    -DMbedTLS_LIBRARY="$DEPS_PREFIX/lib/libmbedtls.a" \
    -DMbedX509_LIBRARY="$DEPS_PREFIX/lib/libmbedx509.a" \
    -DMbedCrypto_LIBRARY="$DEPS_PREFIX/lib/libmbedcrypto.a"

echo "=== Building libobs + core modules ==="
"$CMAKE_BIN" --build "$OBS_BUILD" --target libobs libobs-gles obs-android-plugins image-source obs-filters obs-transitions obs-outputs obs-ffmpeg rtmp-services text-freetype2 obs-x264 -j "$JOBS"
"$CMAKE_BIN" --install "$OBS_BUILD"

echo "=== Build complete ==="
ls -lh "$OBS_INSTALL/lib" "$OBS_INSTALL/lib/obs-modules/core" 2>/dev/null || true
