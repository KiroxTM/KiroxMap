package com.kiroxtm.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

import com.mojang.blaze3d.platform.InputConstants;

import com.kiroxtm.client.KiroxKeys;
import com.kiroxtm.config.AtlasConfig;
import com.kiroxtm.config.Waypoint;
import com.kiroxtm.map.MapView;
import com.kiroxtm.world.Dim;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The full-screen atlas (key M). The map fills the whole screen and the controls float over it in
 * small plates, like classic world-map screens: dimensions and layer toggles at the top, zoom and
 * view tools on the left, panels and help at the bottom, close on the top right.
 *
 * <p>Opening is split in two so the key press feels instant: {@link #init()} only builds widgets;
 * after the screen has been drawn a couple of times the native library is loaded on a background
 * thread, and once it is ready the world (seed) is set up and the map starts to fill in. In a
 * single-player world the seed is read from the integrated server; on a multiplayer server it is
 * typed in (and remembered per server address).
 */
public final class AtlasScreen extends Screen {
	private static final int PAD = 6;
	private static final int GAP = 2;
	private static final int PLATE_PAD = 3;
	private static final int PLATE_GAP = 6;
	private static final int FRAMES_BEFORE_LOAD = 2;
	private static final long SEED_DEBOUNCE_MS = 350;

	private final AtlasSession session = AtlasSession.get();
	private final AtlasConfig config = AtlasConfig.get();
	private String worldKey = "none";
	private EditBox seedBox;
	private MapCanvas map;
	private SidePanel panel;
	private String pendingSeedText;
	private long pendingSeedAt;
	/** init() also runs on resize and rebuildWidgets(); the world is detected once per opening. */
	private boolean opened;
	/** Whether the world has been handed to the session (it needs the warm-up to be done). */
	private boolean worldShown;
	private int framesDrawn;
	/** Widgets must not be rebuilt while a click is dispatched, so rebuilds wait for the next tick. */
	private boolean rebuildPending;

	public AtlasScreen() {
		super(Component.translatable("kiroxmap.title"));
	}

	@Override
	protected void init() {
		if (!opened) {
			opened = true;
			openWorld(); // cheap: no native code involved
		}

		final int S = IconButton.size();
		final int PLATE_H = S + PLATE_PAD * 2;
		List<int[]> plates = new ArrayList<>();
		List<AbstractWidget> overlay = new ArrayList<>();

		// --- Top, centered: dimensions | layer toggles. Close on the right.
		int topY = PAD;
		int[] groups = {Dim.values().length, 4};
		int total = (groups[0] + groups[1]) * S + (groups[0] + groups[1] - 2) * GAP
				+ 4 * PLATE_PAD + PLATE_GAP;
		int x = (width - total) / 2;
		int bx = x + PLATE_PAD;
		for (Dim dim : Dim.values()) {
			ToolIcon icon = switch (dim) {
				case OVERWORLD -> ToolIcon.OVERWORLD;
				case NETHER -> ToolIcon.NETHER;
				case END -> ToolIcon.END;
			};
			overlay.add(button(icon, () -> Component.translatable("kiroxmap.dimension." + dim.key()),
					() -> dim == session.dim, () -> switchDimension(dim)).at(bx + dim.ordinal() * (S + GAP), topY + PLATE_PAD));
		}
		int g1w = groups[0] * S + (groups[0] - 1) * GAP + PLATE_PAD * 2;
		plates.add(new int[] {x, topY, x + g1w, topY + PLATE_H});

		x += g1w + PLATE_GAP;
		int g2w = groups[1] * S + (groups[1] - 1) * GAP + PLATE_PAD * 2;
		bx = x + PLATE_PAD;
		overlay.add(button(ToolIcon.GRID, () -> Component.translatable("kiroxmap.grid"), () -> config.showGrid,
				() -> toggle(() -> config.showGrid = !config.showGrid)).at(bx, topY + PLATE_PAD));
		AbstractWidget slime = button(ToolIcon.SLIME, () -> Component.translatable("kiroxmap.slime"), () -> config.showSlimeChunks,
				() -> toggle(() -> config.showSlimeChunks = !config.showSlimeChunks)).at(bx + S + GAP, topY + PLATE_PAD);
		slime.active = session.dim == Dim.OVERWORLD; // slime chunks only exist in the Overworld
		overlay.add(slime);
		overlay.add(button(ToolIcon.LABELS, () -> Component.translatable("kiroxmap.labels"), () -> config.showLabels,
				() -> toggle(() -> config.showLabels = !config.showLabels)).at(bx + 2 * (S + GAP), topY + PLATE_PAD));
		overlay.add(button(ToolIcon.WAYPOINT_LABELS, () -> Component.translatable("kiroxmap.waypoint_labels"), () -> config.showWaypointLabels,
				() -> toggle(() -> config.showWaypointLabels = !config.showWaypointLabels)).at(bx + 3 * (S + GAP), topY + PLATE_PAD));
		plates.add(new int[] {x, topY, x + g2w, topY + PLATE_H});

		overlay.add(button(ToolIcon.CLOSE, () -> Component.translatable("kiroxmap.close"), () -> false, this::onClose)
				.at(width - PAD - S, PAD));

		// --- Top left: seed.
		int seedW = Math.clamp(width / 4, 110, 170);
		AbstractWidget seedIcon = button(ToolIcon.SEED, () -> Component.translatable("kiroxmap.seed"), () -> false,
				() -> setFocused(seedBox)).at(PAD + PLATE_PAD, topY + PLATE_PAD);
		overlay.add(seedIcon);
		seedBox = new EditBox(font, PAD + PLATE_PAD + S + GAP, topY + PLATE_PAD, seedW, S,
				seedBox, Component.translatable("kiroxmap.seed"));
		seedBox.setMaxLength(64);
		seedBox.setHint(Component.translatable("kiroxmap.seed_hint"));
		if (seedBox.getValue().isEmpty()) {
			seedBox.setValue(session.seedText);
		}
		seedBox.setResponder(text -> {
			pendingSeedText = text;
			pendingSeedAt = System.currentTimeMillis();
		});
		overlay.add(seedBox);
		plates.add(new int[] {PAD, topY, PAD + PLATE_PAD * 2 + S + GAP + seedW, topY + PLATE_H});

		// --- Left column: zoom, center, copy, reset.
		int colY = topY + PLATE_H + 14;
		ToolIcon[] icons = {ToolIcon.ZOOM_IN, ToolIcon.ZOOM_OUT, ToolIcon.CENTER, ToolIcon.COPY, ToolIcon.RESET};
		String[] keys = {"kiroxmap.zoom_in", "kiroxmap.zoom_out", "kiroxmap.center.tooltip", "kiroxmap.copy", "kiroxmap.reset"};
		Runnable[] actions = {
				() -> session.view.setZoom(session.view.zoom() - MapView.ZOOM_STEP * 2),
				() -> session.view.setZoom(session.view.zoom() + MapView.ZOOM_STEP * 2),
				this::centerOnPlayer,
				() -> map.copyCoordinates(),
				() -> {
					session.view.setZoom(MapView.DEFAULT_ZOOM);
					centerOnPlayer();
				}};
		for (int i = 0; i < icons.length; i++) {
			String key = keys[i];
			overlay.add(button(icons[i], () -> Component.translatable(key), () -> false, actions[i])
					.at(PAD + PLATE_PAD, colY + PLATE_PAD + i * (S + GAP)));
		}
		plates.add(new int[] {PAD, colY, PAD + S + PLATE_PAD * 2,
				colY + icons.length * S + (icons.length - 1) * GAP + PLATE_PAD * 2});

		// --- Bottom center: structures, biomes, help.
		int rowY = height - PAD - PLATE_H;
		int rowW = 4 * S + 3 * GAP + PLATE_PAD * 2;
		int rowX = (width - rowW) / 2;
		overlay.add(button(ToolIcon.STRUCTURES, () -> Component.translatable("kiroxmap.tab.structures"),
				() -> config.panelOpen && "STRUCTURES".equals(config.panelTab), () -> openPanel("STRUCTURES"))
				.at(rowX + PLATE_PAD, rowY + PLATE_PAD));
		overlay.add(button(ToolIcon.BIOMES, () -> Component.translatable("kiroxmap.tab.biomes"),
				() -> config.panelOpen && "BIOMES".equals(config.panelTab), () -> openPanel("BIOMES"))
				.at(rowX + PLATE_PAD + S + GAP, rowY + PLATE_PAD));
		overlay.add(button(ToolIcon.WAYPOINT, () -> Component.translatable("kiroxmap.tab.waypoints"),
				() -> config.panelOpen && "WAYPOINTS".equals(config.panelTab), () -> openPanel("WAYPOINTS"))
				.at(rowX + PLATE_PAD + 2 * (S + GAP), rowY + PLATE_PAD));
		overlay.add(button(ToolIcon.HELP, () -> Component.translatable("kiroxmap.help"), () -> config.showHelp,
				() -> toggle(() -> config.showHelp = !config.showHelp))
				.at(rowX + PLATE_PAD + 3 * (S + GAP), rowY + PLATE_PAD));
		plates.add(new int[] {rowX, rowY, rowX + rowW, rowY + PLATE_H});

		// --- Bottom right, in the very corner: UI scale (x1 / x2 / x3).
		int scaleX = width - PAD - PLATE_PAD * 2 - S;
		overlay.add(new IconButton(font, () -> "x" + config.uiScale, () -> Component.translatable("kiroxmap.ui_scale"), () -> false,
				() -> toggle(() -> config.uiScale = config.uiScale % 3 + 1)).at(scaleX + PLATE_PAD, rowY + PLATE_PAD));
		plates.add(new int[] {scaleX, rowY, scaleX + S + PLATE_PAD * 2, rowY + PLATE_H});

		// --- Help box (key list) above the UI scale button; the side panel ends above it.
		int helpW = 0;
		for (String key : MapCanvas.HELP_KEYS) {
			helpW = Math.max(helpW, font.width(Component.translatable(key)));
		}
		helpW += 14;
		int helpH = MapCanvas.HELP_KEYS.length * MapCanvas.HELP_LINE + 8;
		int helpTop = config.showHelp ? rowY - 6 - helpH : rowY;
		if (config.showHelp) {
			plates.add(new int[] {width - PAD - helpW, helpTop, width - PAD, helpTop + helpH});
		}

		// --- Floating panel on the right, under the close button.
		panel = null;
		if (config.panelOpen) {
			int pw = Math.clamp(width / 4, 150, 220);
			int py = PAD + S + 4;
			int ph = Math.max(60, helpTop - 6 - py);
			panel = new SidePanel(session, font, session::refreshPalette);
			panel.setBounds(width - PAD - pw, py, pw, ph);
			plates.add(new int[] {width - PAD - pw - 1, py - 1, width - PAD - pw + pw + 1, py + ph + 1});
			overlay.add(0, panel);
		}

		// Overlay widgets first (they get input before the map), the map under them, then the overlay
		// is drawn on top of the map.
		for (AbstractWidget w : overlay) {
			addWidget(w);
		}
		map = addRenderableWidget(new MapCanvas(session, font));
		map.setBounds(0, 0, width, height);
		map.setWaypointHandler(new MapCanvas.WaypointHandler() {
			@Override
			public void create(int x, int z) {
				Waypoint w = new Waypoint("", x, z, session.dim.key(), Waypoint.COLORS[config.waypointsFor(worldKey).size() % Waypoint.COLORS.length]);
				minecraft.gui.setScreen(new WaypointScreen(AtlasScreen.this, config.waypointsFor(worldKey), w, true));
			}

			@Override
			public void edit(Waypoint waypoint) {
				minecraft.gui.setScreen(new WaypointScreen(AtlasScreen.this, config.waypointsFor(worldKey), waypoint, false));
			}
		});
		map.setLayout(plates, topY + PLATE_H + 4, rowY - 16, config.showHelp ? helpTop : -1);
		for (AbstractWidget w : overlay) {
			addRenderableOnly(w);
		}
		setInitialFocus(map);
	}

	private IconButton button(ToolIcon icon, java.util.function.Supplier<Component> label,
			java.util.function.BooleanSupplier selected, Runnable action) {
		return new IconButton(font, icon, label, selected, action);
	}

	/** Runs a config change, saves it and rebuilds the layout on the next tick. */
	private void toggle(Runnable change) {
		change.run();
		config.save();
		rebuildPending = true;
	}

	private void openPanel(String tab) {
		boolean same = config.panelOpen && tab.equals(config.panelTab == null ? "STRUCTURES" : config.panelTab);
		config.panelOpen = !same;
		config.panelTab = tab;
		config.save();
		rebuildPending = true;
	}

	/** On open: reset the view to the player and detect (or restore) the seed. */
	private void openWorld() {
		String key = currentWorldKey();
		boolean newWorld = !key.equals(session.worldKey);
		worldKey = key;
		session.worldKey = key;

		session.dim = playerDim();
		centerOnPlayer();
		if (newWorld) {
			session.view.setZoom(MapView.DEFAULT_ZOOM);
			OptionalLong detected = singleplayerSeed();
			// Single player: the integrated server knows the real seed. Multiplayer: use what the user
			// typed for this server last time, if anything.
			String text = detected.isPresent() ? Long.toString(detected.getAsLong()) : config.seeds.getOrDefault(key, "");
			session.seedText = text;
		}
		// The world itself is created later, in tick(), once the screen is on display and the native
		// library is loaded.
	}

	private void applySeed(String text) {
		String trimmed = text.trim();
		session.seedText = text;
		OptionalLong seed = trimmed.isEmpty() ? OptionalLong.empty() : WorldOptions.parseSeed(trimmed);
		if (!trimmed.isEmpty()) {
			config.seeds.put(worldKey, text);
		}
		if (worldShown) { // before that, tick() applies session.seedText when the warm-up is done
			session.show(seed.isPresent() ? seed.getAsLong() : null, session.dim);
		}
	}

	private void switchDimension(Dim dim) {
		if (dim == session.dim) {
			return;
		}
		double scale = session.dim.scaleTo(dim);
		if (!Double.isNaN(scale)) {
			session.view.center(session.view.centerX() * scale, session.view.centerZ() * scale);
		}
		if (worldShown) {
			session.show(session.seed(), dim);
		} else {
			session.dim = dim;
		}
		rebuildPending = true; // toolbar highlight, slime toggle and panel lists depend on the dimension
	}

	private void centerOnPlayer() {
		LocalPlayer player = minecraft.player;
		if (player == null) {
			session.view.center(0, 0);
			return;
		}
		double scale = dimOf(player).scaleTo(session.dim);
		if (Double.isNaN(scale)) {
			session.view.center(0.5, 0.5); // no matching position in the End; its main island is at the origin
		} else {
			session.view.center(player.getX() * scale, player.getZ() * scale);
		}
	}

	@Override
	public void tick() {
		// Load stage: wait until the UI has actually been drawn, then warm up off-thread, then show the world.
		if (!worldShown && framesDrawn >= FRAMES_BEFORE_LOAD) {
			AtlasSession.startWarmup();
			if (AtlasSession.isWarm()) {
				worldShown = true;
				applySeed(session.seedText);
				rebuildPending = true; // the biome list can be built now
			}
		}
		if (rebuildPending) {
			rebuildPending = false;
			rebuildWidgets();
		}
		if (pendingSeedText != null && System.currentTimeMillis() - pendingSeedAt >= SEED_DEBOUNCE_MS) {
			String text = pendingSeedText;
			pendingSeedText = null;
			applySeed(text);
			config.save();
		}
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		framesDrawn++;
		graphics.fill(0, 0, width, height, 0xFF2B2B2B); // the map canvas paints over this
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		boolean typing = getFocused() instanceof EditBox;
		if (!typing && KiroxKeys.OPEN_MAP.matches(event)) {
			onClose(); // the key that opens the atlas also closes it
			return true;
		}
		if (!typing) {
			double step = 64 * session.view.blocksPerPixel();
			switch (event.key()) {
				case InputConstants.KEY_LEFT -> session.view.center(session.view.centerX() - step, session.view.centerZ());
				case InputConstants.KEY_RIGHT -> session.view.center(session.view.centerX() + step, session.view.centerZ());
				case InputConstants.KEY_UP -> session.view.center(session.view.centerX(), session.view.centerZ() - step);
				case InputConstants.KEY_DOWN -> session.view.center(session.view.centerX(), session.view.centerZ() + step);
				default -> {
					return super.keyPressed(event);
				}
			}
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void removed() {
		if (pendingSeedText != null) {
			applySeed(pendingSeedText);
			pendingSeedText = null;
		}
		config.save();
	}

	static Dim dimOf(Player player) {
		var dimension = player.level().dimension();
		if (dimension == Level.NETHER) {
			return Dim.NETHER;
		}
		if (dimension == Level.END) {
			return Dim.END;
		}
		return Dim.OVERWORLD;
	}

	private Dim playerDim() {
		LocalPlayer player = minecraft.player;
		return player == null ? Dim.OVERWORLD : dimOf(player);
	}

	/** Identifies the world so each keeps its own seed. */
	private String currentWorldKey() {
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server != null) {
			return "sp:" + server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
		}
		ServerData data = minecraft.getCurrentServer();
		return data != null ? "mp:" + data.ip : "none";
	}

	private OptionalLong singleplayerSeed() {
		IntegratedServer server = minecraft.getSingleplayerServer();
		return server != null ? OptionalLong.of(server.overworld().getSeed()) : OptionalLong.empty();
	}
}
