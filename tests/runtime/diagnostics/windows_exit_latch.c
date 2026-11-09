/* Original deterministic Windows exit-in-progress fixture, MIT. No emulator or game code. */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, LPVOID reserved) {
    (void)instance; (void)reserved;
    if (reason == DLL_PROCESS_DETACH) Sleep(INFINITE);
    return TRUE;
}
__declspec(dllexport) unsigned muxi_exit_latch_fixture(void) { return 1; }
