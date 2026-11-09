# Dolphin SD checkpoint patch

Both patch files are derivative changes to Dolphin and are licensed under
**GPL-2.0-or-later**, matching that file's SPDX header. The upstream copyright
and license notices remain in the complete corresponding-source tree.

`dolphin-sd-checkpoint-sharing.patch` changes `SDIOSlot0.cpp` to allow read-only checkpoint access to the SD image
while the native core owns its writer, and flushing buffered SD writes before
serializing a checkpoint. It neither excludes the SD image from machine files
nor changes serialized fields. Old-state compatibility requires a real runtime
check; a successful native compilation alone does not establish it.

`dolphin-mingw-constexpr.patch` retains the same Windows `WSAGetLastError`
function pointer in `Socket.cpp`, using a const pointer instead of constexpr.
LLVM-MinGW imports that API through the Windows import table, whose address is
not a C++ constant expression. The call and runtime behavior are unchanged.

The original Python build and source-export drivers are MIT. Their license
does not replace Dolphin's license or any bundled dependency's terms.
