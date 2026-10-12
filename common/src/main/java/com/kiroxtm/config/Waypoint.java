package com.kiroxtm.config;

/** A named marker the player placed on the map. Plain fields so the config file stays simple. */
public final class Waypoint {
	/** Colors offered in the editor (ARGB). */
	public static final int[] COLORS = {
		0xFFFF5555, 0xFFFFAA00, 0xFFFFE040, 0xFF55FF55, 0xFF40E0D0, 0xFF5599FF, 0xFFC070FF, 0xFFFFFFFF};

	public String name = "";
	public int x;
	public int z;
	/** {@code Dim.key()} of the dimension the waypoint lives in. */
	public String dim = "overworld";
	public int color = COLORS[0];
	/** Hidden waypoints stay in the list but are not drawn. */
	public boolean visible = true;

	public Waypoint() {
	}

	public Waypoint(String name, int x, int z, String dim, int color) {
		this.name = name;
		this.x = x;
		this.z = z;
		this.dim = dim;
		this.color = color;
	}
}
