[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string] $SourceOuterJar,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9A-Fa-f]{64}$')][string] $ExpectedOuterSha256,
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string] $PatchedInnerJar,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9A-Fa-f]{64}$')][string] $ExpectedPatchedInnerSha256,
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string] $NestedInnerPath,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9A-Fa-f]{64}$')][string] $ExpectedOriginalNestedInnerSha256,
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string] $OutputOuterJar,
    [switch] $Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'Packaging.Common.ps1')

$patchRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$controlledRoot = Join-Path $patchRoot 'outputs'
$sourcePath = Resolve-ExistingPackagingFile -Path $SourceOuterJar -Label 'Exact source outer VersionPack JAR'
$innerPath = Resolve-ExistingPackagingFile -Path $PatchedInnerJar -Label 'Patched inner JAR'
$outputPath = Resolve-ControlledOutputPath -Path $OutputOuterJar -AllowedRoot $controlledRoot -Label 'Patched outer output'

if ([string]::Equals($sourcePath, $innerPath, [System.StringComparison]::OrdinalIgnoreCase) -or
    [string]::Equals($sourcePath, $outputPath, [System.StringComparison]::OrdinalIgnoreCase) -or
    [string]::Equals($innerPath, $outputPath, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Source outer, patched inner, and output paths must all be different.'
}
if (-not $NestedInnerPath.StartsWith('META-INF/jars/', [System.StringComparison]::Ordinal) -or
    -not $NestedInnerPath.EndsWith('.jar', [System.StringComparison]::OrdinalIgnoreCase) -or
    $NestedInnerPath.Contains('..') -or
    $NestedInnerPath.Contains('\')) {
    throw "NestedInnerPath must be a normalized META-INF/jars/*.jar ZIP path: $NestedInnerPath"
}
Assert-ControlledArtifactAvailability -OutputJar $outputPath -Force:$Force

$outerHash = Assert-PackagingSha256 -Path $sourcePath -Expected $ExpectedOuterSha256 -Label 'Exact source outer VersionPack JAR'
$patchedInnerHash = Assert-PackagingSha256 -Path $innerPath -Expected $ExpectedPatchedInnerSha256 -Label 'Patched inner JAR'
$temporaryPath = New-ControlledTemporaryPath -OutputPath $outputPath

try {
    $sourceArchive = [System.IO.Compression.ZipFile]::OpenRead($sourcePath)
    try {
        $sourceEntries = Get-ZipEntryMap -Archive $sourceArchive -Label 'Exact source outer VersionPack JAR'
        Assert-NoJarSignatures -EntryMap $sourceEntries -Label 'Exact source outer VersionPack JAR'
        if (-not $sourceEntries.ContainsKey($NestedInnerPath)) {
            throw "Outer VersionPack does not contain exact nested entry '$NestedInnerPath'."
        }

        $originalNestedHash = Get-BytesSha256 -Bytes (Read-ZipEntryBytes -Entry $sourceEntries[$NestedInnerPath])
        $expectedNestedHash = $ExpectedOriginalNestedInnerSha256.ToUpperInvariant()
        if (-not [string]::Equals($originalNestedHash, $expectedNestedHash, [System.StringComparison]::Ordinal)) {
            throw "Original nested inner SHA-256 mismatch. Expected $expectedNestedHash but found $originalNestedHash."
        }

        [byte[]] $patchedInnerBytes = [System.IO.File]::ReadAllBytes($innerPath)
        $outputArchive = [System.IO.Compression.ZipFile]::Open(
            $temporaryPath,
            [System.IO.Compression.ZipArchiveMode]::Create
        )
        try {
            foreach ($sourceEntry in $sourceArchive.Entries) {
                $bytes = if ($sourceEntry.FullName -eq $NestedInnerPath) {
                    $patchedInnerBytes
                }
                else {
                    Read-ZipEntryBytes -Entry $sourceEntry
                }
                Add-ZipEntryFromBytes -Archive $outputArchive -Name $sourceEntry.FullName -Bytes $bytes -TemplateEntry $sourceEntry | Out-Null
            }
        }
        finally {
            $outputArchive.Dispose()
        }
    }
    finally {
        $sourceArchive.Dispose()
    }

    $sourceArchive = [System.IO.Compression.ZipFile]::OpenRead($sourcePath)
    $candidateArchive = [System.IO.Compression.ZipFile]::OpenRead($temporaryPath)
    try {
        $sourceEntries = Get-ZipEntryMap -Archive $sourceArchive -Label 'Exact source outer VersionPack JAR'
        $candidateEntries = Get-ZipEntryMap -Archive $candidateArchive -Label 'Patched outer candidate'
        Assert-ZipEntrySetsEqual -ExpectedMap $sourceEntries -ActualMap $candidateEntries -Label 'Patched outer candidate'

        foreach ($name in $sourceEntries.Keys) {
            if ($name -ne $NestedInnerPath) {
                Assert-ZipEntryContentEqual `
                    -ExpectedEntry $sourceEntries[$name] `
                    -ActualEntry $candidateEntries[$name] `
                    -Label "Preserved outer entry '$name'"
            }
        }

        $candidateInnerHash = Get-BytesSha256 -Bytes (Read-ZipEntryBytes -Entry $candidateEntries[$NestedInnerPath])
        if (-not [string]::Equals($candidateInnerHash, $patchedInnerHash, [System.StringComparison]::Ordinal)) {
            throw "Patched outer contains wrong nested inner. Expected $patchedInnerHash but found $candidateInnerHash."
        }
    }
    finally {
        $candidateArchive.Dispose()
        $sourceArchive.Dispose()
    }

    Publish-ControlledFile -TemporaryPath $temporaryPath -OutputPath $outputPath -Force:$Force
    $outputHash = (Get-FileHash -LiteralPath $outputPath -Algorithm SHA256).Hash.ToUpperInvariant()
    $hashManifest = Write-PackagingHashManifest -OutputJar $outputPath -Force:$Force -Payload ([ordered]@{
        schemaVersion = 1
        operation = 'assemble-versionpack'
        createdUtc = [DateTime]::UtcNow.ToString('o')
        inputs = @(
            [ordered]@{ role = 'exact-outer'; path = $sourcePath; sha256 = $outerHash },
            [ordered]@{ role = 'patched-inner'; path = $innerPath; sha256 = $patchedInnerHash }
        )
        originalNestedInner = [ordered]@{
            entry = $NestedInnerPath
            sha256 = $ExpectedOriginalNestedInnerSha256.ToUpperInvariant()
        }
        outputs = @([ordered]@{ role = 'patched-versionpack'; path = $outputPath; sha256 = $outputHash })
        replacedEntries = @($NestedInnerPath)
    })

    [pscustomobject]@{
        OutputJar = $outputPath
        Sha256 = $outputHash
        HashManifest = $hashManifest
        ReplacedNestedEntry = $NestedInnerPath
    }
}
finally {
    if (Test-Path -LiteralPath $temporaryPath) {
        Remove-Item -LiteralPath $temporaryPath -Force -ErrorAction SilentlyContinue
    }
}
