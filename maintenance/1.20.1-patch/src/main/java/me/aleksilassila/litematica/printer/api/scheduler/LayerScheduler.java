package me.aleksilassila.litematica.printer.api.scheduler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import me.aleksilassila.litematica.printer.api.scheduler.SchematicPrintIndex.Candidate;
import me.aleksilassila.litematica.printer.api.scheduler.SchematicPrintIndex.Layer;

/**
 * Bounded, persistent, layer-major scheduler over a {@link SchematicPrintIndex}.
 *
 * <p>The scheduler owns only ordering and attempt identity. Minecraft placement
 * semantics remain in the placement adapter, while terminal outcomes must come
 * back through the safety and verification boundary as a {@link PlacementResult}.</p>
 */
public final class LayerScheduler<M> {
	public enum Validation {
		READY,
		SATISFIED,
		TEMPORARILY_BLOCKED,
		MISSING_MATERIAL,
		OUT_OF_REACH,
		MANUAL
	}

	public enum Completion {
		PLACED,
		SATISFIED,
		RETRY,
		DEFER
	}

	/** Resolution chosen after a dispatched lease exceeded the gateway deadline. */
	public enum ExpiredLeaseResolution {
		SATISFIED,
		RETRY
	}

	/** Event classes which may make a deferred candidate useful again. */
	public enum RevisitTrigger {
		SUPPORT_OR_WORLD_CHANGED,
		MATERIAL_AVAILABLE,
		PLAYER_OR_RANGE_CHANGED,
		PERIODIC_RETRY,
		MANUAL_RETRY
	}

	@FunctionalInterface
	public interface CandidateValidator<M> {
		Validation validate(Candidate<M> candidate);
	}

	public record WorkLimits(int candidateWorkUnits, int placements) {
		public WorkLimits {
			if (candidateWorkUnits <= 0) {
				throw new IllegalArgumentException("candidateWorkUnits must be positive");
			}
			if (placements <= 0) throw new IllegalArgumentException("placements must be positive");
		}
	}

	/** One scheduler-issued placement lease. Tokens never survive a retry. */
	public record PlacementWork<M>(Candidate<M> candidate, long attemptToken) {
		public PlacementWork {
			candidate = Objects.requireNonNull(candidate, "candidate");
			if (attemptToken <= 0L) {
				throw new IllegalArgumentException("attemptToken must be positive");
			}
		}
	}

	/**
	 * One bounded scheduling slice. Every non-empty {@code ready} list belongs to
	 * exactly one layer, phase and material run.
	 */
	public record WorkBatch<M>(int activeLayer, int placementPhase, M material,
			int workUnits, int validations, List<PlacementWork<M>> ready,
			boolean phaseAdvanced, boolean layerAdvanced,
			boolean waitingForResults, boolean waitingForRevisit, boolean finished) {
		public WorkBatch {
			if (workUnits < 0) throw new IllegalArgumentException("workUnits must not be negative");
			if (validations < 0 || validations > workUnits) {
				throw new IllegalArgumentException("validations must be within workUnits");
			}
			ready = List.copyOf(Objects.requireNonNull(ready, "ready"));
			if (!ready.isEmpty() && material == null) {
				throw new IllegalArgumentException("a non-empty batch must identify its material");
			}
			for (PlacementWork<M> placement : ready) {
				Candidate<M> candidate = placement.candidate();
				if (candidate.y() != activeLayer
						|| candidate.placementPhase() != placementPhase
						|| !Objects.equals(candidate.material(), material)) {
					throw new IllegalArgumentException(
							"ready placements must share the batch layer, phase and material");
				}
			}
		}
	}

	private static final byte UNKNOWN = 0;
	private static final byte IN_FLIGHT = 1;
	private static final byte COMPLETE = 2;
	private static final byte DEFERRED_TEMPORARY = 3;
	private static final byte DEFERRED_MATERIAL = 4;
	private static final byte DEFERRED_REACH = 5;
	private static final byte DEFERRED_MANUAL = 6;
	private static final int REVISIT_TEMPORARY = 1 << (DEFERRED_TEMPORARY - 3);
	private static final int REVISIT_MATERIAL = 1 << (DEFERRED_MATERIAL - 3);
	private static final int REVISIT_REACH = 1 << (DEFERRED_REACH - 3);
	private static final int REVISIT_MANUAL = 1 << (DEFERRED_MANUAL - 3);
	private static long nextGlobalAttemptToken = 1L;

	private final SchematicPrintIndex<M> index;
	private final List<LayerProgress<M>> layers;
	private final byte[] candidateStates;
	private final int[] candidateLayer;
	private final int[] candidatePhase;
	private final int[] candidateRun;
	private final int[] scheduleOffset;
	private final long[] attemptTokens;
	private final long[] deferredEpoch;
	private final long[] revisitCutoff = new long[DEFERRED_MANUAL + 1];
	private final int[] deferredReasonCounts = new int[DEFERRED_MANUAL + 1];
	private final DeferredQueue temporaryDeferred = new DeferredQueue();
	private final DeferredQueue materialDeferred = new DeferredQueue();
	private final DeferredQueue reachDeferred = new DeferredQueue();
	private final DeferredQueue manualDeferred = new DeferredQueue();
	private int activeLayerIndex;
	private int nextRevisitReason;
	private int pendingRevisitReasons;
	private int remainingCandidates;
	private int deferredCandidates;
	private int inFlightCandidates;
	private long deferredSequence;
	private long generation;

	public LayerScheduler(SchematicPrintIndex<M> index, M preferredMaterial) {
		this.index = Objects.requireNonNull(index, "index");
		this.layers = new ArrayList<>(index.layers().size());
		this.candidateStates = new byte[index.candidateCount()];
		this.candidateLayer = new int[index.candidateCount()];
		this.candidatePhase = new int[index.candidateCount()];
		this.candidateRun = new int[index.candidateCount()];
		this.scheduleOffset = new int[index.candidateCount()];
		this.attemptTokens = new long[index.candidateCount()];
		this.deferredEpoch = new long[index.candidateCount()];
		this.remainingCandidates = index.candidateCount();

		for (int layerIndex = 0; layerIndex < index.layers().size(); layerIndex++) {
			Layer<M> layer = index.layers().get(layerIndex);
			LayerProgress<M> progress = new LayerProgress<>(layer.y(),
					phaseSchedule(layer, preferredMaterial));
			layers.add(progress);
			for (int phaseIndex = 0; phaseIndex < progress.phases.size(); phaseIndex++) {
				PhaseProgress<M> phase = progress.phases.get(phaseIndex);
				for (int runIndex = 0; runIndex < phase.runs.size(); runIndex++) {
					MaterialRun<M> run = phase.runs.get(runIndex);
					for (int offset = 0; offset < run.candidates.size(); offset++) {
						Candidate<M> candidate = run.candidates.get(offset);
						candidateLayer[candidate.id()] = layerIndex;
						candidatePhase[candidate.id()] = phaseIndex;
						candidateRun[candidate.id()] = runIndex;
						scheduleOffset[candidate.id()] = offset;
					}
				}
			}
		}
		activeLayerIndex = layers.isEmpty() ? -1 : 0;
	}

	public WorkBatch<M> poll(WorkLimits limits, CandidateValidator<M> validator) {
		Objects.requireNonNull(limits, "limits");
		Objects.requireNonNull(validator, "validator");
		if (remainingCandidates == 0) {
			activeLayerIndex = -1;
			return emptyBatch(0, 0, false, false);
		}
		// ActionManager and Tom's verification chain have one session-owned lane.
		// Never issue another batch while any earlier lease still owns that lane.
		if (inFlightCandidates > 0) return emptyBatch(0, 0, false, false);

		int workUnits = reactivatePending(limits.candidateWorkUnits());
		int validations = 0;
		if (activeLayerIndex < 0 || workUnits >= limits.candidateWorkUnits()) {
			return emptyBatch(workUnits, validations, false, false);
		}

		LayerProgress<M> reportedLayer = layers.get(activeLayerIndex);
		PhaseProgress<M> reportedPhase = reportedLayer.activePhase();
		int activeLayer = reportedLayer.y;
		int placementPhase = reportedPhase.phase;
		M batchMaterial = null;
		List<PlacementWork<M>> ready =
				new ArrayList<>(Math.min(limits.placements(), 16));
		boolean phaseAdvanced = false;
		boolean layerAdvanced = false;

		while (activeLayerIndex >= 0
				&& workUnits < limits.candidateWorkUnits()
				&& ready.size() < limits.placements()) {
			LayerProgress<M> layer = layers.get(activeLayerIndex);
			PhaseProgress<M> phase = layer.activePhase();
			MaterialRun<M> run = phase.activeRun();

			while (run.cursor < run.candidates.size()
					&& workUnits < limits.candidateWorkUnits()
					&& ready.size() < limits.placements()) {
				Candidate<M> candidate = run.candidates.get(run.cursor);
				workUnits++;
				if (candidateStates[candidate.id()] != UNKNOWN) {
					run.cursor++;
					continue;
				}

				validations++;
				Validation result = Objects.requireNonNull(
						validator.validate(candidate), "validation result");
				// Advance only after validation succeeds. An exception leaves this
				// candidate at the cursor so the caller can retry without losing work.
				run.cursor++;
				switch (result) {
					case READY -> {
						long token = issueAttemptToken();
						candidateStates[candidate.id()] = IN_FLIGHT;
						attemptTokens[candidate.id()] = token;
						run.inFlight++;
						phase.inFlight++;
						layer.inFlight++;
						inFlightCandidates++;
						batchMaterial = run.material;
						ready.add(new PlacementWork<>(candidate, token));
						generation++;
					}
					case SATISFIED -> completeCandidate(candidate.id(), layer, phase, run);
					case TEMPORARILY_BLOCKED -> deferCandidate(candidate.id(),
							DEFERRED_TEMPORARY, temporaryDeferred, layer, phase, run);
					case MISSING_MATERIAL -> deferCandidate(candidate.id(),
							DEFERRED_MATERIAL, materialDeferred, layer, phase, run);
					case OUT_OF_REACH -> deferCandidate(candidate.id(),
							DEFERRED_REACH, reachDeferred, layer, phase, run);
					case MANUAL -> deferCandidate(candidate.id(),
							DEFERRED_MANUAL, manualDeferred, layer, phase, run);
				}
			}

			if (run.cursor < run.candidates.size()
					|| !ready.isEmpty() || run.inFlight > 0) break;

			if (phase.activeRunIndex + 1 < phase.runs.size()) {
				phase.activeRunIndex++;
				generation++;
				continue;
			}

			if (layer.activePhaseIndex + 1 < layer.phases.size()) {
				// A new phase is never issued in the same WorkBatch. This remains true
				// even when the preceding phase produced no placement work.
				layer.activePhaseIndex++;
				generation++;
				phaseAdvanced = true;
				break;
			}

			layerAdvanced = advanceForwardLayer();
			break;
		}

		return new WorkBatch<>(activeLayer, placementPhase, batchMaterial,
				workUnits, validations, ready, phaseAdvanced, layerAdvanced,
				inFlightCandidates > 0, isWaitingForRevisit(), isFinished());
	}

	/**
	 * Applies a session-scoped adapter result. A duplicate, late, retried or
	 * otherwise non-owning result is rejected without mutating scheduler state.
	 */
	public boolean acknowledge(PlacementResult result) {
		Objects.requireNonNull(result, "result");
		if (result.sessionId() != index.sessionId()
				|| result.indexRevision() != index.revision()
				|| result.candidateId() < 0
				|| result.candidateId() >= index.candidateCount()) return false;
		Candidate<M> candidate = index.candidates().get(result.candidateId());
		if (candidate.id() != result.candidateId()
				|| candidate.packedPosition() != result.packedPosition()
				|| candidateStates[result.candidateId()] != IN_FLIGHT
				|| attemptTokens[result.candidateId()] != result.attemptToken()) return false;

		Completion completion = switch (result.outcome()) {
			case PLACED -> Completion.PLACED;
			case SATISFIED -> Completion.SATISFIED;
			case RETRY -> Completion.RETRY;
			case DEFER -> Completion.DEFER;
		};
		applyCompletion(result.candidateId(), completion);
		return true;
	}

	/**
	 * Releases a scheduler lease which the adapter did not dispatch. Every item
	 * in a partially consumed WorkBatch must either enter the safety gateway or be
	 * returned through this method; otherwise the material-run barrier must wait.
	 */
	public boolean cancelUndispatched(PlacementWork<M> placement) {
		Objects.requireNonNull(placement, "placement");
		Candidate<M> supplied = placement.candidate();
		if (supplied.id() < 0 || supplied.id() >= index.candidateCount()) return false;
		Candidate<M> indexed = index.candidates().get(supplied.id());
		if (indexed != supplied
				|| candidateStates[supplied.id()] != IN_FLIGHT
				|| attemptTokens[supplied.id()] != placement.attemptToken()) return false;
		applyCompletion(supplied.id(), Completion.RETRY);
		return true;
	}

	/**
	 * Completes a lease that reached Printer's atomic preparation facade but did
	 * not create an ActionManager queue. This preserves the validator's precise
	 * deferred reason instead of retrying the same impossible attempt every tick.
	 */
	public boolean completeUndispatched(PlacementWork<M> placement,
			Validation terminalValidation) {
		Objects.requireNonNull(placement, "placement");
		Objects.requireNonNull(terminalValidation, "terminalValidation");
		if (terminalValidation == Validation.READY) {
			throw new IllegalArgumentException("READY is not an undispatched terminal state");
		}
		Candidate<M> supplied = placement.candidate();
		if (supplied.id() < 0 || supplied.id() >= index.candidateCount()) return false;
		Candidate<M> indexed = index.candidates().get(supplied.id());
		if (indexed != supplied
				|| candidateStates[supplied.id()] != IN_FLIGHT
				|| attemptTokens[supplied.id()] != placement.attemptToken()) return false;

		int candidateId = supplied.id();
		LayerProgress<M> layer = layers.get(candidateLayer[candidateId]);
		PhaseProgress<M> phase = layer.phases.get(candidatePhase[candidateId]);
		MaterialRun<M> run = phase.runs.get(candidateRun[candidateId]);
		releaseLease(candidateId, layer, phase, run);
		switch (terminalValidation) {
			case SATISFIED -> completeCandidate(candidateId, layer, phase, run);
			case TEMPORARILY_BLOCKED -> deferCandidate(candidateId,
					DEFERRED_TEMPORARY, temporaryDeferred, layer, phase, run);
			case MISSING_MATERIAL -> deferCandidate(candidateId,
					DEFERRED_MATERIAL, materialDeferred, layer, phase, run);
			case OUT_OF_REACH -> deferCandidate(candidateId,
					DEFERRED_REACH, reachDeferred, layer, phase, run);
			case MANUAL -> deferCandidate(candidateId,
					DEFERRED_MANUAL, manualDeferred, layer, phase, run);
			case READY -> throw new AssertionError("validated above");
		}
		if (remainingCandidates == 0) activeLayerIndex = -1;
		return true;
	}

	/**
	 * Resolves a dispatched lease whose terminal verification never arrived.
	 * The gateway must first stop owning the ActionManager queue and reconcile
	 * the world state; only the exact still-current token may be expired.
	 */
	public boolean expireAttempt(PlacementWork<M> placement,
			ExpiredLeaseResolution resolution) {
		Objects.requireNonNull(placement, "placement");
		Objects.requireNonNull(resolution, "resolution");
		Candidate<M> supplied = placement.candidate();
		if (supplied.id() < 0 || supplied.id() >= index.candidateCount()) return false;
		Candidate<M> indexed = index.candidates().get(supplied.id());
		if (indexed != supplied
				|| candidateStates[supplied.id()] != IN_FLIGHT
				|| attemptTokens[supplied.id()] != placement.attemptToken()) return false;
		applyCompletion(supplied.id(), resolution == ExpiredLeaseResolution.SATISFIED
				? Completion.SATISFIED : Completion.RETRY);
		return true;
	}

	/**
	 * Queues only the deferred reasons affected by an external state change.
	 * Reactivation itself is consumed under the next {@link WorkLimits} budget.
	 */
	public boolean requestRevisit(RevisitTrigger trigger) {
		Objects.requireNonNull(trigger, "trigger");
		int requestedReasons = switch (trigger) {
			case SUPPORT_OR_WORLD_CHANGED -> REVISIT_TEMPORARY;
			case MATERIAL_AVAILABLE -> REVISIT_MATERIAL;
			case PLAYER_OR_RANGE_CHANGED -> REVISIT_REACH | REVISIT_TEMPORARY;
			case PERIODIC_RETRY ->
					REVISIT_TEMPORARY | REVISIT_MATERIAL | REVISIT_REACH;
			case MANUAL_RETRY -> REVISIT_TEMPORARY | REVISIT_MATERIAL
					| REVISIT_REACH | REVISIT_MANUAL;
		};
		int availableReasons = 0;
		for (byte state = DEFERRED_TEMPORARY; state <= DEFERRED_MANUAL; state++) {
			int reason = revisitReason(state);
			if ((requestedReasons & reason) != 0 && deferredReasonCounts[state] > 0) {
				availableReasons |= reason;
				revisitCutoff[state] = deferredSequence;
			}
		}
		if (availableReasons == 0) return false;
		pendingRevisitReasons |= availableReasons;
		generation++;
		return true;
	}

	/** Exports at most the current and next material run. */
	public ScheduledMaterialDemand<M> upcomingDemand(int maximumCandidates) {
		return upcomingDemand(maximumCandidates, 2);
	}

	/**
	 * Exports one layer/phase horizon. Material-deferred candidates remain visible
	 * so lack of inventory cannot erase the demand required to wake them.
	 */
	public ScheduledMaterialDemand<M> upcomingDemand(
			int maximumCandidates, int maximumMaterialRuns) {
		if (maximumCandidates < 0) {
			throw new IllegalArgumentException("maximumCandidates must not be negative");
		}
		if (maximumMaterialRuns <= 0) {
			throw new IllegalArgumentException("maximumMaterialRuns must be positive");
		}
		if (remainingCandidates == 0 || maximumCandidates == 0) {
			return emptyDemand();
		}
		DemandAnchor anchor = demandAnchor();
		if (anchor == null) return emptyDemand();

		LayerProgress<M> layer = layers.get(anchor.layerIndex);
		PhaseProgress<M> phase = layer.phases.get(anchor.phaseIndex);
		List<ScheduledMaterialDemand.Entry<M>> entries = new ArrayList<>();
		int remaining = maximumCandidates;
		int selectedRuns = 0;
		int firstRun = anchor.activePath && phase.materialDeferred == 0
				? phase.activeRunIndex : 0;
		for (int runIndex = firstRun; runIndex < phase.runs.size()
				&& remaining > 0 && selectedRuns < maximumMaterialRuns; runIndex++) {
			MaterialRun<M> run = phase.runs.get(runIndex);
			long available = anchor.activePath
					? run.remaining - (run.deferred - run.materialDeferred)
					: run.materialDeferred;
			long count = Math.min(available, remaining);
			remaining -= (int) count;
			if (count > 0L) {
				entries.add(new ScheduledMaterialDemand.Entry<>(run.material, count));
				selectedRuns++;
			}
		}
		return new ScheduledMaterialDemand<>(index.sessionId(), index.revision(), generation,
				layer.y, phase.phase, entries);
	}

	private DemandAnchor demandAnchor() {
		int materialLayer = -1;
		int materialPhase = -1;
		for (int layerIndex = 0; layerIndex < layers.size(); layerIndex++) {
			LayerProgress<M> layer = layers.get(layerIndex);
			if (layer.materialDeferred == 0) continue;
			for (int phaseIndex = 0; phaseIndex < layer.phases.size(); phaseIndex++) {
				if (layer.phases.get(phaseIndex).materialDeferred > 0) {
					materialLayer = layerIndex;
					materialPhase = phaseIndex;
					break;
				}
			}
			break;
		}

		if (activeLayerIndex < 0) {
			return materialLayer >= 0
					? new DemandAnchor(materialLayer, materialPhase, false) : null;
		}
		LayerProgress<M> activeLayer = layers.get(activeLayerIndex);
		int activePhase = activeLayer.activePhaseIndex;
		if (materialLayer >= 0 && (materialLayer < activeLayerIndex
				|| materialLayer == activeLayerIndex && materialPhase < activePhase)) {
			return new DemandAnchor(materialLayer, materialPhase, false);
		}
		return new DemandAnchor(activeLayerIndex, activePhase, true);
	}

	public int activeLayer() {
		return activeLayerIndex >= 0 ? layers.get(activeLayerIndex).y : Integer.MIN_VALUE;
	}

	public int activePlacementPhase() {
		return activeLayerIndex >= 0 ? layers.get(activeLayerIndex).activePhase().phase : -1;
	}

	public M activeMaterial() {
		return activeLayerIndex >= 0
				? layers.get(activeLayerIndex).activePhase().activeRun().material : null;
	}

	public long generation() {
		return generation;
	}

	public int remainingCandidates() {
		return remainingCandidates;
	}

	public int deferredCandidates() {
		return deferredCandidates;
	}

	public int inFlightCandidates() {
		return inFlightCandidates;
	}

	public boolean hasPendingRevisit() {
		return pendingRevisitReasons != 0;
	}

	public boolean isWaitingForRevisit() {
		return activeLayerIndex < 0 && remainingCandidates > 0
				&& pendingRevisitReasons == 0;
	}

	public boolean isFinished() {
		return remainingCandidates == 0;
	}

	private void applyCompletion(int candidateId, Completion completion) {
		int layerIndex = candidateLayer[candidateId];
		LayerProgress<M> layer = layers.get(layerIndex);
		PhaseProgress<M> phase = layer.phases.get(candidatePhase[candidateId]);
		MaterialRun<M> run = phase.runs.get(candidateRun[candidateId]);
		releaseLease(candidateId, layer, phase, run);

		switch (completion) {
			case PLACED, SATISFIED -> completeCandidate(candidateId, layer, phase, run);
			case RETRY -> {
				candidateStates[candidateId] = UNKNOWN;
				resetPath(candidateId);
				generation++;
			}
			case DEFER -> deferCandidate(candidateId, DEFERRED_TEMPORARY,
					temporaryDeferred, layer, phase, run);
		}
		if (remainingCandidates == 0) activeLayerIndex = -1;
	}

	private void releaseLease(int candidateId, LayerProgress<M> layer,
			PhaseProgress<M> phase, MaterialRun<M> run) {
		if (run.inFlight <= 0 || phase.inFlight <= 0 || layer.inFlight <= 0
				|| inFlightCandidates <= 0) {
			throw new IllegalStateException("Printer scheduler in-flight counters underflow");
		}
		run.inFlight--;
		phase.inFlight--;
		layer.inFlight--;
		inFlightCandidates--;
		attemptTokens[candidateId] = 0L;
	}

	private void completeCandidate(int candidateId, LayerProgress<M> layer,
			PhaseProgress<M> phase, MaterialRun<M> run) {
		candidateStates[candidateId] = COMPLETE;
		attemptTokens[candidateId] = 0L;
		remainingCandidates--;
		layer.remaining--;
		phase.remaining--;
		run.remaining--;
		generation++;
	}

	private void deferCandidate(int candidateId, byte state, DeferredQueue reasonQueue,
			LayerProgress<M> layer, PhaseProgress<M> phase, MaterialRun<M> run) {
		candidateStates[candidateId] = state;
		attemptTokens[candidateId] = 0L;
		if (deferredSequence == Long.MAX_VALUE) {
			throw new IllegalStateException("Printer scheduler deferred epoch space exhausted");
		}
		deferredEpoch[candidateId] = ++deferredSequence;
		reasonQueue.add(candidateId, deferredSequence);
		deferredReasonCounts[state]++;
		deferredCandidates++;
		layer.deferred++;
		phase.deferred++;
		if (state == DEFERRED_MATERIAL) {
			layer.materialDeferred++;
			phase.materialDeferred++;
			run.materialDeferred++;
		}
		run.deferred++;
		generation++;
	}

	private int reactivatePending(int budget) {
		// Rewinding an earlier layer/phase while a later lease is live would break
		// the single ActionManager ownership boundary. Wait for all results first.
		if (inFlightCandidates > 0) return 0;
		int consumed = 0;
		while (consumed < budget && pendingRevisitReasons != 0) {
			byte queuedState = nextPendingRevisitState();
			if (queuedState == 0) break;
			int reason = revisitReason(queuedState);
			DeferredQueue queue = deferredQueue(queuedState);
			DeferredEntry entry = queue.peek();
			if (entry == null || entry.epoch > revisitCutoff[queuedState]) {
				pendingRevisitReasons &= ~reason;
				continue;
			}
			queue.remove();
			int candidateId = entry.candidateId;
			byte state = candidateStates[candidateId];
			if (state != queuedState || deferredEpoch[candidateId] != entry.epoch) {
				consumed++;
				continue;
			}
			LayerProgress<M> layer = layers.get(candidateLayer[candidateId]);
			PhaseProgress<M> phase = layer.phases.get(candidatePhase[candidateId]);
			MaterialRun<M> run = phase.runs.get(candidateRun[candidateId]);
			deferredReasonCounts[state]--;
			deferredCandidates--;
			layer.deferred--;
			phase.deferred--;
			if (state == DEFERRED_MATERIAL) {
				layer.materialDeferred--;
				phase.materialDeferred--;
				run.materialDeferred--;
			}
			run.deferred--;
			deferredEpoch[candidateId] = 0L;
			candidateStates[candidateId] = UNKNOWN;
			resetPath(candidateId);
			generation++;
			// Resume normal scheduling immediately so a large revisit set cannot
			// spend many ticks only changing DEFERRED back to UNKNOWN. The valid
			// candidate itself is charged when normal validation visits it below.
			break;
		}
		return consumed;
	}

	private void resetPath(int candidateId) {
		int layerIndex = candidateLayer[candidateId];
		int phaseIndex = candidatePhase[candidateId];
		int runIndex = candidateRun[candidateId];
		LayerProgress<M> layer = layers.get(layerIndex);
		PhaseProgress<M> phase = layer.phases.get(phaseIndex);
		MaterialRun<M> run = phase.runs.get(runIndex);
		run.cursor = Math.min(run.cursor, scheduleOffset[candidateId]);
		phase.activeRunIndex = Math.min(phase.activeRunIndex, runIndex);
		layer.activePhaseIndex = Math.min(layer.activePhaseIndex, phaseIndex);
		activeLayerIndex = activeLayerIndex < 0
				? layerIndex : Math.min(activeLayerIndex, layerIndex);
	}

	private byte nextPendingRevisitState() {
		for (int offset = 0; offset < 4; offset++) {
			int reasonIndex = (nextRevisitReason + offset) & 3;
			int reason = 1 << reasonIndex;
			if ((pendingRevisitReasons & reason) == 0) continue;
			nextRevisitReason = (reasonIndex + 1) & 3;
			return (byte) (DEFERRED_TEMPORARY + reasonIndex);
		}
		return 0;
	}

	private DeferredQueue deferredQueue(byte state) {
		return switch (state) {
			case DEFERRED_TEMPORARY -> temporaryDeferred;
			case DEFERRED_MATERIAL -> materialDeferred;
			case DEFERRED_REACH -> reachDeferred;
			case DEFERRED_MANUAL -> manualDeferred;
			default -> throw new IllegalArgumentException("candidate state is not deferred: " + state);
		};
	}

	private static int revisitReason(byte state) {
		return 1 << (state - DEFERRED_TEMPORARY);
	}

	private boolean advanceForwardLayer() {
		generation++;
		for (int layerIndex = activeLayerIndex + 1; layerIndex < layers.size(); layerIndex++) {
			if (hasForwardWork(layers.get(layerIndex))) {
				activeLayerIndex = layerIndex;
				return true;
			}
		}
		activeLayerIndex = -1;
		return true;
	}

	private boolean hasForwardWork(LayerProgress<M> layer) {
		return layer.remaining > layer.deferred;
	}

	private static synchronized long issueAttemptToken() {
		long token = nextGlobalAttemptToken;
		if (nextGlobalAttemptToken == Long.MAX_VALUE) nextGlobalAttemptToken = 1L;
		else nextGlobalAttemptToken++;
		return token;
	}

	private WorkBatch<M> emptyBatch(int workUnits, int validations,
			boolean phaseAdvanced, boolean layerAdvanced) {
		int layer = activeLayer();
		int phase = activePlacementPhase();
		return new WorkBatch<>(layer, phase, null, workUnits, validations, List.of(),
				phaseAdvanced, layerAdvanced, inFlightCandidates > 0,
				isWaitingForRevisit(), isFinished());
	}

	private ScheduledMaterialDemand<M> emptyDemand() {
		return new ScheduledMaterialDemand<>(index.sessionId(), index.revision(), generation,
				Integer.MIN_VALUE, -1, List.of());
	}

	private static boolean isDeferred(byte state) {
		return state >= DEFERRED_TEMPORARY && state <= DEFERRED_MANUAL;
	}

	private static <M> List<PhaseProgress<M>> phaseSchedule(
			Layer<M> layer, M preferredMaterial) {
		Map<Integer, LinkedHashMap<M, List<Candidate<M>>>> phases = new TreeMap<>();
		for (Candidate<M> candidate : layer.candidates()) {
			phases.computeIfAbsent(candidate.placementPhase(), ignored -> new LinkedHashMap<>())
					.computeIfAbsent(candidate.material(), ignored -> new ArrayList<>())
					.add(candidate);
		}

		List<PhaseProgress<M>> schedule = new ArrayList<>(phases.size());
		for (Map.Entry<Integer, LinkedHashMap<M, List<Candidate<M>>>> phaseEntry
				: phases.entrySet()) {
			LinkedHashMap<M, List<Candidate<M>>> materials = phaseEntry.getValue();
			List<MaterialRun<M>> runs = new ArrayList<>(materials.size());
			if (preferredMaterial != null) {
				List<Candidate<M>> preferred = materials.get(preferredMaterial);
				if (preferred != null) {
					runs.add(new MaterialRun<>(preferredMaterial, preferred));
				}
			}
			for (Map.Entry<M, List<Candidate<M>>> entry : materials.entrySet()) {
				if (!Objects.equals(entry.getKey(), preferredMaterial)) {
					runs.add(new MaterialRun<>(entry.getKey(), entry.getValue()));
				}
			}
			schedule.add(new PhaseProgress<>(phaseEntry.getKey(), runs));
		}
		return List.copyOf(schedule);
	}

	private record DemandAnchor(int layerIndex, int phaseIndex, boolean activePath) {
	}

	private static final class LayerProgress<M> {
		private final int y;
		private final List<PhaseProgress<M>> phases;
		private int activePhaseIndex;
		private int inFlight;
		private int remaining;
		private int deferred;
		private int materialDeferred;

		private LayerProgress(int y, List<PhaseProgress<M>> phases) {
			this.y = y;
			this.phases = List.copyOf(phases);
			for (PhaseProgress<M> phase : phases) remaining += phase.remaining;
		}

		private PhaseProgress<M> activePhase() {
			return phases.get(activePhaseIndex);
		}
	}

	private static final class PhaseProgress<M> {
		private final int phase;
		private final List<MaterialRun<M>> runs;
		private int activeRunIndex;
		private int inFlight;
		private int remaining;
		private int deferred;
		private int materialDeferred;

		private PhaseProgress(int phase, List<MaterialRun<M>> runs) {
			this.phase = phase;
			this.runs = List.copyOf(runs);
			for (MaterialRun<M> run : runs) remaining += run.remaining;
		}

		private MaterialRun<M> activeRun() {
			return runs.get(activeRunIndex);
		}
	}

	private static final class MaterialRun<M> {
		private final M material;
		private final List<Candidate<M>> candidates;
		private int cursor;
		private int inFlight;
		private int remaining;
		private int deferred;
		private int materialDeferred;

		private MaterialRun(M material, List<Candidate<M>> candidates) {
			this.material = Objects.requireNonNull(material, "material");
			this.candidates = List.copyOf(candidates);
			this.remaining = candidates.size();
		}
	}

	private record DeferredEntry(int candidateId, long epoch) {
	}

	/** Allocation-free after growth; one primitive FIFO per deferred reason. */
	private static final class DeferredQueue {
		private int[] candidateIds = new int[16];
		private long[] epochs = new long[16];
		private int head;
		private int size;

		private void add(int candidateId, long epoch) {
			ensureCapacity(size + 1);
			int index = (head + size) % candidateIds.length;
			candidateIds[index] = candidateId;
			epochs[index] = epoch;
			size++;
		}

		private DeferredEntry peek() {
			return size == 0 ? null : new DeferredEntry(candidateIds[head], epochs[head]);
		}

		private void remove() {
			if (size == 0) throw new IllegalStateException("deferred queue is empty");
			head = (head + 1) % candidateIds.length;
			size--;
			if (size == 0) head = 0;
		}

		private void ensureCapacity(int required) {
			if (required <= candidateIds.length) return;
			int capacity = candidateIds.length << 1;
			while (capacity < required) capacity <<= 1;
			int[] grownIds = new int[capacity];
			long[] grownEpochs = new long[capacity];
			for (int index = 0; index < size; index++) {
				int source = (head + index) % candidateIds.length;
				grownIds[index] = candidateIds[source];
				grownEpochs[index] = epochs[source];
			}
			candidateIds = grownIds;
			epochs = grownEpochs;
			head = 0;
		}
	}
}
