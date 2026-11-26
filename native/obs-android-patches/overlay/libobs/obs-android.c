/******************************************************************************
    Copyright (C) 2026 Panggung / OBS-Mobile Contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 2 of the License, or
    (at your option) any later version.
******************************************************************************/

#include "obs-internal.h"
#include "obs-nix-platform.h"

#include <android/log.h>
#include <sys/system_properties.h>
#include <sys/sysinfo.h>
#include <sys/utsname.h>
#include <inttypes.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

static char *android_data_path = NULL;
static char *android_module_path = NULL;

static void android_log_handler(int log_level, const char *format, va_list args, void *param)
{
	UNUSED_PARAMETER(param);
	int prio = ANDROID_LOG_INFO;
	if (log_level <= LOG_ERROR)
		prio = ANDROID_LOG_ERROR;
	else if (log_level <= LOG_WARNING)
		prio = ANDROID_LOG_WARN;
	else if (log_level <= LOG_INFO)
		prio = ANDROID_LOG_INFO;
	else
		prio = ANDROID_LOG_DEBUG;

	__android_log_vprint(prio, "libobs", format, args);
}

EXPORT void obs_android_install_logcat_handler(void)
{
	base_set_log_handler(android_log_handler, NULL);
}

EXPORT void obs_android_set_data_path(const char *path)
{
	bfree(android_data_path);
	android_data_path = path ? bstrdup(path) : NULL;
}

EXPORT void obs_android_set_module_path(const char *path)
{
	bfree(android_module_path);
	android_module_path = path ? bstrdup(path) : NULL;
}

const char *obs_android_get_data_path(void)
{
	return android_data_path;
}

const char *obs_android_get_module_path(void)
{
	return android_module_path;
}

const char *get_module_extension(void)
{
	return ".so";
}

char *find_libobs_data_file(const char *file)
{
	struct dstr output;
	dstr_init(&output);

	if (android_data_path && android_data_path[0] != 0) {
		struct dstr base;
		dstr_init_copy(&base, android_data_path);
		dstr_cat(&base, "/libobs/");
		bool found = check_path(file, base.array, &output);
		dstr_free(&base);
		if (found)
			return output.array;
	}

	if (check_path(file, OBS_DATA_PATH "/libobs/", &output))
		return output.array;

	dstr_free(&output);
	return NULL;
}

static void log_android_prop(const char *label, const char *prop)
{
	char val[PROP_VALUE_MAX] = {0};
	if (__system_property_get(prop, val) > 0)
		blog(LOG_INFO, "%s: %s", label, val);
}

void log_system_info(void)
{
	log_android_prop("Android Device", "ro.product.model");
	log_android_prop("Android Manufacturer", "ro.product.manufacturer");
	log_android_prop("Android Release", "ro.build.version.release");
	log_android_prop("Android SDK", "ro.build.version.sdk");
	log_android_prop("Android ABI", "ro.product.cpu.abi");
	log_android_prop("Android SoC", "ro.soc.model");

	blog(LOG_INFO, "Physical Cores: %d, Logical Cores: %d",
	     os_get_physical_cores(), os_get_logical_cores());

	struct sysinfo info;
	if (sysinfo(&info) == 0) {
		blog(LOG_INFO, "Physical Memory: %" PRIu64 "MB Total, %" PRIu64 "MB Free",
		     (uint64_t)info.totalram * info.mem_unit / 1024 / 1024,
		     ((uint64_t)info.freeram + (uint64_t)info.bufferram) * info.mem_unit / 1024 / 1024);
	}

	struct utsname u;
	if (uname(&u) == 0)
		blog(LOG_INFO, "Kernel Version: %s %s", u.sysname, u.release);
}

/* Android hotkey platform stub (to be wired to Android KeyEvent/MIDI/Gamepad in Phase 2) */

struct obs_hotkeys_platform {
	int dummy;
};

bool obs_hotkeys_platform_init(struct obs_core_hotkeys *hotkeys)
{
	hotkeys->platform_context = bzalloc(sizeof(struct obs_hotkeys_platform));
	return true;
}

void obs_hotkeys_platform_free(struct obs_core_hotkeys *hotkeys)
{
	bfree(hotkeys->platform_context);
	hotkeys->platform_context = NULL;
}

bool obs_hotkeys_platform_is_pressed(obs_hotkeys_platform_t *context, obs_key_t key)
{
	UNUSED_PARAMETER(context);
	UNUSED_PARAMETER(key);
	return false;
}

void obs_key_to_str(obs_key_t key, struct dstr *dstr)
{
	const char *name = obs_key_to_name(key);
	dstr_copy(dstr, name ? name : "");
}

obs_key_t obs_key_from_virtual_key(int sym)
{
	UNUSED_PARAMETER(sym);
	return OBS_KEY_NONE;
}

int obs_key_to_virtual_key(obs_key_t key)
{
	UNUSED_PARAMETER(key);
	return 0;
}

void obs_key_combination_to_str(obs_key_combination_t combination, struct dstr *str)
{
	if ((combination.modifiers & INTERACT_CONTROL_KEY) != 0)
		dstr_cat(str, "Ctrl + ");
	if ((combination.modifiers & INTERACT_COMMAND_KEY) != 0)
		dstr_cat(str, "Meta + ");
	if ((combination.modifiers & INTERACT_ALT_KEY) != 0)
		dstr_cat(str, "Alt + ");
	if ((combination.modifiers & INTERACT_SHIFT_KEY) != 0)
		dstr_cat(str, "Shift + ");
	if (combination.key != OBS_KEY_NONE) {
		struct dstr key_str = {0};
		obs_key_to_str(combination.key, &key_str);
		dstr_cat_dstr(str, &key_str);
		dstr_free(&key_str);
	}
}
