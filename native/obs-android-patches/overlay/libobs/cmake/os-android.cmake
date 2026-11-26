target_sources(
  libobs
  PRIVATE
    obs-android.c
    obs-nix-platform.c
    obs-nix-platform.h
    util/pipe-posix.c
    util/platform-nix.c
    util/threading-posix.c
    util/threading-posix.h
    audio-monitoring/null/null-audio-monitoring.c
)

target_compile_definitions(
  libobs
  PRIVATE
    OBS_INSTALL_PREFIX="${OBS_INSTALL_PREFIX}"
)

target_link_libraries(
  libobs
  PRIVATE
    log
    android
    dl
    m
)

set_target_properties(libobs PROPERTIES OUTPUT_NAME obs)
