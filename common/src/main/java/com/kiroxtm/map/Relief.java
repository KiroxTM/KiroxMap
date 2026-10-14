package com.kiroxtm.map;

/**
 * Terrain relief for the map: turns a grid of approximate surface heights into a per-texel shade
 * (a hillshade with the light coming from the north-west) and applies it to a biome color.
 *
 * <p>This class is pure math (no Minecraft or cubiomes types) so it can be unit tested. The shade of
 * a texel is one signed byte: -127 (darkest) .. 0 (flat, unchanged) .. +127 (lightest). It does not
 * depend on the biome colors, so it is cached on disk once and survives palette changes.
 */
public final class Relief {
	/** How strongly slopes darken or lighten; raise for a more pronounced relief. */
	static final double GAIN = 3.0;
	/** Below this height (blocks) the ground is sea or river bed; its relief is toned down. */
	static final double SEA_LEVEL = 62;
	static final double WATER_FACTOR = 0.35;
	/** Largest darkening and lightening of a color, at full shade. */
	static final double MAX_DARKEN = 0.45;
	static final double MAX_LIGHTEN = 0.30;

	/** Fixed-point (8.8) color multiplier for each shade value, indexed by {@code shade + 128}. */
	private static final int[] MULTIPLIER = new int[256];

	static {
		for (int b = -128; b <= 127; b++) {
			double d = Math.max(-1.0, b / 127.0);
			MULTIPLIER[b + 128] = (int) Math.round((d < 0 ? 1 + MAX_DARKEN * d : 1 + MAX_LIGHTEN * d) * 256);
		}
	}

	private Relief() {
	}

	/**
	 * How much relief to show at a level of detail ({@code scale} blocks per texel). Coarse tiles
	 * sample one height every 64 or 256 blocks, so their slopes are less meaningful and fade out.
	 */
	public static double strength(int scale) {
		return switch (scale) {
			case 4, 16 -> 1.0;
			case 64 -> 0.7;
			case 256 -> 0.35;
			default -> 0.0;
		};
	}

	/**
	 * Hillshade of a {@code texels x texels} tile from heights sampled one texel apart.
	 *
	 * @param heights {@code (texels + 2)^2} heights in blocks, row-major (z outer): the tile plus a
	 *                one-texel border, so neighbouring tiles shade their shared edge identically
	 * @param scale   blocks per texel
	 */
	public static byte[] shade(float[] heights, int texels, int scale) {
		int stride = texels + 2;
		if (heights.length != stride * stride) {
			throw new IllegalArgumentException("expected " + stride * stride + " heights, got " + heights.length);
		}
		byte[] out = new byte[texels * texels];
		double gain = GAIN * strength(scale);
		for (int j = 0; j < texels; j++) {
			for (int i = 0; i < texels; i++) {
				int c = (j + 1) * stride + (i + 1);
				double gx = (heights[c + 1] - heights[c - 1]) / (2.0 * scale);
				double gz = (heights[c + stride] - heights[c - stride]) / (2.0 * scale);
				// Light from the north-west: ground that rises toward the south-east faces it.
				double lit = (gx + gz) * Math.sqrt(0.5);
				double land = heights[c] < SEA_LEVEL ? WATER_FACTOR : 1.0;
				double d = lit * gain * land;
				if (Double.isNaN(d)) {
					d = 0;
				}
				out[j * texels + i] = (byte) Math.round(Math.max(-1.0, Math.min(1.0, d)) * 127);
			}
		}
		return out;
	}

	/** Darkens or lightens the RGB of a pixel (alpha untouched; works for ARGB and ABGR alike). */
	public static int apply(int pixel, byte shade) {
		if (shade == 0) {
			return pixel;
		}
		int m = MULTIPLIER[shade + 128];
		int c0 = Math.min(255, ((pixel & 0xFF) * m) >> 8);
		int c1 = Math.min(255, (((pixel >> 8) & 0xFF) * m) >> 8);
		int c2 = Math.min(255, (((pixel >> 16) & 0xFF) * m) >> 8);
		return (pixel & 0xFF000000) | (c2 << 16) | (c1 << 8) | c0;
	}
}
