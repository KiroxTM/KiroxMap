package com.kiroxtm.screen;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.mojang.blaze3d.platform.InputConstants;

import com.kiroxtm.config.AtlasConfig;
import com.kiroxtm.config.Waypoint;
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
	void setLayout(List<int[]> uiRects, int topInfoY, int bottomInfoY) {
		this.uiRects = uiRects;
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
		drawMenu(graphics, mouseX, mouseY);
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
		layer.update(enabled, worldX(getX()), worldZ(getY()), worldX(getRight()), worldZ(getBottom()));

		Set<Long> occupied = new HashSet<>();
		// Strongholds and end ships are rare and sought after: they claim their cell first.
		List<StructureIndex.Found> candidates = new ArrayList<>(layer.found());
		candidates.sort((p, q) -> Integer.compare(priority(p.kind()), priority(q.kind())));
		for (StructureIndex.Found found : candidates) {
			if (!enabled.contains(found.kind())) {
				continue;
			}
			int x = guiX(found.x()), y = guiY(found.z());
			if (x < getX() - ICON || x > getRight() + ICON || y < getY() - ICON || y > getBottom() + ICON
					|| !occupied.add(((long) Math.floorDiv(x, ICON) << 32) | (Math.floorDiv(y, ICON) & 0xFFFFFFFFL))) {
				continue;
			}
			shown.add(found);
			graphics.fakeItem(Icons.of(found.kind()), x - ICON / 2, y - ICON / 2);
			if (config.showLabels) {
				Component name = Component.translatable("kiroxmap.structure." + found.kind().key());
				int w = font.width(name);
				graphics.fill(x - w / 2 - 2, y + ICON / 2, x + w / 2 + 2, y + ICON / 2 + 10, 0xA0000000);
				graphics.centeredText(font, name, x, y + ICON / 2 + 1, 0xFFFFFFFF);
			}
		}
		return shown;
	}

	private static int priority(StructureKind kind) {
		return kind == StructureKind.STRONGHOLD || kind == StructureKind.END_CITY_SHIP ? 0 : 1;
	}

	private StructureIndex.Found nearest(List<StructureIndex.Found> shown, int mouseX, int mouseY) {
		StructureIndex.Found best = null;
		double bestDist = ICON * ICON / 4.0;
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
		double bestDist = 8 * 8;
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
	 * Waypoint pins are always visible; their name is only shown while the cursor is on one (unlike
	 * structure labels, which follow the "labels" switch).
	 */
	private void drawWaypoints(GuiGraphicsExtractor graphics, Waypoint hovered) {
		for (Waypoint w : waypointsHere()) {
			if (w != hovered) {
				drawPin(graphics, guiX(w.x + 0.5), guiY(w.z + 0.5), w.color, false);
			}
		}
		if (hovered != null) { // drawn last so it sits on top of any neighbour
			int x = guiX(hovered.x + 0.5), y = guiY(hovered.z + 0.5);
			drawPin(graphics, x, y, hovered.color, true);
			String name = hovered.name.isEmpty() ? Component.translatable("kiroxmap.waypoint.default_name").getString() : hovered.name;
			String coords = hovered.x + ", " + hovered.z;
			int w = Math.max(font.width(name), font.width(coords)) + 10;
			int bx = x - w / 2, by = y - 12 - 24;
			graphics.fill(bx - 1, by - 1, bx + w + 1, by + 23, 0xFF000000);
			graphics.fill(bx, by, bx + w, by + 22, 0xF01C1C1C);
			graphics.fill(bx, by, bx + w, by + 2, hovered.color);
			graphics.text(font, name, x - font.width(name) / 2, by + 5, 0xFFFFFFFF);
			graphics.text(font, coords, x - font.width(coords) / 2, by + 13, 0xFF9A9A9A);
		}
	}

	/** A map pin centered on (x, y): a colored diamond with a dark outline and a highlight. */
	private static void drawPin(GuiGraphicsExtractor graphics, int x, int y, int color, boolean big) {
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
		int accent = linked ? 0xFFA0A0A0 : 0xFFFFE040;

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

		// Arrowhead: dark outline first, then the colored triangle on top.
		for (int pass = 0; pass < 2; pass++) {
			double grow = pass == 0 ? 1.0 : 0;
			double baseU = 5 - grow, tipU = 10.5 + grow * 1.6, slope = 0.85;
			int c = pass == 0 ? 0xFF000000 : accent;
			for (int dy = -13; dy <= 13; dy++) {
				for (int dx = -13; dx <= 13; dx++) {
					double u = dx * fx + dy * fz, v = -dx * fz + dy * fx;
					if (u >= baseU && u <= tipU && Math.abs(v) <= (tipU - u) * slope + (pass == 0 ? 0.5 : 0)) {
						graphics.fill(x + dx, y + dy, x + dx + 1, y + dy + 1, c);
					}
				}
			}
		}

		boolean hovered = Math.abs(mouseX - x) <= 5 && Math.abs(mouseY - y) <= 5;
		if (hovered) {
			graphics.setTooltipForNextFrame(font, Component.empty().append(player.getName())
					.append(Component.literal("  " + (int) Math.floor(player.getX() * scale) + ", " + (int) Math.floor(player.getZ() * scale))), mouseX, mouseY);
		}
		return hovered;
	}

	/** A centered line of text on a translucent dark pill, so it stays readable over any biome. */
	private void pill(GuiGraphicsExtractor graphics, String text, int centerX, int y, int color) {
		int w = font.width(text);
		graphics.fill(centerX - w / 2 - 4, y - 2, centerX + w / 2 + 4, y + 10, PILL);
		graphics.text(font, text, centerX - w / 2, y, color);
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
			int x0 = cx - w / 2;
			graphics.fill(x0 - 4, bottomInfoY - 2, x0 + w + 4, bottomInfoY + 10, PILL);
			graphics.fill(x0, bottomInfoY, x0 + 10, bottomInfoY + 8, 0xFF000000);
			graphics.fill(x0 + 1, bottomInfoY + 1, x0 + 9, bottomInfoY + 7, BiomeData.color(id));
			graphics.text(font, line, x0 + 14, bottomInfoY, 0xFFFFFFFF);
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
