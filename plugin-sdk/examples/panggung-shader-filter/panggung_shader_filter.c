#include <obs-module.h>
#include <graphics/graphics.h>

OBS_DECLARE_MODULE()

static const char *CUSTOM_EFFECT =
    "uniform float4x4 ViewProj;\n"
    "uniform texture2d image;\n"
    "uniform float amount;\n"
    "sampler_state def_sampler {\n"
    "    Filter = Linear;\n"
    "    AddressU = Clamp;\n"
    "    AddressV = Clamp;\n"
    "};\n"
    "struct VertData {\n"
    "    float4 pos : POSITION;\n"
    "    float2 uv  : TEXCOORD0;\n"
    "};\n"
    "VertData VSDefault(VertData v_in) {\n"
    "    VertData v_out;\n"
    "    v_out.pos = mul(float4(v_in.pos.xyz, 1.0), ViewProj);\n"
    "    v_out.uv  = v_in.uv;\n"
    "    return v_out;\n"
    "}\n"
    "float4 PSRetroInvert(VertData v_in) : TARGET {\n"
    "    float4 c = image.Sample(def_sampler, v_in.uv);\n"
    "    float3 inv = float3(1.0, 1.0, 1.0) - c.rgb;\n"
    "    return float4(lerp(c.rgb, inv, amount), c.a);\n"
    "}\n"
    "technique Draw {\n"
    "    pass {\n"
    "        vertex_shader = VSDefault(v_in);\n"
    "        pixel_shader  = PSRetroInvert(v_in);\n"
    "    }\n"
    "}\n";

struct panggung_shader_ctx {
    obs_source_t *context;
    gs_effect_t *effect;
    gs_eparam_t *param_amount;
    float amount;
};

static const char *psf_get_name(void *unused) {
    UNUSED_PARAMETER(unused);
    return "Panggung Custom Shader Filter";
}

static void psf_update(void *data, obs_data_t *settings) {
    struct panggung_shader_ctx *ctx = data;
    ctx->amount = (float)obs_data_get_double(settings, "amount");
}

static void *psf_create(obs_data_t *settings, obs_source_t *source) {
    struct panggung_shader_ctx *ctx = bzalloc(sizeof(*ctx));
    ctx->context = source;
    ctx->amount = 1.0f;
    obs_enter_graphics();
    char *err = NULL;
    ctx->effect = gs_effect_create(CUSTOM_EFFECT, "panggung_custom_shader", &err);
    if (err) {
        blog(LOG_ERROR, "[panggung-shader-filter] effect error: %s", err);
        bfree(err);
    }
    if (ctx->effect) {
        ctx->param_amount = gs_effect_get_param_by_name(ctx->effect, "amount");
    }
    obs_leave_graphics();
    psf_update(ctx, settings);
    return ctx;
}

static void psf_destroy(void *data) {
    struct panggung_shader_ctx *ctx = data;
    obs_enter_graphics();
    if (ctx->effect) gs_effect_destroy(ctx->effect);
    obs_leave_graphics();
    bfree(ctx);
}

static void psf_render(void *data, gs_effect_t *effect) {
    UNUSED_PARAMETER(effect);
    struct panggung_shader_ctx *ctx = data;
    if (!ctx->effect) {
        obs_source_skip_video_filter(ctx->context);
        return;
    }
    if (!obs_source_process_filter_begin(ctx->context, GS_RGBA, OBS_ALLOW_DIRECT_RENDERING))
        return;
    if (ctx->param_amount)
        gs_effect_set_float(ctx->param_amount, ctx->amount);
    obs_source_process_filter_end(ctx->context, ctx->effect, 0, 0);
}

static obs_properties_t *psf_properties(void *data) {
    UNUSED_PARAMETER(data);
    obs_properties_t *props = obs_properties_create();
    obs_properties_add_float_slider(props, "amount", "Effect Strength", 0.0, 1.0, 0.05);
    return props;
}

static void psf_defaults(obs_data_t *settings) {
    obs_data_set_default_double(settings, "amount", 0.85);
}

static struct obs_source_info psf_info = {
    .id = "panggung_custom_shader_filter",
    .type = OBS_SOURCE_TYPE_FILTER,
    .output_flags = OBS_SOURCE_VIDEO,
    .get_name = psf_get_name,
    .create = psf_create,
    .destroy = psf_destroy,
    .update = psf_update,
    .get_properties = psf_properties,
    .get_defaults = psf_defaults,
    .video_render = psf_render,
};

bool obs_module_load(void) {
    obs_register_source(&psf_info);
    blog(LOG_INFO, "[panggung-shader-filter] Loaded custom GLSL ES shader filter plugin!");
    return true;
}
