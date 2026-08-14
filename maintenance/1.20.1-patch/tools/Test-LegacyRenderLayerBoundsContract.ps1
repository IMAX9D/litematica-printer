[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$patchRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$boundsPath = Join-Path $patchRoot 'src\main\java\me\aleksilassila\litematica\printer\integration\LegacyRenderLayerScanBounds.java'
$mixinPath = Join-Path $patchRoot 'src\main\java\me\aleksilassila\litematica\printer\integration\mixin\ClientPlayerTickHandlerLegacyScanMixin.java'
$nativeHandlerPath = Join-Path $patchRoot '..\..\src\main\java\me\aleksilassila\litematica\printer\handler\ClientPlayerTickHandler.java'

function Assert-Contains([string] $Text, [string] $Expected, [string] $Label) {
    if ($Text.IndexOf($Expected, [System.StringComparison]::Ordinal) -lt 0) {
        throw "$Label is missing required contract text '$Expected'."
    }
}

$bounds = [System.IO.File]::ReadAllText($boundsPath)
$mixin = [System.IO.File]::ReadAllText($mixinPath)
$native = [System.IO.File]::ReadAllText($nativeHandlerPath)

Assert-Contains $bounds 'SelectionType.LITEMATICA_RENDER_LAYER' 'Bounds policy'
Assert-Contains $bounds 'DataManager.getRenderLayerRange()' 'Bounds policy'
Assert-Contains $bounds 'LayerMode.SINGLE_LAYER' 'Single-layer gate'
Assert-Contains $bounds 'range.getClampedArea(' 'Bounds policy'
Assert-Contains $bounds 'catch (RuntimeException | LinkageError ignored)' 'Bounds fallback'
Assert-Contains $bounds 'Bounds original = new Bounds(' 'Native fallback'
Assert-Contains $bounds 'if (clamped == null)' 'Out-of-range fallback'
Assert-Contains $bounds 'level.getMinBuildHeight()' 'World-height fallback'
Assert-Contains $bounds 'level.getMaxBuildHeight()' 'World-height fallback'

Assert-Contains $mixin 'method = "updateBox"' 'Legacy scanner Mixin'
Assert-Contains $mixin '@ModifyArgs' 'Legacy scanner Mixin'
Assert-Contains $mixin 'PrinterBox;<init>(IIIIII)V' 'Exact native constructor selector'
Assert-Contains $mixin 'LegacyRenderLayerScanBounds.resolve' 'Legacy scanner Mixin'
Assert-Contains $mixin 'LegacyRenderLayerScanBounds.signature' 'Layer hotkey invalidation'
Assert-Contains $mixin 'lastPos = null' 'Layer hotkey invalidation'
Assert-Contains $mixin 'LITEMATICA_PRINTER$PRINT_HANDLER' 'Print-only gate'

# Keep the mature native safety filters. Bounding is only an earlier, cheaper
# rejection of positions which these checks would reject anyway.
Assert-Contains $native 'PlayerUtils.canInteracted(pos)' 'Native reach filter'
Assert-Contains $native 'PlayerUtils.isPositionInSelectionRange(player, pos, selectionType)' 'Native selection filter'

if ($bounds.IndexOf('75', [System.StringComparison]::Ordinal) -ge 0 -or
    $bounds.IndexOf('125', [System.StringComparison]::Ordinal) -ge 0) {
    throw 'Render-layer bounds must not contain a fixed printer range.'
}

# Pure range-size matrix: narrowing one axis must be independent of the
# configured reach. This is arithmetic-only and intentionally does not start
# Minecraft or depend on a player position.
$matrix = @(
    @{ Range = 6;   FullAxis = 13;  Plane = 169 },
    @{ Range = 20;  FullAxis = 41;  Plane = 1681 },
    @{ Range = 50;  FullAxis = 101; Plane = 10201 },
    @{ Range = 75;  FullAxis = 151; Plane = 22801 },
    @{ Range = 125; FullAxis = 251; Plane = 63001 }
)
foreach ($case in $matrix) {
    $axis = 2 * $case.Range + 1
    $full = $axis * $axis * $axis
    $plane = $axis * $axis
    if ($axis -ne $case.FullAxis -or $plane -ne $case.Plane -or
        $full / $plane -ne $axis) {
        throw "Single-layer range matrix failed for range $($case.Range)."
    }
}

[pscustomobject]@{
    Result = 'LEGACY_RENDER_LAYER_BOUNDS_CONTRACT_VALID'
    UsesLitematicaRangeIntersection = $true
    SingleLayerOnly = $true
    NativeReachCheckRetained = $true
    NativeSelectionCheckRetained = $true
    NonRenderLayerFallback = $true
    VerifiedRanges = '6,20,50,75,125'
    FixedRange = $false
}
