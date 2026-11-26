# OBS CMake Android defaults module

include_guard(GLOBAL)

option(ENABLE_PULSEAUDIO "Enable PulseAudio support" OFF)
option(ENABLE_WAYLAND "Enable building with support for Wayland" OFF)
option(ENABLE_RELOCATABLE "Enable relocatable build" ON)
option(ENABLE_PORTABLE_CONFIG "Enable support for portable config file location" ON)

include(GNUInstallDirs)

set(OBS_OUTPUT_DIR "${CMAKE_CURRENT_BINARY_DIR}/rundir")
set(OBS_EXECUTABLE_DESTINATION "bin")
set(OBS_INCLUDE_DESTINATION "include/obs")
set(OBS_LIBRARY_DESTINATION "lib")
set(OBS_PLUGIN_DESTINATION "lib/obs-modules")
set(OBS_SCRIPT_PLUGIN_DESTINATION "lib/obs-scripting")
set(OBS_DATA_DESTINATION "share/obs")
set(OBS_CMAKE_DESTINATION "lib/cmake")

set(OBS_PLUGIN_PATH "lib/obs-modules/core")
set(OBS_SCRIPT_PLUGIN_PATH "lib/obs-scripting")
set(OBS_DATA_PATH "${OBS_DATA_DESTINATION}")
set(OBS_INSTALL_PREFIX "${CMAKE_INSTALL_PREFIX}")

set(CMAKE_FIND_PACKAGE_TARGETS_GLOBAL TRUE)
