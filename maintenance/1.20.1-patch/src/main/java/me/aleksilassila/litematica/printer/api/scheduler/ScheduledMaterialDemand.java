package me.aleksilassila.litematica.printer.api.scheduler;

import java.util.List;
import java.util.Objects;

/**
 * Ordered material demand exported by a scheduler, rather than inferred from
 * recently scanned misses.
 */
public record ScheduledMaterialDemand<M>(long sessionId, long indexRevision,
		long schedulerGeneration, int activeLayer, int placementPhase,
		List<Entry<M>> entries) {
	public ScheduledMaterialDemand {
		if (sessionId < 0L) throw new IllegalArgumentException("sessionId must not be negative");
		if (indexRevision < 0L) throw new IllegalArgumentException("indexRevision must not be negative");
		if (schedulerGeneration < 0L) {
			throw new IllegalArgumentException("schedulerGeneration must not be negative");
		}
		entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
	}

	public record Entry<M>(M material, long requiredCount) {
		public Entry {
			material = Objects.requireNonNull(material, "material");
			if (requiredCount <= 0L) {
				throw new IllegalArgumentException("requiredCount must be positive");
			}
		}
	}

	public boolean isEmpty() {
		return entries.isEmpty();
	}
}
