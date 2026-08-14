[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$patchRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$mixinSourcePath = Join-Path $patchRoot 'src\main\java\me\aleksilassila\litematica\printer\integration\mixin\ClientPlayerTickHandlerLegacyScanMixin.java'
$apiSourcePath = Join-Path $patchRoot 'src\main\java\me\aleksilassila\litematica\printer\api\LegacyScanControl.java'
$nativeHandlerPath = Join-Path $patchRoot '..\..\src\main\java\me\aleksilassila\litematica\printer\handler\ClientPlayerTickHandler.java'

function Assert-Contains([string] $Text, [string] $Expected, [string] $Label) {
    if ($Text.IndexOf($Expected, [System.StringComparison]::Ordinal) -lt 0) {
        throw "$Label is missing required contract text '$Expected'."
    }
}

$mixinSource = [System.IO.File]::ReadAllText($mixinSourcePath)
$apiSource = [System.IO.File]::ReadAllText($apiSourcePath)
$nativeHandler = [System.IO.File]::ReadAllText($nativeHandlerPath)

Assert-Contains $apiSource 'setLegacyScanVisitBudget(int maxVisitsPerTick)' 'Legacy scan API'
Assert-Contains $apiSource 'getLegacyScanVisitBudget()' 'Legacy scan API'
Assert-Contains $apiSource 'configureScanVisitBudget(Object handler, int maxVisitsPerTick)' 'Legacy scan API'
Assert-Contains $apiSource 'maxVisitsPerTick <= 0' 'Legacy scan API'

Assert-Contains $mixinSource 'LITEMATICA_PRINTER$DEFAULT_SCAN_VISIT_BUDGET = 4096' 'Legacy scan budget Mixin'
Assert-Contains $mixinSource 'litematicaPrinter$scanVisitsThisTick' 'Legacy scan budget Mixin'
Assert-Contains $mixinSource 'litematicaPrinter$interruptAtVisitBudget' 'Legacy scan budget Mixin'
Assert-Contains $mixinSource 'litematicaPrinter$countOrdinaryCoordinateVisit' 'Legacy scan budget Mixin'
Assert-Contains $mixinSource 'callback.setReturnValue(true)' 'Legacy scan budget Mixin'

# The budget hooks must target the native iterator invocations. Injected
# targeted-revisit code has its own 512-validation budget and must not be
# charged against ordinary coordinate enumeration.
Assert-Contains $mixinSource 'Ljava/util/Iterator;hasNext()Z' 'Legacy scan budget Mixin'
Assert-Contains $mixinSource 'Ljava/util/Iterator;next()Ljava/lang/Object;' 'Legacy scan budget Mixin'
Assert-Contains $mixinSource 'LITEMATICA_PRINTER$MAX_REVISIT_VALIDATIONS_PER_TICK = 512' 'Targeted revisit lane'

$budgetInterruptStart = $mixinSource.IndexOf('litematicaPrinter$interruptAtVisitBudget(',
        [System.StringComparison]::Ordinal)
$budgetCountStart = $mixinSource.IndexOf('litematicaPrinter$countOrdinaryCoordinateVisit(',
        [System.StringComparison]::Ordinal)
if ($budgetInterruptStart -lt 0 -or $budgetCountStart -lt 0) {
    throw 'Ordinary scan budget hooks are missing.'
}

# A budget interruption must return early before native exhaustion cleanup.
# It must never reset the box or cursor itself.
$budgetHooksEnd = $mixinSource.IndexOf('litematicaPrinter$retryAcknowledgedMissingPositions(',
        [System.StringComparison]::Ordinal)
if ($budgetHooksEnd -le $budgetInterruptStart) {
    throw 'Ordinary scan budget hooks must be declared before targeted revisit handling.'
}
$budgetHooks = $mixinSource.Substring($budgetInterruptStart,
        $budgetHooksEnd - $budgetInterruptStart)
if ($budgetHooks.IndexOf('cachedIterator = null', [System.StringComparison]::Ordinal) -ge 0 -or
    $budgetHooks.IndexOf('currentBoxRef.set(null)', [System.StringComparison]::Ordinal) -ge 0 -or
    $budgetHooks.IndexOf('lastPos = null', [System.StringComparison]::Ordinal) -ge 0) {
    throw 'Visit-budget interruption must preserve the native box and persistent cursor.'
}

# Preserve the independent native time ceiling as a second guard.
Assert-Contains $nativeHandler 'getIterationTimeLimit()' 'Native build.365 handler'
Assert-Contains $nativeHandler 'cachedIterator.hasNext()' 'Native build.365 handler'
Assert-Contains $nativeHandler 'cachedIterator.next()' 'Native build.365 handler'

if ($apiSource.IndexOf('75', [System.StringComparison]::Ordinal) -ge 0 -or
    $apiSource.IndexOf('125', [System.StringComparison]::Ordinal) -ge 0 -or
    $mixinSource.IndexOf('75', [System.StringComparison]::Ordinal) -ge 0 -or
    $mixinSource.IndexOf('125', [System.StringComparison]::Ordinal) -ge 0) {
    throw 'Legacy scan budget must not contain a fixed printer range.'
}

[pscustomobject]@{
    Result = 'LEGACY_SCAN_BUDGET_CONTRACT_VALID'
    DefaultVisitsPerTick = 4096
    NativeTimeLimitRetained = $true
    PersistentCursorPreserved = $true
    TargetedValidationLaneIndependent = $true
    FixedRange = $false
}
