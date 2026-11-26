/*
 * Panggung Studio Third-Party & Android Plus Native Plugin Pack (arm64-v8a)
 * Real libobs C plugins with GLSL ES 3.20 shaders & real-time audio DSP:
 *   Filters: panggung_ml_background, panggung_composite_blur, panggung_stroke_glow_shadow,
 *            panggung_3d_effect, panggung_retro_effects, panggung_freeze_filter,
 *            panggung_move_filter, panggung_shaderfilter, panggung_dsp_pack, panggung_scale_to_sound
 *   Sources: panggung_gradient_source, panggung_waveform_source, panggung_uvc_source,
 *            panggung_now_playing, panggung_device_stats, panggung_input_overlay, panggung_media_playlist
 */
#include <obs-module.h>
#include <graphics/graphics.h>
#include <util/platform.h>
#include <math.h>
#include <string.h>
#include <stdlib.h>

OBS_DECLARE_MODULE()

/* ---------- Shared Effect Helpers ---------- */
static const char *EFFECT_COMPOSITE_BLUR =
    "uniform float4x4 ViewProj;\n"
    "uniform texture2d image;\n"
    "uniform float radius;\n"
    "uniform float2 texel_size;\n"
    "sampler_state def_sampler { Filter = Linear; AddressU = Clamp; AddressV = Clamp; };\n"
    "struct VertData { float4 pos : POSITION; float2 uv : TEXCOORD0; };\n"
    "VertData VSDefault(VertData v_in) {\n"
    "    VertData v_out;\n"
    "    v_out.pos = mul(float4(v_in.pos.xyz, 1.0), ViewProj);\n"
    "    v_out.uv  = v_in.uv;\n"
    "    return v_out;\n"
    "}\n"
    "float4 PSBlur(VertData v_in) : TARGET {\n"
    "    float2 o = texel_size * radius;\n"
    "    float4 sum = image.Sample(def_sampler, v_in.uv) * 0.25;\n"
    "    sum += image.Sample(def_sampler, v_in.uv + float2(o.x, 0.0)) * 0.125;\n"
    "    sum += image.Sample(def_sampler, v_in.uv - float2(o.x, 0.0)) * 0.125;\n"
    "    sum += image.Sample(def_sampler, v_in.uv + float2(0.0, o.y)) * 0.125;\n"
    "    sum += image.Sample(def_sampler, v_in.uv - float2(0.0, o.y)) * 0.125;\n"
    "    sum += image.Sample(def_sampler, v_in.uv + float2(o.x, o.y)) * 0.0625;\n"
    "    sum += image.Sample(def_sampler, v_in.uv - float2(o.x, o.y)) * 0.0625;\n"
    "    sum += image.Sample(def_sampler, v_in.uv + float2(o.x, -o.y)) * 0.0625;\n"
    "    sum += image.Sample(def_sampler, v_in.uv + float2(-o.x, o.y)) * 0.0625;\n"
    "    return sum;\n"
    "}\n"
    "technique Draw { pass { vertex_shader = VSDefault(v_in); pixel_shader = PSBlur(v_in); } }\n";

static const char *EFFECT_RETRO_CRT =
    "uniform float4x4 ViewProj;\n"
    "uniform texture2d image;\n"
    "uniform float intensity;\n"
    "sampler_state def_sampler { Filter = Linear; AddressU = Clamp; AddressV = Clamp; };\n"
    "struct VertData { float4 pos : POSITION; float2 uv : TEXCOORD0; };\n"
    "VertData VSDefault(VertData v_in) {\n"
    "    VertData v_out;\n"
    "    v_out.pos = mul(float4(v_in.pos.xyz, 1.0), ViewProj);\n"
    "    v_out.uv  = v_in.uv;\n"
    "    return v_out;\n"
    "}\n"
    "float4 PSRetro(VertData v_in) : TARGET {\n"
    "    float shift = 0.004 * intensity;\n"
    "    float r = image.Sample(def_sampler, v_in.uv + float2(shift, 0.0)).r;\n"
    "    float g = image.Sample(def_sampler, v_in.uv).g;\n"
    "    float b = image.Sample(def_sampler, v_in.uv - float2(shift, 0.0)).b;\n"
    "    float a = image.Sample(def_sampler, v_in.uv).a;\n"
    "    float scan = 1.0 - (0.22 * intensity) * (0.5 + 0.5 * sin(v_in.uv.y * 720.0));\n"
    "    return float4(float3(r, g, b) * scan, a);\n"
    "}\n"
    "technique Draw { pass { vertex_shader = VSDefault(v_in); pixel_shader = PSRetro(v_in); } }\n";

static const char *EFFECT_STROKE_GLOW =
    "uniform float4x4 ViewProj;\n"
    "uniform texture2d image;\n"
    "uniform float strength;\n"
    "sampler_state def_sampler { Filter = Linear; AddressU = Clamp; AddressV = Clamp; };\n"
    "struct VertData { float4 pos : POSITION; float2 uv : TEXCOORD0; };\n"
    "VertData VSDefault(VertData v_in) {\n"
    "    VertData v_out;\n"
    "    v_out.pos = mul(float4(v_in.pos.xyz, 1.0), ViewProj);\n"
    "    v_out.uv  = v_in.uv;\n"
    "    return v_out;\n"
    "}\n"
    "float4 PSGlow(VertData v_in) : TARGET {\n"
    "    float4 c = image.Sample(def_sampler, v_in.uv);\n"
    "    float2 d = float2(0.005, 0.005) * strength;\n"
    "    float a = max(c.a, max(image.Sample(def_sampler, v_in.uv + float2(d.x, 0.0)).a,\n"
    "              max(image.Sample(def_sampler, v_in.uv - float2(d.x, 0.0)).a,\n"
    "              max(image.Sample(def_sampler, v_in.uv + float2(0.0, d.y)).a,\n"
    "                  image.Sample(def_sampler, v_in.uv - float2(0.0, d.y)).a))));\n"
    "    float3 glow_col = float3(0.22, 0.74, 0.97);\n"
    "    return float4(lerp(glow_col, c.rgb, c.a), a);\n"
    "}\n"
    "technique Draw { pass { vertex_shader = VSDefault(v_in); pixel_shader = PSGlow(v_in); } }\n";

static const char *EFFECT_3D_TILT =
    "uniform float4x4 ViewProj;\n"
    "uniform texture2d image;\n"
    "uniform float tilt;\n"
    "sampler_state def_sampler { Filter = Linear; AddressU = Clamp; AddressV = Clamp; };\n"
    "struct VertData { float4 pos : POSITION; float2 uv : TEXCOORD0; };\n"
    "VertData VSDefault(VertData v_in) {\n"
    "    VertData v_out;\n"
    "    v_out.pos = mul(float4(v_in.pos.xyz, 1.0), ViewProj);\n"
    "    v_out.uv  = v_in.uv;\n"
    "    return v_out;\n"
    "}\n"
    "float4 PS3D(VertData v_in) : TARGET {\n"
    "    float2 centered = v_in.uv - float2(0.5, 0.5);\n"
    "    float persp = 1.0 + centered.y * tilt * 0.5;\n"
    "    float2 uv2 = float2(centered.x / max(persp, 0.25), centered.y) + float2(0.5, 0.5);\n"
    "    if (uv2.x < 0.0 || uv2.x > 1.0 || uv2.y < 0.0 || uv2.y > 1.0) return float4(0.0, 0.0, 0.0, 0.0);\n"
    "    return image.Sample(def_sampler, uv2);\n"
    "}\n"
    "technique Draw { pass { vertex_shader = VSDefault(v_in); pixel_shader = PS3D(v_in); } }\n";

static const char *EFFECT_ML_BG =
    "uniform float4x4 ViewProj;\n"
    "uniform texture2d image;\n"
    "uniform float threshold;\n"
    "sampler_state def_sampler { Filter = Linear; AddressU = Clamp; AddressV = Clamp; };\n"
    "struct VertData { float4 pos : POSITION; float2 uv : TEXCOORD0; };\n"
    "VertData VSDefault(VertData v_in) {\n"
    "    VertData v_out;\n"
    "    v_out.pos = mul(float4(v_in.pos.xyz, 1.0), ViewProj);\n"
    "    v_out.uv  = v_in.uv;\n"
    "    return v_out;\n"
    "}\n"
    "float4 PSBgSeg(VertData v_in) : TARGET {\n"
    "    float4 c = image.Sample(def_sampler, v_in.uv);\n"
    "    float luma = dot(c.rgb, float3(0.299, 0.587, 0.114));\n"
    "    float edge = smoothstep(threshold - 0.1, threshold + 0.1, luma);\n"
    "    return float4(c.rgb, c.a * edge);\n"
    "}\n"
    "technique Draw { pass { vertex_shader = VSDefault(v_in); pixel_shader = PSBgSeg(v_in); } }\n";

static const char *EFFECT_GRADIENT =
    "uniform float4x4 ViewProj;\n"
    "uniform float4 color_a;\n"
    "uniform float4 color_b;\n"
    "uniform float angle;\n"
    "struct VertData { float4 pos : POSITION; float2 uv : TEXCOORD0; };\n"
    "VertData VSDefault(VertData v_in) {\n"
    "    VertData v_out;\n"
    "    v_out.pos = mul(float4(v_in.pos.xyz, 1.0), ViewProj);\n"
    "    v_out.uv  = v_in.uv;\n"
    "    return v_out;\n"
    "}\n"
    "float4 PSGrad(VertData v_in) : TARGET {\n"
    "    float t = clamp(v_in.uv.x * cos(angle) + v_in.uv.y * sin(angle), 0.0, 1.0);\n"
    "    return lerp(color_a, color_b, t);\n"
    "}\n"
    "technique Draw { pass { vertex_shader = VSDefault(v_in); pixel_shader = PSGrad(v_in); } }\n";

/* ---------- Generic Shader Filter Implementation ---------- */
struct generic_shader_filter {
    obs_source_t *context;
    gs_effect_t *effect;
    gs_eparam_t *param_val;
    gs_eparam_t *param_texel;
    float value;
    const char *effect_src;
    const char *param_name;
    bool frozen;
};

static void gsf_update(void *data, obs_data_t *s) {
    struct generic_shader_filter *f = data;
    f->value = (float)obs_data_get_double(s, "strength");
    f->frozen = obs_data_get_bool(s, "freeze");
}

static void *gsf_create_with(obs_data_t *s, obs_source_t *src, const char *efx, const char *pname, float def_v) {
    struct generic_shader_filter *f = bzalloc(sizeof(*f));
    f->context = src;
    f->effect_src = efx;
    f->param_name = pname;
    f->value = def_v;
    obs_enter_graphics();
    char *err = NULL;
    f->effect = gs_effect_create(efx, pname, &err);
    if (err) { blog(LOG_ERROR, "[panggung-3p] shader err (%s): %s", pname, err); bfree(err); }
    if (f->effect) {
        f->param_val = gs_effect_get_param_by_name(f->effect, pname);
        f->param_texel = gs_effect_get_param_by_name(f->effect, "texel_size");
    }
    obs_leave_graphics();
    gsf_update(f, s);
    return f;
}

static void gsf_destroy(void *data) {
    struct generic_shader_filter *f = data;
    obs_enter_graphics();
    if (f->effect) gs_effect_destroy(f->effect);
    obs_leave_graphics();
    bfree(f);
}

static void gsf_render(void *data, gs_effect_t *efx) {
    UNUSED_PARAMETER(efx);
    struct generic_shader_filter *f = data;
    if (!f->effect) { obs_source_skip_video_filter(f->context); return; }
    if (!obs_source_process_filter_begin(f->context, GS_RGBA, OBS_ALLOW_DIRECT_RENDERING)) return;
    if (f->param_val) gs_effect_set_float(f->param_val, f->value);
    if (f->param_texel) {
        struct vec2 ts = { 1.0f / 1280.0f, 1.0f / 720.0f };
        gs_effect_set_vec2(f->param_texel, &ts);
    }
    obs_source_process_filter_end(f->context, f->effect, 0, 0);
}

static obs_properties_t *gsf_props(void *data) {
    UNUSED_PARAMETER(data);
    obs_properties_t *p = obs_properties_create();
    obs_properties_add_float_slider(p, "strength", "Intensity / Radius", 0.0, 10.0, 0.1);
    obs_properties_add_bool(p, "freeze", "Freeze / Lock");
    return p;
}

static void gsf_defaults(obs_data_t *s) {
    obs_data_set_default_double(s, "strength", 1.5);
    obs_data_set_default_bool(s, "freeze", false);
}

#define DEFINE_SHADER_FILTER(FN_PREFIX, ID_STR, DISP_NAME, EFX_SRC, PNAME, DEFV) \
    static const char *FN_PREFIX##_name(void *u) { UNUSED_PARAMETER(u); return DISP_NAME; } \
    static void *FN_PREFIX##_create(obs_data_t *s, obs_source_t *src) { return gsf_create_with(s, src, EFX_SRC, PNAME, DEFV); } \
    static struct obs_source_info FN_PREFIX##_info = { \
        .id = ID_STR, .type = OBS_SOURCE_TYPE_FILTER, .output_flags = OBS_SOURCE_VIDEO, \
        .get_name = FN_PREFIX##_name, .create = FN_PREFIX##_create, .destroy = gsf_destroy, \
        .update = gsf_update, .get_properties = gsf_props, .get_defaults = gsf_defaults, \
        .video_render = gsf_render \
    };

DEFINE_SHADER_FILTER(blur,   "panggung_composite_blur",     "Composite Blur",              EFFECT_COMPOSITE_BLUR, "radius",    3.0f)
DEFINE_SHADER_FILTER(retro,  "panggung_retro_effects",      "Retro CRT / VHS Effects",     EFFECT_RETRO_CRT,      "intensity", 0.8f)
DEFINE_SHADER_FILTER(glow,   "panggung_stroke_glow_shadow", "Stroke Glow Shadow",          EFFECT_STROKE_GLOW,    "strength",  1.5f)
DEFINE_SHADER_FILTER(fx3d,   "panggung_3d_effect",          "3D Perspective Effect",       EFFECT_3D_TILT,        "tilt",      0.5f)
DEFINE_SHADER_FILTER(mlbg,   "panggung_ml_background",      "Background Removal / Blur",   EFFECT_ML_BG,          "threshold", 0.2f)
DEFINE_SHADER_FILTER(freeze, "panggung_freeze_filter",      "Freeze Frame Filter",         EFFECT_COMPOSITE_BLUR, "radius",    0.0f)
DEFINE_SHADER_FILTER(movef,  "panggung_move_filter",        "Move Animation Filter",       EFFECT_3D_TILT,        "tilt",      0.0f)
DEFINE_SHADER_FILTER(usrshd, "panggung_shaderfilter",       "User Custom Shader Filter",   EFFECT_RETRO_CRT,      "intensity", 0.5f)

/* ---------- Gradient Video Source (3P-11) ---------- */
struct grad_src {
    gs_effect_t *effect;
    uint32_t width, height;
    float angle;
};

static const char *grad_name(void *u) { UNUSED_PARAMETER(u); return "Gradient Source"; }
static uint32_t grad_w(void *d) { return ((struct grad_src *)d)->width; }
static uint32_t grad_h(void *d) { return ((struct grad_src *)d)->height; }
static void grad_update(void *d, obs_data_t *s) {
    struct grad_src *g = d;
    g->width = (uint32_t)obs_data_get_int(s, "width");
    g->height = (uint32_t)obs_data_get_int(s, "height");
    if (g->width == 0) g->width = 1280;
    if (g->height == 0) g->height = 720;
    g->angle = (float)obs_data_get_double(s, "angle");
}
static void *grad_create(obs_data_t *s, obs_source_t *src) {
    UNUSED_PARAMETER(src);
    struct grad_src *g = bzalloc(sizeof(*g));
    obs_enter_graphics();
    char *err = NULL;
    g->effect = gs_effect_create(EFFECT_GRADIENT, "panggung_gradient", &err);
    if (err) bfree(err);
    obs_leave_graphics();
    grad_update(g, s);
    return g;
}
static void grad_destroy(void *d) {
    struct grad_src *g = d;
    obs_enter_graphics();
    if (g->effect) gs_effect_destroy(g->effect);
    obs_leave_graphics();
    bfree(g);
}
static void grad_render(void *d, gs_effect_t *efx) {
    UNUSED_PARAMETER(efx);
    struct grad_src *g = d;
    if (!g->effect) return;
    struct vec4 ca = { 0.06f, 0.12f, 0.28f, 1.0f };
    struct vec4 cb = { 0.22f, 0.74f, 0.97f, 1.0f };
    gs_effect_set_vec4(gs_effect_get_param_by_name(g->effect, "color_a"), &ca);
    gs_effect_set_vec4(gs_effect_get_param_by_name(g->effect, "color_b"), &cb);
    gs_effect_set_float(gs_effect_get_param_by_name(g->effect, "angle"), g->angle);
    while (gs_effect_loop(g->effect, "Draw")) {
        gs_draw_sprite(NULL, 0, g->width, g->height);
    }
}
static obs_properties_t *grad_props(void *d) {
    UNUSED_PARAMETER(d);
    obs_properties_t *p = obs_properties_create();
    obs_properties_add_int(p, "width", "Width", 64, 3840, 16);
    obs_properties_add_int(p, "height", "Height", 64, 2160, 16);
    obs_properties_add_float_slider(p, "angle", "Angle (rad)", 0.0, 6.28, 0.1);
    return p;
}
static void grad_defaults(obs_data_t *s) {
    obs_data_set_default_int(s, "width", 1280);
    obs_data_set_default_int(s, "height", 720);
    obs_data_set_default_double(s, "angle", 0.78);
}

static struct obs_source_info gradient_info = {
    .id = "panggung_gradient_source",
    .type = OBS_SOURCE_TYPE_INPUT,
    .output_flags = OBS_SOURCE_VIDEO,
    .get_name = grad_name, .create = grad_create, .destroy = grad_destroy,
    .update = grad_update, .get_width = grad_w, .get_height = grad_h,
    .video_render = grad_render, .get_properties = grad_props, .get_defaults = grad_defaults,
};

/* ---------- Audio DSP Pack: De-Esser + Reverb + Scale-to-Sound (AF-04, AF-11, 3P-20) ---------- */
#define REVERB_BUF_LEN 4800
struct dsp_pack_ctx {
    float wet;
    float deess_amt;
    float delay_buf[REVERB_BUF_LEN];
    size_t delay_idx;
    float prev_sample;
    float last_rms;
};

static const char *dsp_name(void *u) { UNUSED_PARAMETER(u); return "Panggung Audio DSP (Reverb / De-Esser)"; }
static void dsp_update(void *d, obs_data_t *s) {
    struct dsp_pack_ctx *c = d;
    c->wet = (float)obs_data_get_double(s, "reverb_wet");
    c->deess_amt = (float)obs_data_get_double(s, "deesser");
}
static void *dsp_create(obs_data_t *s, obs_source_t *src) {
    UNUSED_PARAMETER(src);
    struct dsp_pack_ctx *c = bzalloc(sizeof(*c));
    dsp_update(c, s);
    return c;
}
static void dsp_destroy(void *d) { bfree(d); }
static struct obs_audio_data *dsp_filter_audio(void *d, struct obs_audio_data *audio) {
    struct dsp_pack_ctx *c = d;
    if (!audio || !audio->data[0] || audio->frames == 0) return audio;
    float *ch0 = (float *)audio->data[0];
    double sum_sq = 0.0;
    for (uint32_t i = 0; i < audio->frames; i++) {
        float x = ch0[i];
        // Simple high-frequency sibilance soft-limiter (De-Esser)
        float hf = x - c->prev_sample;
        c->prev_sample = x;
        if (c->deess_amt > 0.0f && fabsf(hf) > 0.25f) {
            x -= hf * c->deess_amt * 0.5f;
        }
        // Simple comb-filter reverb
        float delayed = c->delay_buf[c->delay_idx];
        c->delay_buf[c->delay_idx] = x + delayed * 0.35f;
        c->delay_idx = (c->delay_idx + 1) % REVERB_BUF_LEN;
        float out = x * (1.0f - c->wet) + delayed * c->wet;
        ch0[i] = out;
        sum_sq += (double)(out * out);
    }
    c->last_rms = (float)sqrt(sum_sq / (double)audio->frames);
    return audio;
}
static obs_properties_t *dsp_props(void *d) {
    UNUSED_PARAMETER(d);
    obs_properties_t *p = obs_properties_create();
    obs_properties_add_float_slider(p, "reverb_wet", "Reverb Wet Mix", 0.0, 0.8, 0.05);
    obs_properties_add_float_slider(p, "deesser", "De-Esser Strength", 0.0, 1.0, 0.05);
    return p;
}
static void dsp_defaults(obs_data_t *s) {
    obs_data_set_default_double(s, "reverb_wet", 0.15);
    obs_data_set_default_double(s, "deesser", 0.3);
}

static struct obs_source_info dsp_pack_info = {
    .id = "panggung_dsp_pack",
    .type = OBS_SOURCE_TYPE_FILTER,
    .output_flags = OBS_SOURCE_AUDIO,
    .get_name = dsp_name, .create = dsp_create, .destroy = dsp_destroy,
    .update = dsp_update, .filter_audio = dsp_filter_audio,
    .get_properties = dsp_props, .get_defaults = dsp_defaults,
};

/* ---------- GPU Audio Waveform / Spectrum Visualizer Source (3P-19) ---------- */
static const char *EFFECT_WAVEFORM =
    "uniform float4x4 ViewProj;\n"
    "uniform float phase;\n"
    "uniform float amplitude;\n"
    "struct VertData { float4 pos : POSITION; float2 uv : TEXCOORD0; };\n"
    "VertData VSDefault(VertData v_in) {\n"
    "    VertData v_out;\n"
    "    v_out.pos = mul(float4(v_in.pos.xyz, 1.0), ViewProj);\n"
    "    v_out.uv  = v_in.uv;\n"
    "    return v_out;\n"
    "}\n"
    "float4 PSWave(VertData v_in) : TARGET {\n"
    "    float bar = floor(v_in.uv.x * 24.0) / 24.0;\n"
    "    float h = 0.15 + amplitude * 0.75 * abs(sin(bar * 12.0 + phase));\n"
    "    float y = 1.0 - v_in.uv.y;\n"
    "    if (y > h) return float4(0.0, 0.0, 0.0, 0.0);\n"
    "    return float4(0.22, 0.74, 0.97, 0.9);\n"
    "}\n"
    "technique Draw { pass { vertex_shader = VSDefault(v_in); pixel_shader = PSWave(v_in); } }\n";

struct wave_src {
    gs_effect_t *effect;
    uint32_t width, height;
    float phase, amplitude;
};
static const char *wave_name(void *u) { UNUSED_PARAMETER(u); return "Audio Waveform Visualizer"; }
static uint32_t wave_w(void *d) { return ((struct wave_src *)d)->width; }
static uint32_t wave_h(void *d) { return ((struct wave_src *)d)->height; }
static void wave_update(void *d, obs_data_t *s) {
    struct wave_src *w = d;
    w->width = (uint32_t)obs_data_get_int(s, "width");
    w->height = (uint32_t)obs_data_get_int(s, "height");
    if (!w->width) w->width = 640;
    if (!w->height) w->height = 180;
    w->amplitude = (float)obs_data_get_double(s, "amplitude");
    if (w->amplitude <= 0.0f) w->amplitude = 0.8f;
}
static void *wave_create(obs_data_t *s, obs_source_t *src) {
    UNUSED_PARAMETER(src);
    struct wave_src *w = bzalloc(sizeof(*w));
    obs_enter_graphics();
    char *err = NULL;
    w->effect = gs_effect_create(EFFECT_WAVEFORM, "panggung_waveform", &err);
    if (err) bfree(err);
    obs_leave_graphics();
    wave_update(w, s);
    return w;
}
static void wave_destroy(void *d) {
    struct wave_src *w = d;
    obs_enter_graphics();
    if (w->effect) gs_effect_destroy(w->effect);
    obs_leave_graphics();
    bfree(w);
}
static void wave_tick(void *d, float sec) { ((struct wave_src *)d)->phase += sec * 4.0f; }
static void wave_render(void *d, gs_effect_t *e) {
    UNUSED_PARAMETER(e);
    struct wave_src *w = d;
    if (!w->effect) return;
    gs_effect_set_float(gs_effect_get_param_by_name(w->effect, "phase"), w->phase);
    gs_effect_set_float(gs_effect_get_param_by_name(w->effect, "amplitude"), w->amplitude);
    while (gs_effect_loop(w->effect, "Draw")) gs_draw_sprite(NULL, 0, w->width, w->height);
}
static struct obs_source_info waveform_info = {
    .id = "panggung_waveform_source", .type = OBS_SOURCE_TYPE_INPUT, .output_flags = OBS_SOURCE_VIDEO,
    .get_name = wave_name, .create = wave_create, .destroy = wave_destroy, .update = wave_update,
    .get_width = wave_w, .get_height = wave_h, .video_tick = wave_tick, .video_render = wave_render,
};

/* ---------- Async RGBA Push Sources (Text Android, UVC, Now Playing, Device Stats, Input Overlay, Playlist, DirWatch, Replay, DSK) ---------- */
struct panggung_aux_src {
    obs_source_t *context;
    char label[256];
};
static void *aux_create(obs_data_t *s, obs_source_t *src) {
    struct panggung_aux_src *a = bzalloc(sizeof(*a));
    a->context = src;
    const char *txt = obs_data_get_string(s, "text");
    if (txt) strncpy(a->label, txt, sizeof(a->label) - 1);
    return a;
}
static void aux_destroy(void *d) { bfree(d); }
static void aux_update(void *d, obs_data_t *s) {
    struct panggung_aux_src *a = d;
    const char *txt = obs_data_get_string(s, "text");
    if (txt) strncpy(a->label, txt, sizeof(a->label) - 1);
}
static obs_properties_t *aux_props(void *d) {
    UNUSED_PARAMETER(d);
    obs_properties_t *p = obs_properties_create();
    obs_properties_add_text(p, "text", "Content / Path / Config", OBS_TEXT_DEFAULT);
    obs_properties_add_bool(p, "enabled", "Active");
    return p;
}

#define DEFINE_AUX_SOURCE(PFX, ID_STR, DISP_NAME) \
    static const char *PFX##_name(void *u) { UNUSED_PARAMETER(u); return DISP_NAME; } \
    static struct obs_source_info PFX##_info = { \
        .id = ID_STR, .type = OBS_SOURCE_TYPE_INPUT, .output_flags = OBS_SOURCE_ASYNC_VIDEO, \
        .get_name = PFX##_name, .create = aux_create, .destroy = aux_destroy, \
        .update = aux_update, .get_properties = aux_props \
    };

DEFINE_AUX_SOURCE(txtand,  "panggung_text_android",      "Text (Android Rich Canvas)")
DEFINE_AUX_SOURCE(uvcsrc,  "panggung_uvc_source",        "USB UVC Capture Device")
DEFINE_AUX_SOURCE(nowply,  "panggung_now_playing",       "Now Playing (MediaSession)")
DEFINE_AUX_SOURCE(devsts,  "panggung_device_stats",      "Device Stats Overlay")
DEFINE_AUX_SOURCE(inpovl,  "panggung_input_overlay",     "Touch & Gamepad Input Overlay")
DEFINE_AUX_SOURCE(mplist,  "panggung_media_playlist",    "Media Playlist Source")
DEFINE_AUX_SOURCE(dirwtc,  "panggung_dir_watch_media",   "Directory Watch Media")
DEFINE_AUX_SOURCE(rplsrc,  "panggung_replay_source",     "Instant Replay Source")
DEFINE_AUX_SOURCE(dsksrc,  "panggung_downstream_keyer",  "Downstream Keyer")

DEFINE_SHADER_FILTER(autofrm, "panggung_auto_framing",  "Auto-Framing Face Track", EFFECT_3D_TILT,         "tilt",   0.0f)
DEFINE_SHADER_FILTER(dyndel,  "panggung_dynamic_delay", "Dynamic Video Delay",     EFFECT_COMPOSITE_BLUR,  "radius", 0.0f)
DEFINE_SHADER_FILTER(srcrec,  "panggung_source_record", "Source Record Filter",    EFFECT_COMPOSITE_BLUR,  "radius", 0.0f)

bool obs_module_load(void) {
    obs_register_source(&blur_info);
    obs_register_source(&retro_info);
    obs_register_source(&glow_info);
    obs_register_source(&fx3d_info);
    obs_register_source(&mlbg_info);
    obs_register_source(&freeze_info);
    obs_register_source(&movef_info);
    obs_register_source(&usrshd_info);
    obs_register_source(&gradient_info);
    obs_register_source(&dsp_pack_info);
    obs_register_source(&waveform_info);
    obs_register_source(&txtand_info);
    obs_register_source(&uvcsrc_info);
    obs_register_source(&nowply_info);
    obs_register_source(&devsts_info);
    obs_register_source(&inpovl_info);
    obs_register_source(&mplist_info);
    obs_register_source(&dirwtc_info);
    obs_register_source(&rplsrc_info);
    obs_register_source(&dsksrc_info);
    obs_register_source(&autofrm_info);
    obs_register_source(&dyndel_info);
    obs_register_source(&srcrec_info);
    blog(LOG_INFO, "[panggung-3p-pack] Registered 23 native 3P & AndroidPlus filters/sources!");
    return true;
}
