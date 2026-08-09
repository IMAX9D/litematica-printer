package me.aleksilassila.litematica.printer.api.scheduler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Immutable layer/tile index of schematic candidates.
 *
 * <p>Coordinates use one packed primitive key and are grouped into 16x16 X/Z
 * tiles. The index deliberately carries an adapter-supplied placement phase;
 * it does not guess Minecraft support or special-block semantics.</p>
 */
public final class SchematicPrintIndex<M> {
	private static final int MIN_PACKED_XZ = -33_554_432;
	private static final int MAX_PACKED_XZ = 33_554_431;
	private static final int MIN_PACKED_Y = -2_048;
	private static final int MAX_PACKED_Y = 2_047;

	public record Candidate<M>(int id, long packedPosition, int placementPhase, M material) {
		public Candidate {
			if (id < 0) throw new IllegalArgumentException("id must not be negative");
			if (placementPhase < 0) {
				throw new IllegalArgumentException("placementPhase must not be negative");
			}
			material = Objects.requireNonNull(material, "material");
		}

		public int x() {
			return unpackX(packedPosition);
		}

		public int y() {
			return unpackY(packedPosition);
		}

		public int z() {
			return unpackZ(packedPosition);
		}
	}

	public record Tile<M>(int tileX, int tileZ, List<Candidate<M>> candidates) {
		public Tile {
			candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
		}
	}

	public record Layer<M>(int y, List<Tile<M>> tiles, List<Candidate<M>> candidates) {
		public Layer {
			tiles = List.copyOf(Objects.requireNonNull(tiles, "tiles"));
			candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
		}
	}

	private final long sessionId;
	private final long revision;
	private final List<Layer<M>> layers;
	private final List<Candidate<M>> candidates;

	private SchematicPrintIndex(long sessionId, long revision,
			List<Layer<M>> layers, List<Candidate<M>> candidates) {
		this.sessionId = sessionId;
		this.revision = revision;
		this.layers = List.copyOf(layers);
		this.candidates = List.copyOf(candidates);
	}

	public static <M> Builder<M> builder(long sessionId, long revision) {
		return new Builder<>(sessionId, revision);
	}

	public long sessionId() {
		return sessionId;
	}

	public long revision() {
		return revision;
	}

	public List<Layer<M>> layers() {
		return layers;
	}

	public List<Candidate<M>> candidates() {
		return candidates;
	}

	public int candidateCount() {
		return candidates.size();
	}

	/**
	 * Allocation-free coordinate lookup through the sorted layer/tile index.
	 * Returns {@code null} when the projection index has no candidate there.
	 */
	public Candidate<M> candidateAt(int x, int y, int z) {
		int low = 0;
		int high = layers.size() - 1;
		Layer<M> layer = null;
		while (low <= high) {
			int middle = (low + high) >>> 1;
			Layer<M> candidate = layers.get(middle);
			if (candidate.y() < y) low = middle + 1;
			else if (candidate.y() > y) high = middle - 1;
			else {
				layer = candidate;
				break;
			}
		}
		if (layer == null) return null;

		int tileX = Math.floorDiv(x, 16);
		int tileZ = Math.floorDiv(z, 16);
		low = 0;
		high = layer.tiles().size() - 1;
		Tile<M> tile = null;
		while (low <= high) {
			int middle = (low + high) >>> 1;
			Tile<M> candidate = layer.tiles().get(middle);
			int comparison = Integer.compare(candidate.tileZ(), tileZ);
			if (comparison == 0) comparison = Integer.compare(candidate.tileX(), tileX);
			if (comparison < 0) low = middle + 1;
			else if (comparison > 0) high = middle - 1;
			else {
				tile = candidate;
				break;
			}
		}
		if (tile == null) return null;

		low = 0;
		high = tile.candidates().size() - 1;
		while (low <= high) {
			int middle = (low + high) >>> 1;
			Candidate<M> candidate = tile.candidates().get(middle);
			int comparison = Integer.compare(candidate.z(), z);
			if (comparison == 0) comparison = Integer.compare(candidate.x(), x);
			if (comparison < 0) low = middle + 1;
			else if (comparison > 0) high = middle - 1;
			else return candidate;
		}
		return null;
	}

	public static long pack(int x, int y, int z) {
		if (x < MIN_PACKED_XZ || x > MAX_PACKED_XZ
				|| z < MIN_PACKED_XZ || z > MAX_PACKED_XZ
				|| y < MIN_PACKED_Y || y > MAX_PACKED_Y) {
			throw new IllegalArgumentException("position is outside Minecraft's packed coordinate range");
		}
		return ((long) x & 0x3FFFFFFL) << 38
				| ((long) z & 0x3FFFFFFL) << 12
				| (long) y & 0xFFFL;
	}

	public static int unpackX(long packed) {
		return (int) (packed >> 38);
	}

	public static int unpackY(long packed) {
		return (int) (packed << 52 >> 52);
	}

	public static int unpackZ(long packed) {
		return (int) (packed << 26 >> 38);
	}

	public static final class Builder<M> {
		private final long sessionId;
		private final long revision;
		private final Map<Integer, Map<Long, MutableTile<M>>> layers = new TreeMap<>();
		private final Set<Long> positions = new HashSet<>();
		private boolean built;

		private Builder(long sessionId, long revision) {
			if (sessionId < 0L) throw new IllegalArgumentException("sessionId must not be negative");
			if (revision < 0L) throw new IllegalArgumentException("revision must not be negative");
			this.sessionId = sessionId;
			this.revision = revision;
		}

		public Builder<M> add(int x, int y, int z, int placementPhase, M material) {
			if (!addIfAbsent(x, y, z, placementPhase, material)) {
				throw new IllegalArgumentException("duplicate candidate position: " + x + "," + y + "," + z);
			}
			return this;
		}

		/** Adds a candidate unless an overlapping placement already supplied it. */
		public boolean addIfAbsent(int x, int y, int z, int placementPhase, M material) {
			if (built) throw new IllegalStateException("index was already built");
			if (placementPhase < 0) {
				throw new IllegalArgumentException("placementPhase must not be negative");
			}
			Objects.requireNonNull(material, "material");
			long packedPosition = pack(x, y, z);
			if (!positions.add(packedPosition)) return false;
			int tileX = Math.floorDiv(x, 16);
			int tileZ = Math.floorDiv(z, 16);
			long tileKey = ((long) tileX << 32) ^ (tileZ & 0xFFFFFFFFL);
			Map<Long, MutableTile<M>> layer = layers.computeIfAbsent(y,
					ignored -> new LinkedHashMap<>());
			MutableTile<M> tile = layer.computeIfAbsent(tileKey,
					ignored -> new MutableTile<>(tileX, tileZ));
			tile.candidates.add(new MutableCandidate<>(packedPosition, placementPhase, material));
			return true;
		}

		public SchematicPrintIndex<M> build() {
			if (built) throw new IllegalStateException("index was already built");
			built = true;
			List<Layer<M>> immutableLayers = new ArrayList<>();
			List<Candidate<M>> allCandidates = new ArrayList<>(positions.size());
			int nextId = 0;
			for (Map.Entry<Integer, Map<Long, MutableTile<M>>> layerEntry : layers.entrySet()) {
				List<MutableTile<M>> sortedTiles = new ArrayList<>(layerEntry.getValue().values());
				sortedTiles.sort(Comparator.comparingInt((MutableTile<M> tile) -> tile.tileZ)
						.thenComparingInt(tile -> tile.tileX));
				List<Tile<M>> immutableTiles = new ArrayList<>();
				List<Candidate<M>> layerCandidates = new ArrayList<>();
				for (MutableTile<M> mutableTile : sortedTiles) {
					mutableTile.candidates.sort(Comparator
							.comparingInt((MutableCandidate<M> candidate) -> unpackZ(candidate.packedPosition))
							.thenComparingInt(candidate -> unpackX(candidate.packedPosition))
							.thenComparingInt(candidate -> candidate.placementPhase));
					List<Candidate<M>> tileCandidates = new ArrayList<>();
					for (MutableCandidate<M> candidate : mutableTile.candidates) {
						Candidate<M> immutable = new Candidate<>(nextId++, candidate.packedPosition,
								candidate.placementPhase, candidate.material);
						tileCandidates.add(immutable);
						layerCandidates.add(immutable);
						allCandidates.add(immutable);
					}
					immutableTiles.add(new Tile<>(mutableTile.tileX, mutableTile.tileZ, tileCandidates));
				}
				immutableLayers.add(new Layer<>(layerEntry.getKey(), immutableTiles, layerCandidates));
			}
			return new SchematicPrintIndex<>(sessionId, revision, immutableLayers, allCandidates);
		}
	}

	private static final class MutableTile<M> {
		private final int tileX;
		private final int tileZ;
		private final List<MutableCandidate<M>> candidates = new ArrayList<>();

		private MutableTile(int tileX, int tileZ) {
			this.tileX = tileX;
			this.tileZ = tileZ;
		}
	}

	private record MutableCandidate<M>(long packedPosition, int placementPhase, M material) {
	}
}
