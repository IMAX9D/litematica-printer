[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string] $SourceInnerJar,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9A-Fa-f]{64}$')][string] $ExpectedSourceSha256,
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string] $RemappedPatchJar,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9A-Fa-f]{64}$')][string] $ExpectedPatchSha256,
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string] $OutputInnerJar,
    [ValidateNotNullOrEmpty()][string] $MixinConfigName = 'litematica-printer-maintenance.mixins.json',
    [ValidateNotNullOrEmpty()][string] $ExpectedModId = 'litematica-printer',
    [ValidateNotNullOrEmpty()][string] $ExpectedMinecraftVersion = '1.20.1',
    [ValidateNotNullOrEmpty()][string] $ExpectedSourceModVersion = '1.3-beta.3+260531+build.365',
    [ValidateNotNullOrEmpty()][string] $PatchedModVersion = '1.3.0-beta.3.tombridge.1+260531.build.365',
    [switch] $Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'Packaging.Common.ps1')

$patchRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$controlledRoot = Join-Path $patchRoot 'outputs'
$sourcePath = Resolve-ExistingPackagingFile -Path $SourceInnerJar -Label 'Exact source inner JAR'
$patchPath = Resolve-ExistingPackagingFile -Path $RemappedPatchJar -Label 'Remapped maintenance patch JAR'
$outputPath = Resolve-ControlledOutputPath -Path $OutputInnerJar -AllowedRoot $controlledRoot -Label 'Patched inner output'

if ([string]::Equals($sourcePath, $patchPath, [System.StringComparison]::OrdinalIgnoreCase) -or
    [string]::Equals($sourcePath, $outputPath, [System.StringComparison]::OrdinalIgnoreCase) -or
    [string]::Equals($patchPath, $outputPath, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Source, patch, and output paths must all be different.'
}
Assert-ControlledArtifactAvailability -OutputJar $outputPath -Force:$Force

$sourceHash = Assert-PackagingSha256 -Path $sourcePath -Expected $ExpectedSourceSha256 -Label 'Exact source inner JAR'
$patchHash = Assert-PackagingSha256 -Path $patchPath -Expected $ExpectedPatchSha256 -Label 'Remapped maintenance patch JAR'
$temporaryPath = New-ControlledTemporaryPath -OutputPath $outputPath
$metadataEntryName = 'fabric.mod.json'
$ignoredPatchEntries = @('META-INF/MANIFEST.MF', 'fabric.mod.json')
$allowedReplacementEntries = @('me/aleksilassila/litematica/printer/printer/ActionManager.class')
$requiredPatchEntries = @(
    $MixinConfigName,
    'me/aleksilassila/litematica/printer/printer/ActionManager.class',
    'me/aleksilassila/litematica/printer/api/PrinterIntegrationApi.class',
    'me/aleksilassila/litematica/printer/api/LegacyScanControl.class',
    'me/aleksilassila/litematica/printer/api/PrinterPlacementFacade.class',
    'me/aleksilassila/litematica/printer/integration/LegacyRenderLayerScanBounds.class',
    'me/aleksilassila/litematica/printer/integration/LegacyRenderLayerScanBounds$Bounds.class',
    'me/aleksilassila/litematica/printer/integration/mixin/ClientPlayerTickHandlerLegacyScanMixin.class',
    'me/aleksilassila/litematica/printer/integration/mixin/PrintHandlerPlacementFacadeMixin.class'
)

try {
    $sourceArchive = [System.IO.Compression.ZipFile]::OpenRead($sourcePath)
    $patchArchive = [System.IO.Compression.ZipFile]::OpenRead($patchPath)
    try {
        $sourceEntries = Get-ZipEntryMap -Archive $sourceArchive -Label 'Exact source inner JAR'
        $patchEntries = Get-ZipEntryMap -Archive $patchArchive -Label 'Remapped maintenance patch JAR'
        Assert-NoJarSignatures -EntryMap $sourceEntries -Label 'Exact source inner JAR'
        Assert-NoJarSignatures -EntryMap $patchEntries -Label 'Remapped maintenance patch JAR'

        if (-not $sourceEntries.ContainsKey($metadataEntryName)) {
            throw "Exact source inner JAR has no '$metadataEntryName'."
        }
        foreach ($requiredEntry in $requiredPatchEntries) {
            if (-not $patchEntries.ContainsKey($requiredEntry)) {
                throw "Remapped maintenance patch JAR is missing required entry '$requiredEntry'."
            }
        }

        $patchPayloadNames = @($patchEntries.Keys | Where-Object {
            -not $_.EndsWith('/', [System.StringComparison]::Ordinal) -and
            $ignoredPatchEntries -notcontains $_
        })
        $replacementNames = @($patchPayloadNames | Where-Object { $sourceEntries.ContainsKey($_) })
        $unapprovedReplacements = @($replacementNames | Where-Object { $allowedReplacementEntries -notcontains $_ })
        if ($unapprovedReplacements.Count -gt 0) {
            throw "Patch would overwrite unapproved baseline entries: $($unapprovedReplacements -join ', ')"
        }

        $metadataEncoding = New-Object System.Text.UTF8Encoding($false, $true)
        $metadataText = $metadataEncoding.GetString((Read-ZipEntryBytes -Entry $sourceEntries[$metadataEntryName]))
        $metadata = $metadataText | ConvertFrom-Json
        if (-not [string]::Equals([string]$metadata.id, $ExpectedModId, [System.StringComparison]::Ordinal)) {
            throw "Unexpected Fabric mod id. Expected '$ExpectedModId' but found '$($metadata.id)'."
        }
        if (-not [string]::Equals([string]$metadata.version, $ExpectedSourceModVersion,
                [System.StringComparison]::Ordinal)) {
            throw "Unexpected source mod version. Expected '$ExpectedSourceModVersion' but found '$($metadata.version)'."
        }
        if ($null -eq $metadata.depends -or
            -not [string]::Equals([string]$metadata.depends.minecraft, $ExpectedMinecraftVersion, [System.StringComparison]::Ordinal)) {
            throw "Unexpected Minecraft dependency. Expected '$ExpectedMinecraftVersion'."
        }

        $originalMixinConfigs = @($metadata.mixins | ForEach-Object { [string] $_ })
        if ($originalMixinConfigs -notcontains $MixinConfigName) {
            $metadata.mixins = @($originalMixinConfigs + $MixinConfigName)
        }
        $metadata.version = $PatchedModVersion
        $updatedMetadataText = ($metadata | ConvertTo-Json -Depth 100) + [Environment]::NewLine
        $updatedMetadataBytes = $metadataEncoding.GetBytes($updatedMetadataText)

        $outputArchive = [System.IO.Compression.ZipFile]::Open(
            $temporaryPath,
            [System.IO.Compression.ZipArchiveMode]::Create
        )
        try {
            foreach ($sourceEntry in $sourceArchive.Entries) {
                if ($sourceEntry.FullName -eq $metadataEntryName) {
                    Add-ZipEntryFromBytes -Archive $outputArchive -Name $sourceEntry.FullName -Bytes $updatedMetadataBytes -TemplateEntry $sourceEntry | Out-Null
                }
                elseif ($patchEntries.ContainsKey($sourceEntry.FullName) -and $patchPayloadNames -contains $sourceEntry.FullName) {
                    $patchEntry = $patchEntries[$sourceEntry.FullName]
                    Add-ZipEntryFromBytes -Archive $outputArchive -Name $sourceEntry.FullName -Bytes (Read-ZipEntryBytes -Entry $patchEntry) -TemplateEntry $patchEntry | Out-Null
                }
                else {
                    Add-ZipEntryFromBytes -Archive $outputArchive -Name $sourceEntry.FullName -Bytes (Read-ZipEntryBytes -Entry $sourceEntry) -TemplateEntry $sourceEntry | Out-Null
                }
            }

            foreach ($name in ($patchPayloadNames | Sort-Object)) {
                if (-not $sourceEntries.ContainsKey($name)) {
                    $patchEntry = $patchEntries[$name]
                    Add-ZipEntryFromBytes -Archive $outputArchive -Name $name -Bytes (Read-ZipEntryBytes -Entry $patchEntry) -TemplateEntry $patchEntry | Out-Null
                }
            }
        }
        finally {
            $outputArchive.Dispose()
        }
    }
    finally {
        $patchArchive.Dispose()
        $sourceArchive.Dispose()
    }

    $sourceArchive = [System.IO.Compression.ZipFile]::OpenRead($sourcePath)
    $patchArchive = [System.IO.Compression.ZipFile]::OpenRead($patchPath)
    $candidateArchive = [System.IO.Compression.ZipFile]::OpenRead($temporaryPath)
    try {
        $sourceEntries = Get-ZipEntryMap -Archive $sourceArchive -Label 'Exact source inner JAR'
        $patchEntries = Get-ZipEntryMap -Archive $patchArchive -Label 'Remapped maintenance patch JAR'
        $candidateEntries = Get-ZipEntryMap -Archive $candidateArchive -Label 'Patched inner candidate'

        foreach ($name in $sourceEntries.Keys) {
            if ($name -ne $metadataEntryName -and $replacementNames -notcontains $name) {
                Assert-ZipEntryContentEqual `
                    -ExpectedEntry $sourceEntries[$name] `
                    -ActualEntry $candidateEntries[$name] `
                    -Label "Preserved baseline entry '$name'"
            }
        }
        foreach ($name in $patchPayloadNames) {
            if (-not $candidateEntries.ContainsKey($name)) {
                throw "Patched inner candidate is missing patch entry '$name'."
            }
            Assert-ZipEntryContentEqual `
                -ExpectedEntry $patchEntries[$name] `
                -ActualEntry $candidateEntries[$name] `
                -Label "Merged patch entry '$name'"
        }

        $candidateMetadataText = $metadataEncoding.GetString((Read-ZipEntryBytes -Entry $candidateEntries[$metadataEntryName]))
        $candidateMetadata = $candidateMetadataText | ConvertFrom-Json
        $candidateMixins = @($candidateMetadata.mixins | ForEach-Object { [string] $_ })
        foreach ($existingMixin in $originalMixinConfigs) {
            if ($candidateMixins -notcontains $existingMixin) {
                throw "Patched metadata lost existing mixin config '$existingMixin'."
            }
        }
        if (@($candidateMixins | Where-Object { $_ -eq $MixinConfigName }).Count -ne 1) {
            throw "Patched metadata must contain exactly one '$MixinConfigName' entry."
        }
        if (-not [string]::Equals([string]$candidateMetadata.version, $PatchedModVersion,
                [System.StringComparison]::Ordinal)) {
            throw "Patched metadata version is not '$PatchedModVersion'."
        }
    }
    finally {
        $candidateArchive.Dispose()
        $patchArchive.Dispose()
        $sourceArchive.Dispose()
    }

    Publish-ControlledFile -TemporaryPath $temporaryPath -OutputPath $outputPath -Force:$Force
    $outputHash = (Get-FileHash -LiteralPath $outputPath -Algorithm SHA256).Hash.ToUpperInvariant()
    $hashManifest = Write-PackagingHashManifest -OutputJar $outputPath -Force:$Force -Payload ([ordered]@{
        schemaVersion = 1
        operation = 'merge-remapped-maintenance-patch'
        createdUtc = [DateTime]::UtcNow.ToString('o')
        inputs = @(
            [ordered]@{ role = 'exact-inner'; path = $sourcePath; sha256 = $sourceHash },
            [ordered]@{ role = 'remapped-patch'; path = $patchPath; sha256 = $patchHash }
        )
        outputs = @([ordered]@{ role = 'patched-inner'; path = $outputPath; sha256 = $outputHash })
        addedMixinConfig = $MixinConfigName
        sourceModVersion = $ExpectedSourceModVersion
        patchedModVersion = $PatchedModVersion
        replacedEntries = @($replacementNames | Sort-Object)
        mergedPatchEntries = @($patchPayloadNames | Sort-Object)
    })

    [pscustomobject]@{
        OutputJar = $outputPath
        Sha256 = $outputHash
        HashManifest = $hashManifest
        ReplacedEntries = @($replacementNames | Sort-Object)
        MergedPatchEntryCount = $patchPayloadNames.Count
    }
}
finally {
    if (Test-Path -LiteralPath $temporaryPath) {
        Remove-Item -LiteralPath $temporaryPath -Force -ErrorAction SilentlyContinue
    }
}
