#include <file-updater/file-updater.h>
#include <util/c99defs.h>

update_info_t *update_info_create(const char *log_prefix, const char *user_agent, const char *update_url,
				  const char *local_dir, const char *cache_dir,
				  confirm_file_callback_t confirm_callback, void *param)
{
	UNUSED_PARAMETER(log_prefix); UNUSED_PARAMETER(user_agent); UNUSED_PARAMETER(update_url);
	UNUSED_PARAMETER(local_dir); UNUSED_PARAMETER(cache_dir); UNUSED_PARAMETER(confirm_callback);
	UNUSED_PARAMETER(param);
	return NULL;
}

update_info_t *update_info_create_single(const char *log_prefix, const char *user_agent, const char *file_url,
					 confirm_file_callback_t confirm_callback, void *param)
{
	UNUSED_PARAMETER(log_prefix); UNUSED_PARAMETER(user_agent); UNUSED_PARAMETER(file_url);
	UNUSED_PARAMETER(confirm_callback); UNUSED_PARAMETER(param);
	return NULL;
}

void update_info_destroy(update_info_t *info)
{
	UNUSED_PARAMETER(info);
}

const char *nimotv_get_ingest(const char *key)
{
	UNUSED_PARAMETER(key);
	return NULL;
}

struct showroom_ingest *showroom_get_ingest(const char *server, const char *access_key)
{
	UNUSED_PARAMETER(server);
	UNUSED_PARAMETER(access_key);
	return NULL;
}

void free_showroom_data(void) {}
