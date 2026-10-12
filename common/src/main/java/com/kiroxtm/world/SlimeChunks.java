package com.kiroxtm.world;

/**
 * Slime chunks. Minecraft seeds a {@code java.util.Random} from the world seed and the chunk
 * position and takes {@code nextInt(10) == 0}; this is the same computation without allocating.
 * (Same formula as {@code isSlimeChunk} in cubiomes' finders.h.)
 */
public final class SlimeChunks {
	private static final long SALT = 987234911L;
	private static final long MULTIPLIER = 0x5DEECE66DL;
	private static final long MASK = (1L << 48) - 1;

	private SlimeChunks() {
	}

	public static boolean isSlime(long seed, int chunkX, int chunkZ) {
		// The int products overflow exactly like Minecraft's before they are widened.
		long mixed = seed
				+ (long) (chunkX * chunkX * 4987142)
				+ (long) (chunkX * 5947611)
				+ (long) (chunkZ * chunkZ) * 4392871L
				+ (long) (chunkZ * 389711);
		long state = ((mixed ^ SALT) ^ MULTIPLIER) & MASK;
		while (true) {
			// Random.nextInt(10): next(31) with rejection of the biased top range.
			state = (state * MULTIPLIER + 0xBL) & MASK;
			int bits = (int) (state >>> 17);
			int value = bits % 10;
			if (bits - value + 9 >= 0) {
				return value == 0;
			}
		}
	}
}
