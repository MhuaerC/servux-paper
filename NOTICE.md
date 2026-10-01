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
