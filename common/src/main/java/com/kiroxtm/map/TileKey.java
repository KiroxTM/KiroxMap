package com.kiroxtm.map;

/**
 * Identifies a map tile: {@code scale} blocks per texel (4, 16, 64 or 256) and tile coordinates in
 * units of {@code TEXELS * scale} blocks. Tiles of all scales are aligned, so a tile is always
 * fully covered by exactly one tile of every coarser scale.
 */
public record TileKey(int scale, int tx, int tz) {
	public static final int TEXELS = 128;

	public static TileKey containing(int scale, long blockX, long blockZ) {
		long blocks = (long) TEXELS * scale;
		return new TileKey(scale, (int) Math.floorDiv(blockX, blocks), (int) Math.floorDiv(blockZ, blocks));
	}

	/** Side of the tile in blocks. */
	public long blocks() {
		return (long) TEXELS * scale;
	}

	public long minX() {
		return tx * blocks();
	}

	public long maxX() {
		return minX() + blocks();
	}

	public long minZ() {
		return tz * blocks();
	}

	public long maxZ() {
		return minZ() + blocks();
	}

	/** Squared distance from the tile center to a block position, normalised by the tile size. */
	public double distanceSq(double x, double z) {
		double dx = minX() + blocks() / 2.0 - x, dz = minZ() + blocks() / 2.0 - z;
		return (dx * dx + dz * dz) / blocks() / blocks();
	}
}
