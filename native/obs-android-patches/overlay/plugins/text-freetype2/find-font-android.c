#include "find-font.h"
#include <obs.h>
#include <util/platform.h>
#include <string.h>

bool load_cached_os_font_list(void) { return true; }
void load_os_font_list(void) {}
void free_os_font_list(void) {}

static const char *try_font(const char *path)
{
	return os_file_exists(path) ? path : NULL;
}

const char *get_font_path(const char *family, uint16_t size, const char *style, uint32_t flags, FT_Long *idx)
{
	UNUSED_PARAMETER(size);
	if (idx)
		*idx = 0;

	/* Allow direct .ttf/.otf file paths in family */
	if (family && family[0] == '/' && os_file_exists(family))
		return family;

	bool bold = (flags & OBS_FONT_BOLD) != 0 || (style && strstr(style, "Bold") != NULL);
	bool italic = (flags & OBS_FONT_ITALIC) != 0 || (style && strstr(style, "Italic") != NULL);

	const char *p = NULL;
	if (bold && italic)
		p = try_font("/system/fonts/Roboto-BoldItalic.ttf");
	else if (bold)
		p = try_font("/system/fonts/Roboto-Bold.ttf");
	else if (italic)
		p = try_font("/system/fonts/Roboto-Italic.ttf");

	if (!p)
		p = try_font("/system/fonts/Roboto-Regular.ttf");
	if (!p)
		p = try_font("/system/fonts/RobotoStatic-Regular.ttf");
	if (!p)
		p = try_font("/system/fonts/DroidSans.ttf");
	return p;
}
