package com.kiroxtm.world;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;

import dev.xpple.cubiomes.Cubiomes;
import dev.xpple.cubiomes.Generator;
import dev.xpple.cubiomes.Piece;
import dev.xpple.cubiomes.Pos;
import dev.xpple.cubiomes.Range;
import dev.xpple.cubiomes.StrongholdIter;
import dev.xpple.cubiomes.SurfaceNoise;

/**
 * Everything the atlas knows about one world: a seed in one dimension, answered purely by the
 * cubiomes generation rules. Nothing here looks at the real level, its chunks or its blocks.
 *
 * <p>A native cubiomes generator is not thread-safe, so every thread lazily gets its own. Instances
 * are immutable and can be shared freely between the render thread and the tile workers; a new seed
 * or dimension always means a new instance, so results of different worlds can never mix.
 */
public final class SeedWorld {
	/**
	 * Y used to sample biomes. Biomes are 3D since 1.18; sampling far above the terrain yields the
	 * surface biome and keeps cave biomes off the map.
	 */
	public static final int MAP_Y = 255;

	static {
		NativeLib.load();
	}

	private final long seed;
	private final Dim dim;
	private final ThreadLocal<MemorySegment> generator = ThreadLocal.withInitial(this::createGenerator);
	private final ThreadLocal<MemorySegment> endSurface = ThreadLocal.withInitial(this::createEndSurface);

	public SeedWorld(long seed, Dim dim) {
		this.seed = seed;
		this.dim = dim;
	}

	/** The cubiomes version constant of the Minecraft version this build targets. */
	public static int mcVersion() {
		return Cubiomes.MC_26_3();
	}

	public long seed() {
		return seed;
	}

	public Dim dim() {
		return dim;
	}

	/**
	 * Biome ids of a {@code width x height} area, row-major (z outer, x inner). {@code x} and
	 * {@code z} are in units of {@code scale} blocks (1, 4, 16, 64 or 256).
	 */
	public int[] biomes(int scale, int x, int z, int width, int height) {
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("empty area");
		}
		MemorySegment g = generator.get();
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment range = Range.allocate(arena);
			Range.scale(range, scale);
			Range.x(range, x);
			Range.z(range, z);
			Range.sx(range, width);
			Range.sz(range, height);
			Range.y(range, scale == 1 ? MAP_Y : MAP_Y >> 2);
			Range.sy(range, 1);

			long cacheInts = Cubiomes.getMinCacheSize(g, scale, width, 1, height);
			MemorySegment cache = arena.allocate(ValueLayout.JAVA_INT, cacheInts);
			int err = Cubiomes.genBiomes(g, cache, range);
			if (err != 0) {
				throw new IllegalStateException("cubiomes genBiomes failed (" + err + ") at scale " + scale);
			}
			return Arrays.copyOf(cache.toArray(ValueLayout.JAVA_INT), width * height);
		}
	}

	/**
	 * Whether {@link #heights} can answer at this level of detail. The Overworld has approximate
	 * heights at every scale; the End only at 4 blocks per texel (its island heights need real noise
	 * columns, too slow to sample for every texel of a coarse tile); the Nether has none in cubiomes.
	 */
	public boolean hasRelief(int scale) {
		return switch (dim) {
			case OVERWORLD -> true;
			case END -> scale == 4;
			case NETHER -> false;
		};
	}

	/**
	 * Approximate surface heights, in blocks, of a {@code width x height} area, row-major (z outer).
	 * {@code x} and {@code z} are in units of {@code scale} blocks, like {@link #biomes}. Returns null
	 * where {@link #hasRelief} is false.
	 *
	 * <p>Overworld: cubiomes derives the height from the climate "depth" parameter (1.18+ terrain
	 * shape), one sample per 4 blocks; for coarser scales it is sampled once per texel, at its
	 * center. End: the island surface from the End noise.
	 */
	public float[] heights(int scale, int x, int z, int width, int height) {
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("empty area");
		}
		if (!hasRelief(scale)) {
			return null;
		}
		MemorySegment g = generator.get();
		try (Arena arena = Arena.ofConfined()) {
			if (dim == Dim.END) {
				MemorySegment y = arena.allocate(ValueLayout.JAVA_FLOAT, (long) width * height);
				int err = Cubiomes.mapEndSurfaceHeight(y, Generator.en(g), endSurface.get(), x, z, width, height, 4, 0);
				if (err != 0) {
					throw new IllegalStateException("cubiomes mapEndSurfaceHeight failed (" + err + ")");
				}
				return y.toArray(ValueLayout.JAVA_FLOAT);
			}
			if (scale == 4) {
				MemorySegment y = arena.allocate(ValueLayout.JAVA_FLOAT, (long) width * height);
				int err = Cubiomes.mapApproxHeight(y, MemorySegment.NULL, g, MemorySegment.NULL, x, z, width, height);
				if (err != 0) {
					throw new IllegalStateException("cubiomes mapApproxHeight failed (" + err + ")");
				}
				return y.toArray(ValueLayout.JAVA_FLOAT);
			}
			float[] out = new float[width * height];
			MemorySegment one = arena.allocate(ValueLayout.JAVA_FLOAT);
			for (int j = 0; j < height; j++) {
				int cz = Math.floorDiv((z + j) * scale + scale / 2, 4);
				for (int i = 0; i < width; i++) {
					int cx = Math.floorDiv((x + i) * scale + scale / 2, 4);
					int err = Cubiomes.mapApproxHeight(one, MemorySegment.NULL, g, MemorySegment.NULL, cx, cz, 1, 1);
					if (err != 0) {
						throw new IllegalStateException("cubiomes mapApproxHeight failed (" + err + ")");
					}
					out[j * width + i] = one.get(ValueLayout.JAVA_FLOAT, 0);
				}
			}
			return out;
		}
	}

	/** Surface biome id at a block column. */
	public int biomeAt(int blockX, int blockZ) {
		return Cubiomes.getBiomeAt(generator.get(), 4, blockX >> 2, MAP_Y >> 2, blockZ >> 2);
	}

	/**
	 * Whether a structure generation attempt at a block position passes the biome (and, where
	 * cubiomes can tell, terrain) checks.
	 */
	boolean isViable(int structureType, int blockX, int blockZ) {
		MemorySegment g = generator.get();
		if (Cubiomes.isViableStructurePos(structureType, g, blockX, blockZ, 0) == 0) {
			return false;
		}
		if (dim == Dim.OVERWORLD) {
			return Cubiomes.isViableStructureTerrain(structureType, g, blockX, blockZ) != 0;
		}
		return true;
	}

	/** End cities additionally need a high enough island surface. */
	boolean isViableEndCity(int blockX, int blockZ) {
		MemorySegment g = generator.get();
		if (Cubiomes.isViableStructurePos(Cubiomes.End_City(), g, blockX, blockZ, 0) == 0) {
			return false;
		}
		return Cubiomes.isViableEndCityTerrain(g, endSurface.get(), blockX, blockZ) != 0;
	}

	/** Whether the end city that starts in this block's chunk has a ship. */
	boolean endCityHasShip(int blockX, int blockZ) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment pieces = Piece.allocateArray(Cubiomes.END_CITY_PIECES_MAX(), arena);
			int count = Cubiomes.getEndCityPieces(pieces, seed, blockX >> 4, blockZ >> 4);
			for (int i = 0; i < count; i++) {
				if (Piece.type(pieces.asSlice(i * Piece.sizeof(), Piece.sizeof())) == Cubiomes.END_SHIP()) {
					return true;
				}
			}
			return false;
		}
	}

	/** Accurate stronghold positions {x0, z0, x1, z1, ...}, nearest ring first. Overworld only. */
	public int[] strongholds(int maxCount) {
		if (dim != Dim.OVERWORLD) {
			return new int[0];
		}
		MemorySegment g = generator.get();
		int[] out = new int[maxCount * 2];
		int count = 0;
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment iter = StrongholdIter.allocate(arena);
			Cubiomes.initFirstStronghold(arena, iter, mcVersion(), seed);
			while (count < maxCount) {
				// nextStronghold resolves the current stronghold into iter.pos and returns how
				// many strongholds follow it.
				int remaining = Cubiomes.nextStronghold(iter, g);
				MemorySegment pos = StrongholdIter.pos(iter);
				out[count * 2] = Pos.x(pos);
				out[count * 2 + 1] = Pos.z(pos);
				count++;
				if (remaining <= 0) {
					break;
				}
			}
		}
		return Arrays.copyOf(out, count * 2);
	}

	private MemorySegment createGenerator() {
		// Arena.ofAuto: released by the GC once this thread and this SeedWorld are gone.
		MemorySegment g = Generator.allocate(Arena.ofAuto());
		Cubiomes.setupGenerator(g, mcVersion(), 0);
		Cubiomes.applySeed(g, dim.cubiomesId(), seed);
		return g;
	}

	private MemorySegment createEndSurface() {
		MemorySegment surface = SurfaceNoise.allocate(Arena.ofAuto());
		Cubiomes.initSurfaceNoise(surface, Cubiomes.DIM_END(), seed);
		return surface;
	}
}
