[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$patchRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$mixinPath = Join-Path $patchRoot 'src\main\java\me\aleksilassila\litematica\printer\integration\mixin\ClientPlayerTickHandlerLegacyScanMixin.java'
$actionManagerPath = Join-Path $patchRoot 'src\main\java\me\aleksilassila\litematica\printer\printer\ActionManager.java'
$integrationApiPath = Join-Path $patchRoot 'src\main\java\me\aleksilassila\litematica\printer\api\PrinterIntegrationApi.java'
$mixin = [System.IO.File]::ReadAllText($mixinPath)
$actionManager = [System.IO.File]::ReadAllText($actionManagerPath)
$integrationApi = [System.IO.File]::ReadAllText($integrationApiPath)

function Assert-Contains([string] $Text, [string] $Expected, [string] $Label) {
    if ($Text.IndexOf($Expected, [System.StringComparison]::Ordinal) -lt 0) {
        throw "$Label is missing required contract text '$Expected'."
    }
}

Assert-Contains $actionManager 'getNativeDispatchSequence()' 'ActionManager outcome boundary'
Assert-Contains $actionManager 'nativeDispatchSequence = nativeDispatchSequence == Long.MAX_VALUE' 'ActionManager outcome boundary'
Assert-Contains $actionManager 'nativeBoundaryEntered = true;' 'ActionManager outcome boundary'
$sequenceAdvance = $actionManager.IndexOf(
        'nativeDispatchSequence = nativeDispatchSequence == Long.MAX_VALUE',
        [System.StringComparison]::Ordinal)
$nativeEntry = $actionManager.IndexOf('nativeBoundaryEntered = true;',
        [System.StringComparison]::Ordinal)
$nativeCall = $actionManager.IndexOf('litematica_printer$useItemOn(',
        [System.StringComparison]::Ordinal)
if ($sequenceAdvance -lt 0 -or $nativeEntry -le $sequenceAdvance -or
    $nativeCall -le $nativeEntry) {
    throw 'Dispatch sequence must advance immediately before entering the native placement call.'
}

Assert-Contains $mixin 'candidatesThisTick = Math.min(validationBudget' 'Targeted revisit outcome Mixin'
Assert-Contains $mixin 'legacyRevisits.peekFirst()' 'Targeted revisit outcome Mixin'
Assert-Contains $mixin 'getNativeDispatchSequence()' 'Targeted revisit outcome Mixin'
Assert-Contains $mixin 'boolean queueOwned = !ActionManager.INSTANCE.isQueueIdle()' 'Targeted revisit outcome Mixin'
Assert-Contains $mixin 'litematicaPrinter$removeFirstRevisit(pos)' 'Targeted revisit outcome Mixin'
Assert-Contains $mixin 'litematicaPrinter$rotateFirstRevisit(pos)' 'Targeted revisit outcome Mixin'
Assert-Contains $integrationApi 'markExternalDispatchBoundary()' 'External dispatch boundary API'
Assert-Contains $integrationApi 'externalDispatchSequence()' 'External dispatch boundary API'
Assert-Contains $integrationApi 'EXTERNAL_DISPATCH_SEQUENCE.updateAndGet(' 'External dispatch boundary API'
Assert-Contains $mixin 'externalDispatchBefore = PrinterIntegrationApi.externalDispatchSequence()' 'Targeted revisit outcome Mixin'
Assert-Contains $mixin 'externalBoundaryCrossed' 'Targeted revisit outcome Mixin'
Assert-Contains $mixin 'queueOwned || nativeBoundaryCrossed || externalBoundaryCrossed' 'Targeted revisit outcome Mixin'
Assert-Contains $mixin 'boolean targetedActionOwnedOrDispatched = false' 'Targeted revisit liveness Mixin'
Assert-Contains $mixin 'targetedActionOwnedOrDispatched = true' 'Targeted revisit liveness Mixin'
Assert-Contains $mixin 'if (targetedActionOwnedOrDispatched' 'Targeted revisit liveness Mixin'

$cancelStart = $mixin.IndexOf('if (targetedActionOwnedOrDispatched',
        [System.StringComparison]::Ordinal)
$helperStart = $mixin.IndexOf('private void litematicaPrinter$removeFirstRevisit(',
        [System.StringComparison]::Ordinal)
if ($cancelStart -lt 0 -or $helperStart -le $cancelStart) {
    throw 'Targeted cancellation outcome block is missing.'
}
$cancelBlock = $mixin.Substring($cancelStart, $helperStart - $cancelStart)
if ($cancelBlock.IndexOf('executions > 0', [System.StringComparison]::Ordinal) -ge 0 -or
    $cancelBlock.IndexOf('validations > 0', [System.StringComparison]::Ordinal) -ge 0) {
    throw 'Undispatched rotations must not starve the ordinary persistent cursor.'
}

$execute = $mixin.IndexOf('executeIteration(pos, skipIteration);',
        [System.StringComparison]::Ordinal)
$outcome = $mixin.IndexOf('boolean queueOwned = !ActionManager.INSTANCE.isQueueIdle()',
        [System.StringComparison]::Ordinal)
$remove = $mixin.IndexOf('litematicaPrinter$removeFirstRevisit(pos)', $outcome,
        [System.StringComparison]::Ordinal)
if ($execute -lt 0 -or $outcome -le $execute -or $remove -le $outcome) {
    throw 'Exact revisit removal must happen only after synchronous placement outcome classification.'
}

$executionCharge = $mixin.IndexOf('executions++;', $outcome,
        [System.StringComparison]::Ordinal)
$requeueBranch = $mixin.IndexOf('} else {', $remove,
        [System.StringComparison]::Ordinal)
if ($executionCharge -le $remove -or $requeueBranch -le $executionCharge) {
    throw 'Only an owned or dispatched exact action may consume placement credit.'
}

$loopStart = $mixin.IndexOf('while (!litematicaPrinter$legacyRevisits.isEmpty()',
        [System.StringComparison]::Ordinal)
$executeAfterLoop = $mixin.IndexOf('executeIteration(pos, skipIteration);', $loopStart,
        [System.StringComparison]::Ordinal)
$prematureRemove = $mixin.IndexOf('legacyRevisitKeys.remove(pos.asLong())', $loopStart,
        [System.StringComparison]::Ordinal)
if ($prematureRemove -ge 0 -and $prematureRemove -lt $executeAfterLoop) {
    throw 'Exact revisit key is still removed before executeIteration outcome is known.'
}

[pscustomobject]@{
    Result = 'LEGACY_TARGETED_REVISIT_OUTCOME_CONTRACT_VALID'
    ServerAcknowledgementWait = $false
    HeldQueueConsumesExactPosition = $true
    NativeDispatchConsumesExactPosition = $true
    SynchronousDropRequeues = $true
    SameTickDirectReplayPrevented = $true
}
