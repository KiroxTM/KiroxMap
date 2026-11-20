package com.kiroxtm.config;

import com.kiroxtm.KiroxMap;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import com.kiroxtm.world.StructureKind;
import com.kiroxtm.platform.Services;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Persistent client settings, stored as {@code config/kirox-map.json}. */
public final class AtlasConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = Services.PLATFORM.getConfigDir().resolve("kirox-map.json");
	private static AtlasConfig instance;
	/** True after the failure message was shown, so it is not repeated on every save. */
	private static boolean saveFailureShown;

	public boolean showGrid = true;
	public boolean showSlimeChunks = false;
	public boolean showLabels = false;
	/** Show every waypoint's name on the map (not only the one under the cursor). */
	public boolean showWaypointLabels = false;
	/** Name + distance tags over the visible waypoints in the 3D world (HUD). On by default. */
	public boolean showWaypointTags = true;
	/** Terrain relief (hillshade) over the biome colors, where cubiomes can provide heights. */
	public boolean showRelief = true;
	/** Keep computed tiles and structure positions on disk between sessions ({@code kirox-map-cache/}). */
	public boolean diskCache = true;
	/** Toolbar scale: 1, 2 or 3. 3 is the original size. */
	public int uiScale = 3;
	public boolean panelOpen = true;
	/** The key-bindings list in the bottom-right corner. */
	public boolean showHelp = false;
	/** "STRUCTURES" or "BIOMES". */
	public String panelTab = "STRUCTURES";

	/** Structures drawn on the map, by {@link StructureKind#key()}. */
	public Set<String> structures = new LinkedHashSet<>(defaultStructures());
	/** Biome ids the user switched off; they are drawn dark instead of in color. */
	public Set<Integer> hiddenBiomes = new LinkedHashSet<>();
	/** Seed text per world ("sp:<save folder>" or "mp:<server address>"). */
	public Map<String, String> seeds = new LinkedHashMap<>();

	/** Waypoints per world, same keys as {@link #seeds}. */
	public Map<String, List<Waypoint>> waypoints = new LinkedHashMap<>();

	/** The (mutable) waypoint list of a world; created on first use. */
	public List<Waypoint> waypointsFor(String worldKey) {
		return waypoints.computeIfAbsent(worldKey == null ? "none" : worldKey, k -> new ArrayList<>());
	}

	public static AtlasConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	public boolean isEnabled(StructureKind kind) {
		return structures.contains(kind.key());
	}

	public void setEnabled(StructureKind kind, boolean enabled) {
		if (enabled) {
			structures.add(kind.key());
		} else {
			structures.remove(kind.key());
		}
	}

	private static Set<String> defaultStructures() {
		Set<String> keys = new LinkedHashSet<>();
		for (StructureKind kind : new StructureKind[] {
			StructureKind.VILLAGE, StructureKind.STRONGHOLD, StructureKind.PILLAGER_OUTPOST,
			StructureKind.OCEAN_MONUMENT, StructureKind.WOODLAND_MANSION, StructureKind.RUINED_PORTAL,
			StructureKind.ANCIENT_CITY, StructureKind.TRIAL_CHAMBERS, StructureKind.NETHER_FORTRESS,
			StructureKind.BASTION_REMNANT, StructureKind.END_CITY, StructureKind.END_CITY_SHIP, StructureKind.END_GATEWAY}) {
			keys.add(kind.key());
		}
		return keys;
	}

	private static AtlasConfig load() {
		if (Files.exists(FILE)) {
			try {
				AtlasConfig loaded = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), AtlasConfig.class);
				if (loaded != null) {
					return loaded.sanitized();
				}
			} catch (IOException | JsonParseException e) {
				KiroxMap.LOGGER.warn("Could not read {}, using defaults", FILE, e);
				keepCopyOfUnreadable();
			}
		}
		return new AtlasConfig();
	}

	/** Gson leaves fields missing from an older file as null. */
	private AtlasConfig sanitized() {
		if (structures == null) structures = new LinkedHashSet<>(defaultStructures());
		if (hiddenBiomes == null) hiddenBiomes = new LinkedHashSet<>();
		if (seeds == null) seeds = new LinkedHashMap<>();
		if (waypoints == null) waypoints = new LinkedHashMap<>();
		if (panelTab == null) panelTab = "STRUCTURES";
		uiScale = Math.clamp(uiScale, 1, 3);
		return this;
	}

	/** The defaults we fall back to will overwrite this file on the next save, so keep what was in it. */
	private static void keepCopyOfUnreadable() {
		try {
			Files.copy(FILE, FILE.resolveSibling(FILE.getFileName() + ".bad-" + System.currentTimeMillis()));
		} catch (IOException e) {
			KiroxMap.LOGGER.warn("Could not keep a copy of {}", FILE, e);
		}
	}

	public void save() {
		String json = GSON.toJson(this);
		try {
			Files.createDirectories(FILE.getParent());
			Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
			Files.writeString(tmp, json, StandardCharsets.UTF_8);
			Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
			saveFailureShown = false;
		} catch (IOException e) {
			KiroxMap.LOGGER.warn("Could not save {} (the rename failed), writing it in place", FILE, e);
			try { // a locked file (antivirus, cloud sync) can refuse the rename but still accept a write
				Files.writeString(FILE, json, StandardCharsets.UTF_8);
				saveFailureShown = false;
			} catch (IOException second) {
				KiroxMap.LOGGER.warn("Could not save {}", FILE, second);
				tellSaveFailed();
			}
		}
		try {
			WorldWaypoints.saveCurrent(this); // singleplayer: also keep the waypoints in the world's save folder
		} catch (RuntimeException e) {
			KiroxMap.LOGGER.warn("Could not save the world waypoints", e);
		}
		try {
			ServerProfiles.saveCurrent(this); // multiplayer: also keep the seed and waypoints in the server's profile
		} catch (RuntimeException e) {
			KiroxMap.LOGGER.warn("Could not save the server profile", e);
		}
	}

	/** Saving used to fail silently; say it once in the chat so the player knows the data is not being kept. */
	private static void tellSaveFailed() {
		if (saveFailureShown) {
			return;
		}
		saveFailureShown = true;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null) {
			minecraft.player.sendSystemMessage(Component.literal(
					"[Kirox Map] Could not save kirox-map.json, your waypoints and seeds may not be kept. "
							+ "Check that the config folder is writable and not locked (antivirus / cloud sync); details are in the game log."));
		}
	}
}
