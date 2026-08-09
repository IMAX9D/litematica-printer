Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Resolve-ExistingPackagingFile {
    param(
        [Parameter(Mandatory = $true)][string] $Path,
        [Parameter(Mandatory = $true)][string] $Label
    )

    if ([string]::IsNullOrWhiteSpace($Path)) {
        throw "$Label path is empty."
    }

    $item = Get-Item -LiteralPath $Path -ErrorAction Stop
    if ($item.PSIsContainer) {
        throw "$Label must be a file: $($item.FullName)"
    }

    return [System.IO.Path]::GetFullPath($item.FullName)
}

function Resolve-ControlledOutputPath {
    param(
        [Parameter(Mandatory = $true)][string] $Path,
        [Parameter(Mandatory = $true)][string] $AllowedRoot,
        [Parameter(Mandatory = $true)][string] $Label
    )

    if ([string]::IsNullOrWhiteSpace($Path)) {
        throw "$Label path is empty."
    }

    $root = [System.IO.Path]::GetFullPath($AllowedRoot)
    $resolved = [System.IO.Path]::GetFullPath($Path)
    $rootPrefix = $root.TrimEnd([char[]] '\/') + [System.IO.Path]::DirectorySeparatorChar

    if (-not $resolved.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "$Label must stay below controlled directory '$root': $resolved"
    }
    if (-not [string]::Equals([System.IO.Path]::GetExtension($resolved), '.jar', [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "$Label must have a .jar extension: $resolved"
    }

    return $resolved
}

function Assert-PackagingSha256 {
    param(
        [Parameter(Mandatory = $true)][string] $Path,
        [Parameter(Mandatory = $true)][ValidatePattern('^[0-9A-Fa-f]{64}$')][string] $Expected,
        [Parameter(Mandatory = $true)][string] $Label
    )

    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256 -ErrorAction Stop).Hash.ToUpperInvariant()
    $normalizedExpected = $Expected.ToUpperInvariant()
    if (-not [string]::Equals($actual, $normalizedExpected, [System.StringComparison]::Ordinal)) {
        throw "$Label SHA-256 mismatch. Expected $normalizedExpected but found $actual at '$Path'."
    }

    return $actual
}

function Get-BytesSha256 {
    param([Parameter(Mandatory = $true)][AllowEmptyCollection()][byte[]] $Bytes)

    $algorithm = [System.Security.Cryptography.SHA256]::Create()
    try {
        return (($algorithm.ComputeHash($Bytes) | ForEach-Object { $_.ToString('x2') }) -join '').ToUpperInvariant()
    }
    finally {
        $algorithm.Dispose()
    }
}

function Read-ZipEntryBytes {
    param([Parameter(Mandatory = $true)] $Entry)

    $inputStream = $Entry.Open()
    $memory = New-Object System.IO.MemoryStream
    try {
        $inputStream.CopyTo($memory)
        return ,$memory.ToArray()
    }
    finally {
        $memory.Dispose()
        $inputStream.Dispose()
    }
}

function Get-ZipEntryMap {
    param(
        [Parameter(Mandatory = $true)] $Archive,
        [Parameter(Mandatory = $true)][string] $Label
    )

    $entries = New-Object 'System.Collections.Generic.Dictionary[string,System.Object]' ([System.StringComparer]::Ordinal)
    foreach ($entry in $Archive.Entries) {
        if ($entries.ContainsKey($entry.FullName)) {
            throw "$Label contains duplicate ZIP entry '$($entry.FullName)'. Deterministic packaging is not possible."
        }
        $entries.Add($entry.FullName, $entry)
    }
    return $entries
}

function Assert-NoJarSignatures {
    param(
        [Parameter(Mandatory = $true)] $EntryMap,
        [Parameter(Mandatory = $true)][string] $Label
    )

    $signatureEntries = @($EntryMap.Keys | Where-Object { $_ -match '^META-INF/[^/]+\.(SF|RSA|DSA|EC)$' })
    if ($signatureEntries.Count -gt 0) {
        throw "$Label is signed. Merging would invalidate these signatures: $($signatureEntries -join ', ')"
    }
}

function Add-ZipEntryFromBytes {
    param(
        [Parameter(Mandatory = $true)] $Archive,
        [Parameter(Mandatory = $true)][string] $Name,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][byte[]] $Bytes,
        $TemplateEntry = $null
    )

    $compression = [System.IO.Compression.CompressionLevel]::Optimal
    if ($Name.EndsWith('/', [System.StringComparison]::Ordinal)) {
        $compression = [System.IO.Compression.CompressionLevel]::NoCompression
    }

    $entry = $Archive.CreateEntry($Name, $compression)
    if ($null -ne $TemplateEntry) {
        $entry.LastWriteTime = $TemplateEntry.LastWriteTime
        $entry.ExternalAttributes = $TemplateEntry.ExternalAttributes
    }

    if ($Bytes.Length -gt 0) {
        $outputStream = $entry.Open()
        try {
            $outputStream.Write($Bytes, 0, $Bytes.Length)
        }
        finally {
            $outputStream.Dispose()
        }
    }

    return $entry
}

function New-ControlledTemporaryPath {
    param([Parameter(Mandatory = $true)][string] $OutputPath)

    $directory = [System.IO.Path]::GetDirectoryName($OutputPath)
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) {
        New-Item -ItemType Directory -Path $directory -Force -ErrorAction Stop | Out-Null
    }

    return [System.IO.Path]::Combine(
        $directory,
        '.' + [System.IO.Path]::GetFileName($OutputPath) + '.' + [guid]::NewGuid().ToString('N') + '.tmp'
    )
}

function Assert-ControlledArtifactAvailability {
    param(
        [Parameter(Mandatory = $true)][string] $OutputJar,
        [switch] $Force
    )

    $existing = @(@($OutputJar, $OutputJar + '.sha256.json') | Where-Object { Test-Path -LiteralPath $_ })
    if ($existing.Count -gt 0 -and -not $Force) {
        throw "Controlled output already exists. Re-run with -Force to replace it: $($existing -join ', ')"
    }
}

function Publish-ControlledFile {
    param(
        [Parameter(Mandatory = $true)][string] $TemporaryPath,
        [Parameter(Mandatory = $true)][string] $OutputPath,
        [switch] $Force
    )

    if (Test-Path -LiteralPath $OutputPath) {
        if (-not $Force) {
            throw "Output already exists. Re-run with -Force to replace this controlled artifact: $OutputPath"
        }
        Remove-Item -LiteralPath $OutputPath -Force -ErrorAction Stop
    }

    [System.IO.File]::Move($TemporaryPath, $OutputPath)
}

function Write-PackagingHashManifest {
    param(
        [Parameter(Mandatory = $true)][string] $OutputJar,
        [Parameter(Mandatory = $true)] $Payload,
        [switch] $Force
    )

    $manifestPath = $OutputJar + '.sha256.json'
    $temporaryPath = New-ControlledTemporaryPath -OutputPath $manifestPath
    try {
        $json = $Payload | ConvertTo-Json -Depth 100
        # A BOM keeps non-ASCII Windows paths readable in Windows PowerShell 5.1.
        $encoding = New-Object System.Text.UTF8Encoding($true)
        [System.IO.File]::WriteAllText($temporaryPath, $json + [Environment]::NewLine, $encoding)

        if (Test-Path -LiteralPath $manifestPath) {
            if (-not $Force) {
                throw "Hash manifest already exists. Re-run with -Force to replace it: $manifestPath"
            }
            Remove-Item -LiteralPath $manifestPath -Force -ErrorAction Stop
        }
        [System.IO.File]::Move($temporaryPath, $manifestPath)
    }
    finally {
        if (Test-Path -LiteralPath $temporaryPath) {
            Remove-Item -LiteralPath $temporaryPath -Force -ErrorAction SilentlyContinue
        }
    }

    return $manifestPath
}

function Assert-ZipEntrySetsEqual {
    param(
        [Parameter(Mandatory = $true)] $ExpectedMap,
        [Parameter(Mandatory = $true)] $ActualMap,
        [Parameter(Mandatory = $true)][string] $Label
    )

    $missing = @($ExpectedMap.Keys | Where-Object { -not $ActualMap.ContainsKey($_) })
    $extra = @($ActualMap.Keys | Where-Object { -not $ExpectedMap.ContainsKey($_) })
    if ($missing.Count -gt 0 -or $extra.Count -gt 0) {
        throw "$Label ZIP entry set mismatch. Missing=[$($missing -join ', ')] Extra=[$($extra -join ', ')]"
    }
}

function Assert-ZipEntryContentEqual {
    param(
        [Parameter(Mandatory = $true)] $ExpectedEntry,
        [Parameter(Mandatory = $true)] $ActualEntry,
        [Parameter(Mandatory = $true)][string] $Label
    )

    $expectedHash = Get-BytesSha256 -Bytes (Read-ZipEntryBytes -Entry $ExpectedEntry)
    $actualHash = Get-BytesSha256 -Bytes (Read-ZipEntryBytes -Entry $ActualEntry)
    if (-not [string]::Equals($expectedHash, $actualHash, [System.StringComparison]::Ordinal)) {
        throw "$Label content mismatch. Expected $expectedHash but found $actualHash."
    }
}
