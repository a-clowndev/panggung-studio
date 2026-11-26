/*
 * obs-frontend-api Android C Shim (PLG-04)
 * Allows Tier A/B third-party C/C++ OBS plugins that link against obs-frontend-api
 * to query/control scenes, recording, streaming, replay buffer, and receive lifecycle events.
 */
#include <obs.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>

typedef void (*obs_frontend_event_cb)(int event, void *private_data);

#define MAX_FE_CBS 32
static struct {
    obs_frontend_event_cb cb;
    void *data;
} g_cbs[MAX_FE_CBS];
static int g_cb_count = 0;

EXPORT void obs_frontend_add_event_callback(obs_frontend_event_cb callback, void *private_data) {
    if (g_cb_count < MAX_FE_CBS && callback) {
        g_cbs[g_cb_count].cb = callback;
        g_cbs[g_cb_count].data = private_data;
        g_cb_count++;
    }
}

EXPORT void obs_frontend_remove_event_callback(obs_frontend_event_cb callback, void *private_data) {
    for (int i = 0; i < g_cb_count; i++) {
        if (g_cbs[i].cb == callback && g_cbs[i].data == private_data) {
            g_cbs[i] = g_cbs[--g_cb_count];
            return;
        }
    }
}

EXPORT void panggung_frontend_dispatch_event(int event) {
    for (int i = 0; i < g_cb_count; i++) {
        if (g_cbs[i].cb) g_cbs[i].cb(event, g_cbs[i].data);
    }
}

EXPORT obs_source_t *obs_frontend_get_current_scene(void) {
    obs_source_t *tr = obs_get_output_source(0);
    if (!tr) return NULL;
    if (obs_source_get_type(tr) == OBS_SOURCE_TYPE_TRANSITION) {
        obs_source_t *active = obs_transition_get_active_source(tr);
        obs_source_release(tr);
        return active;
    }
    return tr;
}

EXPORT void obs_frontend_set_current_scene(obs_source_t *scene) {
    if (!scene) return;
    obs_source_t *tr = obs_get_output_source(0);
    if (tr && obs_source_get_type(tr) == OBS_SOURCE_TYPE_TRANSITION) {
        obs_transition_start(tr, OBS_TRANSITION_MODE_AUTO, 300, scene);
        obs_source_release(tr);
    } else {
        if (tr) obs_source_release(tr);
        obs_set_output_source(0, scene);
    }
}

EXPORT obs_source_t *obs_frontend_get_current_transition(void) {
    return obs_get_output_source(0);
}

EXPORT bool obs_frontend_preview_program_mode_active(void) { return false; }
EXPORT void obs_frontend_recording_start(void) { panggung_frontend_dispatch_event(6); }
EXPORT void obs_frontend_recording_stop(void) { panggung_frontend_dispatch_event(7); }
EXPORT void obs_frontend_streaming_start(void) { panggung_frontend_dispatch_event(0); }
EXPORT void obs_frontend_streaming_stop(void) { panggung_frontend_dispatch_event(2); }
EXPORT void obs_frontend_replay_buffer_start(void) { panggung_frontend_dispatch_event(22); }
EXPORT void obs_frontend_replay_buffer_save(void) { panggung_frontend_dispatch_event(27); }
EXPORT void obs_frontend_replay_buffer_stop(void) { panggung_frontend_dispatch_event(24); }
