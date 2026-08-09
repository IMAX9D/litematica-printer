package me.aleksilassila.litematica.printer.integration.mixin;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import me.aleksilassila.litematica.printer.api.PrinterIntegrationApi;
import me.aleksilassila.litematica.printer.api.PrinterIntegrationApi.ScheduledAttempt;
import me.aleksilassila.litematica.printer.api.PrinterPlacementFacade;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/** Printer-owned adapter; storage integrations never reflect protected methods. */
@Pseudo
@Mixin(targets = "me.aleksilassila.litematica.printer.handler.handlers.PrintHandler",
        remap = false)
public abstract class PrintHandlerPlacementFacadeMixin implements PrinterPlacementFacade {
    @Shadow(remap = false)
    public abstract boolean canProcessPos(BlockPos pos);

    @Shadow(remap = false)
    public abstract boolean isOnCooldown(BlockPos pos);

    @Shadow(remap = false)
    protected abstract void executeIteration(BlockPos pos,
            AtomicReference<Boolean> skipIteration);

    @Shadow(remap = false)
    protected abstract void updateVariables();

    /** Marks both legacy and scheduled PrintHandler actions without affecting other handlers. */
    @Redirect(method = "executeIteration",
            at = @At(value = "INVOKE",
                    target = "Lme/aleksilassila/litematica/printer/printer/ActionManager;sendQueue(Lnet/minecraft/client/player/LocalPlayer;)Lme/aleksilassila/litematica/printer/printer/ActionManager;",
                    remap = false),
            remap = false, require = 1)
    private ActionManager litematicaPrinter$sendVerifiedPrintRequest(
            ActionManager manager, LocalPlayer player) {
        return manager.sendQueueFromPrintHandler(player);
    }

    @Override
    public boolean isPlacementQueueIdle() {
        return ActionManager.INSTANCE.isQueueIdle();
    }

    @Override
    public boolean cancelScheduled(ScheduledAttempt attempt) {
        return ActionManager.INSTANCE.cancelScheduled(attempt);
    }

    @Override
    public SubmissionResult submitScheduled(ScheduledAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        if (!isPlacementQueueIdle()) return SubmissionResult.BUSY;
        if (!PrinterIntegrationApi.stageScheduledAttempt(attempt)) {
            return SubmissionResult.BUSY;
        }
        boolean finished = false;
        try {
            // PrintHandler.tick may return for PLACE_INTERVAL before refreshing
            // these inherited fields. A scheduler submission is an independent
            // entry point and must refresh them explicitly after respawn/change.
            updateVariables();
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null || minecraft.level == null
                    || minecraft.gameMode == null || minecraft.getConnection() == null) {
                finished = true;
                PrinterIntegrationApi.finishScheduledAttempt(attempt);
                return SubmissionResult.NOT_ACTIONABLE;
            }
            BlockPos pos = BlockPos.of(attempt.packedPosition());
            if (!canProcessPos(pos) || isOnCooldown(pos)) {
                finished = true;
                PrinterIntegrationApi.finishScheduledAttempt(attempt);
                return SubmissionResult.NOT_ACTIONABLE;
            }
            executeIteration(pos, new AtomicReference<>(false));
            finished = true;
            return PrinterIntegrationApi.finishScheduledAttempt(attempt)
                    ? SubmissionResult.CAPTURED_BY_GATEWAY
                    : SubmissionResult.NOT_CAPTURED;
        } catch (RuntimeException exception) {
            return SubmissionResult.FAILED;
        } finally {
            if (!finished) PrinterIntegrationApi.finishScheduledAttempt(attempt);
        }
    }
}
