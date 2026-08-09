package me.aleksilassila.litematica.printer.api;

import me.aleksilassila.litematica.printer.api.PrinterIntegrationApi.ScheduledAttempt;

/** Atomic bridge into build.365's mature canProcessPos/executeIteration pair. */
public interface PrinterPlacementFacade {
    enum SubmissionResult {
        CAPTURED_BY_GATEWAY,
        NOT_ACTIONABLE,
        NOT_CAPTURED,
        BUSY,
        FAILED
    }

    /** True only when no native ActionManager entry can be overwritten. */
    boolean isPlacementQueueIdle();

    /**
     * Cancels only the queue entry carrying this exact scheduled identity.
     * The gateway receives queueClearedWithoutDispatch before this returns.
     */
    boolean cancelScheduled(ScheduledAttempt attempt);

    SubmissionResult submitScheduled(ScheduledAttempt attempt);
}
