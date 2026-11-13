package com.kiroxtm.screen;

import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntUnaryOperator;

import com.kiroxtm.config.AtlasConfig;
import com.kiroxtm.map.DiskCache;
import com.kiroxtm.map.MapView;
import com.kiroxtm.map.StructureLayer;
import com.kiroxtm.map.TileCache;
import com.kiroxtm.world.BiomeData;
import com.kiroxtm.world.Dim;
import com.kiroxtm.world.NativeLib;
import com.kiroxtm.world.SeedWorld;
import com.kiroxtm.world.StructureIndex;

/**
 * Map state that outlives the screen: the current world (seed + dimension), its tile cache and
 * structure search, and the view. Reopening the map is instant because the tiles stay cached.
 * Render thread only.
 */
final class AtlasSession {
	private static AtlasSession instance;

	// --- Warm-up. Loading the native library and reading the biome table is the slow part of opening
	// the map, so it runs on a background thread once the screen has been drawn; the screen itself
	// never waits for it. It happens once per game session.
	private static final ExecutorService WARMUP = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Kirox Map warm-up");
		thread.setDaemon(true);
		return thread;
	});
	private static volatile boolean warm;
	private static volatile boolean warming;
	private static volatile Throwable warmError;

	final MapView view = new MapView();
	Dim dim = Dim.OVERWORLD;
	/** Which world the session was last opened in; the seed is re-detected when it changes. */
	String worldKey;
	/** What the user sees in the seed box (a number or any text Minecraft would hash). */
	String seedText = "";

	private Long seed;
	private TileCache tiles;
	private StructureLayer structures;
	/** Saved copy of the current world's tiles and structures; null when the disk cache is off. */
	private DiskCache.Store store;

	/** Whether the native library and biome data are ready, i.e. a world can be shown without blocking. */
	static boolean isWarm() {
		return warm;
	}

	/** The failure of the last warm-up attempt, or null. */
	static Throwable warmupError() {
		return warmError;
	}

	/** Whether a warm-up is still pending (not finished and not failed). */
	static boolean isLoading() {
		return !warm && warmError == null;
	}

	/** Starts the background warm-up if it has not run yet. Safe to call every frame. */
	static synchronized void startWarmup() {
		if (warm || warming) {
			return;
		}
		warming = true;
		warmError = null;
		WARMUP.execute(() -> {
			try {
				NativeLib.load();
				BiomeData.color(0); // forces the biome table to load
				warm = true;
			} catch (Throwable t) {
				warmError = t;
			} finally {
				warming = false;
			}
		});
	}

	static AtlasSession get() {
		if (instance == null) {
			instance = new AtlasSession();
		}
		return instance;
	}

	/** Switches to a seed/dimension. Everything stays cached when nothing changed; null clears the map. */
	void show(Long newSeed, Dim newDim) {
		dim = newDim;
		if (newSeed == null) {
			clear();
			return;
		}
		if (tiles != null && newSeed.equals(seed) && tiles.world().dim() == newDim) {
			return;
		}
		clear();
		seed = newSeed;
		SeedWorld world = new SeedWorld(newSeed, newDim);
		store = DiskCache.open(newSeed, newDim, AtlasConfig.get().diskCache);
		tiles = new TileCache(world, palette(), store);
		tiles.setRelief(AtlasConfig.get().showRelief);
		structures = new StructureLayer(new StructureIndex(world, store));
	}

	/** Saves pending structure results to the disk cache (call when the map is closed). */
	void flush() {
		if (structures != null) {
			structures.flush();
		}
	}

	/** Deletes the saved data of the current seed and computes the map again from scratch. */
	void regenerate() {
		Long current = seed;
		if (current == null) {
			return;
		}
		Dim currentDim = dim;
		if (store != null) {
			store.invalidate(); // work still in flight can no longer write into the deleted folder
		}
		clear();
		DiskCache.clearSeed(current);
		show(current, currentDim);
	}

	Long seed() {
		return seed;
	}

	TileCache tiles() {
		return tiles;
	}

	StructureLayer structures() {
		return structures;
	}

	/** Re-applies the biome toggles to all tiles. */
	void refreshPalette() {
		if (tiles != null) {
			tiles.setPalette(palette());
		}
	}

	/** Applies the relief switch to all tiles. */
	void applyRelief() {
		if (tiles != null) {
			tiles.setRelief(AtlasConfig.get().showRelief);
		}
	}

	private static IntUnaryOperator palette() {
		Set<Integer> hidden = Set.copyOf(AtlasConfig.get().hiddenBiomes);
		if (hidden.isEmpty()) {
			return BiomeData::color;
		}
		return id -> hidden.contains(id) ? BiomeData.dimmed(BiomeData.color(id)) : BiomeData.color(id);
	}

	private void clear() {
		flush();
		if (tiles != null) {
			tiles.close();
		}
		tiles = null;
		structures = null;
		store = null;
		seed = null;
	}
}
