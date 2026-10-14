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

/** Persistent client settings, stored as {@code config/kirox-map.json}. */
public final class AtlasConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = Services.PLATFORM.getConfigDir().resolve("kirox-map.json");
	private static AtlasConfig instance;

	public boolean showGrid = true;
	public boolean showSlimeChunks = false;
	public boolean showLabels = false;
	/** Show every waypoint's name on the map (not only the one under the cursor). */
	public boolean showWaypointLabels = false;
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

	public void save() {
		try {
			Files.createDirectories(FILE.getParent());
			Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
			Files.writeString(tmp, GSON.toJson(this), StandardCharsets.UTF_8);
			Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			KiroxMap.LOGGER.warn("Could not save {}", FILE, e);
		}
	}
}
