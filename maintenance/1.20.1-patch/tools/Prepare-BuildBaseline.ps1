[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string] $SourceInnerJar,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9A-Fa-f]{64}$')][string] $ExpectedSourceSha256,
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string] $OutputJar,
    [ValidateNotNullOrEmpty()][string] $ExpectedOriginalLoomVersion = '1.15.5',
    [ValidateNotNullOrEmpty()][string] $BuildLoomVersion = '1.8.13',
    [switch] $Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'Packaging.Common.ps1')

$patchRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$controlledRoot = Join-Path $patchRoot 'build\baseline'
$sourcePath = Resolve-ExistingPackagingFile -Path $SourceInnerJar -Label 'Exact source inner JAR'
$outputPath = Resolve-ControlledOutputPath -Path $OutputJar -AllowedRoot $controlledRoot -Label 'Build baseline output'

if ([string]::Equals($sourcePath, $outputPath, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Source and output paths must be different.'
}
Assert-ControlledArtifactAvailability -OutputJar $outputPath -Force:$Force

$sourceHash = Assert-PackagingSha256 -Path $sourcePath -Expected $ExpectedSourceSha256 -Label 'Exact source inner JAR'
$temporaryPath = New-ControlledTemporaryPath -OutputPath $outputPath
$manifestEntryName = 'META-INF/MANIFEST.MF'

try {
    $sourceArchive = [System.IO.Compression.ZipFile]::OpenRead($sourcePath)
    try {
        $sourceEntries = Get-ZipEntryMap -Archive $sourceArchive -Label 'Exact source inner JAR'
        Assert-NoJarSignatures -EntryMap $sourceEntries -Label 'Exact source inner JAR'
        if (-not $sourceEntries.ContainsKey($manifestEntryName)) {
            throw "Exact source inner JAR has no '$manifestEntryName'."
        }

        $manifestBytes = Read-ZipEntryBytes -Entry $sourceEntries[$manifestEntryName]
        $encoding = New-Object System.Text.UTF8Encoding($false, $true)
        $manifestText = $encoding.GetString($manifestBytes)
        $pattern = '(?m)^Fabric-Loom-Version:[ \t]*([^\r\n]+)(\r?)$'
        $matches = [regex]::Matches($manifestText, $pattern)
        if ($matches.Count -ne 1) {
            throw "Expected exactly one Fabric-Loom-Version line in '$manifestEntryName', found $($matches.Count)."
        }

        $actualLoomVersion = $matches[0].Groups[1].Value.Trim()
        if (-not [string]::Equals($actualLoomVersion, $ExpectedOriginalLoomVersion, [System.StringComparison]::Ordinal)) {
            throw "Unexpected original Fabric-Loom-Version. Expected '$ExpectedOriginalLoomVersion' but found '$actualLoomVersion'."
        }

        $replacementEvaluator = [System.Text.RegularExpressions.MatchEvaluator] {
            param($match)
            return 'Fabric-Loom-Version: ' + $BuildLoomVersion + $match.Groups[2].Value
        }
        $updatedManifestText = [regex]::Replace($manifestText, $pattern, $replacementEvaluator, 1)
        $updatedManifestBytes = $encoding.GetBytes($updatedManifestText)

        $outputArchive = [System.IO.Compression.ZipFile]::Open(
            $temporaryPath,
            [System.IO.Compression.ZipArchiveMode]::Create
        )
        try {
            foreach ($entry in $sourceArchive.Entries) {
                $bytes = if ($entry.FullName -eq $manifestEntryName) {
                    $updatedManifestBytes
                }
                else {
                    Read-ZipEntryBytes -Entry $entry
                }
                Add-ZipEntryFromBytes -Archive $outputArchive -Name $entry.FullName -Bytes $bytes -TemplateEntry $entry | Out-Null
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
        $sourceEntries = Get-ZipEntryMap -Archive $sourceArchive -Label 'Exact source inner JAR'
        $candidateEntries = Get-ZipEntryMap -Archive $candidateArchive -Label 'Build baseline candidate'
        Assert-ZipEntrySetsEqual -ExpectedMap $sourceEntries -ActualMap $candidateEntries -Label 'Build baseline candidate'

        foreach ($name in $sourceEntries.Keys) {
            if ($name -ne $manifestEntryName) {
                Assert-ZipEntryContentEqual `
                    -ExpectedEntry $sourceEntries[$name] `
                    -ActualEntry $candidateEntries[$name] `
                    -Label "Unmodified entry '$name'"
            }
        }

        $candidateManifest = $encoding.GetString((Read-ZipEntryBytes -Entry $candidateEntries[$manifestEntryName]))
        $expectedLine = 'Fabric-Loom-Version: ' + $BuildLoomVersion
        if ($candidateManifest -notmatch ('(?m)^' + [regex]::Escape($expectedLine) + '\r?$')) {
            throw "Build baseline manifest does not contain expected line '$expectedLine'."
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
        operation = 'prepare-offline-build-baseline'
        createdUtc = [DateTime]::UtcNow.ToString('o')
        inputs = @([ordered]@{ path = $sourcePath; sha256 = $sourceHash })
        outputs = @([ordered]@{ path = $outputPath; sha256 = $outputHash })
        changedEntries = @($manifestEntryName)
        originalFabricLoomVersion = $ExpectedOriginalLoomVersion
        buildFabricLoomVersion = $BuildLoomVersion
    })

    [pscustomobject]@{
        OutputJar = $outputPath
        Sha256 = $outputHash
        HashManifest = $hashManifest
    }
}
finally {
    if (Test-Path -LiteralPath $temporaryPath) {
        Remove-Item -LiteralPath $temporaryPath -Force -ErrorAction SilentlyContinue
    }
}
