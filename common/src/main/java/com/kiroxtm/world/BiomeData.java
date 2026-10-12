package com.kiroxtm.world;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

import dev.xpple.cubiomes.Cubiomes;

/** Biome names, colors and per-dimension biome lists, straight from cubiomes. */
public final class BiomeData {
	/** Biome ids are below 256 in every supported version. */
	public static final int ID_LIMIT = 256;
	private static final int[] COLORS = loadColors();

	private BiomeData() {
	}

	/** Map color of a biome as 0xAARRGGBB; opaque black for unknown ids. */
	public static int color(int biomeId) {
		return biomeId >= 0 && biomeId < ID_LIMIT ? COLORS[biomeId] : 0xFF000000;
	}

	/** The color used for biomes the user switched off: dark and desaturated, but recognisable. */
	public static int dimmed(int argb) {
		int r = argb >> 16 & 0xFF, g = argb >> 8 & 0xFF, b = argb & 0xFF;
		int gray = (r * 30 + g * 59 + b * 11) / 100;
		int v = 24 + gray / 6;
		return 0xFF000000 | v << 16 | v << 8 | v;
	}

	/** Vanilla biome path for this version ("windswept_hills"), or null for an unknown id. */
	public static String name(int biomeId) {
		MemorySegment str = Cubiomes.biome2str(SeedWorld.mcVersion(), biomeId);
		return str.equals(MemorySegment.NULL) ? null : str.reinterpret(Long.MAX_VALUE).getString(0);
	}

	/** Biomes that appear on the map of a dimension (underground-only biomes are left out). */
	public static List<Integer> mapBiomes(Dim dim) {
		int mc = SeedWorld.mcVersion();
		List<Integer> ids = new ArrayList<>();
		for (int id = 0; id < ID_LIMIT; id++) {
			String name = name(id);
			if (name == null || name.endsWith("_caves") || name.equals("deep_dark")) {
				continue;
			}
			boolean generates = switch (dim) {
				case OVERWORLD -> Cubiomes.isOverworld(mc, id) != 0;
				case NETHER, END -> Cubiomes.getDimension(id) == dim.cubiomesId();
			};
			if (generates) {
				ids.add(id);
			}
		}
		return ids;
	}

	private static int[] loadColors() {
		NativeLib.load();
		int[] colors = new int[ID_LIMIT];
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment rgb = arena.allocate(ID_LIMIT * 3L);
			Cubiomes.initBiomeColors(rgb);
			for (int i = 0; i < ID_LIMIT; i++) {
				int r = Byte.toUnsignedInt(rgb.get(ValueLayout.JAVA_BYTE, i * 3L));
				int g = Byte.toUnsignedInt(rgb.get(ValueLayout.JAVA_BYTE, i * 3L + 1));
				int b = Byte.toUnsignedInt(rgb.get(ValueLayout.JAVA_BYTE, i * 3L + 2));
				colors[i] = 0xFF000000 | r << 16 | g << 8 | b;
			}
		}
		return colors;
	}
}
