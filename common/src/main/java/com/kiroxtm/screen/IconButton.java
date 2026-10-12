package com.kiroxtm.screen;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * A square toolbar button: black frame, dark bevelled face and a white pixel-art glyph, in the
 * style of classic map screens. A button can be "selected" (a toggle that is on, or the current
 * dimension), which brightens its face and adds an accent bar. Hovering shows its name as a tooltip.
 */
final class IconButton extends AbstractWidget {
	static final int SIZE = 22;

	private static final int FRAME = 0xFF000000;
	private static final int FACE = 0xFF2F2F2F;
	private static final int FACE_HOVER = 0xFF3F3F3F;
	private static final int FACE_SELECTED = 0xFF383838;
	private static final int BEVEL_LIGHT = 0xFF505050;
	private static final int BEVEL_DARK = 0xFF1C1C1C;
	private static final int GLYPH = 0xFFFFFFFF;
	private static final int GLYPH_SECONDARY = 0xFF9A9A9A;
	private static final int GLYPH_DISABLED = 0xFF606060;
	static final int ACCENT = 0xFFFFD84A;

	private final Font font;
	private final PixelIcons glyph;
	private final Supplier<Component> label;
	private final BooleanSupplier selected;
	private final Runnable action;

	IconButton(Font font, PixelIcons glyph, Supplier<Component> label, BooleanSupplier selected, Runnable action) {
		super(0, 0, SIZE, SIZE, Component.empty());
		this.font = font;
		this.glyph = glyph;
		this.label = label;
		this.selected = selected;
		this.action = action;
	}

	IconButton at(int x, int y) {
		setRectangle(SIZE, SIZE, x, y);
		return this;
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		int x = getX(), y = getY();
		boolean hover = isMouseOver(mouseX, mouseY);
		boolean on = selected.getAsBoolean();

		graphics.fill(x, y, x + SIZE, y + SIZE, FRAME);
		graphics.fill(x + 1, y + 1, x + SIZE - 1, y + SIZE - 1, hover ? FACE_HOVER : on ? FACE_SELECTED : FACE);
		graphics.fill(x + 1, y + 1, x + SIZE - 1, y + 2, BEVEL_LIGHT);
		graphics.fill(x + 1, y + 1, x + 2, y + SIZE - 1, BEVEL_LIGHT);
		graphics.fill(x + 1, y + SIZE - 2, x + SIZE - 1, y + SIZE - 1, BEVEL_DARK);
		graphics.fill(x + SIZE - 2, y + 1, x + SIZE - 1, y + SIZE - 1, BEVEL_DARK);

		int gx = x + (SIZE - glyph.width()) / 2, gy = y + (SIZE - glyph.height()) / 2;
		glyph.draw(graphics, gx, gy, active ? GLYPH : GLYPH_DISABLED, active ? GLYPH_SECONDARY : GLYPH_DISABLED);
		if (on) {
			graphics.fill(x + 3, y + SIZE - 3, x + SIZE - 3, y + SIZE - 2, ACCENT);
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
