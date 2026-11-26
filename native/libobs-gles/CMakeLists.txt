cmake_minimum_required(VERSION 3.28...3.30)

add_library(libobs-gles SHARED)
add_library(OBS::libobs-gles ALIAS libobs-gles)

target_sources(
  libobs-gles
  PRIVATE
    gl-android-egl.c
    gl-helpers.c
    gl-helpers.h
    gl-indexbuffer.c
    gl-shader.c
    gl-shaderparser.c
    gl-shaderparser.h
    gl-stagesurf.c
    gl-subsystem.c
    gl-subsystem.h
    gl-texture2d.c
    gl-texture3d.c
    gl-texturecube.c
    gl-vertexbuffer.c
    gl-zstencil.c
    gles_compat.h
)

target_compile_definitions(libobs-gles PRIVATE OBS_GLES=1)
target_compile_options(libobs-gles PRIVATE -Wno-strict-prototypes -Wno-unused-function -Wno-unused-parameter)

target_link_libraries(
  libobs-gles
  PRIVATE
    OBS::libobs
    EGL
    GLESv3
    android
    log
)

set_target_properties_obs(
  libobs-gles
  PROPERTIES FOLDER core
             PREFIX ""
             OUTPUT_NAME "libobs-gles"
)
