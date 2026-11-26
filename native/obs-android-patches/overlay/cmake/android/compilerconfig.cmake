# OBS CMake Android compiler configuration module

include_guard(GLOBAL)

include(ccache)
set(CMAKE_COMPILE_WARNING_AS_ERROR OFF CACHE BOOL "Treat warnings as errors" FORCE)
include(compiler_common)

add_compile_options(
  -fopenmp-simd
  "$<$<COMPILE_LANG_AND_ID:C,Clang>:${_obs_clang_c_options}>"
  "$<$<COMPILE_LANG_AND_ID:CXX,Clang>:${_obs_clang_cxx_options}>"
)

add_link_options(-Wl,-z,max-page-size=16384)

add_compile_definitions($<$<CONFIG:DEBUG>:DEBUG> $<$<CONFIG:DEBUG>:_DEBUG> SIMDE_ENABLE_OPENMP)
