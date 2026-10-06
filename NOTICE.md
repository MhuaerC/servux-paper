# Source and license

This project is based on the Paper implementation by MattLavalleeMA:

- Repository: https://github.com/MattLavalleeMA/servux
- Branch: `26.2-PaperMC`
- Commit: `13642bc72085937798c215ec291a1ec6968aef64`
- Imported source: `paper/src/main`, Gradle wrapper and `LICENSE.txt`.

Servux was created by masa / maruohon and extended by sakura-ryoko and contributors.
The upstream Paper implementation and this derivative are licensed under LGPL-3.0;
see `LICENSE.txt`. Original author and source references are retained.

Changes by MhuaerC's repository on 2026-10-01: a standalone Paper-only build for
Minecraft 26.3, the 26.3 direct recipe codec, compatible custom-payload transport
and login handshakes, buffer cleanup, named-root Data Tag decoding, and GitHub
Actions integration checks. The previous implementation in this repository has
been replaced; its additional gameplay features are not part of this codebase.

The Gradle wrapper and launch scripts were refreshed from the official Gradle
9.7.1 release (commit `92f0512e7f06d84621afba191f75e265363890cf`).
Gradle is licensed under Apache-2.0; its original notices are retained in those
files. Source: https://github.com/gradle/gradle/tree/v9.7.1
License: https://www.apache.org/licenses/LICENSE-2.0

The original LGPL license text is retained verbatim; its publication date is not
the Minecraft compatibility date of this project.

Changes on 2026-10-05 add Paper integration for accurate placement protocol V3
and a standalone Syncmatica-compatible sharing service. The V3 property whitelist,
ordering and bit layout follow Servux / Litematica by masa and sakura-ryoko (LGPL-3.0):
https://github.com/sakura-ryoko/servux/blob/57834cc5add156cfec07ef5ab72e03fa771a953d/src/main/java/fi/dy/masa/servux/util/PlacementHandler.java

Syncmatica packet layouts and feature negotiation were referenced from End-Tech's
Syncmatica (CC0-1.0), commit `3fa9575207bddd51cce447140a2ebab6056c3e48`:
https://github.com/End-Tech/syncmatica
The Paper sharing implementation is new code; it implements the public wire protocol.

On 2026-10-06 the transport was updated to the Syncmatica 26.3 / 0.3.20 single-channel
envelope. The integration test downloads the unmodified SyncmaticaPacket and PacketType
codec sources from sakura-ryoko's Syncmatica fork (CC0-1.0), pinned to
`37a969e571aa4f7c5cc25ab80c49cca4e8a51cc7`, and verifies their SHA-256 hashes.
These sources and the minimal mod-constant test stub are only in the integration-test
JAR, never in the production plugin.
https://github.com/sakura-ryoko/syncmatica
