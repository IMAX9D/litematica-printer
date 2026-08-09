# Maintained build.365 source baseline

This working tree is the exact source snapshot for the Printer wrapper currently
used by the Minecraft 1.20.1 instance.

- Upstream: `https://github.com/BiliXWhite/litematica-printer`
- Upstream commit: `30262f90d7ea03f6427c4a28354f7c883e2b0dee`
- Upstream workflow run: `26710377813` (build 365, completed 2026-05-31)
- Wrapper artifact name: `litematica-printer-versionpack-1.3-beta.3+260531+build.365.jar`
- Installed wrapper SHA-256: `F0BB717994003A40ACE864636EA50800F7D2C5802B65E1E22FA4EE7EC5A96CEC`
- Installed wrapper size: `8,620,266` bytes
- Processed Minecraft 1.20.1 inner JAR SHA-256:
  `3F0C9450424360FDE6C27B1D4B980A54C94BD6795120C23E9A0EC083B729B47C`

The public `1.3-beta.3` release tag points at a later commit with a different
scanner structure. It is not a bytecode-equivalent maintenance baseline for the
installed build.365 artifact.

The snapshot was obtained from GitHub codeload for the commit above after the
ordinary Git smart-HTTP connection was unavailable. The upstream commit hash is
therefore recorded explicitly even though this local repository starts with a
snapshot commit.

## Build boundary

The upstream wrapper uses Gradle 9.5 and a multi-version preprocessing build.
Project maintenance should initially target only `:1.20.1:compileJava` and
`:1.20.1:remapJar`. Do not run the full wrapper build while iterating on the
Minecraft 1.20.1 integration.

No new Gradle distribution, JDK, Loom plugin, or dependency was downloaded when
this baseline was created. Until those exact dependencies already exist locally,
the installed inner JAR remains the bytecode oracle and Tom's Storage remains the
only module compiled in the current migration checkpoint.
