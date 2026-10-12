package com.kiroxtm.screen;

import java.util.function.BooleanSupplier;

import com.kiroxtm.client.KiroxKeys;
import com.kiroxtm.config.AtlasConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The mod's settings page (the Config button of the mod list): the key that opens the map and the
 * default look of the map screen. Built from vanilla widgets only, so it works from the main menu.
 */
public final class KiroxConfigScreen extends Screen {
	private static final int COL = 156;
	private static final int GAP = 6;

	private final Screen parent;
	private final AtlasConfig config = AtlasConfig.get();

	public KiroxConfigScreen(Screen parent) {
		super(Component.translatable("kiroxmap.config.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int left = width / 2 - COL - GAP / 2;
		int top = height / 2 - 40;
		toggle(left, top, "kiroxmap.grid", () -> config.showGrid, () -> config.showGrid = !config.showGrid);
		toggle(left + COL + GAP, top, "kiroxmap.slime", () -> config.showSlimeChunks, () -> config.showSlimeChunks = !config.showSlimeChunks);
		toggle(left, top + 24, "kiroxmap.labels", () -> config.showLabels, () -> config.showLabels = !config.showLabels);
		toggle(left + COL + GAP, top + 24, "kiroxmap.help", () -> config.showHelp, () -> config.showHelp = !config.showHelp);
		toggle(left, top + 48, "kiroxmap.config.panel", () -> config.panelOpen, () -> config.panelOpen = !config.panelOpen);
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.bounds(width / 2 - 75, top + 84, 150, 20).build());
	}

	private void toggle(int x, int y, String key, BooleanSupplier value, Runnable flip) {
		java.util.function.Supplier<Component> label = () -> Component.translatable(key).append(": ")
				.append(Component.translatable(value.getAsBoolean() ? "options.on" : "options.off"));
		addRenderableWidget(Button.builder(label.get(), b -> {
			flip.run();
			config.save();
			b.setMessage(label.get());
		}).bounds(x, y, COL, 20).build());
	}

	@Override
	public void onClose() {
		config.save();
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractBackground(graphics, mouseX, mouseY, a);
		int top = height / 2 - 40;
		graphics.centeredText(font, title, width / 2, top - 52, 0xFFFFFFFF);
		Component key = Component.translatable("kiroxmap.config.key", KiroxKeys.OPEN_MAP.getTranslatedKeyMessage());
		graphics.centeredText(font, key, width / 2, top - 30, 0xFFFFE040);
		graphics.centeredText(font, Component.translatable("kiroxmap.config.key_hint"), width / 2, top - 18, 0xFFA0A0A0);
	}
}
