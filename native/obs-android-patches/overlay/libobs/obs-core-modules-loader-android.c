/******************************************************************************
    Copyright (C) 2026 Panggung / OBS-Mobile Contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 2 of the License, or
    (at your option) any later version.
******************************************************************************/

#include "obs-core-modules.h"

#include <obs-internal.h>
#include <obs.h>
#include <util/dstr.h>
#include <util/platform.h>

extern const char *obs_android_get_data_path(void);
extern const char *obs_android_get_module_path(void);
extern bool find_core_module(struct obs_runtime_module_info *info, obs_find_module_callback2_t callback, void *data);

void load_core_modules(obs_find_module_callback2_t callback, void *data)
{
	const char *mod_dir = obs_android_get_module_path();
	const char *data_dir = obs_android_get_data_path();
	if (!mod_dir || !mod_dir[0])
		return;

	for (unsigned int i = 0; i < obs_core_modules_count; i++) {
		const char *name = obs_core_modules[i];
		struct dstr bin_path = {0};
		struct dstr data_path = {0};

		dstr_printf(&bin_path, "%s/libobs-mod-%s", mod_dir, name);
		if (data_dir && data_dir[0])
			dstr_printf(&data_path, "%s/obs-modules/core/%s", data_dir, name);
		else
			dstr_printf(&data_path, "%s/obs-modules/core/%s", OBS_DATA_PATH, name);

		struct obs_runtime_module_info module_info = {
			.path_info = {.binary = bin_path.array, .data = data_path.array},
			.type = OBS_MODULE_TYPE_CORE,
			.name = name,
		};

		find_core_module(&module_info, callback, data);

		dstr_free(&bin_path);
		dstr_free(&data_path);
	}
}
