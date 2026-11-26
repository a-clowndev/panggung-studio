cmake_minimum_required(VERSION 3.28...3.30)

add_library(obs-android-plugins SHARED)
add_library(OBS::android-plugins ALIAS obs-android-plugins)

target_sources(
  obs-android-plugins
  PRIVATE
    android-sources.c
    android-mediacodec.c
)

target_compile_options(obs-android-plugins PRIVATE -Wno-unused-parameter -Wno-unused-function)

target_link_libraries(
  obs-android-plugins
  PRIVATE
    OBS::libobs
    mediandk
    android
    log
)

set_target_properties_obs(
  obs-android-plugins
  PROPERTIES FOLDER core
             PREFIX ""
             OUTPUT_NAME "libobs-android-plugins"
)
