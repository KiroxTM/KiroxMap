package com.kiroxtm.screen;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import com.mojang.blaze3d.platform.InputConstants;

import com.kiroxtm.config.AtlasConfig;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * A square toolbar button: black frame, dark bevelled face and a white pixel-art glyph (or a short
 * text), in the style of classic map screens. A button can be "selected" (a toggle that is on, or
 * the current dimension), which brightens its face and adds an accent bar. Hovering shows its name
 * as a tooltip. The size follows the UI scale setting (x1, x2, x3; x3 is the original 22 px).
 */
final class IconButton extends AbstractWidget {
	/** Button size at UI scale x3. */
	static final int BASE_SIZE = 22;
	/** Icon size at UI scale x3: 75% of the original 16 px glyph (the icon, not the button, is 25% smaller). */
	static final int ICON_BASE_SIZE = 12;

	private static final int FRAME = 0xFF000000;
	private static final int FACE = 0xFF2F2F2F;
	private static final int FACE_HOVER = 0xFF3F3F3F;
	private static final int FACE_SELECTED = 0xFF383838;
	private static final int BEVEL_LIGHT = 0xFF505050;
	private static final int BEVEL_DARK = 0xFF1C1C1C;
	private static final int GLYPH = 0xFFFFFFFF;
	private static final int GLYPH_DISABLED = 0xFF606060;
	static final int ACCENT = 0xFFFFD84A;

	private final Font font;
	private final ToolIcon glyph;
	private final Supplier<String> text;
	private final Supplier<Component> label;
	private final BooleanSupplier selected;
	private final Runnable action;
	private final int size = size();

	/** Scale factor of the toolbar for the current setting: x1 = 0.6, x2 = 0.8, x3 = 1. */
	static double factor() {
		return switch (AtlasConfig.get().uiScale) {
			case 1 -> 0.6;
			case 2 -> 0.8;
			default -> 1.0;
		};
	}

	/** Current button size in GUI pixels. */
	static int size() {
		return (int) Math.round(BASE_SIZE * factor());
	}

	IconButton(Font font, ToolIcon glyph, Supplier<Component> label, BooleanSupplier selected, Runnable action) {
		this(font, glyph, null, label, selected, action);
	}

	/** A button that shows a short text (like "x2") instead of a glyph. */
	IconButton(Font font, Supplier<String> text, Supplier<Component> label, BooleanSupplier selected, Runnable action) {
		this(font, null, text, label, selected, action);
	}

	private IconButton(Font font, ToolIcon glyph, Supplier<String> text, Supplier<Component> label, BooleanSupplier selected, Runnable action) {
		super(0, 0, size(), size(), Component.empty());
		this.font = font;
		this.glyph = glyph;
		this.text = text;
		this.label = label;
		this.selected = selected;
		this.action = action;
	}

	IconButton at(int x, int y) {
		setRectangle(size, size, x, y);
		return this;
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		int x = getX(), y = getY();
		boolean hover = isMouseOver(mouseX, mouseY);
		boolean on = selected.getAsBoolean();

		graphics.fill(x, y, x + size, y + size, FRAME);
		graphics.fill(x + 1, y + 1, x + size - 1, y + size - 1, hover ? FACE_HOVER : on ? FACE_SELECTED : FACE);
		graphics.fill(x + 1, y + 1, x + size - 1, y + 2, BEVEL_LIGHT);
		graphics.fill(x + 1, y + 1, x + 2, y + size - 1, BEVEL_LIGHT);
		graphics.fill(x + 1, y + size - 2, x + size - 1, y + size - 1, BEVEL_DARK);
		graphics.fill(x + size - 2, y + 1, x + size - 1, y + size - 1, BEVEL_DARK);

		if (glyph != null) {
			int icon = Math.max(6, (int) Math.round(ICON_BASE_SIZE * factor()));
			glyph.draw(graphics, x + (size - icon) / 2, y + (size - icon) / 2, icon, active ? GLYPH : GLYPH_DISABLED);
		} else if (text != null) {
			String t = text.get();
			graphics.text(font, t, x + (size - font.width(t)) / 2, y + (size - 9) / 2 + 1, active ? GLYPH : GLYPH_DISABLED);
		}
		if (on) {
			graphics.fill(x + 3, y + size - 3, x + size - 3, y + size - 2, ACCENT);
		}
		if (hover && active) {
			// Drawn after everything else by the GUI, so neighbouring buttons never cover it.
			graphics.setTooltipForNextFrame(font, label.get(), mouseX, mouseY);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (active && event.button() == InputConstants.MOUSE_BUTTON_LEFT && isMouseOver(event.x(), event.y())) {
			action.run();
			return true;
		}
		return false;
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}
}
