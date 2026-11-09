/* Original Muxi disc diagnostic loader, MIT. No vendor apploader code or data.
 * The disc builder supplies the exact public DOL segment addresses and offsets.
 * Functions follow the disc apploader entry/init/read/close calling convention.
 */
#include "wii-disc-layout.h"
typedef unsigned u32;
static unsigned next_section;

static void loader_init(void *report) {
    (void)report;
    next_section = 0;
}

static int loader_read(void **destination, u32 *length, u32 *disc_words) {
    if (next_section >= MUXI_SECTION_COUNT) return 0;
    const struct muxi_section *section = &muxi_sections[next_section++];
    *destination = (void *)section->address;
    *length = section->length;
    *disc_words = section->disc_words;
    return 1;
}

static void *loader_close(void) {
    volatile unsigned char *bss = (volatile unsigned char *)MUXI_BSS_ADDRESS;
    for (u32 i = 0; i < MUXI_BSS_LENGTH; ++i) bss[i] = 0;
    return (void *)MUXI_DOL_ENTRY;
}

void apploader_entry(void (**init)(void *), int (**read)(void **, u32 *, u32 *), void *(**close)(void)) {
    *init = loader_init;
    *read = loader_read;
    *close = loader_close;
}
