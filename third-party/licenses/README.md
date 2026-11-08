# Third-party notices

Files below retain their upstream copyright and license text without edits. The
MIT license at the repository root applies to our own code, not these materials.
Each component's complete source archive preserves its original per-file headers,
vendored code, notices and build scripts; a directory-level license label does
not replace those more specific terms.

The exact source revisions, delivered archive hashes, recursive submodule
gitlinks and corresponding binary hashes are recorded in
[`../source_manifest.json`](../source_manifest.json). The release JAR contains
the complete source delivery at `META-INF/muxi/source-bundle.zip`, together with
the MIT Java worker/protocol source and independent build instructions. Extract
the ZIP and read `README-SOURCES.txt`. This is an included source copy, rather
than a written offer requiring a later request.

JNA 5.14.0 is distributed using its LGPL-2.1-or-later license alternative. Its
original dual-license notice, LGPL2.1 and AL2.0 texts are preserved. JNA remains
a separate, replaceable dependency JAR; modification and debugging of that
library are permitted by its license. LWJGL, GLFW, libffi and Khronos retain the
notices provided by their upstream source tree.

For MAME, `COPYING`, the full `docs/legal/` directory and vendor license files
are preserved. Its source tree has multiple licenses: the official README
declares the project as a whole GPL version 2 or later, and individual source
headers and `COPYING` govern the components. The binary's core-info label alone
is not a replacement for those notices. SameBoy's source archive likewise
retains its directory-specific exceptions to the main Expat license.

The build gate verifies source delivery and exact pins separately from gameplay
testing. Source provenance does not assert that every commercial game works, or
that the upstream rolling binaries have been reproduced byte for byte.
