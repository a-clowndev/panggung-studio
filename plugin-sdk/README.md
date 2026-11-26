# Panggung Studio Plugin SDK (Android arm64-v8a)

Build native C/C++/Rust OBS Studio plugins (`.pgplugin`) that run directly inside Panggung Studio on Android without root or PC.

## Quickstart: Build the Custom GLSL ES Shader Filter Example

```bash
./build_plugin.sh examples/panggung-shader-filter/panggung_shader_filter.c libobs-mod-panggung-shader.so
```

The resulting `.so` uses standard upstream `obs-module.h`, `OBS_DECLARE_MODULE()`, `obs_register_source()`, and `gs_effect_create()` (compiled through `libobs-gles` to OpenGL ES 3.20).
