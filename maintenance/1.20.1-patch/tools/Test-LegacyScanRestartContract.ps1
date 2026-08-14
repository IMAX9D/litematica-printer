[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$patchRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $patchRoot '..\..'))
$mixinSourcePath = Join-Path $patchRoot 'src\main\java\me\aleksilassila\litematica\printer\integration\mixin\ClientPlayerTickHandlerLegacyScanMixin.java'
$apiSourcePath = Join-Path $patchRoot 'src\main\java\me\aleksilassila\litematica\printer\api\LegacyScanControl.java'
$nativeHandlerPath = Join-Path $repositoryRoot 'src\main\java\me\aleksilassila\litematica\printer\handler\ClientPlayerTickHandler.java'
$mixinConfigPath = Join-Path $patchRoot 'src\main\resources\litematica-printer-maintenance.mixins.json'

function Assert-Contains([string] $Text, [string] $Expected, [string] $Label) {
    if ($Text.IndexOf($Expected, [System.StringComparison]::Ordinal) -lt 0) {
        throw "$Label is missing required contract text '$Expected'."
    }
}

$mixinSource = [System.IO.File]::ReadAllText($mixinSourcePath)
$apiSource = [System.IO.File]::ReadAllText($apiSourcePath)
$nativeHandler = [System.IO.File]::ReadAllText($nativeHandlerPath)
$mixinConfig = [System.IO.File]::ReadAllText($mixinConfigPath) | ConvertFrom-Json

Assert-Contains $apiSource 'restartLegacyScanFromCurrentRangeStart' 'Legacy scan API'
Assert-Contains $apiSource 'restartCurrentRange(Object handler)' 'Legacy scan API'
Assert-Contains $apiSource 'revisitLegacyMissingPositions(long[] packedPositions)' 'Legacy scan API'
Assert-Contains $apiSource 'revisitMissingPositions(Object handler, long[] packedPositions)' 'Legacy scan API'
Assert-Contains $apiSource 'clearLegacyMissingPositionRevisits' 'Legacy scan API'
Assert-Contains $mixinSource '!ActionManager.INSTANCE.isQueueIdle()' 'Legacy scan Mixin'
Assert-Contains $mixinSource 'cachedIterator = null;' 'Legacy scan Mixin'
Assert-Contains $mixinSource 'lastPos = null;' 'Legacy scan Mixin'
Assert-Contains $mixinSource 'lastBox = null;' 'Legacy scan Mixin'
Assert-Contains $mixinSource 'currentBoxRef.set(null);' 'Legacy scan Mixin'
Assert-Contains $mixinSource 'litematicaPrinter$legacyRevisits' 'Legacy targeted revisit Mixin'
Assert-Contains $mixinSource 'LITEMATICA_PRINTER$MAX_REVISIT_POSITIONS = 8192' 'Legacy targeted revisit Mixin'
Assert-Contains $mixinSource 'LITEMATICA_PRINTER$MAX_REVISIT_VALIDATIONS_PER_TICK = 512' 'Legacy targeted revisit Mixin'
Assert-Contains $mixinSource 'PlayerUtils.canInteracted(pos)' 'Legacy targeted revisit Mixin'
Assert-Contains $mixinSource 'canProcessPos(pos)' 'Legacy targeted revisit Mixin'
Assert-Contains $mixinSource 'executeIteration(pos, skipIteration)' 'Legacy targeted revisit Mixin'
Assert-Contains $mixinSource 'getMaxExecutions()' 'Legacy targeted revisit Mixin'

$targetedStart = $mixinSource.IndexOf('litematicaPrinter$retryAcknowledgedMissingPositions(',
        [System.StringComparison]::Ordinal)
$restartStart = $mixinSource.IndexOf('restartLegacyScanFromCurrentRangeStart()',
        [System.StringComparison]::Ordinal)
if ($targetedStart -lt 0 -or $restartStart -le $targetedStart) {
    throw 'Targeted revisit must be implemented independently before the compatibility restart.'
}
$targetedMethod = $mixinSource.Substring($targetedStart, $restartStart - $targetedStart)
if ($targetedMethod.IndexOf('cachedIterator = null', [System.StringComparison]::Ordinal) -ge 0 -or
    $targetedMethod.IndexOf('currentBoxRef.set(null)', [System.StringComparison]::Ordinal) -ge 0) {
    throw 'Targeted missing-position revisit must preserve the native scan cursor and box.'
}

$queueGuard = $mixinSource.IndexOf('!ActionManager.INSTANCE.isQueueIdle()', [System.StringComparison]::Ordinal)
$firstReset = $mixinSource.IndexOf('cachedIterator = null;', [System.StringComparison]::Ordinal)
if ($queueGuard -lt 0 -or $firstReset -lt 0 -or $queueGuard -gt $firstReset) {
    throw 'Legacy scan cursor mutation must remain behind the ActionManager queue-idle guard.'
}
if ($apiSource.IndexOf('125', [System.StringComparison]::Ordinal) -ge 0 -or
    $mixinSource.IndexOf('125', [System.StringComparison]::Ordinal) -ge 0) {
    throw 'Legacy scan restart API must not contain a fixed range.'
}

# The restart deliberately delegates the new envelope and spherical filtering
# to build.365. Guard the verified native calls that make this true.
Assert-Contains $nativeHandler 'ConfigUtils.getEffectiveRange()' 'Native build.365 handler'
Assert-Contains $nativeHandler 'PlayerUtils.canInteracted(pos)' 'Native build.365 handler'
Assert-Contains $nativeHandler 'box == null' 'Native build.365 handler rebuild condition'

$clientMixins = @($mixinConfig.client | ForEach-Object { [string] $_ })
if ($clientMixins -notcontains 'ClientPlayerTickHandlerLegacyScanMixin') {
    throw 'Maintenance Mixin config does not declare ClientPlayerTickHandlerLegacyScanMixin.'
}

[pscustomobject]@{
    Result = 'LEGACY_SCAN_RESTART_CONTRACT_VALID'
    QueueIdleGuard = $true
    FixedRange = $false
    NativeEffectiveRange = $true
    NativeSphericalFilter = $true
    TargetedRevisit = $true
    PersistentCursorPreserved = $true
}
