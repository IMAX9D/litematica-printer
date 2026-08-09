# Printer build.365 maintenance packaging

These scripts package the maintained 1.20.1 Printer patch without modifying the
Minecraft instance. They deliberately accept the original files as read-only
inputs and permit generated release artifacts only below this module's
`outputs` directory. The offline Loom build baseline is permitted only below
`build/baseline`.

Every input JAR is pinned by an explicit SHA-256 argument. Every generated JAR
gets a neighboring `.sha256.json` provenance manifest. A script stops before
publishing an artifact when a hash, expected entry, Mixin configuration, or
allowed output boundary does not match.

## Fixed build.365 identities

- Exact 1.20.1 inner SHA-256:
  `3F0C9450424360FDE6C27B1D4B980A54C94BD6795120C23E9A0EC083B729B47C`
- Exact multi-version outer SHA-256:
  `F0BB717994003A40ACE864636EA50800F7D2C5802B65E1E22FA4EE7EC5A96CEC`
- Exact outer entry:
  `META-INF/jars/1.20.1-1.3-beta.3+260531+build.365.jar`
- Original manifest Loom version: `1.15.5`
- Cached offline-compatible Loom version: `1.8.13`

The original inner hash is intentionally checked twice: once while producing
the patched inner and again against the bytes nested in the original outer
VersionPack. This prevents a similarly named processed JAR or another Minecraft
version from being packaged accidentally.

## 1. Prepare the offline build baseline

This operation copies the exact inner JAR and changes only
`Fabric-Loom-Version` in the build copy's manifest. It verifies that every other
ZIP entry has identical uncompressed content.

```powershell
$patchRoot = 'E:\Fabric\清水二改\Litematica-Printer-build365-source\maintenance\1.20.1-patch'
$exactInner = 'D:\MinePixel\MinePixel乌托邦3.5.1Fix\.minecraft\versions\乌托邦探险之旅3.5.1fix\.fabric\processedMods\litematica-printer-1.3-beta.3+261+build.365-631d774557958125.jar'
$baseline = Join-Path $patchRoot 'build\baseline\build365-loom18-input.jar'

& (Join-Path $patchRoot 'tools\Prepare-BuildBaseline.ps1') `
  -SourceInnerJar $exactInner `
  -ExpectedSourceSha256 '3F0C9450424360FDE6C27B1D4B980A54C94BD6795120C23E9A0EC083B729B47C' `
  -OutputJar $baseline `
  -ExpectedOriginalLoomVersion '1.15.5' `
  -BuildLoomVersion '1.8.13' `
  -Force
```

`-Force` is needed only when deliberately replacing an existing controlled
artifact. It never permits writing outside the controlled directory.

## 2. Build and merge the remapped maintenance patch

Run the module's already configured offline `remapJar` task first. Supply the
baseline prepared above as `printer_inner_jar`; the Litematica and Malilib paths
must point at the existing local 1.20.1 JARs. Do not use a downloaded substitute.

```powershell
$gradle = 'C:\Users\Administrator\.gradle\wrapper\dists\gradle-8.10-bin\deqhafrv1ntovfmgh0nh3npr9\gradle-8.10\bin\gradle.bat'
$litematica = 'D:\MinePixel\MinePixel乌托邦3.5.1Fix\.minecraft\versions\乌托邦探险之旅3.5.1fix\mods\投影MOD.jar'
$malilib = 'D:\MinePixel\MinePixel乌托邦3.5.1Fix\.minecraft\versions\乌托邦探险之旅3.5.1fix\mods\malilib-fabric-1.20.1-0.16.3.jar'

Push-Location $patchRoot
try {
  & $gradle remapJar --offline --no-daemon `
    "-Pprinter_inner_jar=$baseline" `
    "-Plitematica_jar=$litematica" `
    "-Pmalilib_jar=$malilib"
  if ($LASTEXITCODE -ne 0) { throw "remapJar failed with exit code $LASTEXITCODE" }
}
finally {
  Pop-Location
}
```

After `remapJar` succeeds, pin that exact output and merge it:

```powershell
$remappedPatch = Join-Path $patchRoot 'build\libs\litematica-printer-build365-maintenance-patch-1.0.0-remapped.jar'
$patchHash = (Get-FileHash -LiteralPath $remappedPatch -Algorithm SHA256).Hash
$patchedInner = Join-Path $patchRoot 'outputs\litematica-printer-1.20.1-build365-maintenance-inner.jar'

& (Join-Path $patchRoot 'tools\Merge-PrinterPatch.ps1') `
  -SourceInnerJar $exactInner `
  -ExpectedSourceSha256 '3F0C9450424360FDE6C27B1D4B980A54C94BD6795120C23E9A0EC083B729B47C' `
  -RemappedPatchJar $remappedPatch `
  -ExpectedPatchSha256 $patchHash `
  -OutputInnerJar $patchedInner `
  -MixinConfigName 'litematica-printer-maintenance.mixins.json' `
  -ExpectedModId 'litematica-printer' `
  -ExpectedMinecraftVersion '1.20.1' `
  -ExpectedSourceModVersion '1.3-beta.3+260531+build.365' `
  -PatchedModVersion '1.3.0-beta.3.tombridge.1+260531.build.365' `
  -Force
```

The merge ignores the patch JAR's build manifest, preserves the original inner
metadata except for the explicit maintenance version and appended Mixin config,
and only allows the known `ActionManager.class` replacement. The distinct
version lets the paired Tom JAR fail at Fabric dependency resolution when the
unpatched Printer is installed. Any other collision stops the operation.

## 3. Assemble the multi-version test JAR

Only the exact 1.20.1 nested inner is replaced. All other outer entries are
verified byte-for-byte at the uncompressed-content level.

```powershell
$exactOuter = 'D:\MinePixel\MinePixel乌托邦3.5.1Fix\.minecraft\versions\乌托邦探险之旅3.5.1fix\mods\litematica-printer-versionpack-1.3-beta.3 260531 build.365.jar'
$patchedInnerHash = (Get-FileHash -LiteralPath $patchedInner -Algorithm SHA256).Hash
$patchedOuter = Join-Path $patchRoot 'outputs\litematica-printer-versionpack-1.3-beta.3-build365-maintenance.jar'

& (Join-Path $patchRoot 'tools\Assemble-VersionPack.ps1') `
  -SourceOuterJar $exactOuter `
  -ExpectedOuterSha256 'F0BB717994003A40ACE864636EA50800F7D2C5802B65E1E22FA4EE7EC5A96CEC' `
  -PatchedInnerJar $patchedInner `
  -ExpectedPatchedInnerSha256 $patchedInnerHash `
  -NestedInnerPath 'META-INF/jars/1.20.1-1.3-beta.3+260531+build.365.jar' `
  -ExpectedOriginalNestedInnerSha256 '3F0C9450424360FDE6C27B1D4B980A54C94BD6795120C23E9A0EC083B729B47C' `
  -OutputOuterJar $patchedOuter `
  -Force
```

The final JAR and both packaging manifests remain under `outputs`. Installation
into a Minecraft `mods` directory is a separate, explicit release/test step and
is intentionally not implemented by these scripts.

## 4. Validate the paired Printer + Tom release

`Test-PairedRelease.ps1` is a read-only final-artifact gate. It does not run
Gradle, modify either JAR, or write to the Minecraft instance. Supply hashes
calculated from the exact final artifacts; do not reuse a hash from an earlier
build.

The check opens the exact 1.20.1 nested Printer JAR in memory and verifies its
own SHA-256, Fabric id/version/Mixin declaration, maintenance Mixin config, and
the Scheduler, placement API, gateway Mixin, and replacement ActionManager
classes. It separately verifies the Tom JAR metadata, explicit Printer bridge,
layered controller, refill/lease/transaction classes, and safety Mixins. The
two Mixin configs must both opt into required loading with
`injectors.defaultRequire` set to `1`; Tom's declaration must retain the
projection lifecycle, range, ActionManager, inventory, diagnostics, safety,
tick, and missing-item hooks, with their corresponding classes present. The
Tom dependency must be the exact scalar predicate formed from the same patched
Printer version, so the pair cannot silently accept the original build.365
inner JAR.

```powershell
$tomJar = 'E:\Fabric\清水二改\Toms-Storage-1.20.1-Fabric-JustLikeAe2-main\Toms-Storage-Fabric-1.20\build\libs\toms_storage_fabric-1.20-1.8.6.jar'
$outerHash = (Get-FileHash -LiteralPath $patchedOuter -Algorithm SHA256).Hash
$innerHash = (Get-FileHash -LiteralPath $patchedInner -Algorithm SHA256).Hash
$tomHash = (Get-FileHash -LiteralPath $tomJar -Algorithm SHA256).Hash

& (Join-Path $patchRoot 'tools\Test-PairedRelease.ps1') `
  -PrinterOuterJar $patchedOuter `
  -ExpectedPrinterOuterSha256 $outerHash `
  -NestedInnerPath 'META-INF/jars/1.20.1-1.3-beta.3+260531+build.365.jar' `
  -ExpectedNestedInnerSha256 $innerHash `
  -TomJar $tomJar `
  -ExpectedTomSha256 $tomHash `
  -ExpectedPatchedPrinterVersion '1.3.0-beta.3.tombridge.1+260531.build.365'
```

Success returns one object whose `Result` is `PAIRED_RELEASE_VALID`. Any hash,
nested path, metadata dependency, required class, or Mixin mismatch terminates
with an error before installation.

## Safety behavior

- Existing signed JARs are rejected because merging would invalidate them.
- Duplicate ZIP entry names are rejected.
- Source, patch, and output paths must differ.
- Temporary files are created beside the controlled output and renamed only
  after structural and content validation passes.
- Existing outputs are never replaced unless `-Force` is supplied.
- No script downloads dependencies, starts Gradle, launches Minecraft, or writes
  to a game directory.
- The paired-release validator is read-only and accepts final JARs from any
  explicit path; all other packaging scripts retain their controlled-output
  boundary.
