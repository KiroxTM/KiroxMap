package com.kiroxtm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import com.kiroxtm.map.MapView;
import com.kiroxtm.map.TileKey;
import com.kiroxtm.world.SlimeChunks;
import org.junit.jupiter.api.Test;

/** Pure-Java checks; no Minecraft or native library needed. */
class CoreMathTest {
	@Test
	void slimeChunksMatchJavaRandom() {
		long[] seeds = {0L, 1L, 262L, -1L, 123456789012345L, Long.MIN_VALUE};
		for (long seed : seeds) {
			for (int cx = -40; cx <= 40; cx += 3) {
				for (int cz = -40; cz <= 40; cz += 7) {
					Random r = new Random(seed
							+ (long) (cx * cx * 4987142) + (long) (cx * 5947611)
							+ (long) (cz * cz) * 4392871L + (long) (cz * 389711) ^ 987234911L);
					assertEquals(r.nextInt(10) == 0, SlimeChunks.isSlime(seed, cx, cz), seed + " " + cx + " " + cz);
				}
			}
		}
	}

	@Test
	void zoomKeepsBlockUnderCursor() {
		MapView v = new MapView();
		v.center(100, -50);
		double dx = 120, dz = -40;
		double bx = v.centerX() + dx * v.blocksPerPixel(), bz = v.centerZ() + dz * v.blocksPerPixel();
		v.zoomAround(-2, dx, dz);
		assertEquals(bx, v.centerX() + dx * v.blocksPerPixel(), 1e-9);
		assertEquals(bz, v.centerZ() + dz * v.blocksPerPixel(), 1e-9);
	}

	@Test
	void zoomIsClamped() {
		MapView v = new MapView();
		v.setZoom(100);
		assertEquals(MapView.MAX_ZOOM, v.zoom());
		v.setZoom(-100);
		assertEquals(MapView.MIN_ZOOM, v.zoom());
	}

	@Test
	void tilesNestAcrossScales() {
		for (long x : new long[] {-100000, -513, -1, 0, 1, 511, 99999}) {
			TileKey fine = TileKey.containing(4, x, -x);
			TileKey coarse = TileKey.containing(16, fine.minX(), fine.minZ());
			assertTrue(coarse.minX() <= fine.minX() && fine.maxX() <= coarse.maxX());
			assertTrue(coarse.minZ() <= fine.minZ() && fine.maxZ() <= coarse.maxZ());
			assertTrue(fine.minX() <= x && x < fine.maxX());
		}
	}
}
