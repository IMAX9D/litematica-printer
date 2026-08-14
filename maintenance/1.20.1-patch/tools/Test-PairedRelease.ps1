[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string] $PrinterOuterJar,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9A-Fa-f]{64}$')][string] $ExpectedPrinterOuterSha256,
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string] $NestedInnerPath,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9A-Fa-f]{64}$')][string] $ExpectedNestedInnerSha256,
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string] $TomJar,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9A-Fa-f]{64}$')][string] $ExpectedTomSha256,
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string] $ExpectedPatchedPrinterVersion,
    [ValidateNotNullOrEmpty()][string] $PrinterMixinConfig = 'litematica-printer-maintenance.mixins.json',
    [ValidateNotNullOrEmpty()][string] $TomPrinterMixinConfig = 'toms_storage_printer.mixins.json'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'Packaging.Common.ps1')

function ConvertFrom-ZipJsonEntry {
    param(
        [Parameter(Mandatory = $true)] $Entry,
        [Parameter(Mandatory = $true)][string] $Label
    )

    $encoding = New-Object System.Text.UTF8Encoding($false, $true)
    try {
        $text = $encoding.GetString((Read-ZipEntryBytes -Entry $Entry))
        $text = $text.TrimStart([char[]] @([char] 0xFEFF))
        return $text | ConvertFrom-Json -ErrorAction Stop
    }
    catch {
        throw "$Label is not valid UTF-8 JSON: $($_.Exception.Message)"
    }
}

function Assert-RequiredZipEntries {
    param(
        [Parameter(Mandatory = $true)] $EntryMap,
        [Parameter(Mandatory = $true)][string[]] $RequiredEntries,
        [Parameter(Mandatory = $true)][string] $Label
    )

    $missing = @($RequiredEntries | Where-Object { -not $EntryMap.ContainsKey($_) })
    if ($missing.Count -gt 0) {
        throw "$Label is missing required entries: $($missing -join ', ')"
    }
}

function Assert-ZipEntryAsciiContract {
    param(
        [Parameter(Mandatory = $true)] $Entry,
        [Parameter(Mandatory = $true)][string[]] $RequiredText,
        [string[]] $ForbiddenText = @(),
        [Parameter(Mandatory = $true)][string] $Label
    )

    $classText = [System.Text.Encoding]::ASCII.GetString(
        (Read-ZipEntryBytes -Entry $Entry))
    foreach ($expected in $RequiredText) {
        if ($classText.IndexOf($expected, [System.StringComparison]::Ordinal) -lt 0) {
            throw "$Label is missing required bytecode contract text: $expected"
        }
    }
    foreach ($forbidden in $ForbiddenText) {
        if ($classText.IndexOf($forbidden, [System.StringComparison]::Ordinal) -ge 0) {
            throw "$Label contains forbidden bytecode contract text: $forbidden"
        }
    }
}

function Assert-JsonStringArrayContains {
    param(
        [Parameter(Mandatory = $true)] $Values,
        [Parameter(Mandatory = $true)][string[]] $ExpectedValues,
        [Parameter(Mandatory = $true)][string] $Label
    )

    $actual = @($Values | ForEach-Object { [string] $_ })
    $missing = @($ExpectedValues | Where-Object { $actual -notcontains $_ })
    if ($missing.Count -gt 0) {
        throw "$Label is missing declarations: $($missing -join ', ')"
    }
}

function Assert-RequiredMixinPolicy {
    param(
        [Parameter(Mandatory = $true)] $Metadata,
        [Parameter(Mandatory = $true)][string] $Label
    )

    $requiredProperty = $Metadata.PSObject.Properties['required']
    if ($null -eq $requiredProperty -or
        -not ($requiredProperty.Value -is [bool]) -or
        -not $requiredProperty.Value) {
        throw "$Label must declare the JSON boolean 'required' as true."
    }

    $injectorsProperty = $Metadata.PSObject.Properties['injectors']
    if ($null -eq $injectorsProperty -or $null -eq $injectorsProperty.Value) {
        throw "$Label must declare an injectors object."
    }
    $defaultRequireProperty = $injectorsProperty.Value.PSObject.Properties['defaultRequire']
    if ($null -eq $defaultRequireProperty -or
        -not (($defaultRequireProperty.Value -is [int]) -or
            ($defaultRequireProperty.Value -is [long])) -or
        [long] $defaultRequireProperty.Value -ne 1) {
        throw "$Label must declare the JSON integer 'injectors.defaultRequire' as 1."
    }
}

$outerPath = Resolve-ExistingPackagingFile -Path $PrinterOuterJar -Label 'Patched Printer outer JAR'
$tomPath = Resolve-ExistingPackagingFile -Path $TomJar -Label 'Paired Tom JAR'
if ([string]::Equals($outerPath, $tomPath, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Printer outer and Tom JAR paths must be different.'
}
if (-not $NestedInnerPath.StartsWith('META-INF/jars/', [System.StringComparison]::Ordinal) -or
    -not $NestedInnerPath.EndsWith('.jar', [System.StringComparison]::OrdinalIgnoreCase) -or
    $NestedInnerPath.Contains('..') -or
    $NestedInnerPath.Contains('\')) {
    throw "NestedInnerPath must be a normalized META-INF/jars/*.jar ZIP path: $NestedInnerPath"
}

$outerHash = Assert-PackagingSha256 `
    -Path $outerPath `
    -Expected $ExpectedPrinterOuterSha256 `
    -Label 'Patched Printer outer JAR'
$tomHash = Assert-PackagingSha256 `
    -Path $tomPath `
    -Expected $ExpectedTomSha256 `
    -Label 'Paired Tom JAR'

$printerRequiredEntries = @(
    'fabric.mod.json',
    $PrinterMixinConfig,
    'me/aleksilassila/litematica/printer/printer/ActionManager.class',
    'me/aleksilassila/litematica/printer/api/PrinterIntegrationApi.class',
    'me/aleksilassila/litematica/printer/api/LegacyScanControl.class',
    'me/aleksilassila/litematica/printer/api/PrinterPlacementFacade.class',
    'me/aleksilassila/litematica/printer/api/scheduler/SchematicPrintIndex.class',
    'me/aleksilassila/litematica/printer/api/scheduler/LayerScheduler.class',
    'me/aleksilassila/litematica/printer/api/scheduler/ScheduledMaterialDemand.class',
    'me/aleksilassila/litematica/printer/api/scheduler/PlacementResult.class',
    'me/aleksilassila/litematica/printer/integration/LegacyRenderLayerScanBounds.class',
    'me/aleksilassila/litematica/printer/integration/LegacyRenderLayerScanBounds$Bounds.class',
    'me/aleksilassila/litematica/printer/integration/mixin/ClientPlayerTickHandlerAccessor.class',
    'me/aleksilassila/litematica/printer/integration/mixin/ClientPlayerTickHandlerLegacyScanMixin.class',
    'me/aleksilassila/litematica/printer/integration/mixin/PrintHandlerPlacementFacadeMixin.class'
)
$tomRequiredEntries = @(
    'fabric.mod.json',
    $TomPrinterMixinConfig,
    'toms_storage_fabric-1.20-refmap.json',
    'com/tom/storagemod/StorageModClient.class',
    'com/tom/storagemod/util/ClientPrinterLayeredController.class',
    'com/tom/storagemod/util/ClientPrinterLayeredIndex.class',
    'com/tom/storagemod/util/ClientPrinterNativeIntegration.class',
    'com/tom/storagemod/util/ClientPrinterNativeIntegrationStatus.class',
    'com/tom/storagemod/util/ClientPrinterDiagnostics.class',
    'com/tom/storagemod/util/ClientPrinterInfiniteWater.class',
    'com/tom/storagemod/util/ClientPrinterPrintSession.class',
    'com/tom/storagemod/util/ClientPrinterStorageBridge.class',
    'com/tom/storagemod/util/ClientPrinterSafety.class',
    'com/tom/storagemod/util/LegacyClientPrinterRefillEngine.class',
    'com/tom/storagemod/util/FrozenRefillPlanPolicy.class',
    'com/tom/storagemod/util/FrozenRefillPlanPolicy$Plan.class',
    'com/tom/storagemod/util/FrozenRefillPlanPolicy$Source.class',
    'com/tom/storagemod/util/PrinterAcknowledgedBatchRetention.class',
    'com/tom/storagemod/util/PrinterLegacyMissingRevisitBatch.class',
    'com/tom/storagemod/util/PrinterLegacyScanRestartLatch.class',
    'com/tom/storagemod/util/PrinterMaterialLeaseLedger.class',
    'com/tom/storagemod/util/PrinterPlacementStatePolicy.class',
    'com/tom/storagemod/util/PrinterTransferTransaction.class',
    'com/tom/storagemod/mixin/ClientPrinterNetworkAckMixin.class',
    'com/tom/storagemod/mixin/LitematicaSchematicPlacementManagerMixin.class',
    'com/tom/storagemod/mixin/LitematicaPrinterVersionPackActionManagerMixin.class',
    'com/tom/storagemod/mixin/LitematicaPrinterVersionPackInventoryMixin.class',
    'com/tom/storagemod/mixin/LitematicaPrinterVersionPackMissingMixin.class',
    'com/tom/storagemod/mixin/LitematicaPrinterVersionPackPrintDiagnosticsMixin.class',
    'com/tom/storagemod/mixin/LitematicaPrinterVersionPackRangeMixin.class',
    'com/tom/storagemod/mixin/LitematicaPrinterVersionPackSafetyMixin.class',
    'com/tom/storagemod/mixin/LitematicaPrinterVersionPackTickMixin.class'
)

$outerArchive = $null
$nestedStream = $null
$innerArchive = $null
$tomArchive = $null
try {
    $outerArchive = [System.IO.Compression.ZipFile]::OpenRead($outerPath)
    $outerEntries = Get-ZipEntryMap -Archive $outerArchive -Label 'Patched Printer outer JAR'
    if (-not $outerEntries.ContainsKey($NestedInnerPath)) {
        throw "Patched Printer outer JAR does not contain exact nested entry '$NestedInnerPath'."
    }

    [byte[]] $nestedBytes = Read-ZipEntryBytes -Entry $outerEntries[$NestedInnerPath]
    $nestedHash = Get-BytesSha256 -Bytes $nestedBytes
    $normalizedNestedHash = $ExpectedNestedInnerSha256.ToUpperInvariant()
    if (-not [string]::Equals($nestedHash, $normalizedNestedHash, [System.StringComparison]::Ordinal)) {
        throw "Nested Printer inner SHA-256 mismatch. Expected $normalizedNestedHash but found $nestedHash."
    }

    $nestedStream = New-Object System.IO.MemoryStream
    $nestedStream.Write($nestedBytes, 0, $nestedBytes.Length)
    $nestedStream.Position = 0
    $innerArchive = [System.IO.Compression.ZipArchive]::new(
        $nestedStream,
        [System.IO.Compression.ZipArchiveMode]::Read,
        $false
    )
    $innerEntries = Get-ZipEntryMap -Archive $innerArchive -Label 'Nested patched Printer inner JAR'
    Assert-RequiredZipEntries `
        -EntryMap $innerEntries `
        -RequiredEntries $printerRequiredEntries `
        -Label 'Nested patched Printer inner JAR'

    $printerMetadata = ConvertFrom-ZipJsonEntry `
        -Entry $innerEntries['fabric.mod.json'] `
        -Label 'Nested Printer fabric.mod.json'
    if (-not [string]::Equals([string] $printerMetadata.id, 'litematica-printer', [System.StringComparison]::Ordinal)) {
        throw "Nested Printer mod id must be 'litematica-printer', found '$($printerMetadata.id)'."
    }
    if (-not [string]::Equals([string] $printerMetadata.version, $ExpectedPatchedPrinterVersion,
            [System.StringComparison]::Ordinal)) {
        throw "Nested Printer version must be '$ExpectedPatchedPrinterVersion', found '$($printerMetadata.version)'."
    }
    Assert-JsonStringArrayContains `
        -Values $printerMetadata.mixins `
        -ExpectedValues @($PrinterMixinConfig) `
        -Label 'Nested Printer fabric.mod.json mixins'

    $printerMixinMetadata = ConvertFrom-ZipJsonEntry `
        -Entry $innerEntries[$PrinterMixinConfig] `
        -Label "Nested Printer $PrinterMixinConfig"
    if (-not [string]::Equals([string] $printerMixinMetadata.package,
            'me.aleksilassila.litematica.printer.integration.mixin',
            [System.StringComparison]::Ordinal)) {
        throw "Nested Printer Mixin package is unexpected: '$($printerMixinMetadata.package)'."
    }
    Assert-RequiredMixinPolicy `
        -Metadata $printerMixinMetadata `
        -Label "Nested Printer $PrinterMixinConfig"
    Assert-JsonStringArrayContains `
        -Values $printerMixinMetadata.client `
        -ExpectedValues @(
            'ClientPlayerTickHandlerAccessor',
            'ClientPlayerTickHandlerLegacyScanMixin',
            'PrintHandlerPlacementFacadeMixin'
        ) `
        -Label "Nested Printer $PrinterMixinConfig client Mixins"

    $printDispatchDescriptor = 'Lme/aleksilassila/litematica/printer/printer/ActionManager;sendQueue(Lnet/minecraft/class_746;)Lme/aleksilassila/litematica/printer/printer/ActionManager;'
    $namedPrintDispatchDescriptor = 'Lme/aleksilassila/litematica/printer/printer/ActionManager;sendQueue(Lnet/minecraft/client/player/LocalPlayer;)Lme/aleksilassila/litematica/printer/printer/ActionManager;'
    Assert-ZipEntryAsciiContract `
        -Entry $innerEntries['me/aleksilassila/litematica/printer/integration/mixin/PrintHandlerPlacementFacadeMixin.class'] `
        -RequiredText @($printDispatchDescriptor, 'sendQueueFromPrintHandler') `
        -ForbiddenText @($namedPrintDispatchDescriptor) `
        -Label 'Nested Printer PrintHandler facade Mixin'
    Assert-ZipEntryAsciiContract `
        -Entry $innerEntries['me/aleksilassila/litematica/printer/integration/mixin/ClientPlayerTickHandlerAccessor.class'] `
        -RequiredText @(
            'me.aleksilassila.litematica.printer.handler.ClientPlayerTickHandler',
            'litematicaPrinter$updateVariables',
            'litematicaPrinter$isOnCooldown',
            'Lorg/spongepowered/asm/mixin/gen/Invoker;'
        ) `
        -Label 'Nested Printer inherited handler accessor Mixin'
    Assert-ZipEntryAsciiContract `
        -Entry $innerEntries['me/aleksilassila/litematica/printer/api/LegacyScanControl.class'] `
        -RequiredText @(
            'restartLegacyScanFromCurrentRangeStart',
            'restartCurrentRange',
            'revisitLegacyMissingPositions',
            'revisitMissingPositions',
            'clearMissingPositionRevisits',
            'configureScanVisitBudget',
            'setLegacyScanVisitBudget'
        ) `
        -Label 'Nested Printer legacy scan control API'
    Assert-ZipEntryAsciiContract `
        -Entry $innerEntries['me/aleksilassila/litematica/printer/integration/LegacyRenderLayerScanBounds.class'] `
        -RequiredText @(
            'LITEMATICA_RENDER_LAYER',
            'SINGLE_LAYER',
            'getRenderLayerRange',
            'getClampedArea',
            'net/minecraft/class_638',
            'method_31607',
            'method_31600'
        ) `
        -ForbiddenText @('75', '125') `
        -Label 'Nested Printer render-layer scan bounds'
    Assert-ZipEntryAsciiContract `
        -Entry $innerEntries['me/aleksilassila/litematica/printer/integration/mixin/ClientPlayerTickHandlerLegacyScanMixin.class'] `
        -RequiredText @(
            'me.aleksilassila.litematica.printer.handler.ClientPlayerTickHandler',
            'restartLegacyScanFromCurrentRangeStart',
            'revisitLegacyMissingPositions',
            'litematicaPrinter$legacyRevisits',
            'LitematicaUtils',
            'PlayerUtils',
            'isQueueIdle',
            'getNativeDispatchSequence',
            'externalDispatchSequence',
            'LegacyRenderLayerScanBounds',
            'PrinterBox;<init>(IIIIII)V',
            'cachedIterator',
            'lastPos',
            'lastBox',
            'java/util/concurrent/atomic/AtomicReference'
        ) `
        -Label 'Nested Printer legacy scan reset Mixin'

    $tomArchive = [System.IO.Compression.ZipFile]::OpenRead($tomPath)
    $tomEntries = Get-ZipEntryMap -Archive $tomArchive -Label 'Paired Tom JAR'
    Assert-RequiredZipEntries `
        -EntryMap $tomEntries `
        -RequiredEntries $tomRequiredEntries `
        -Label 'Paired Tom JAR'

    $tomMetadata = ConvertFrom-ZipJsonEntry `
        -Entry $tomEntries['fabric.mod.json'] `
        -Label 'Tom fabric.mod.json'
    if (-not [string]::Equals([string] $tomMetadata.id, 'toms_storage', [System.StringComparison]::Ordinal)) {
        throw "Tom mod id must be 'toms_storage', found '$($tomMetadata.id)'."
    }
    if ($null -eq $tomMetadata.depends) {
        throw 'Tom fabric.mod.json has no depends object.'
    }
    $expectedDependency = '=' + $ExpectedPatchedPrinterVersion
    $actualDependency = $tomMetadata.depends.'litematica-printer'
    if (-not ($actualDependency -is [string]) -or
        -not [string]::Equals([string] $actualDependency, $expectedDependency, [System.StringComparison]::Ordinal)) {
        throw "Tom must have the exact scalar dependency '$expectedDependency' for litematica-printer; found '$actualDependency'."
    }
    Assert-JsonStringArrayContains `
        -Values $tomMetadata.mixins `
        -ExpectedValues @($TomPrinterMixinConfig) `
        -Label 'Tom fabric.mod.json mixins'

    $tomMixinMetadata = ConvertFrom-ZipJsonEntry `
        -Entry $tomEntries[$TomPrinterMixinConfig] `
        -Label "Tom $TomPrinterMixinConfig"
    if (-not [string]::Equals([string] $tomMixinMetadata.package, 'com.tom.storagemod.mixin',
            [System.StringComparison]::Ordinal)) {
        throw "Tom Printer Mixin package is unexpected: '$($tomMixinMetadata.package)'."
    }
    Assert-RequiredMixinPolicy `
        -Metadata $tomMixinMetadata `
        -Label "Tom $TomPrinterMixinConfig"
    Assert-JsonStringArrayContains `
        -Values $tomMixinMetadata.client `
        -ExpectedValues @(
            'ClientPrinterNetworkAckMixin',
            'LitematicaSchematicPlacementManagerMixin',
            'LitematicaPrinterVersionPackActionManagerMixin',
            'LitematicaPrinterVersionPackInventoryMixin',
            'LitematicaPrinterVersionPackMissingMixin',
            'LitematicaPrinterVersionPackPrintDiagnosticsMixin',
            'LitematicaPrinterVersionPackRangeMixin',
            'LitematicaPrinterVersionPackSafetyMixin',
            'LitematicaPrinterVersionPackTickMixin'
        ) `
        -Label "Tom $TomPrinterMixinConfig client Mixins"

    if (-not [string]::Equals([string] $tomMixinMetadata.refmap,
            'toms_storage_fabric-1.20-refmap.json',
            [System.StringComparison]::Ordinal)) {
        throw "Tom Printer Mixin refmap is unexpected: '$($tomMixinMetadata.refmap)'."
    }
    Assert-ZipEntryAsciiContract `
        -Entry $tomEntries['com/tom/storagemod/util/ClientPrinterStorageBridge.class'] `
        -RequiredText @(
            'me/aleksilassila/litematica/printer/api/LegacyScanControl',
            'observeLegacyPrintHandler',
            'restartLegacyScanFromCurrentRange',
            'restartCurrentRange',
            'configureScanVisitBudget',
            'revisitLegacyMissingPositions',
            'revisitMissingPositions',
            'clearMissingPositionRevisits'
        ) `
        -Label 'Tom Printer storage bridge legacy scan restart boundary'
    Assert-ZipEntryAsciiContract `
        -Entry $tomEntries['com/tom/storagemod/util/LegacyClientPrinterRefillEngine.class'] `
        -RequiredText @(
            'PrinterAcknowledgedBatchRetention',
            'PrinterLegacyMissingRevisitBatch',
            'transactionAcknowledgedInboundKeys',
            'legacy_missing_backlog',
            'store_transfer_not_acknowledged',
            'recipe_paths_temporarily_unavailable',
            'FrozenRefillPlanPolicy',
            'activeFrozenRefillPlan',
            'frozenInventorySlots',
            'PrinterControlledSlotPolicy',
            'RESERVED_PRINTER_SLOTS'
        ) `
        -ForbiddenText @(
            'MAX_REFILL_BATCH_SLOTS'
        ) `
        -Label 'Tom Printer bounded refill and exact revisit engine'
    Assert-ZipEntryAsciiContract `
        -Entry $tomEntries['com/tom/storagemod/util/FrozenRefillPlanPolicy.class'] `
        -RequiredText @(
            'PrinterControlledSlotPolicy',
            'usableSlots',
            'PrinterRefillTargetPolicy',
            'allocate',
            'maximumTypes'
        ) `
        -ForbiddenText @(
            'MAX_REFILL_BATCH_SLOTS'
        ) `
        -Label 'Tom frozen refill generation policy'
    Assert-ZipEntryAsciiContract `
        -Entry $tomEntries['com/tom/storagemod/util/FrozenRefillPlanPolicy$Source.class'] `
        -RequiredText @('REQUIRED_MISS', 'SCHEDULED', 'RECENT') `
        -Label 'Tom frozen refill demand sources'
    Assert-ZipEntryAsciiContract `
        -Entry $tomEntries['com/tom/storagemod/util/FrozenRefillPlanPolicy$Plan.class'] `
        -RequiredText @('generation', 'scheduledIdentity', 'entries', 'controlledSlots', 'statistics') `
        -Label 'Tom immutable frozen refill plan'
    Assert-ZipEntryAsciiContract `
        -Entry $tomEntries['com/tom/storagemod/util/PrinterLegacyScanRestartLatch.class'] `
        -RequiredText @(
            'missingObserved',
            'inventoryAcknowledged',
            'shouldAttemptRestart',
            'restartAttempted'
        ) `
        -Label 'Tom Printer acknowledged legacy scan restart latch'
    $tomRefmap = ConvertFrom-ZipJsonEntry `
        -Entry $tomEntries['toms_storage_fabric-1.20-refmap.json'] `
        -Label 'Tom Printer Mixin refmap'
    $networkAckMappings = $tomRefmap.mappings.'com/tom/storagemod/mixin/ClientPrinterNetworkAckMixin'
    if ($null -eq $networkAckMappings) {
        throw 'Tom Printer Mixin refmap has no ClientPrinterNetworkAckMixin mappings.'
    }
    $expectedSendMapping = 'Lnet/minecraft/class_634;method_2883(Lnet/minecraft/class_2596;)V'
    $actualSendMapping = $networkAckMappings.'send(Lnet/minecraft/network/protocol/Packet;)V'
    if (-not [string]::Equals([string] $actualSendMapping, $expectedSendMapping,
            [System.StringComparison]::Ordinal)) {
        throw "Tom network ACK send mapping mismatch. Expected '$expectedSendMapping' but found '$actualSendMapping'."
    }
    $expectedAckMapping = 'Lnet/minecraft/class_634;method_21707(Lnet/minecraft/class_4463;)V'
    $actualAckMapping = $networkAckMappings.'handleBlockChangedAck(Lnet/minecraft/network/protocol/game/ClientboundBlockChangedAckPacket;)V'
    if (-not [string]::Equals([string] $actualAckMapping, $expectedAckMapping,
            [System.StringComparison]::Ordinal)) {
        throw "Tom network ACK handler mapping mismatch. Expected '$expectedAckMapping' but found '$actualAckMapping'."
    }

    [pscustomobject]@{
        PrinterOuterJar = $outerPath
        PrinterOuterSha256 = $outerHash
        NestedInnerPath = $NestedInnerPath
        NestedInnerSha256 = $nestedHash
        PrinterModVersion = [string] $printerMetadata.version
        TomJar = $tomPath
        TomSha256 = $tomHash
        TomModVersion = [string] $tomMetadata.version
        TomPrinterDependency = [string] $actualDependency
        Result = 'PAIRED_RELEASE_VALID'
    }
}
finally {
    if ($null -ne $tomArchive) {
        $tomArchive.Dispose()
    }
    if ($null -ne $innerArchive) {
        $innerArchive.Dispose()
    }
    if ($null -ne $nestedStream) {
        $nestedStream.Dispose()
    }
    if ($null -ne $outerArchive) {
        $outerArchive.Dispose()
    }
}
