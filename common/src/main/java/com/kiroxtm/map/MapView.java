package com.kiroxtm.map;

/**
 * Where the map looks: a center in block coordinates and a zoom. The zoom is an exponent, so the
 * scale is {@code 2^zoom} blocks per GUI pixel, from 1/4 (four pixels per block) to 512.
 * Pure arithmetic with no Minecraft types, so it is unit-tested.
 */
public final class MapView {
	public static final double MIN_ZOOM = -2;
	public static final double MAX_ZOOM = 9;
	public static final double DEFAULT_ZOOM = 2;
	/** One wheel notch. */
	public static final double ZOOM_STEP = 0.5;

	private double centerX;
	private double centerZ;
	private double zoom = DEFAULT_ZOOM;

	public double centerX() {
		return centerX;
	}

	public double centerZ() {
		return centerZ;
	}

	public double zoom() {
		return zoom;
	}

	/** Blocks per GUI pixel. */
	public double blocksPerPixel() {
		return Math.pow(2, zoom);
	}

	public void center(double x, double z) {
		centerX = x;
		centerZ = z;
	}

	public void setZoom(double zoom) {
		this.zoom = Math.clamp(zoom, MIN_ZOOM, MAX_ZOOM);
	}

	/**
	 * Zooms by {@code steps} notches (negative = zoom in) keeping the block under the cursor fixed.
	 * {@code dx, dz} is the cursor's GUI offset from the center of the map area.
	 */
	public void zoomAround(double steps, double dx, double dz) {
		double before = blocksPerPixel();
		setZoom(zoom + steps * ZOOM_STEP);
		double after = blocksPerPixel();
		centerX += dx * (before - after);
		centerZ += dz * (before - after);
	}

	/** Pans by a GUI-pixel drag: dragging the map right moves the view left. */
	public void panPixels(double dx, double dy) {
		double bpp = blocksPerPixel();
		centerX -= dx * bpp;
		centerZ -= dy * bpp;
	}
}
