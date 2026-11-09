/* SPDX-License-Identifier: MIT
 * Original Muxi bridge. The included SameBoy implementation remains its authors' MIT code.
 * Build against exactly libretro/SameBoy 90e2899574b7b63c59dce8a7276783426d52a068.
 * No emulation or serial scheduler is replaced: retro_load_game_special(0x101) still runs both CPUs.
 */
#include "libretro/libretro.c"

#ifdef _WIN32
#define MUXI_EXPORT __declspec(dllexport)
#else
#define MUXI_EXPORT __attribute__((visibility("default")))
#endif

#define MUXI_LINK_AUDIO_SHORTS 8192
static int16_t muxi_audio[2][MUXI_LINK_AUDIO_SHORTS];
static unsigned muxi_audio_count[2];
static bool muxi_audio_overflow;

static void muxi_sample_tap(GB_gameboy_t *gb, GB_sample_t *sample)
{
    unsigned endpoint = gb == &gameboy[1] ? 1 : 0;
    unsigned count = muxi_audio_count[endpoint];
    if (count + 2 <= MUXI_LINK_AUDIO_SHORTS) {
        muxi_audio[endpoint][count] = sample->left;
        muxi_audio[endpoint][count + 1] = sample->right;
        muxi_audio_count[endpoint] = count + 2;
    }
    else muxi_audio_overflow = true;
    /* Preserve the existing libretro frontend's audio bookkeeping and timing exactly. */
    audio_callback(gb, sample);
}

MUXI_EXPORT unsigned muxi_link_abi(void) { return 1; }
MUXI_EXPORT void muxi_link_audio_clear(void)
{
    muxi_audio_count[0] = muxi_audio_count[1] = 0;
    muxi_audio_overflow = false;
}
MUXI_EXPORT bool muxi_link_audio_attach(void)
{
    if (emulated_devices != 2 || !GB_is_inited(&gameboy[0]) || !GB_is_inited(&gameboy[1])) return false;
    muxi_link_audio_clear();
    GB_apu_set_sample_callback(&gameboy[0], muxi_sample_tap);
    GB_apu_set_sample_callback(&gameboy[1], muxi_sample_tap);
    return true;
}
MUXI_EXPORT bool muxi_link_audio_did_overflow(void) { return muxi_audio_overflow; }
MUXI_EXPORT int muxi_link_audio_read(unsigned endpoint, int16_t *samples, unsigned capacity)
{
    if (endpoint >= 2 || !samples || capacity < muxi_audio_count[endpoint]) return -1;
    unsigned count = muxi_audio_count[endpoint];
    memcpy(samples, muxi_audio[endpoint], count * sizeof(int16_t));
    return (int)count;
}
MUXI_EXPORT size_t muxi_link_endpoint_state_size(unsigned endpoint)
{
    if (endpoint >= 2 || emulated_devices != 2 || !GB_is_inited(&gameboy[endpoint])) return 0;
    return GB_get_save_state_size(&gameboy[endpoint]);
}
MUXI_EXPORT bool muxi_link_endpoint_state_save(unsigned endpoint, void *data, size_t capacity)
{
    size_t needed = muxi_link_endpoint_state_size(endpoint);
    if (!needed || !data || capacity < needed) return false;
    memset(data, 0, needed);
    GB_save_state_to_buffer(&gameboy[endpoint], data);
    return true;
}
MUXI_EXPORT bool muxi_link_endpoint_state_load(unsigned endpoint, const void *data, size_t size)
{
    if (!muxi_link_endpoint_state_size(endpoint) || !data || size < 16) return false;
    return GB_load_state_from_buffer(&gameboy[endpoint], data, size) == 0;
}
/* These two frontend serial bits are outside GB's own serialized sections. */
MUXI_EXPORT unsigned muxi_link_bus_state(void)
{
    return (bit_to_send1 ? 1u : 0u) | (bit_to_send2 ? 2u : 0u);
}
MUXI_EXPORT bool muxi_link_bus_restore(unsigned bits)
{
    if (bits > 3 || emulated_devices != 2) return false;
    bit_to_send1 = (bits & 1) != 0;
    bit_to_send2 = (bits & 2) != 0;
    output_audio_buffer.sizes[0] = output_audio_buffer.sizes[1] = 0;
    return muxi_link_audio_attach();
}
