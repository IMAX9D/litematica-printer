package me.aleksilassila.litematica.printer.integration.mixin;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import me.aleksilassila.litematica.printer.api.LegacyScanControl;
import me.aleksilassila.litematica.printer.api.PrinterIntegrationApi;
import me.aleksilassila.litematica.printer.integration.LegacyRenderLayerScanBounds;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.LitematicaUtils;
import me.aleksilassila.litematica.printer.utils.PlayerUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/** Adds the explicit legacy-scan restart API to build.365's handler. */
@Pseudo
@Mixin(targets = "me.aleksilassila.litematica.printer.handler.ClientPlayerTickHandler",
        remap = false)
public abstract class ClientPlayerTickHandlerLegacyScanMixin implements LegacyScanControl {
    @Unique
    private static final String LITEMATICA_PRINTER$PRINT_HANDLER =
            "me.aleksilassila.litematica.printer.handler.handlers.PrintHandler";
    @Unique
    private static final int LITEMATICA_PRINTER$MAX_REVISIT_POSITIONS = 8192;
    @Unique
    private static final int LITEMATICA_PRINTER$MAX_REVISIT_PLACEMENTS_PER_TICK = 256;
    @Unique
    private static final int LITEMATICA_PRINTER$MAX_REVISIT_VALIDATIONS_PER_TICK = 512;
    @Unique
    private static final int LITEMATICA_PRINTER$DEFAULT_SCAN_VISIT_BUDGET = 4096;

    @Shadow(remap = false)
    @Final
    @Nullable
    public AtomicReference<PrinterBox> boxRef;

    @Shadow(remap = false)
    @Nullable
    private Iterator<BlockPos> cachedIterator;

    @Shadow(remap = false)
    @Nullable
    private PrinterBox lastBox;

    @Shadow(remap = false)
    @Nullable
    private BlockPos lastPos;

    @Shadow(remap = false)
    @Final
    @Nullable
    private ConfigOptionList selectionType;

    @Shadow(remap = false)
    @Nullable
    protected LocalPlayer player;

    @Shadow(remap = false)
    protected abstract boolean canExecute();

    @Shadow(remap = false)
    protected abstract boolean canIterate();

    @Shadow(remap = false)
    protected abstract int getMaxExecutions();

    @Shadow(remap = false)
    public abstract boolean canProcessPos(BlockPos pos);

    @Shadow(remap = false)
    public abstract boolean isOnCooldown(@Nullable BlockPos pos);

    @Shadow(remap = false)
    protected abstract void executeIteration(BlockPos pos,
            AtomicReference<Boolean> skipIteration);

    @Shadow(remap = false)
    protected abstract void stopIteration(boolean interrupt);

    @Unique
    private final ArrayDeque<BlockPos> litematicaPrinter$legacyRevisits =
            new ArrayDeque<>();
    @Unique
    private final Set<Long> litematicaPrinter$legacyRevisitKeys = new HashSet<>();
    @Unique
    @Nullable
    private Object litematicaPrinter$revisitLevelIdentity;
    @Unique
    @Nullable
    private Object litematicaPrinter$revisitSchematicIdentity;
    @Unique
    private int litematicaPrinter$scanVisitBudget =
            LITEMATICA_PRINTER$DEFAULT_SCAN_VISIT_BUDGET;
    @Unique
    private int litematicaPrinter$scanVisitsThisTick;
    @Unique
    private long litematicaPrinter$renderLayerBoundsSignature;

    @Override
    public void setLegacyScanVisitBudget(int maxVisitsPerTick) {
        litematicaPrinter$scanVisitBudget = Math.max(1, maxVisitsPerTick);
    }

    @Override
    public int getLegacyScanVisitBudget() {
        return litematicaPrinter$scanVisitBudget;
    }

    @Override
    public boolean revisitLegacyMissingPositions(long[] packedPositions) {
        if (!getClass().getName().equals(LITEMATICA_PRINTER$PRINT_HANDLER)
                || packedPositions == null || packedPositions.length == 0) return false;
        Minecraft minecraft = Minecraft.getInstance();
        Object levelIdentity = minecraft.level;
        Object schematicIdentity = SchematicWorldHandler.getSchematicWorld();
        if (levelIdentity == null || schematicIdentity == null) return false;

        if (litematicaPrinter$revisitLevelIdentity != levelIdentity
                || litematicaPrinter$revisitSchematicIdentity != schematicIdentity) {
            clearLegacyMissingPositionRevisits();
            litematicaPrinter$revisitLevelIdentity = levelIdentity;
            litematicaPrinter$revisitSchematicIdentity = schematicIdentity;
        }

        for (long packedPosition : packedPositions) {
            if (litematicaPrinter$legacyRevisits.size()
                    >= LITEMATICA_PRINTER$MAX_REVISIT_POSITIONS) break;
            if (litematicaPrinter$legacyRevisitKeys.add(packedPosition)) {
                litematicaPrinter$legacyRevisits.addLast(BlockPos.of(packedPosition));
            }
        }
        return true;
    }

    @Override
    public void clearLegacyMissingPositionRevisits() {
        litematicaPrinter$legacyRevisits.clear();
        litematicaPrinter$legacyRevisitKeys.clear();
        litematicaPrinter$revisitLevelIdentity = null;
        litematicaPrinter$revisitSchematicIdentity = null;
    }

    @Inject(method = "tick", at = @At("HEAD"), remap = false, require = 1)
    private void litematicaPrinter$clearRevisitsWhileDisabled(CallbackInfo callback) {
        if (!litematicaPrinter$legacyRevisits.isEmpty()
                && !ConfigUtils.isPrinterEnable()) {
            clearLegacyMissingPositionRevisits();
        }
    }

    /**
     * Render-layer changes are scan-envelope changes. Invalidate the native
     * cursor before updateBox evaluates its rebuild condition so a layer hotkey
     * takes effect immediately instead of after the old full pass completes.
     */
    @Inject(method = "updateBox", at = @At("HEAD"), remap = false, require = 1)
    private void litematicaPrinter$observeRenderLayerBounds(CallbackInfo callback) {
        if (!getClass().getName().equals(LITEMATICA_PRINTER$PRINT_HANDLER)) return;

        long nextSignature = LegacyRenderLayerScanBounds.signature(selectionType);
        if (nextSignature != litematicaPrinter$renderLayerBoundsSignature) {
            // A retained hand-swap/placement request owns its exact context.
            // Observe the layer hotkey after that queue drains, just like the
            // explicit legacy scan restart boundary does.
            if (!ActionManager.INSTANCE.isQueueIdle()) return;
            litematicaPrinter$renderLayerBoundsSignature = nextSignature;
            lastPos = null;
        }
    }

    /**
     * Uses Litematica's own LayerRange intersection to avoid enumerating axes
     * which the unchanged native selection check would reject later.
     */
    @ModifyArgs(method = "updateBox",
            at = @At(value = "INVOKE",
                    target = "Lme/aleksilassila/litematica/printer/printer/PrinterBox;<init>(IIIIII)V"),
            remap = false, require = 1)
    private void litematicaPrinter$applyRenderLayerBounds(Args args) {
        if (!getClass().getName().equals(LITEMATICA_PRINTER$PRINT_HANDLER)) return;

        LegacyRenderLayerScanBounds.Bounds bounds =
                LegacyRenderLayerScanBounds.resolve(selectionType,
                        args.get(0), args.get(1), args.get(2),
                        args.get(3), args.get(4), args.get(5));
        args.set(0, bounds.minX());
        args.set(1, bounds.minY());
        args.set(2, bounds.minZ());
        args.set(3, bounds.maxX());
        args.set(4, bounds.maxY());
        args.set(5, bounds.maxZ());
    }

    @Inject(method = "iterateBlocks", at = @At("HEAD"), remap = false,
            require = 1)
    private void litematicaPrinter$beginBoundedOrdinaryScan(
            CallbackInfoReturnable<Boolean> callback) {
        litematicaPrinter$scanVisitsThisTick = 0;
    }

    /**
     * Interrupts before the next ordinary iterator condition once this tick's
     * coordinate budget has been consumed. Returning {@code true} follows the
     * native time-limit path: the handler keeps {@code cachedIterator} and the
     * next tick resumes at the following coordinate.
     */
    @Inject(method = "iterateBlocks",
            at = @At(value = "INVOKE",
                    target = "Ljava/util/Iterator;hasNext()Z", ordinal = 0,
                    shift = At.Shift.BEFORE),
            cancellable = true, remap = false, require = 1)
    private void litematicaPrinter$interruptAtVisitBudget(
            CallbackInfoReturnable<Boolean> callback) {
        if (litematicaPrinter$scanVisitsThisTick
                < litematicaPrinter$scanVisitBudget) return;
        stopIteration(true);
        callback.setReturnValue(true);
    }

    /** Counts every raw coordinate, including air and non-schematic space. */
    @Inject(method = "iterateBlocks",
            at = @At(value = "INVOKE",
                    target = "Ljava/util/Iterator;next()Ljava/lang/Object;",
                    ordinal = 0, shift = At.Shift.BEFORE),
            remap = false, require = 1)
    private void litematicaPrinter$countOrdinaryCoordinateVisit(
            CallbackInfoReturnable<Boolean> callback) {
        litematicaPrinter$scanVisitsThisTick++;
    }

    /**
     * Targeted misses run before ordinary enumeration without moving or
     * reconstructing {@code cachedIterator}. Every actual retry still enters
     * PrintHandler's mature canProcessPos/executeIteration chain and therefore
     * the installed placement gateway and hand-swap protection.
     */
    @Inject(method = "iterateBlocks", at = @At("HEAD"), cancellable = true,
            remap = false, require = 1)
    private void litematicaPrinter$retryAcknowledgedMissingPositions(
            CallbackInfoReturnable<Boolean> callback) {
        if (litematicaPrinter$legacyRevisits.isEmpty()
                || !getClass().getName().equals(LITEMATICA_PRINTER$PRINT_HANDLER)) return;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != litematicaPrinter$revisitLevelIdentity
                || SchematicWorldHandler.getSchematicWorld()
                        != litematicaPrinter$revisitSchematicIdentity) {
            clearLegacyMissingPositionRevisits();
            return;
        }
        AtomicReference<PrinterBox> currentBoxRef = boxRef;
        if (currentBoxRef == null || currentBoxRef.get() == null
                || !canExecute() || !canIterate()) return;
        if (ActionManager.INSTANCE.needWaitModifyLook) {
            callback.setReturnValue(true);
            return;
        }

        int configuredPlacements = getMaxExecutions();
        int placementBudget = configuredPlacements > 0
                ? Math.min(configuredPlacements,
                        LITEMATICA_PRINTER$MAX_REVISIT_PLACEMENTS_PER_TICK)
                : LITEMATICA_PRINTER$MAX_REVISIT_PLACEMENTS_PER_TICK;
        int validationBudget = Math.min(
                LITEMATICA_PRINTER$MAX_REVISIT_VALIDATIONS_PER_TICK,
                Math.max(64, placementBudget * 4));
        // Snapshot the queue depth so a synchronously deferred position can be
        // rotated at most once this tick. This also prevents direct placement
        // lanes (for example an optional infinite-water hook which bypasses
        // ActionManager) from being replayed in a tight same-tick loop while
        // their local world prediction settles.
        int candidatesThisTick = Math.min(validationBudget,
                litematicaPrinter$legacyRevisits.size());
        int validations = 0;
        int executions = 0;
        boolean targetedActionOwnedOrDispatched = false;
        AtomicReference<Boolean> skipIteration = new AtomicReference<>(false);

        while (!litematicaPrinter$legacyRevisits.isEmpty()
                && validations < candidatesThisTick && executions < placementBudget) {
            BlockPos pos = litematicaPrinter$legacyRevisits.peekFirst();
            validations++;

            // Dynamic reach remains Printer-owned and spherical. A position no
            // longer in range is discarded; native movement/range box rebuilds
            // remain responsible for discovering it again later.
            if (!PlayerUtils.canInteracted(pos)
                    || !LitematicaUtils.isSchematicBlock(pos)
                    || selectionType != null
                            && !PlayerUtils.isPositionInSelectionRange(
                                    player, pos, selectionType)
                    || !canProcessPos(pos)) {
                litematicaPrinter$removeFirstRevisit(pos);
                continue;
            }
            if (isOnCooldown(pos)) {
                litematicaPrinter$rotateFirstRevisit(pos);
                continue;
            }

            long dispatchBefore = ActionManager.INSTANCE.getNativeDispatchSequence();
            long externalDispatchBefore = PrinterIntegrationApi.externalDispatchSequence();
            executeIteration(pos, skipIteration);
            boolean queueOwned = !ActionManager.INSTANCE.isQueueIdle();
            boolean nativeBoundaryCrossed = dispatchBefore
                    != ActionManager.INSTANCE.getNativeDispatchSequence();
            boolean externalBoundaryCrossed = externalDispatchBefore
                    != PrinterIntegrationApi.externalDispatchSequence();
            if (queueOwned || nativeBoundaryCrossed || externalBoundaryCrossed) {
                // A retained HOLD owns this exact action, while a crossed native
                // boundary is already confirmed-or-uncertain locally. Neither
                // outcome may be submitted from the exact queue again.
                litematicaPrinter$removeFirstRevisit(pos);
                executions++;
                targetedActionOwnedOrDispatched = true;
            } else {
                // Missing material, unsupported placement, or a synchronous
                // gateway DROP produced no owned/sent action. Keep the exact
                // coordinate and retry it only on a later tick/refill cycle.
                litematicaPrinter$rotateFirstRevisit(pos);
            }
            if (skipIteration.get() || ActionManager.INSTANCE.needWaitModifyLook) break;
        }

        if (litematicaPrinter$legacyRevisits.isEmpty()) {
            litematicaPrinter$revisitLevelIdentity = null;
            litematicaPrinter$revisitSchematicIdentity = null;
        }
        // Do not let the same tick spend a second placement budget in the
        // ordinary iterator after a targeted executeIteration call.
        if (targetedActionOwnedOrDispatched
                || skipIteration.get()
                || ActionManager.INSTANCE.needWaitModifyLook) {
            callback.setReturnValue(true);
        }
    }

    @Unique
    private void litematicaPrinter$removeFirstRevisit(BlockPos expected) {
        BlockPos removed = litematicaPrinter$legacyRevisits.removeFirst();
        if (removed != expected && !removed.equals(expected)) {
            throw new IllegalStateException("Legacy revisit queue order changed during iteration");
        }
        litematicaPrinter$legacyRevisitKeys.remove(removed.asLong());
    }

    @Unique
    private void litematicaPrinter$rotateFirstRevisit(BlockPos expected) {
        BlockPos removed = litematicaPrinter$legacyRevisits.removeFirst();
        if (removed != expected && !removed.equals(expected)) {
            throw new IllegalStateException("Legacy revisit queue order changed during iteration");
        }
        litematicaPrinter$legacyRevisits.addLast(removed);
    }

    @Override
    public boolean restartLegacyScanFromCurrentRangeStart() {
        // A held placement owns both its material lease and native queue
        // identity. Never move the scanner underneath that action; the storage
        // bridge retains its restart latch and retries after the queue drains.
        if (!ActionManager.INSTANCE.isQueueIdle()) return false;

        AtomicReference<PrinterBox> currentBoxRef = boxRef;
        if (currentBoxRef == null) return false;

        PrinterBox currentBox = currentBoxRef.get();
        if (currentBox == null) return false;

        // Do not restart the old envelope. The player may have moved or changed
        // configured/legal range while the terminal was open. Nulling the box
        // makes native updateBox rebuild from current state on the next tick.
        cachedIterator = null;
        lastPos = null;
        lastBox = null;
        currentBoxRef.set(null);
        return true;
    }
}
