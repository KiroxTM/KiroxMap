package com.kiroxtm.world;

import dev.xpple.cubiomes.Cubiomes;

/** The three dimensions the atlas can show. */
public enum Dim {
	OVERWORLD("overworld"),
	NETHER("the_nether"),
	END("the_end");

	private final String key;

	Dim(String key) {
		this.key = key;
	}

	/** Vanilla dimension path ("the_nether"); also used for translation keys. */
	public String key() {
		return key;
	}

	/**
	 * Factor that converts coordinates of this dimension into {@code to}, like a portal does.
	 * NaN when the dimensions have no coordinate correspondence (anything involving the End).
	 */
	public double scaleTo(Dim to) {
		if (this == to) {
			return 1;
		}
		if (this == OVERWORLD && to == NETHER) {
			return 1 / 8.0;
		}
		if (this == NETHER && to == OVERWORLD) {
			return 8;
		}
		return Double.NaN;
	}

	/** The cubiomes dimension constant. */
	public int cubiomesId() {
		return switch (this) {
			case OVERWORLD -> Cubiomes.DIM_OVERWORLD();
			case NETHER -> Cubiomes.DIM_NETHER();
			case END -> Cubiomes.DIM_END();
		};
	}
}
