package me.aleksilassila.litematica.printer.api;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stable optional boundary between Printer's placement engine and storage mods.
 * The default gateway is fail-open for legacy Printer behavior; executable
 * layered ownership must separately require an installed capable gateway.
 */
public final class PrinterIntegrationApi {
    public enum GateDecision {
        ALLOW,
        HOLD,
        DROP
    }

    /** Identifies whether Tom may require PrintHandler's verified schematic context. */
    public enum PlacementSource {
        PRINT_HANDLER(true),
        OTHER_HANDLER(false);

        private final boolean verifiedPrintContextRequired;

        PlacementSource(boolean verifiedPrintContextRequired) {
            this.verifiedPrintContextRequired = verifiedPrintContextRequired;
        }

        public boolean requiresVerifiedPrintContext() {
            return verifiedPrintContextRequired;
        }
    }

    public record ScheduledAttempt(long sessionId, long indexRevision,
            int candidateId, long packedPosition, long attemptToken) {
        public ScheduledAttempt {
            if (sessionId < 0L) throw new IllegalArgumentException("sessionId must not be negative");
            if (indexRevision < 0L) {
                throw new IllegalArgumentException("indexRevision must not be negative");
            }
            if (candidateId < 0) throw new IllegalArgumentException("candidateId must not be negative");
            if (attemptToken <= 0L) {
                throw new IllegalArgumentException("attemptToken must be positive");
            }
        }
    }

    /** Immutable identity for the one ActionManager queue entry. */
    public record PlacementRequest(long requestId, long packedTarget,
            int sideOrdinal, double hitModifierX, double hitModifierY,
            double hitModifierZ, boolean precisionProtocol,
            PlacementSource source, ScheduledAttempt scheduledAttempt) {
        public PlacementRequest {
            if (requestId <= 0L) throw new IllegalArgumentException("requestId must be positive");
            if (sideOrdinal < 0 || sideOrdinal > 5) {
                throw new IllegalArgumentException("sideOrdinal is outside Direction values");
            }
            if (!Double.isFinite(hitModifierX) || !Double.isFinite(hitModifierY)
                    || !Double.isFinite(hitModifierZ)) {
                throw new IllegalArgumentException("hit modifier must be finite");
            }
            source = Objects.requireNonNull(source, "source");
            // packedTarget is the native click target. A normal placement often
            // clicks the supporting neighbour, while ScheduledAttempt stores the
            // desired schematic position. They are intentionally independent.
        }
    }

    public record GateResult(GateDecision decision,
            boolean restartNativeLookProtocol, String reason) {
        public GateResult {
            decision = Objects.requireNonNull(decision, "decision");
            reason = reason == null ? "" : reason;
            if (decision != GateDecision.DROP && !reason.isEmpty()) {
                throw new IllegalArgumentException("only a dropped request may carry a reason");
            }
        }

        public static GateResult allow(boolean restartNativeLookProtocol) {
            return new GateResult(GateDecision.ALLOW, restartNativeLookProtocol, "");
        }

        public static GateResult hold() {
            return new GateResult(GateDecision.HOLD, false, "");
        }

        public static GateResult drop(String reason) {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("drop reason must not be blank");
            }
            return new GateResult(GateDecision.DROP, false, reason);
        }
    }

    public interface Gateway {
        GatewayCapabilities capabilities();

        GateResult evaluate(PlacementRequest request);

        default void beforeDispatch(PlacementRequest request) {
        }

        default void dispatchConfirmed(PlacementRequest request) {
        }

        /**
         * Called when the native dispatch boundary was entered but Java control
         * did not return normally. The queue will be cleared and must not be
         * treated as an undispatched retry.
         */
        default void dispatchUncertain(PlacementRequest request, String reason) {
        }

        default void queueClearedWithoutDispatch(PlacementRequest request) {
        }

        default void dropped(PlacementRequest request, String reason) {
        }
    }

    public record GatewayCapabilities(boolean safeHandSwap,
            boolean actionGate, boolean placementVerification,
            boolean materialLease, boolean scheduledDemand) {
        public boolean supportsLayeredPlacement() {
            return safeHandSwap && actionGate && placementVerification && materialLease;
        }
    }

    private static final GatewayCapabilities NO_CAPABILITIES =
            new GatewayCapabilities(false, false, false, false, false);
    private static final Gateway NOOP = new Gateway() {
        @Override
        public GatewayCapabilities capabilities() {
            return NO_CAPABILITIES;
        }

        @Override
        public GateResult evaluate(PlacementRequest request) {
            return GateResult.allow(false);
        }
    };
    private static final AtomicReference<Gateway> GATEWAY = new AtomicReference<>(NOOP);
    private static final AtomicLong NEXT_REQUEST_ID = new AtomicLong(1L);
    private static final AtomicLong EXTERNAL_DISPATCH_SEQUENCE = new AtomicLong();
    private static volatile boolean nativeActionManagerHookAvailable;
    private static ScheduledAttempt scheduledAttempt;
    private static Thread scheduledAttemptOwner;
    private static boolean scheduledAttemptCaptured;

    private PrinterIntegrationApi() {
    }

    public static boolean install(Gateway expectedCurrent, Gateway replacement) {
        return GATEWAY.compareAndSet(Objects.requireNonNull(expectedCurrent, "expectedCurrent"),
                Objects.requireNonNull(replacement, "replacement"));
    }

    public static boolean isGatewayInstalled() {
        return GATEWAY.get() != NOOP;
    }

    public static Gateway gateway() {
        return GATEWAY.get();
    }

    public static Gateway noopGateway() {
        return NOOP;
    }

    /** Called only from the maintained ActionManager replacement's static init. */
    public static boolean registerNativeActionManagerHook() {
        nativeActionManagerHookAvailable = true;
        return true;
    }

    public static boolean nativeActionManagerHookAvailable() {
        return nativeActionManagerHookAvailable;
    }

    /**
     * Marks a direct placement boundary which intentionally bypasses
     * ActionManager (for example an integration-owned use-item lane). The mark
     * is synchronous and says only that replay is no longer safe; it never
     * waits for, or implies, a server acknowledgement.
     */
    public static long markExternalDispatchBoundary() {
        return EXTERNAL_DISPATCH_SEQUENCE.updateAndGet(
                value -> value == Long.MAX_VALUE ? 1L : value + 1L);
    }

    /** Current direct-placement boundary sequence for same-thread comparison. */
    public static long externalDispatchSequence() {
        return EXTERNAL_DISPATCH_SEQUENCE.get();
    }

    public static synchronized boolean stageScheduledAttempt(ScheduledAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        Thread current = Thread.currentThread();
        if (scheduledAttempt != null) return false;
        scheduledAttempt = attempt;
        scheduledAttemptOwner = current;
        scheduledAttemptCaptured = false;
        return true;
    }

    public static synchronized boolean finishScheduledAttempt(ScheduledAttempt expected) {
        if (scheduledAttempt != expected || scheduledAttemptOwner != Thread.currentThread()) {
            return false;
        }
        boolean captured = scheduledAttemptCaptured;
        scheduledAttempt = null;
        scheduledAttemptOwner = null;
        scheduledAttemptCaptured = false;
        return captured;
    }

    /** Used only by Printer's queue implementation on the same client thread. */
    public static synchronized PlacementRequest captureRequest(long packedTarget,
            int sideOrdinal, double hitModifierX, double hitModifierY,
            double hitModifierZ, boolean precisionProtocol, PlacementSource source) {
        ScheduledAttempt scheduled = scheduledAttemptOwner == Thread.currentThread()
                ? scheduledAttempt : null;
        PlacementRequest request = new PlacementRequest(nextRequestId(), packedTarget, sideOrdinal,
                hitModifierX, hitModifierY, hitModifierZ, precisionProtocol,
                Objects.requireNonNull(source, "source"), scheduled);
        if (scheduled != null) scheduledAttemptCaptured = true;
        return request;
    }

    private static long nextRequestId() {
        while (true) {
            long current = NEXT_REQUEST_ID.getAndUpdate(value -> value == Long.MAX_VALUE ? 1L : value + 1L);
            if (current > 0L) return current;
        }
    }
}
