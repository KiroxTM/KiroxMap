package com.kiroxtm.screen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mojang.blaze3d.platform.InputConstants;

import com.kiroxtm.config.AtlasConfig;
import com.kiroxtm.config.Waypoint;
import com.kiroxtm.map.MapView;
import com.kiroxtm.map.StructureLayer;
import com.kiroxtm.map.TileCache;
import com.kiroxtm.world.BiomeData;
import com.kiroxtm.world.Dim;
import com.kiroxtm.world.SlimeChunks;
import com.kiroxtm.world.StructureIndex;
import com.kiroxtm.world.StructureKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * The interactive map: drag to pan, wheel to zoom, right-click to copy coordinates. It draws a
 * dark grid, the biome tiles, optional overlays, structure markers and the live player marker.
 * Everything shown here is derived from the seed; the real level is only read for the player's
 * own position and heading.
 */
final class MapCanvas extends AbstractWidget {
	private static final int BG = 0xFF2B2B2B;
	private static final int GRID = 0x16FFFFFF;
	private static final int GRID_AXIS = 0x50FFFFFF;
	private static final int PLATE = 0xC0101010;
	private static final int PLATE_BORDER = 0xFF3A3A3A;
	private static final int PILL = 0x90000000;
	private static final int ICON = 16;
	private static final int[] GRID_STEPS = {16, 32, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536, 131072, 262144};
	private static final int SLIME_MIN_PIXELS = 3;
	private static final int SLIME_COLOR = 0x7050E050;
	/** Marker size at the farthest zoom, as a fraction of the base size (a 75% reduction). */
	private static final double MIN_MARKER_SCALE = 0.25;
	static final String[] HELP_KEYS = {"kiroxmap.help.pan", "kiroxmap.help.zoom", "kiroxmap.help.copy", "kiroxmap.help.arrows", "kiroxmap.help.close"};
	static final int HELP_LINE = 11;
	private static final int TEXT_H = 9;

	private final AtlasSession session;
	private final Font font;
	private final AtlasConfig config = AtlasConfig.get();
	private boolean dragging;
	private boolean hasHover;
	private int hoverX;
	private int hoverZ;
	private String toast = "";
	private long toastUntil;
	/** Rectangles {x0, y0, x1, y1} covered by toolbar groups and panels: no map interaction there. */
	private List<int[]> uiRects = List.of();
	private WaypointHandler waypointHandler;
	private Menu menu;
	private int topInfoY = 34;
	private int bottomInfoY;
	/** Top of the help box, or -1 when the help is off. */
	private int helpTop = -1;

	/** Opens the waypoint editor; implemented by the screen that owns this canvas. */
	interface WaypointHandler {
		void create(int x, int z);

		void edit(Waypoint waypoint);
	}

	private record MenuItem(Component label, Runnable action) {
	}

	/** Right-click menu at a screen position. */
	private record Menu(int x, int y, int width, List<MenuItem> items) {
		static final int ROW = 14;

		int height() {
			return items.size() * ROW + 4;
		}
	}

	void setWaypointHandler(WaypointHandler handler) {
		this.waypointHandler = handler;
	}

	MapCanvas(AtlasSession session, Font font) {
		super(0, 0, 0, 0, Component.translatable("kiroxmap.map"));
		this.session = session;
		this.font = font;
	}

	void setBounds(int x, int y, int width, int height) {
		setRectangle(width, height, x, y);
	}

	/** Toolbar plates and panels drawn over the map, and where the two info lines go. */
	void setLayout(List<int[]> uiRects, int topInfoY, int bottomInfoY, int helpTop) {
		this.uiRects = uiRects;
		this.helpTop = helpTop;
		this.topInfoY = topInfoY;
		this.bottomInfoY = bottomInfoY;
	}

	private boolean isOverUi(double x, double y) {
		for (int[] r : uiRects) {
			if (x >= r[0] && x < r[2] && y >= r[1] && y < r[3]) {
				return true;
			}
		}
		return false;
	}

	/** Copies the block under the cursor (or the view center if the cursor is elsewhere) as "x z". */
	void copyCoordinates() {
		int x = hasHover ? hoverX : (int) Math.floor(session.view.centerX());
		int z = hasHover ? hoverZ : (int) Math.floor(session.view.centerZ());
		copy(x, z);
	}

	private void copy(int x, int z) {
		Minecraft.getInstance().keyboardHandler.setClipboard(x + " " + z);
		toast = Component.translatable("kiroxmap.copied", x, z).getString();
		toastUntil = System.currentTimeMillis() + 2500;
	}

	void toast(String text) {
		toast = text;
		toastUntil = System.currentTimeMillis() + 2500;
	}

	/** Block coordinate under a GUI position. */
	private double worldX(double guiX) {
		return session.view.centerX() + (guiX - (getX() + width / 2.0)) * session.view.blocksPerPixel();
	}

	private double worldZ(double guiY) {
		return session.view.centerZ() + (guiY - (getY() + height / 2.0)) * session.view.blocksPerPixel();
	}

	private int guiX(double worldX) {
		return (int) Math.floor(getX() + width / 2.0 + (worldX - session.view.centerX()) / session.view.blocksPerPixel());
	}

	private int guiY(double worldZ) {
		return (int) Math.floor(getY() + height / 2.0 + (worldZ - session.view.centerZ()) / session.view.blocksPerPixel());
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		graphics.fill(getX(), getY(), getRight(), getBottom(), BG);
		TileCache tiles = session.tiles();
		if (tiles == null) {
			graphics.enableScissor(getX(), getY(), getRight(), getBottom());
			if (config.showGrid) {
				drawGrid(graphics);
			}
			graphics.disableScissor();
			hasHover = false;
			drawStatus(graphics);
			drawPlates(graphics);
			drawInfo(graphics, null);
			drawHud(graphics);
			return;
		}

		tiles.draw(graphics, getX(), getY(), width, height, session.view, Minecraft.getInstance().getWindow().getGuiScale());

		graphics.enableScissor(getX(), getY(), getRight(), getBottom());
		if (config.showSlimeChunks && session.dim == Dim.OVERWORLD) {
			drawSlimeChunks(graphics, tiles.world().seed());
		}
		if (config.showGrid) {
			drawGrid(graphics);
		}
		List<StructureIndex.Found> shown = drawStructures(graphics);
		hasHover = isMouseOver(mouseX, mouseY) && !isOverUi(mouseX, mouseY) && menu == null;
		Waypoint hoveredWaypoint = hasHover ? waypointAt(mouseX, mouseY) : null;
		drawWaypoints(graphics, hoveredWaypoint);
		boolean playerHovered = drawPlayer(graphics, mouseX, mouseY);
		graphics.disableScissor();

		if (hasHover) {
			hoverX = (int) Math.floor(worldX(mouseX));
			hoverZ = (int) Math.floor(worldZ(mouseY));
			if (!playerHovered && hoveredWaypoint == null) {
				StructureIndex.Found near = nearest(shown, mouseX, mouseY);
				if (near != null) {
					graphics.setTooltipForNextFrame(font, Component.translatable("kiroxmap.structure." + near.kind().key())
							.append(Component.literal("  " + near.x() + ", " + near.z())), mouseX, mouseY);
				}
			}
		}
		drawPlates(graphics);
		drawInfo(graphics, tiles);
		drawHud(graphics);
		drawMenu(graphics, mouseX, mouseY);
	}

	/** Seed label (bottom left) and the key list inside its plate (bottom right). */
	private void drawHud(GuiGraphicsExtractor graphics) {
		if (session.seed() != null) {
			String seedLine = Component.translatable("kiroxmap.seed").getString() + ": " + session.seed();
			graphics.text(font, seedLine, getX() + 8, getBottom() - 6 - 9, 0xFFB0B0B0);
		}
		if (helpTop >= 0) {
			int widest = 0;
			for (String key : HELP_KEYS) {
				widest = Math.max(widest, font.width(Component.translatable(key)));
			}
			int x = getRight() - 6 - (widest + 14) + 7;
			for (int i = 0; i < HELP_KEYS.length; i++) {
				graphics.text(font, Component.translatable(HELP_KEYS[i]), x, helpTop + 5 + i * HELP_LINE, 0xFFD0D0D0);
			}
		}
	}

	/**
	 * Size of structure icons and waypoint pins: full size at the base zoom (and when zoomed in),
	 * shrinking linearly as the map is zoomed out, down to 25% at the farthest zoom.
	 */
	private double markerScale() {
		double t = Math.clamp((session.view.zoom() - MapView.DEFAULT_ZOOM) / (MapView.MAX_ZOOM - MapView.DEFAULT_ZOOM), 0.0, 1.0);
		return 1.0 - (1.0 - MIN_MARKER_SCALE) * t;
	}

	/** Message in the middle of the empty map: loading, failed, or waiting for a seed. */
	private void drawStatus(GuiGraphicsExtractor graphics) {
		int cx = getX() + width / 2, cy = getY() + height / 2 - 4;
		if (AtlasSession.warmupError() != null) {
			graphics.centeredText(font, Component.translatable("kiroxmap.load_failed"), cx, cy, 0xFFFF6060);
			return;
		}
		if (AtlasSession.isLoading()) {
			Component text = Component.translatable("kiroxmap.loading");
			int w = font.width(text);
			graphics.text(font, text, cx - w / 2, cy, 0xFFC0C0C0);
			graphics.text(font, ".".repeat((int) (System.currentTimeMillis() / 350 % 4)), cx + w / 2 + 1, cy, 0xFFC0C0C0);
			return;
		}
		graphics.centeredText(font, Component.translatable("kiroxmap.enter_seed"), cx, cy, 0xFFA0A0A0);
	}

	private void drawPlates(GuiGraphicsExtractor graphics) {
		for (int[] r : uiRects) {
			graphics.fill(r[0], r[1], r[2], r[3], PLATE_BORDER);
			graphics.fill(r[0] + 1, r[1] + 1, r[2] - 1, r[3] - 1, PLATE);
		}
	}

	private void drawGrid(GuiGraphicsExtractor graphics) {
		double bpp = session.view.blocksPerPixel();
		int step = GRID_STEPS[GRID_STEPS.length - 1];
		for (int s : GRID_STEPS) {
			if (s / bpp >= 48) {
				step = s;
				break;
			}
		}
		long x0 = Math.floorDiv((long) Math.floor(worldX(getX())), step) * step;
		for (long wx = x0; guiX(wx) <= getRight(); wx += step) {
			int gx = guiX(wx);
			graphics.fill(gx, getY(), gx + 1, getBottom(), wx == 0 ? GRID_AXIS : GRID);
		}
		long z0 = Math.floorDiv((long) Math.floor(worldZ(getY())), step) * step;
		for (long wz = z0; guiY(wz) <= getBottom(); wz += step) {
			int gy = guiY(wz);
			graphics.fill(getX(), gy, getRight(), gy + 1, wz == 0 ? GRID_AXIS : GRID);
		}
	}

	private void drawSlimeChunks(GuiGraphicsExtractor graphics, long seed) {
		if (16 / session.view.blocksPerPixel() < SLIME_MIN_PIXELS) {
			return;
		}
		int cx0 = Math.floorDiv((int) Math.floor(worldX(getX())), 16), cx1 = Math.floorDiv((int) Math.ceil(worldX(getRight())), 16);
		int cz0 = Math.floorDiv((int) Math.floor(worldZ(getY())), 16), cz1 = Math.floorDiv((int) Math.ceil(worldZ(getBottom())), 16);
		for (int cz = cz0; cz <= cz1; cz++) {
			for (int cx = cx0; cx <= cx1; cx++) {
				if (SlimeChunks.isSlime(seed, cx, cz)) {
					graphics.fill(guiX(cx * 16), guiY(cz * 16), guiX(cx * 16 + 16), guiY(cz * 16 + 16), SLIME_COLOR);
				}
			}
		}
	}

	/** Draws the enabled structure markers, at most one per icon-sized cell. Returns what was drawn. */
	private List<StructureIndex.Found> drawStructures(GuiGraphicsExtractor graphics) {
		List<StructureIndex.Found> shown = new ArrayList<>();
		StructureLayer layer = session.structures();
		if (layer == null) {
			return shown;
		}
		Set<StructureKind> enabled = EnumSet.noneOf(StructureKind.class);
		for (StructureKind kind : StructureKind.in(session.dim)) {
			if (config.isEnabled(kind)) {
				enabled.add(kind);
			}
		}
		double scale = markerScale();
		int cell = Math.max(4, (int) Math.round(ICON * scale)); // icons are smaller when zoomed out, so more fit
		// Look a little past the screen edge: an icon just outside can still hide one just inside.
		int margin = 2 * ICON;
		layer.update(enabled, worldX(getX() - margin), worldZ(getY() - margin), worldX(getRight() + margin), worldZ(getBottom() + margin));

		// Which icon wins when several overlap must not depend on the order the layer lists them in
		// (that changes while panning and caused icons to swap): rare ones first, then a fixed order.
		List<StructureIndex.Found> candidates = new ArrayList<>(layer.found());
		candidates.sort(Comparator.comparingInt((StructureIndex.Found f) -> priority(f.kind()))
				.thenComparingInt(f -> f.kind().ordinal()).thenComparingInt(StructureIndex.Found::x).thenComparingInt(StructureIndex.Found::z));

		// Accepted icons, bucketed so the overlap test only looks at the neighbouring buckets.
		Map<Long, List<int[]>> placed = new HashMap<>();
		for (StructureIndex.Found found : candidates) {
			if (!enabled.contains(found.kind())) {
				continue;
			}
			int x = guiX(found.x()), y = guiY(found.z());
			int bx = Math.floorDiv(x, cell), by = Math.floorDiv(y, cell);
			boolean blocked = false;
			for (int ox = -1; ox <= 1 && !blocked; ox++) {
				for (int oy = -1; oy <= 1 && !blocked; oy++) {
					List<int[]> bucket = placed.get(bucketKey(bx + ox, by + oy));
					if (bucket != null) {
						for (int[] other : bucket) {
							if (Math.abs(other[0] - x) < cell && Math.abs(other[1] - y) < cell) {
								blocked = true;
								break;
							}
						}
					}
				}
			}
			if (blocked) {
				continue;
			}
			placed.computeIfAbsent(bucketKey(bx, by), k -> new ArrayList<>(2)).add(new int[] {x, y});
			if (x < getX() - ICON || x > getRight() + ICON || y < getY() - ICON || y > getBottom() + ICON) {
				continue; // off screen: it still claimed its spot above, it just is not drawn
			}
			shown.add(found);
			if (scale >= 0.999) {
				graphics.fakeItem(Icons.of(found.kind()), x - ICON / 2, y - ICON / 2);
			} else {
				var pose = graphics.pose();
				pose.pushMatrix();
				pose.translate(x, y);
				pose.scale((float) scale, (float) scale);
				graphics.fakeItem(Icons.of(found.kind()), -ICON / 2, -ICON / 2);
				pose.popMatrix();
			}
			if (config.showLabels) {
				Component name = Component.translatable("kiroxmap.structure." + found.kind().key());
				int w = font.width(name);
				int ly = y + cell / 2;
				graphics.fill(x - w / 2 - 2, ly, x + w / 2 + 2, ly + 10, 0xA0000000);
				graphics.centeredText(font, name, x, ly + 1, 0xFFFFFFFF);
			}
		}
		return shown;
	}

	private static long bucketKey(int bx, int by) {
		return ((long) bx << 32) | (by & 0xFFFFFFFFL);
	}

	private static int priority(StructureKind kind) {
		return kind == StructureKind.STRONGHOLD || kind == StructureKind.END_CITY_SHIP ? 0 : 1;
	}

	private StructureIndex.Found nearest(List<StructureIndex.Found> shown, int mouseX, int mouseY) {
		StructureIndex.Found best = null;
		double half = Math.max(4, ICON * markerScale() / 2);
		double bestDist = half * half;
		for (StructureIndex.Found found : shown) {
			double dx = guiX(found.x()) - mouseX, dz = guiY(found.z()) - mouseY;
			double dist = dx * dx + dz * dz;
			if (dist <= bestDist) {
				best = found;
				bestDist = dist;
			}
		}
		return best;
	}

	// ------------------------------------------------------------------ waypoints

	private List<Waypoint> waypointsHere() {
		List<Waypoint> out = new ArrayList<>();
		for (Waypoint w : config.waypointsFor(session.worldKey)) {
			if (w.visible && session.dim.key().equals(w.dim)) {
				out.add(w);
			}
		}
		return out;
	}

	/** The topmost waypoint marker under a screen position, or null. */
	private Waypoint waypointAt(double mouseX, double mouseY) {
		Waypoint best = null;
		double reach = Math.max(4, 8 * markerScale());
		double bestDist = reach * reach;
		for (Waypoint w : waypointsHere()) {
			double dx = guiX(w.x + 0.5) - mouseX, dy = guiY(w.z + 0.5) - mouseY;
			double dist = dx * dx + dy * dy;
			if (dist <= bestDist) {
				best = w;
				bestDist = dist;
			}
		}
		return best;
	}

	/**
	 * Waypoint pins are always visible. Names are shown for the pin under the cursor (name and
	 * coordinates card) and, when the waypoint-labels switch is on, under every pin. Pins keep their
	 * design at every zoom: only their size changes.
	 */
	private void drawWaypoints(GuiGraphicsExtractor graphics, Waypoint hovered) {
		double scale = markerScale();
		int r = Math.max(2, (int) Math.round(5 * scale));
		for (Waypoint w : waypointsHere()) {
			if (w == hovered) {
				continue;
			}
			int x = guiX(w.x + 0.5), y = guiY(w.z + 0.5);
			drawPin(graphics, x, y, w.color, false, scale);
			if (config.showWaypointLabels) {
				String name = waypointName(w);
				int tw = font.width(name);
				int ly = y + r + 3;
				graphics.fill(x - tw / 2 - 2, ly, x + tw / 2 + 3, ly + TEXT_H + 2, 0xA0000000);
				graphics.text(font, name, x - tw / 2, ly + 2, 0xFFFFFFFF);
			}
		}
		if (hovered == null) {
			return;
		}
		// Drawn last so it sits on top of any neighbour.
		int x = guiX(hovered.x + 0.5), y = guiY(hovered.z + 0.5);
		int hr = (int) Math.ceil(6 * scale);
		drawPin(graphics, x, y, hovered.color, true, scale);
		String name = waypointName(hovered);
		String coords = hovered.x + ", " + hovered.z;
		int w = Math.max(font.width(name), font.width(coords)) + 12;
		int h = 2 + 5 + TEXT_H + 3 + TEXT_H + 5; // accent bar, padding, name, gap, coordinates, padding
		int bx = Math.max(getX() + 2, Math.min(x - w / 2, getRight() - w - 2));
		int by = y - hr - 4 - h;
		if (by < getY() + 2) { // no room above: put the card under the pin
			by = y + hr + 4;
		}
		graphics.fill(bx - 1, by - 1, bx + w + 1, by + h + 1, 0xFF000000);
		graphics.fill(bx, by, bx + w, by + h, 0xF01C1C1C);
		graphics.fill(bx, by, bx + w, by + 2, hovered.color);
		graphics.text(font, name, bx + (w - font.width(name)) / 2, by + 2 + 5, 0xFFFFFFFF);
		graphics.text(font, coords, bx + (w - font.width(coords)) / 2, by + 2 + 5 + TEXT_H + 3, 0xFF9A9A9A);
	}

	private static String waypointName(Waypoint w) {
		return w.name.isEmpty() ? Component.translatable("kiroxmap.waypoint.default_name").getString() : w.name;
	}

	/** A map pin centered on (x, y), scaled by {@code scale}; the design never changes. */
	private static void drawPin(GuiGraphicsExtractor graphics, int x, int y, int color, boolean big, double scale) {
		if (scale >= 0.999) {
			drawPinShape(graphics, x, y, color, big);
			return;
		}
		var pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale((float) scale, (float) scale);
		drawPinShape(graphics, 0, 0, color, big);
		pose.popMatrix();
	}

	/** A colored diamond with a dark outline and a highlight. */
	private static void drawPinShape(GuiGraphicsExtractor graphics, int x, int y, int color, boolean big) {
		int r = big ? 6 : 5;
		for (int dy = -r; dy <= r; dy++) {
			int half = r - Math.abs(dy);
			graphics.fill(x - half - 1, y + dy, x + half + 2, y + dy + 1, 0xFF000000);
		}
		int ri = r - 1;
		for (int dy = -ri; dy <= ri; dy++) {
			int half = ri - Math.abs(dy);
			graphics.fill(x - half, y + dy, x + half + 1, y + dy + 1, color);
		}
		graphics.fill(x - 1, y - ri + 1, x + 1, y - ri + 3, 0x80FFFFFF); // highlight
		graphics.fill(x, y, x + 1, y + 1, 0xFF000000);                   // center dot
	}

	// ------------------------------------------------------------------ context menu

	private void openMenu(int mouseX, int mouseY) {
		int x = (int) Math.floor(worldX(mouseX)), z = (int) Math.floor(worldZ(mouseY));
		Waypoint target = waypointAt(mouseX, mouseY);
		List<MenuItem> items = new ArrayList<>();
		if (target != null) {
			items.add(new MenuItem(Component.translatable("kiroxmap.waypoint.edit"), () -> {
				if (waypointHandler != null) {
					waypointHandler.edit(target);
				}
			}));
			items.add(new MenuItem(Component.translatable("kiroxmap.waypoint.delete"), () -> {
				config.waypointsFor(session.worldKey).remove(target);
				config.save();
			}));
			items.add(new MenuItem(Component.translatable("kiroxmap.menu.copy"), () -> copy(target.x, target.z)));
		} else {
			items.add(new MenuItem(Component.translatable("kiroxmap.menu.add_waypoint"), () -> {
				if (waypointHandler != null) {
					waypointHandler.create(x, z);
				}
			}));
			items.add(new MenuItem(Component.translatable("kiroxmap.menu.copy"), () -> copy(x, z)));
		}
		int w = 12;
		for (MenuItem item : items) {
			w = Math.max(w, font.width(item.label()) + 14);
		}
		Menu m = new Menu(0, 0, w, items);
		int mx = Math.min(mouseX, getRight() - w - 2), my = Math.min(mouseY, getBottom() - m.height() - 2);
		menu = new Menu(Math.max(getX(), mx), Math.max(getY(), my), w, items);
	}

	private void drawMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (menu == null) {
			return;
		}
		graphics.fill(menu.x() - 1, menu.y() - 1, menu.x() + menu.width() + 1, menu.y() + menu.height() + 1, 0xFF000000);
		graphics.fill(menu.x(), menu.y(), menu.x() + menu.width(), menu.y() + menu.height(), 0xFF2B2B2B);
		for (int i = 0; i < menu.items().size(); i++) {
			int y = menu.y() + 2 + i * Menu.ROW;
			boolean hover = mouseX >= menu.x() && mouseX < menu.x() + menu.width() && mouseY >= y && mouseY < y + Menu.ROW;
			if (hover) {
				graphics.fill(menu.x() + 1, y, menu.x() + menu.width() - 1, y + Menu.ROW, 0xFF4A4A4A);
			}
			graphics.text(font, menu.items().get(i).label(), menu.x() + 7, y + 3, hover ? 0xFFFFFFFF : 0xFFD0D0D0);
		}
	}

	/** Returns true when the click was inside the menu (and ran an item). */
	private boolean clickMenu(double mouseX, double mouseY) {
		Menu m = menu;
		menu = null;
		if (m == null || mouseX < m.x() || mouseX >= m.x() + m.width() || mouseY < m.y() + 2 || mouseY >= m.y() + 2 + m.items().size() * Menu.ROW) {
			return false;
		}
		m.items().get((int) (mouseY - m.y() - 2) / Menu.ROW).action().run();
		return true;
	}

	// ------------------------------------------------------------------ player

	/**
	 * The live player marker: a soft view cone in the facing direction, the skin face in a ring, and
	 * an outlined arrowhead pointing where the player looks. Returns whether the cursor is on it.
	 */
	private boolean drawPlayer(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return false;
		}
		Dim actual = AtlasScreen.dimOf(player);
		double scale = actual.scaleTo(session.dim);
		if (Double.isNaN(scale)) {
			return false; // the End has no coordinate correspondence with the other dimensions
		}
		int x = guiX(player.getX() * scale), y = guiY(player.getZ() * scale);
		boolean linked = actual != session.dim;
		int accent = linked ? 0xFFA0A0A0 : 0xFFFFFFFF;

		// Heading: Minecraft yaw 0 faces +Z (south), 90 faces -X (west).
		double yaw = Math.toRadians(player.getYRot());
		double fx = -Math.sin(yaw), fz = Math.cos(yaw);

		// View cone: translucent wedge that fades out with distance, drawn in 2x2 blocks.
		if (!linked) {
			final int reach = 24;
			for (int dy = -reach; dy <= reach; dy += 2) {
				for (int dx = -reach; dx <= reach; dx += 2) {
					double u = dx * fx + dy * fz;       // distance ahead
					double v = -dx * fz + dy * fx;      // sideways
					if (u > 6 && u < reach && Math.abs(v) <= u * 0.6) {
						int alpha = (int) (0x48 * (1 - u / reach));
						graphics.fill(x + dx, y + dy, x + dx + 2, y + dy + 2, (alpha << 24) | 0xFFFFFF);
					}
				}
			}
		}

		// Ring + face.
		graphics.fill(x - 4, y - 4, x + 4, y + 4, 0xFF000000);
		graphics.fill(x - 3, y - 3, x + 3, y + 3, accent);
		PlayerFaceExtractor.extractRenderState(graphics, player.getSkin(), x - 3, y - 3, 6);
		if (linked) {
			graphics.fill(x - 3, y - 3, x + 3, y + 3, 0x80000000);
		}

		// Arrowhead: a white chevron with a gray border (and a soft dark halo), pointing where the
		// player looks. Pixels are tested against the polygon in the player's own frame (u ahead,
		// v sideways), so it rotates smoothly with the view.
		int fill = linked ? 0xFFB4B4B4 : 0xFFFFFFFF;
		int fillShade = linked ? 0xFF9C9C9C : 0xFFD6D6D6;
		int border = linked ? 0xFF4A4A4A : 0xFF6E6E6E;
		for (int dy = -17; dy <= 17; dy++) {
			for (int dx = -17; dx <= 17; dx++) {
				double px = dx + 0.5, py = dy + 0.5;
				double u = px * fx + py * fz, v = -px * fz + py * fx;
				double d = arrowDistance(u, v);
				int c;
				if (arrowContains(u, v)) {
					c = d < 1.0 ? border : v > 0 ? fillShade : fill;
				} else if (d < 1.3) {
					c = 0xB0000000;
				} else {
					continue;
				}
				graphics.fill(x + dx, y + dy, x + dx + 1, y + dy + 1, c);
			}
		}

		boolean hovered = Math.abs(mouseX - x) <= 5 && Math.abs(mouseY - y) <= 5;
		if (hovered) {
			graphics.setTooltipForNextFrame(font, Component.empty().append(player.getName())
					.append(Component.literal("  " + (int) Math.floor(player.getX() * scale) + ", " + (int) Math.floor(player.getZ() * scale))), mouseX, mouseY);
		}
		return hovered;
	}

	/** Arrowhead outline in the player's frame {u ahead, v sideways}: tip, right wing, notch, left wing. */
	private static final double[][] ARROW = {{15, 0}, {5, 6.5}, {8.5, 0}, {5, -6.5}};

	private static boolean arrowContains(double u, double v) {
		boolean inside = false;
		for (int i = 0, j = ARROW.length - 1; i < ARROW.length; j = i++) {
			double ui = ARROW[i][0], vi = ARROW[i][1], uj = ARROW[j][0], vj = ARROW[j][1];
			if ((vi > v) != (vj > v) && u < (uj - ui) * (v - vi) / (vj - vi) + ui) {
				inside = !inside;
			}
		}
		return inside;
	}

	/** Distance from (u, v) to the nearest edge of the arrowhead. */
	private static double arrowDistance(double u, double v) {
		double best = Double.MAX_VALUE;
		for (int i = 0, j = ARROW.length - 1; i < ARROW.length; j = i++) {
			double au = ARROW[j][0], av = ARROW[j][1], bu = ARROW[i][0], bv = ARROW[i][1];
			double eu = bu - au, ev = bv - av;
			double t = Math.clamp(((u - au) * eu + (v - av) * ev) / (eu * eu + ev * ev), 0.0, 1.0);
			best = Math.min(best, Math.hypot(u - (au + t * eu), v - (av + t * ev)));
		}
		return best;
	}

	/** A centered line of text on a translucent dark pill, scaled with the UI scale setting. */
	private void pill(GuiGraphicsExtractor graphics, String text, int centerX, int y, int color) {
		int w = font.width(text);
		var pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(centerX, y);
		float f = (float) IconButton.factor();
		pose.scale(f, f);
		graphics.fill(-w / 2 - 4, -2, w / 2 + 4, 10, PILL);
		graphics.text(font, text, -w / 2, 0, color);
		pose.popMatrix();
	}

	/** Top line: the player and the view. Bottom line: whatever is under the cursor. */
	private void drawInfo(GuiGraphicsExtractor graphics, TileCache tiles) {
		int cx = getX() + width / 2;

		StringBuilder top = new StringBuilder();
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null) {
			Dim actual = AtlasScreen.dimOf(player);
			double scale = actual.scaleTo(session.dim);
			top.append(player.getName().getString());
			if (!Double.isNaN(scale)) {
				int px = (int) Math.floor(player.getX() * scale), pz = (int) Math.floor(player.getZ() * scale);
				top.append("  |  x: ").append(px).append(", z: ").append(pz);
				if (actual == session.dim) {
					top.append(", y: ").append((int) Math.floor(player.getY()));
				}
				if (tiles != null) {
					int id = tiles.cachedBiomeAt(px, pz); // only what is already generated: no work on the render thread
					String biome = id < 0 ? null : BiomeData.name(id);
					if (biome != null) {
						top.append("  |  ").append(Component.translatable("biome.minecraft." + biome).getString());
					}
				}
			}
			top.append("  |  ");
		}
		top.append(String.format("%s %.1f %s", Component.translatable("kiroxmap.zoom").getString(),
				session.view.blocksPerPixel(), Component.translatable("kiroxmap.blocks_per_pixel").getString()));
		if (tiles != null && tiles.pendingCount() > 0) {
			top.append("  |  ").append(Component.translatable("kiroxmap.generating").getString())
					.append(" ").append(tiles.pendingCount());
		}
		pill(graphics, top.toString(), cx, topInfoY, 0xFFD8D8D8);

		if (System.currentTimeMillis() < toastUntil) {
			pill(graphics, toast, cx, bottomInfoY - 14, 0xFF55FF55);
		}
		if (tiles != null && hasHover) {
			int id = tiles.cachedBiomeAt(hoverX, hoverZ);
			if (id < 0) {
				id = tiles.world().biomeAt(hoverX, hoverZ);
			}
			String biome = BiomeData.name(id);
			String name = biome == null ? "?" : Component.translatable("biome.minecraft." + biome).getString();
			String line = name + "  |  x: " + hoverX + ", z: " + hoverZ
					+ "  |  " + Component.translatable("kiroxmap.chunk").getString() + ": "
					+ Math.floorDiv(hoverX, 16) + ", " + Math.floorDiv(hoverZ, 16);
			int w = font.width(line) + 14;
			int x0 = -w / 2;
			var pose = graphics.pose();
			pose.pushMatrix();
			pose.translate(cx, bottomInfoY);
			float f = (float) IconButton.factor();
			pose.scale(f, f);
			graphics.fill(x0 - 4, -2, x0 + w + 4, 10, PILL);
			graphics.fill(x0, 0, x0 + 10, 8, 0xFF000000);
			graphics.fill(x0 + 1, 1, x0 + 9, 7, BiomeData.color(id));
			graphics.text(font, line, x0 + 14, 0, 0xFFFFFFFF);
			pose.popMatrix();
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (menu != null && clickMenu(event.x(), event.y())) {
			return true;
		}
		if (session.tiles() == null || !isMouseOver(event.x(), event.y()) || isOverUi(event.x(), event.y())) {
			return false;
		}
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			dragging = true;
			return true;
		}
		if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
			openMenu((int) event.x(), (int) event.y());
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dragging && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			session.view.panPixels(dx, dy);
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && dragging) {
			dragging = false;
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (session.tiles() == null || !isMouseOver(x, y) || isOverUi(x, y) || scrollY == 0) {
			return false;
		}
		session.view.zoomAround(scrollY > 0 ? -1 : 1, x - (getX() + width / 2.0), y - (getY() + height / 2.0));
		return true;
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}
}
