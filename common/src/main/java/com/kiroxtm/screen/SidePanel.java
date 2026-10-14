package com.kiroxtm.screen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.kiroxtm.config.AtlasConfig;
import com.kiroxtm.config.Waypoint;
import com.kiroxtm.world.BiomeData;
import com.kiroxtm.world.StructureKind;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The right-hand panel: two tabs, one with a switch per structure of the current dimension and one
 * with a switch per biome, plus "all / none" shortcuts and a scrolling list. Custom drawn so rows
 * can carry an item icon (structures) or a color swatch (biomes).
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

	SidePanel(AtlasSession session, Font font, Runnable onBiomesChanged) {
		super(0, 0, 0, 0, Component.translatable("kiroxmap.panel"));
		this.session = session;
		this.font = font;
		this.onBiomesChanged = onBiomesChanged;
		this.tab = "BIOMES".equals(config.panelTab) ? Tab.BIOMES : "WAYPOINTS".equals(config.panelTab) ? Tab.WAYPOINTS : Tab.STRUCTURES;
		rebuildRows();
	}

	void setBounds(int x, int y, int width, int height) {
		setRectangle(width, height, x, y);
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

	private int listTop() {
		return getY() + HEADER + ACTIONS;
	}

	private double maxScroll() {
		return Math.max(0, rows.size() * ROW - (getBottom() - listTop()));
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		graphics.fill(getX() - 1, getY() - 1, getRight() + 1, getBottom() + 1, FRAME);
		graphics.fill(getX(), getY(), getRight(), getBottom(), FRAME_LIGHT);
		graphics.fill(getX() + 1, getY() + 1, getRight() - 1, getBottom() - 1, BG);

		// Title bar (the tab is chosen with the toolbar buttons).
		graphics.fill(getX() + 1, getY() + 1, getRight() - 1, getY() + HEADER, TITLE_BG);
		graphics.fill(getX() + 1, getY() + HEADER - 2, getRight() - 1, getY() + HEADER, ACCENT);
		Component title = Component.translatable("kiroxmap.tab." + tab.name().toLowerCase(java.util.Locale.ROOT));
		graphics.centeredText(font, title, getX() + width / 2, getY() + 5, 0xFFFFFFFF);

		// All / none.
		int ay = getY() + HEADER;
		drawAction(graphics, Component.translatable("kiroxmap.all"), getX() + 6, ay + 2, mouseX, mouseY);
		drawAction(graphics, Component.translatable("kiroxmap.none"), getX() + 12 + actionWidth("kiroxmap.all"), ay + 2, mouseX, mouseY);

		// List.
		int top = listTop();
		graphics.enableScissor(getX() + 1, top, getRight(), getBottom());
		for (int i = 0; i < rows.size(); i++) {
			int y = top + i * ROW - (int) scroll;
			if (y + ROW < top || y > getBottom()) {
				continue;
			}
			Row row = rows.get(i);
			boolean hover = mouseX >= getX() && mouseX < getRight() && mouseY >= Math.max(y, top) && mouseY < y + ROW && mouseY < getBottom();
			if (hover) {
				graphics.fill(getX() + 1, y, getRight(), y + ROW, 0x30FFFFFF);
			}
			boolean on = row.checked().getAsBoolean();
			int bx = getX() + 6, by = y + (ROW - 9) / 2;
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
		graphics.disableScissor();
	}

	private int actionWidth(String key) {
		return font.width(Component.translatable(key)) + 8;
	}

	private void drawAction(GuiGraphicsExtractor graphics, Component label, int x, int y, int mouseX, int mouseY) {
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
		double mx = event.x(), my = event.y();
		if (my < getY() + HEADER) {
			return true;
		}
		if (my < listTop()) {
			int allX = getX() + 6, noneX = getX() + 12 + actionWidth("kiroxmap.all");
			if (mx >= allX && mx < allX + actionWidth("kiroxmap.all")) {
				setAll(true);
			} else if (mx >= noneX && mx < noneX + actionWidth("kiroxmap.none")) {
				setAll(false);
			}
			return true;
		}
		int index = (int) Math.floor((my - listTop() + scroll) / ROW);
		if (index >= 0 && index < rows.size()) {
			Row row = rows.get(index);
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
