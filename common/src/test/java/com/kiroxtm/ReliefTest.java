package com.kiroxtm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kiroxtm.map.Relief;
import org.junit.jupiter.api.Test;

class ReliefTest {
	private static final int T = 4;

	private static float[] grid(java.util.function.BiFunction<Integer, Integer, Float> height) {
		int stride = T + 2;
		float[] h = new float[stride * stride];
		for (int j = 0; j < stride; j++) {
			for (int i = 0; i < stride; i++) {
				h[j * stride + i] = height.apply(i, j);
			}
		}
		return h;
	}

	@Test
	void flatGroundIsUnshaded() {
		assertArrayEquals(new byte[T * T], Relief.shade(grid((i, j) -> 100f), T, 4));
	}

	@Test
	void groundFacingTheLightIsBrighterAndTheOtherSideDarker() {
		byte[] rising = Relief.shade(grid((i, j) -> 80f + 2 * (i + j)), T, 4); // rises toward the south-east
		byte[] falling = Relief.shade(grid((i, j) -> 80f - 2 * (i + j)), T, 4);
		for (int k = 0; k < T * T; k++) {
			assertTrue(rising[k] > 0, "rising slope should be lit");
			assertTrue(falling[k] < 0, "falling slope should be in shadow");
		}
	}

	@Test
	void seaBedIsShadedLessThanLand() {
		byte land = Relief.shade(grid((i, j) -> 80f + i), T, 4)[0];
		byte sea = Relief.shade(grid((i, j) -> 20f + i), T, 4)[0];
		assertTrue(land > 0 && sea > 0 && sea < land);
	}

	@Test
	void coarserLevelsFadeAndTheCoarsestIsWeakest() {
		assertTrue(Relief.strength(4) >= Relief.strength(64));
		assertTrue(Relief.strength(64) > Relief.strength(256));
		assertEquals(0.0, Relief.strength(1024));
	}

	@Test
	void shadeChangesColorButNotAlpha() {
		int pixel = 0xFF808080;
		assertEquals(pixel, Relief.apply(pixel, (byte) 0));
		int dark = Relief.apply(pixel, (byte) -127), light = Relief.apply(pixel, (byte) 127);
		assertEquals(0xFF, dark >>> 24);
		assertEquals(0xFF, light >>> 24);
		assertTrue((dark & 0xFF) < 0x80 && (light & 0xFF) > 0x80);
		assertEquals(0xFF, Relief.apply(0xFFF0F0F0, (byte) 127) & 0xFF, "channels clamp at 255");
	}

	@Test
	void wrongGridSizeIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> Relief.shade(new float[T * T], T, 4));
	}
}
