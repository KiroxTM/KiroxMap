package com.kiroxtm.world;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.xpple.cubiomes.Cubiomes;
import dev.xpple.cubiomes.Pos;
import dev.xpple.cubiomes.StructureConfig;

/**
 * Predicts structures of one {@link SeedWorld}. Minecraft divides the world into regions and makes
 * one generation attempt per region; the position of that attempt depends only on the seed and
 * the region ({@code getStructurePos}). The attempt only becomes a structure if the biome and
 * terrain checks pass ({@code isViable}). Results are cached per region, so panning back over an
 * area costs nothing. Thread-safe.
 */
public final class StructureIndex {
	public record Found(StructureKind kind, int x, int z) {
	}

	private static final int[] NONE = new int[0];
	private static final int MAX_CACHED_REGIONS = 400_000;
	private static final int STRONGHOLD_COUNT = 128;

	private final SeedWorld world;
	/** Per kind: region key -> {x, z} of the structure, or empty when the attempt fails. */
	private final Map<StructureKind, Map<Long, int[]>> regions = new EnumMap<>(StructureKind.class);
	private final Map<StructureKind, Integer> regionBlocks = new EnumMap<>(StructureKind.class);
	private volatile int[] strongholds;

	public StructureIndex(SeedWorld world) {
		this.world = world;
		for (StructureKind kind : StructureKind.values()) {
			regions.put(kind, new ConcurrentHashMap<>());
			regionBlocks.put(kind, loadRegionBlocks(kind));
		}
	}

	public SeedWorld world() {
		return world;
	}

	/** Whether the structure exists in this Minecraft version and dimension. */
	public boolean supports(StructureKind kind) {
		return kind.dim() == world.dim() && (kind == StructureKind.STRONGHOLD || regionBlocks.get(kind) > 0);
	}

	/** Side length of the kind's placement region in blocks (0 when unsupported or not region based). */
	public int regionBlocks(StructureKind kind) {
		return regionBlocks.get(kind);
	}

	/** Structures of a kind whose position lies in the block rectangle [x0, x1) x [z0, z1). */
	public List<Found> find(StructureKind kind, int x0, int z0, int x1, int z1) {
		List<Found> out = new ArrayList<>();
		if (!supports(kind)) {
			return out;
		}
		if (kind == StructureKind.STRONGHOLD) {
			int[] all = strongholds();
			for (int i = 0; i < all.length; i += 2) {
				if (all[i] >= x0 && all[i] < x1 && all[i + 1] >= z0 && all[i + 1] < z1) {
					out.add(new Found(kind, all[i], all[i + 1]));
				}
			}
			return out;
		}

		long size = regionBlocks.get(kind);
		Map<Long, int[]> cache = regions.get(kind);
		if (cache.size() > MAX_CACHED_REGIONS) {
			cache.clear();
		}
		// A structure always lands inside its own region, so scanning the regions that cover the
		// rectangle finds everything in it.
		long rx0 = Math.floorDiv((long) x0, size), rx1 = Math.floorDiv(x1 - 1L, size);
		long rz0 = Math.floorDiv((long) z0, size), rz1 = Math.floorDiv(z1 - 1L, size);
		for (long rz = rz0; rz <= rz1; rz++) {
			for (long rx = rx0; rx <= rx1; rx++) {
				int regionX = (int) rx, regionZ = (int) rz;
				int[] pos = cache.computeIfAbsent((rx << 32) | (rz & 0xFFFFFFFFL), k -> locate(kind, regionX, regionZ));
				if (pos.length == 2 && pos[0] >= x0 && pos[0] < x1 && pos[1] >= z0 && pos[1] < z1) {
					out.add(new Found(kind, pos[0], pos[1]));
				}
			}
		}
		return out;
	}

	/**
	 * The 20 gateways that appear on a ring of radius 96 around the main End island, one per dragon
	 * kill. Their positions do not depend on the seed (only the order they open in does).
	 */
	private static final int[] FIXED_GATEWAYS = {
		96, 0, 91, 29, 77, 56, 56, 77, 29, 91, -1, 96, -30, 91, -57, 77, -78, 56, -92, 29,
		-96, -1, -92, -30, -78, -57, -57, -78, -30, -92, 0, -96, 29, -92, 56, -78, 77, -57, 91, -30};

	/** Fixed gateways inside the block rectangle. Independent of zoom level, so always cheap. */
	public List<Found> findFixedGateways(int x0, int z0, int x1, int z1) {
		List<Found> out = new ArrayList<>();
		if (world.dim() != Dim.END) {
			return out;
		}
		for (int i = 0; i < FIXED_GATEWAYS.length; i += 2) {
			int x = FIXED_GATEWAYS[i], z = FIXED_GATEWAYS[i + 1];
			if (x >= x0 && x < x1 && z >= z0 && z < z1) {
				out.add(new Found(StructureKind.END_GATEWAY, x, z));
			}
		}
		return out;
	}

	/** Accurate stronghold positions, computed once. */
	public int[] strongholds() {
		int[] result = strongholds;
		if (result == null) {
			result = world.strongholds(STRONGHOLD_COUNT);
			strongholds = result;
		}
		return result;
	}

	private int[] locate(StructureKind kind, int regionX, int regionZ) {
		int type = kind.cubiomesType();
		int x, z;
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment pos = Pos.allocate(arena);
			if (Cubiomes.getStructurePos(type, SeedWorld.mcVersion(), world.seed(), regionX, regionZ, pos) == 0) {
				return NONE;
			}
			x = Pos.x(pos);
			z = Pos.z(pos);
		}
		boolean viable = switch (kind) {
			case END_CITY -> world.isViableEndCity(x, z) && !world.endCityHasShip(x, z);
			case END_CITY_SHIP -> world.isViableEndCity(x, z) && world.endCityHasShip(x, z);
			default -> world.isViable(type, x, z);
		};
		return viable ? new int[] {x, z} : NONE;
	}

	private int loadRegionBlocks(StructureKind kind) {
		if (kind == StructureKind.STRONGHOLD) {
			return 0;
		}
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment config = StructureConfig.allocate(arena);
			if (Cubiomes.getStructureConfig(kind.cubiomesType(), SeedWorld.mcVersion(), config) == 0) {
				return 0;
			}
			return StructureConfig.regionSize(config) * 16;
		}
	}
}
