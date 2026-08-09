package me.aleksilassila.litematica.printer.printer;

import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.api.PrinterIntegrationApi;
import me.aleksilassila.litematica.printer.api.PrinterIntegrationApi.GateDecision;
import me.aleksilassila.litematica.printer.api.PrinterIntegrationApi.GateResult;
import me.aleksilassila.litematica.printer.api.PrinterIntegrationApi.Gateway;
import me.aleksilassila.litematica.printer.api.PrinterIntegrationApi.PlacementRequest;
import me.aleksilassila.litematica.printer.api.PrinterIntegrationApi.PlacementSource;
import me.aleksilassila.litematica.printer.api.PrinterIntegrationApi.ScheduledAttempt;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.mixin_extension.MultiPlayerGameModeExtension;
import me.aleksilassila.litematica.printer.utils.BlockUtils;
import me.aleksilassila.litematica.printer.utils.PacketUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * build.365 ActionManager with a stable pre-dispatch integration boundary.
 * The native queue and placement packet semantics remain unchanged.
 */
public class ActionManager {
    @SuppressWarnings("unused")
    private static final boolean INTEGRATION_HOOK_REGISTERED =
            PrinterIntegrationApi.registerNativeActionManagerHook();
    public static final ActionManager INSTANCE = new ActionManager();

    public BlockPos target;
    public Direction side;
    public Vec3 hitModifier;
    public boolean useShift = false;
    public boolean useProtocol = false;
    public PlayerLook look;
    public boolean needWaitModifyLook = false;
    private boolean actionRequiresWaitModifyLook = false;

    private PlacementRequest integrationRequest;
    private boolean integrationHold;
    private boolean integrationDispatchCompleted;
    private boolean syntheticDropWait;

    private ActionManager() {
    }

    public void queueClick(BlockPos target, Direction side, Vec3 hitModifier, boolean useShift) {
        if (target == null || side == null || hitModifier == null) {
            throw new NullPointerException("queued placement fields must not be null");
        }
        if (this.target != null) {
            System.out.println("Was not ready yet.");
            return;
        }
        this.target = target;
        this.side = side;
        this.hitModifier = hitModifier;
        this.useShift = useShift;
    }

    public ActionManager sendQueue(LocalPlayer player) {
        return sendQueueForSource(player, PlacementSource.OTHER_HANDLER);
    }

    /** Entry used only by the maintained PrintHandler Mixin. */
    public ActionManager sendQueueFromPrintHandler(LocalPlayer player) {
        return sendQueueForSource(player, PlacementSource.PRINT_HANDLER);
    }

    private ActionManager sendQueueForSource(LocalPlayer player, PlacementSource source) {
        if (target == null && syntheticDropWait) {
            needWaitModifyLook = false;
            syntheticDropWait = false;
        }
        if (target == null || side == null || hitModifier == null) {
            clearQueue();
            return this;
        }

        Gateway gateway = PrinterIntegrationApi.gateway();
        if (integrationRequest == null) {
            integrationRequest = PrinterIntegrationApi.captureRequest(target.asLong(),
                    side.ordinal(), hitModifier.x, hitModifier.y, hitModifier.z,
                    useProtocol, source);
        }
        GateResult evaluation;
        try {
            evaluation = gateway.evaluate(integrationRequest);
        } catch (RuntimeException exception) {
            dropBeforeDispatch(gateway, "integration_evaluate_failed");
            return this;
        }
        if (evaluation.decision() == GateDecision.HOLD) {
            integrationHold = true;
            // Keep build.365's scanner from overwriting its one-element queue.
            needWaitModifyLook = true;
            return this;
        }
        if (integrationHold) {
            integrationHold = false;
            needWaitModifyLook = false;
        }
        if (evaluation.decision() == GateDecision.DROP) {
            dropBeforeDispatch(gateway, evaluation.reason());
            return this;
        }
        if (evaluation.restartNativeLookProtocol()) needWaitModifyLook = false;

        if (look != null) PacketUtils.sendLookPacket(player, look);

        if (!useProtocol && !needWaitModifyLook && actionRequiresWaitModifyLook) {
            if (look != null) {
                Direction lookDirection = BlockUtils.orderedByNearest(look.yaw(), look.pitch())[0];
                if (lookDirection.getAxis().isHorizontal()) {
                    needWaitModifyLook = true;
                    return this;
                }
            }
        }

        if (needWaitModifyLook) needWaitModifyLook = false;

        Direction direction = look == null ? side : BlockUtils.getHorizontalDirection(look.yaw());
        Vec3 hitVec;
        if (!useProtocol) {
            Vec3 targetCenter = Vec3.atCenterOf(target);
            Vec3 sideOffset = Vec3.atLowerCornerOf(BlockUtils.getVector(side)).scale(0.5);
            Vec3 rotatedHitModifier = hitModifier.yRot((direction.toYRot() + 90) % 360).scale(0.5);
            hitVec = targetCenter.add(sideOffset).add(rotatedHitModifier);
        } else {
            hitVec = hitModifier;
        }

        if (!(Reference.MINECRAFT.gameMode
                instanceof MultiPlayerGameModeExtension gameModeExtension)) {
            dropBeforeDispatch(gateway, "game_mode_unavailable");
            return this;
        }

        try {
            gateway.beforeDispatch(integrationRequest);
        } catch (RuntimeException exception) {
            dropBeforeDispatch(gateway, "integration_before_dispatch_failed");
            return this;
        }

        boolean wasSneak = false;
        boolean changedSneak = false;
        boolean localPrediction;
        BlockHitResult blockHitResult;
        try {
            wasSneak = player.isShiftKeyDown();
            changedSneak = useShift != wasSneak;
            if (changedSneak) setShift(player, useShift);
            localPrediction = !Configs.Placement.PRINT_USE_PACKET.getBooleanValue();
            blockHitResult = new BlockHitResult(hitVec, side, target, false);
        } catch (RuntimeException exception) {
            if (changedSneak) {
                try {
                    setShift(player, wasSneak);
                } catch (RuntimeException restoreFailure) {
                    exception.addSuppressed(restoreFailure);
                }
            }
            exception.printStackTrace();
            dropBeforeDispatch(gateway, "pre_native_dispatch_failed");
            return this;
        }

        RuntimeException dispatchFailure = null;
        boolean nativeBoundaryEntered = false;
        boolean nativeDispatchReturned = false;
        try {
            nativeBoundaryEntered = true;
            gameModeExtension.litematica_printer$useItemOn(
                    localPrediction, InteractionHand.MAIN_HAND, blockHitResult);
            nativeDispatchReturned = true;
        } catch (RuntimeException exception) {
            dispatchFailure = exception;
        }

        if (nativeDispatchReturned) {
            try {
                gateway.dispatchConfirmed(integrationRequest);
            } catch (RuntimeException exception) {
                // Native dispatch returned, so replay is forbidden even when its
                // observer fails. The controller's barrier/watchdog owns recovery.
                exception.printStackTrace();
            }
        } else if (nativeBoundaryEntered) {
            notifyDispatchUncertain(gateway, "native_dispatch_failed");
        }
        integrationDispatchCompleted = true;

        if (changedSneak) {
            try {
                setShift(player, wasSneak);
            } catch (RuntimeException restoreFailure) {
                if (dispatchFailure != null) dispatchFailure.addSuppressed(restoreFailure);
                else dispatchFailure = restoreFailure;
            }
        }
        clearQueue();
        if (dispatchFailure != null) {
            // The native outcome is already classified as confirmed or uncertain
            // and the queue is gone. Returning normally lets the legacy RETURN
            // observer preserve its verification/cooldown semantics as well.
            dispatchFailure.printStackTrace();
        }
        return this;
    }

    public void setLook(PlayerLook look) {
        this.look = look;
    }

    public void setNeedWaitModifyLookFromAction(boolean needWaitModifyLook) {
        this.actionRequiresWaitModifyLook = needWaitModifyLook;
    }

    public boolean isIntegrationHold() {
        return integrationHold;
    }

    public boolean isQueueIdle() {
        return target == null && integrationRequest == null;
    }

    public boolean cancelScheduled(ScheduledAttempt expected) {
        if (expected == null || integrationRequest == null
                || !expected.equals(integrationRequest.scheduledAttempt())) return false;
        clearQueue();
        return true;
    }

    public void setShift(LocalPlayer player, boolean shift) {
        ServerboundPlayerCommandPacket packet = new ServerboundPlayerCommandPacket(player,
                shift ? ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY
                        : ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY);
        player.setShiftKeyDown(shift);
        PacketUtils.sendPacket(packet);
    }

    public void clearQueue() {
        PlacementRequest cleared = integrationRequest;
        if (cleared != null && !integrationDispatchCompleted) {
            try {
                PrinterIntegrationApi.gateway().queueClearedWithoutDispatch(cleared);
            } catch (RuntimeException exception) {
                exception.printStackTrace();
            }
        }
        integrationRequest = null;
        integrationHold = false;
        integrationDispatchCompleted = false;
        this.target = null;
        this.side = null;
        this.hitModifier = null;
        this.useShift = false;
        this.useProtocol = false;
        this.needWaitModifyLook = false;
        this.actionRequiresWaitModifyLook = false;
        this.look = null;
    }

    private void dropBeforeDispatch(Gateway gateway, String reason) {
        PlacementRequest dropped = integrationRequest;
        if (dropped != null) {
            try {
                gateway.dropped(dropped, reason == null || reason.isBlank()
                        ? "integration_drop" : reason);
            } catch (RuntimeException exception) {
                exception.printStackTrace();
            }
        }
        // Suppress the generic queue-cleared callback after the explicit drop.
        integrationRequest = null;
        integrationDispatchCompleted = true;
        clearQueue();
        needWaitModifyLook = true;
        syntheticDropWait = true;
    }

    private void notifyDispatchUncertain(Gateway gateway, String reason) {
        PlacementRequest uncertain = integrationRequest;
        if (uncertain == null) return;
        try {
            gateway.dispatchUncertain(uncertain,
                    reason == null || reason.isBlank() ? "dispatch_uncertain" : reason);
        } catch (RuntimeException exception) {
            exception.printStackTrace();
        }
    }
}
