package com.kiroxtm.screen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.kiroxtm.config.AtlasConfig;
import com.kiroxtm.config.Waypoint;
import com.kiroxtm.world.BiomeData;
import com.kiroxtm.world.StructureKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The right-hand panel: three tabs (structures, biomes, waypoints) with a switch per entry, "all /
 * none" shortcuts and a scrolling list. Custom drawn so rows can carry an item icon (structures) or
 * a color swatch (biomes, waypoints).
 *
 * <p>The panel follows the UI scale setting: everything is laid out in "logical" pixels (the size it
 * has at UI scale x3) and drawn through a scale transform, so the box, its rows and its text all
 * grow and shrink together with the toolbar. {@link #setBounds} takes the on-screen size; the
 * logical size is that divided by the scale.
 */
final class SidePanel extends AbstractWidget {
	enum Tab { STRUCTURES, BIOMES, WAYPOINTS }

	private static final int ROW = 18;
	private static final int HEADER = 18;
	private static final int ACTIONS = 16;
	private static final int BG = 0xE8141414;
	private static final int FRAME = 0xFF000000;
	private static final int FRAME_LIGHT = 0xFF3C3C3C;
	private static final int TITLE_BG = 0xFF242424;
	private static final int ACCENT = 0xFFFFD84A;

	private record Row(Component label, int swatch, ItemStack icon, BooleanSupplier checked, Consumer<Boolean> set) {
	}

	private final AtlasSession session;
	private final Font font;
	private final AtlasConfig config = AtlasConfig.get();
	private final Runnable onBiomesChanged;
	private Tab tab;
	private double scroll;
	private List<Row> rows = List.of();
	/** On-screen pixels per logical pixel (the UI scale factor). */
	private double scale = 1.0;
	/** Fingerprint of the waypoint list the rows were built from, to notice changes made elsewhere. */
	private int waypointSignature;

	SidePanel(AtlasSession session, Font font, Runnable onBiomesChanged) {
		super(0, 0, 0, 0, Component.translatable("kiroxmap.panel"));
		this.session = session;
		this.font = font;
		this.onBiomesChanged = onBiomesChanged;
		this.tab = "BIOMES".equals(config.panelTab) ? Tab.BIOMES : "WAYPOINTS".equals(config.panelTab) ? Tab.WAYPOINTS : Tab.STRUCTURES;
		rebuildRows();
	}

	/** Sets the on-screen rectangle and the scale the content is drawn at. */
	void setBounds(int x, int y, int width, int height, double scale) {
		this.scale = scale;
		setRectangle(width, height, x, y);
	}

	/** Width and height in logical pixels. */
	private int logicalWidth() {
		return (int) Math.round(width / scale);
	}

	private int logicalHeight() {
		return (int) Math.round(height / scale);
	}

	/** Rebuilds the list for the current tab and dimension. */
	void rebuildRows() {
		List<Row> list = new ArrayList<>();
		if (tab == Tab.STRUCTURES) {
			for (StructureKind kind : StructureKind.in(session.dim)) {
				list.add(new Row(Component.translatable("kiroxmap.structure." + kind.key()), 0, Icons.of(kind),
						() -> config.isEnabled(kind), on -> {
							config.setEnabled(kind, on);
							config.save();
						}));
			}
		} else if (tab == Tab.WAYPOINTS) {
			// Each row switches one waypoint of the current world and dimension on or off.
			for (Waypoint w : config.waypointsFor(session.worldKey)) {
				if (session.dim.key().equals(w.dim)) {
					Component label = Component.literal(w.name.isEmpty() ? w.x + ", " + w.z : w.name);
					list.add(new Row(label, w.color, null, () -> w.visible, on -> {
						w.visible = on;
						config.save();
					}));
				}
			}
			waypointSignature = computeWaypointSignature();
		} else if (AtlasSession.isWarm()) {
			// The biome table comes from the native library, which loads in the background after the
			// screen is shown; until then the list stays empty and is rebuilt when loading finishes.
			List<Row> biomes = new ArrayList<>();
			for (int id : BiomeData.mapBiomes(session.dim)) {
				String name = BiomeData.name(id);
				biomes.add(new Row(Component.translatable("biome.minecraft." + name), BiomeData.color(id), null,
						() -> !config.hiddenBiomes.contains(id), on -> {
							if (on) {
								config.hiddenBiomes.remove(id);
							} else {
								config.hiddenBiomes.add(id);
							}
							config.save();
							onBiomesChanged.run();
						}));
			}
			biomes.sort(Comparator.comparing(row -> row.label().getString(), String.CASE_INSENSITIVE_ORDER));
			list = biomes;
		}
		rows = list;
		scroll = Math.min(scroll, maxScroll());
	}

	/**
	 * A hash of what the waypoint rows show (which waypoints, their names, colors and positions).
	 * Waypoints can be deleted or edited from the map's context menu while this panel is open, so the
	 * rows are rebuilt whenever it changes.
	 */
	private int computeWaypointSignature() {
		int hash = 1;
		for (Waypoint w : config.waypointsFor(session.worldKey)) {
			if (session.dim.key().equals(w.dim)) {
				hash = 31 * hash + Objects.hash(System.identityHashCode(w), w.name, w.color, w.x, w.z);
			}
		}
		return hash;
	}

	private void refreshWaypointRows() {
		if (tab == Tab.WAYPOINTS && computeWaypointSignature() != waypointSignature) {
			rebuildRows();
		}
	}

	private int listTop() {
		return HEADER + ACTIONS;
	}

	private double maxScroll() {
		return Math.max(0, rows.size() * ROW - (logicalHeight() - listTop()));
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		refreshWaypointRows();
		int w = logicalWidth(), h = logicalHeight();
		// Mouse position in the panel's own (logical) pixels.
		double mx = (mouseX - getX()) / scale, my = (mouseY - getY()) / scale;

		// The scissor is given in screen pixels, before the scale transform is pushed.
		graphics.enableScissor(getX() - 1, getY() - 1, getRight() + 1, getBottom() + 1);
		var pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(getX(), getY());
		pose.scale((float) scale, (float) scale);

		graphics.fill(-1, -1, w + 1, h + 1, FRAME);
		graphics.fill(0, 0, w, h, FRAME_LIGHT);
		graphics.fill(1, 1, w - 1, h - 1, BG);

		// Title bar (the tab is chosen with the toolbar buttons).
		graphics.fill(1, 1, w - 1, HEADER, TITLE_BG);
		graphics.fill(1, HEADER - 2, w - 1, HEADER, ACCENT);
		Component title = Component.translatable("kiroxmap.tab." + tab.name().toLowerCase(java.util.Locale.ROOT));
		graphics.centeredText(font, title, w / 2, 5, 0xFFFFFFFF);

		// All / none.
		drawAction(graphics, Component.translatable("kiroxmap.all"), 6, HEADER + 2, mx, my);
		drawAction(graphics, Component.translatable("kiroxmap.none"), 12 + actionWidth("kiroxmap.all"), HEADER + 2, mx, my);
		pose.popMatrix();
		graphics.disableScissor();

		// List, clipped to the area under the header (screen pixels again).
		int top = listTop();
		int clipTop = getY() + (int) Math.round(top * scale);
		graphics.enableScissor(getX() + 1, clipTop, getRight(), getBottom() - 1);
		pose.pushMatrix();
		pose.translate(getX(), getY());
		pose.scale((float) scale, (float) scale);
		for (int i = 0; i < rows.size(); i++) {
			int y = top + i * ROW - (int) scroll;
			if (y + ROW < top || y > h) {
				continue;
			}
			Row row = rows.get(i);
			boolean hover = mx >= 0 && mx < w && my >= Math.max(y, top) && my < y + ROW && my < h;
			if (hover) {
				graphics.fill(1, y, w, y + ROW, 0x30FFFFFF);
			}
			boolean on = row.checked().getAsBoolean();
			int bx = 6, by = y + (ROW - 9) / 2;
			graphics.outline(bx, by, 9, 9, on ? ACCENT : 0xFF8090A0);
			if (on) {
				graphics.fill(bx + 2, by + 2, bx + 7, by + 7, ACCENT);
			}
			int tx = bx + 15;
			if (row.icon() != null) {
				graphics.fakeItem(row.icon(), tx, y + 1);
				tx += 20;
			} else {
				graphics.fill(tx, y + 4, tx + 10, y + 14, 0xFF000000);
				graphics.fill(tx + 1, y + 5, tx + 9, y + 13, row.swatch());
				tx += 15;
			}
			graphics.text(font, row.label(), tx, y + (ROW - 8) / 2, on ? 0xFFFFFFFF : 0xFF707880);
		}
		pose.popMatrix();
		graphics.disableScissor();
	}

	private int actionWidth(String key) {
		return font.width(Component.translatable(key)) + 8;
	}

	private void drawAction(GuiGraphicsExtractor graphics, Component label, int x, int y, double mouseX, double mouseY) {
		int w = font.width(label) + 8;
		boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 12;
		graphics.fill(x, y, x + w, y + 12, hover ? 0xFF3A4254 : 0xFF2A3140);
		graphics.text(font, label, x + 4, y + 2, 0xFFE0E0E0);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (!isMouseOver(event.x(), event.y())) {
			return false;
		}
		refreshWaypointRows();
		double mx = (event.x() - getX()) / scale, my = (event.y() - getY()) / scale;
		if (my < HEADER) {
			return true;
		}
		if (my < listTop()) {
			int allX = 6, noneX = 12 + actionWidth("kiroxmap.all");
			if (mx >= allX && mx < allX + actionWidth("kiroxmap.all")) {
				playDownSound(Minecraft.getInstance().getSoundManager());
				setAll(true);
			} else if (mx >= noneX && mx < noneX + actionWidth("kiroxmap.none")) {
				playDownSound(Minecraft.getInstance().getSoundManager());
				setAll(false);
			}
			return true;
		}
		int index = (int) Math.floor((my - listTop() + scroll) / ROW);
		if (index >= 0 && index < rows.size()) {
			Row row = rows.get(index);
			playDownSound(Minecraft.getInstance().getSoundManager());
			row.set().accept(!row.checked().getAsBoolean());
		}
		return true;
	}

	private void setAll(boolean on) {
		for (Row row : rows) {
			if (row.checked().getAsBoolean() != on) {
				row.set().accept(on);
			}
		}
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (!isMouseOver(x, y)) {
			return false;
		}
		scroll = Math.clamp(scroll - scrollY * ROW * 2, 0, maxScroll());
		return true;
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}
}
