package me.aleksilassila.litematica.printer.api;

/**
 * Explicit maintenance boundary for restarting build.365's legacy scanner.
 *
 * <p>The implementation invalidates only the native scan envelope and cursor.
 * The next Printer tick rebuilds them through the native handler from the
 * current player position and {@code ConfigUtils.getEffectiveRange()}, so
 * configured range, legal reach, spherical interaction filtering, iteration
 * order and player-movement semantics remain owned by Printer.</p>
 */
public interface LegacyScanControl {
    /**
     * Sets the maximum number of ordinary legacy coordinates visited by one
     * handler tick. This is deliberately independent from the placement
     * execution limit: air and non-schematic coordinates consume this budget,
     * while the iterator cursor is retained for the next tick.
     */
    void setLegacyScanVisitBudget(int maxVisitsPerTick);

    /** Returns the active ordinary-coordinate visit budget. */
    int getLegacyScanVisitBudget();

    /**
     * Queues exact positions skipped by the legacy iterator because their
     * required material was absent.
     *
     * <p>The native iterator cursor is deliberately left untouched. The
     * handler retries the accepted positions, with bounded work, before its
     * next ordinary scan. Capacity is fail-soft: an oversized batch is
     * truncated rather than converted into a full-range rescan. A
     * {@code false} result therefore means the targeted API itself is
     * unavailable and the caller may use its compatibility fallback.</p>
     */
    boolean revisitLegacyMissingPositions(long[] packedPositions);

    /** Clears only queued targeted revisits; it never changes the native cursor. */
    void clearLegacyMissingPositionRevisits();

    /**
     * Restarts legacy enumeration at the origin of a freshly rebuilt box.
     *
     * <p>This is intended for an inventory-acknowledged refill completion. It
     * does not calculate a range and deliberately invalidates the old box so
     * movement or a range change during refill is observed before scanning.</p>
     *
     * @return {@code true} when a current native box was reset; {@code false}
     *         when the placement queue is owned or the handler has not
     *         established a box yet. Callers must retain their restart latch
     *         and retry after a {@code false} result.
     */
    boolean restartLegacyScanFromCurrentRangeStart();

    /** Optional-integration helper which fails closed for an unpatched handler. */
    static boolean revisitMissingPositions(Object handler, long[] packedPositions) {
        return handler instanceof LegacyScanControl control
                && control.revisitLegacyMissingPositions(packedPositions);
    }

    /** Optional lifecycle hook; safe when the maintained Printer is absent. */
    static void clearMissingPositionRevisits(Object handler) {
        if (handler instanceof LegacyScanControl control) {
            control.clearLegacyMissingPositionRevisits();
        }
    }

    /**
     * Optional-integration helper for assigning a bounded ordinary scan budget.
     * A non-positive value is rejected instead of silently disabling the hard
     * bound.
     */
    static boolean configureScanVisitBudget(Object handler, int maxVisitsPerTick) {
        if (maxVisitsPerTick <= 0 || !(handler instanceof LegacyScanControl control)) {
            return false;
        }
        control.setLegacyScanVisitBudget(maxVisitsPerTick);
        return true;
    }

    /**
     * Compatibility fallback for an unpatched or saturated targeted queue.
     */
    static boolean restartCurrentRange(Object handler) {
        return handler instanceof LegacyScanControl control
                && control.restartLegacyScanFromCurrentRangeStart();
    }
}
