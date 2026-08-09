package me.aleksilassila.litematica.printer.api.scheduler;

/**
 * Session-scoped acknowledgement produced only after Tom's placement safety
 * chain reaches a terminal outcome.
 *
 * <p>A scheduler must ignore a result whose session or index revision does not
 * match its owning {@link SchematicPrintIndex}. Candidate IDs are therefore
 * never meaningful on their own.</p>
 */
public record PlacementResult(long sessionId, long indexRevision,
		int candidateId, long packedPosition, long attemptToken, Outcome outcome) {
	public enum Outcome {
		PLACED,
		SATISFIED,
		RETRY,
		DEFER
	}

	public PlacementResult {
		if (sessionId < 0L) throw new IllegalArgumentException("sessionId must not be negative");
		if (indexRevision < 0L) {
			throw new IllegalArgumentException("indexRevision must not be negative");
		}
		if (candidateId < 0) throw new IllegalArgumentException("candidateId must not be negative");
		if (attemptToken <= 0L) {
			throw new IllegalArgumentException("attemptToken must be positive");
		}
		if (outcome == null) throw new NullPointerException("outcome");
	}
}
