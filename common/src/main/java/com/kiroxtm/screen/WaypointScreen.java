package com.kiroxtm.screen;

import java.util.List;

import com.kiroxtm.config.AtlasConfig;
import com.kiroxtm.config.Waypoint;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Small form to create or edit a waypoint: name, coordinates and color. Returns to the atlas when done. */
final class WaypointScreen extends Screen {
	private static final int W = 230;

	private final Screen parent;
	private final List<Waypoint> list;
	private final Waypoint waypoint;
	private final boolean isNew;
	private EditBox nameBox;
	private EditBox xBox;
	private EditBox zBox;
	private int color;

	WaypointScreen(Screen parent, List<Waypoint> list, Waypoint waypoint, boolean isNew) {
		super(Component.translatable(isNew ? "kiroxmap.waypoint.new" : "kiroxmap.waypoint.edit"));
		this.parent = parent;
		this.list = list;
		this.waypoint = waypoint;
		this.isNew = isNew;
		this.color = waypoint.color;
	}

	@Override
	protected void init() {
		int left = (width - W) / 2;
		int top = height / 2 - 70;

		nameBox = addRenderableWidget(new EditBox(font, left, top + 22, W, 20, nameBox, Component.translatable("kiroxmap.waypoint.name")));
		nameBox.setMaxLength(32);
		nameBox.setHint(Component.translatable("kiroxmap.waypoint.name_hint"));
		if (nameBox.getValue().isEmpty()) {
			nameBox.setValue(waypoint.name);
		}

		int half = (W - 8) / 2;
		xBox = addRenderableWidget(new EditBox(font, left, top + 60, half, 20, xBox, Component.literal("X")));
		zBox = addRenderableWidget(new EditBox(font, left + half + 8, top + 60, half, 20, zBox, Component.literal("Z")));
		for (EditBox box : new EditBox[] {xBox, zBox}) {
			box.setMaxLength(9);
		}
		if (xBox.getValue().isEmpty()) {
			xBox.setValue(Integer.toString(waypoint.x));
		}
		if (zBox.getValue().isEmpty()) {
			zBox.setValue(Integer.toString(waypoint.z));
		}

		addRenderableWidget(new Swatches(left, top + 98, W, 16));

		int by = top + 124;
		if (isNew) {
			addRenderableWidget(Button.builder(Component.translatable("kiroxmap.waypoint.save"), b -> save()).bounds(left, by, half, 20).build());
			addRenderableWidget(Button.builder(Component.translatable("kiroxmap.waypoint.cancel"), b -> onClose()).bounds(left + half + 8, by, half, 20).build());
		} else {
			int third = (W - 8) / 3;
			addRenderableWidget(Button.builder(Component.translatable("kiroxmap.waypoint.save"), b -> save()).bounds(left, by, third, 20).build());
			addRenderableWidget(Button.builder(Component.translatable("kiroxmap.waypoint.delete"), b -> delete()).bounds(left + third + 4, by, third, 20).build());
			addRenderableWidget(Button.builder(Component.translatable("kiroxmap.waypoint.cancel"), b -> onClose()).bounds(left + 2 * (third + 4), by, third, 20).build());
		}
		setInitialFocus(nameBox);
	}

	private void save() {
		waypoint.name = nameBox.getValue().trim();
		if (waypoint.name.isEmpty()) {
			waypoint.name = Component.translatable("kiroxmap.waypoint.default_name").getString();
		}
		waypoint.x = parse(xBox.getValue(), waypoint.x);
		waypoint.z = parse(zBox.getValue(), waypoint.z);
		waypoint.color = color;
		if (isNew) {
			list.add(waypoint);
		}
		AtlasConfig.get().save();
		onClose();
	}

	private void delete() {
		list.remove(waypoint);
		AtlasConfig.get().save();
		onClose();
	}

	private static int parse(String text, int fallback) {
		try {
			return Integer.parseInt(text.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		// Keep the map on display behind the form (the cursor is placed off-screen so nothing in it reacts),
		// so opening and closing this form does not look like the map screen closing.
		parent.extractRenderState(graphics, -1000, -1000, a);
		graphics.fill(0, 0, width, height, 0x90101010);
		int left = (width - W) / 2, top = height / 2 - 70;
		graphics.fill(left - 12, top - 22, left + W + 12, top + 160, 0xFF000000);
		graphics.fill(left - 11, top - 21, left + W + 11, top + 159, 0xFF2B2B2B);
		graphics.centeredText(font, title, width / 2, top - 12, 0xFFFFFFFF);
		graphics.text(font, Component.translatable("kiroxmap.waypoint.name"), left, top + 11, 0xFFB0B0B0);
		graphics.text(font, "X", left, top + 49, 0xFFB0B0B0);
		graphics.text(font, "Z", left + (W - 8) / 2 + 8, top + 49, 0xFFB0B0B0);
		graphics.text(font, Component.translatable("kiroxmap.waypoint.color"), left, top + 87, 0xFFB0B0B0);
	}

	/** A row of color squares; the chosen one is outlined in white. */
	private final class Swatches extends AbstractWidget {
		private static final int SIZE = 16;

		Swatches(int x, int y, int width, int height) {
			super(x, y, width, height, Component.translatable("kiroxmap.waypoint.color"));
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
			for (int i = 0; i < Waypoint.COLORS.length; i++) {
				int x = getX() + i * (SIZE + 10), y = getY();
				boolean chosen = Waypoint.COLORS[i] == color;
				graphics.fill(x - 1, y - 1, x + SIZE + 1, y + SIZE + 1, chosen ? 0xFFFFFFFF : 0xFF000000);
				graphics.fill(x, y, x + SIZE, y + SIZE, Waypoint.COLORS[i]);
			}
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
			if (!isMouseOver(event.x(), event.y())) {
				return false;
			}
			int i = (int) ((event.x() - getX()) / (SIZE + 10));
			double within = (event.x() - getX()) - i * (SIZE + 10);
			if (i >= 0 && i < Waypoint.COLORS.length && within <= SIZE) {
				color = Waypoint.COLORS[i];
			}
			return true;
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}
	}
}
