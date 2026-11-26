# OBS CMake Android helper functions module

include_guard(GLOBAL)

include(helpers_common)

function(set_target_properties_obs target)
  set(options "")
  set(oneValueArgs "")
  set(multiValueArgs PROPERTIES)
  cmake_parse_arguments(PARSE_ARGV 0 _STPO "${options}" "${oneValueArgs}" "${multiValueArgs}")

  while(_STPO_PROPERTIES)
    list(POP_FRONT _STPO_PROPERTIES key value)
    # Android dynamic linker does not support versioned .so symlinks well; strip VERSION/SOVERSION.
    if(NOT key STREQUAL "VERSION" AND NOT key STREQUAL "SOVERSION")
      set_property(TARGET ${target} PROPERTY ${key} "${value}")
    endif()
  endwhile()

  get_target_property(target_type ${target} TYPE)

  if(target_type STREQUAL SHARED_LIBRARY)
    set_target_properties(${target} PROPERTIES NO_SONAME OFF)
    install(
      TARGETS ${target}
      LIBRARY DESTINATION "${OBS_LIBRARY_DESTINATION}" COMPONENT Runtime
      PUBLIC_HEADER DESTINATION "${OBS_INCLUDE_DESTINATION}" COMPONENT Development EXCLUDE_FROM_ALL
    )
    add_custom_command(
      TARGET ${target}
      POST_BUILD
      COMMAND "${CMAKE_COMMAND}" -E make_directory "${OBS_OUTPUT_DIR}/$<CONFIG>/${OBS_LIBRARY_DESTINATION}"
      COMMAND "${CMAKE_COMMAND}" -E copy_if_different "$<TARGET_FILE:${target}>"
              "${OBS_OUTPUT_DIR}/$<CONFIG>/${OBS_LIBRARY_DESTINATION}/"
      COMMENT "Copy ${target} to Android library directory"
      VERBATIM
    )
  elseif(target_type STREQUAL MODULE_LIBRARY)
    set(plugin_destination "${OBS_PLUGIN_DESTINATION}/core")
    if(${target} STREQUAL obspython OR ${target} STREQUAL obslua)
      set(plugin_destination "${OBS_SCRIPT_PLUGIN_DESTINATION}")
    endif()
    # Android APK packaging requires lib*.so naming in jniLibs
    set_target_properties(${target} PROPERTIES PREFIX "libobs-mod-")
    install(TARGETS ${target} LIBRARY DESTINATION "${plugin_destination}" COMPONENT Runtime)
    add_custom_command(
      TARGET ${target}
      POST_BUILD
      COMMAND "${CMAKE_COMMAND}" -E make_directory "${OBS_OUTPUT_DIR}/$<CONFIG>/${plugin_destination}"
      COMMAND "${CMAKE_COMMAND}" -E copy_if_different "$<TARGET_FILE:${target}>"
              "${OBS_OUTPUT_DIR}/$<CONFIG>/${plugin_destination}/"
      COMMENT "Copy ${target} to Android plugin directory"
      VERBATIM
    )
  endif()

  target_install_resources(${target})
endfunction()

function(target_install_resources target)
  if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/data")
    get_target_property(target_type ${target} TYPE)
    if(target_type STREQUAL MODULE_LIBRARY)
      set(target_destination "${OBS_DATA_DESTINATION}/obs-modules/core/${target}")
    elseif(target STREQUAL obs)
      set(target_destination "${OBS_DATA_DESTINATION}/obs-studio")
    else()
      set(target_destination "${OBS_DATA_DESTINATION}/${target}")
    endif()

    install(
      DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/data/"
      DESTINATION "${target_destination}"
      USE_SOURCE_PERMISSIONS
      COMPONENT Runtime
    )
    add_custom_command(
      TARGET ${target}
      POST_BUILD
      COMMAND "${CMAKE_COMMAND}" -E make_directory "${OBS_OUTPUT_DIR}/$<CONFIG>/${target_destination}"
      COMMAND "${CMAKE_COMMAND}" -E copy_directory "${CMAKE_CURRENT_SOURCE_DIR}/data"
              "${OBS_OUTPUT_DIR}/$<CONFIG>/${target_destination}"
      COMMENT "Copy ${target} resources to data directory"
      VERBATIM
    )
  endif()
endfunction()

function(target_add_resource target resource)
  get_target_property(target_type ${target} TYPE)
  if(ARGN)
    set(target_destination "${ARGN}")
  elseif(target_type STREQUAL MODULE_LIBRARY)
    set(target_destination "${OBS_DATA_DESTINATION}/obs-modules/core/${target}")
  else()
    set(target_destination "${OBS_DATA_DESTINATION}/${target}")
  endif()
  install(FILES "${resource}" DESTINATION "${target_destination}" COMPONENT Runtime)
endfunction()

function(target_export target)
  # No-op for Android internal build
endfunction()
