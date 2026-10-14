package com.kiroxtm.map;

import com.kiroxtm.KiroxMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;

import com.mojang.blaze3d.platform.NativeImage;

import com.kiroxtm.world.SeedWorld;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;

/**
 * Biome tiles of one {@link SeedWorld}. Generation is split cleanly from drawing:
 *
 * <ul>
 *   <li>worker threads compute biome ids with cubiomes and paint them into a {@link NativeImage};</li>
 *   <li>the render thread only uploads finished images as textures and draws quads.</li>
 * </ul>
 *
 * With relief on, each texel is also darkened or lightened by a hillshade of the approximate terrain
 * height ({@link Relief}); the shade is computed once per tile and cached next to the biome ids.
 *
 * A tile is {@value TileKey#TEXELS}x{@value TileKey#TEXELS} texels at one of four levels of detail
 * (4, 16, 64 or 256 blocks per texel). Only tiles in view are ever requested. A tile that is not
 * ready yet is drawn from its nearest ready coarser ancestor, and the coarsest level is always
 * requested for the whole view first, so the map never shows holes and detail only improves.
 * Nothing in here touches the real level: tiles are a pure function of seed and dimension.
 */
public final class TileCache implements AutoCloseable {
	private static final int[] SCALES = {4, 16, 64, 256};
	private static final int BASE_SCALE = 256;
	private static final int MAX_TEXTURES = 768;
	private static final int UPLOADS_PER_FRAME = 16;
	private static final int PLACEHOLDER = 0xFF15171B;

	private static final AtomicInteger THREAD_ID = new AtomicInteger();
	private static final ExecutorService WORKERS = createWorkers();

	private final SeedWorld world;
	private final Map<TileKey, Tile> tiles = new ConcurrentHashMap<>();
	private final Set<TileKey> pending = ConcurrentHashMap.newKeySet();
	private final ConcurrentLinkedQueue<Finished> finished = new ConcurrentLinkedQueue<>();
	private volatile Set<TileKey> wanted = Set.of();
	private volatile IntUnaryOperator palette;
	private volatile int paletteVersion;
	private volatile boolean closed;
	/** Whether terrain relief is drawn on top of the biome colors. */
	private volatile boolean relief = true;
	private long frame;

	/** Optional on-disk copy of computed tiles; null when the disk cache is off. */
	private final DiskCache.Store store;

	public TileCache(SeedWorld world, IntUnaryOperator palette) {
		this(world, palette, null);
	}

	public TileCache(SeedWorld world, IntUnaryOperator palette, DiskCache.Store store) {
		this.world = world;
		this.palette = palette;
		this.store = store;
	}

	public SeedWorld world() {
		return world;
	}

	/** Changes how biome ids map to colors; cached tiles are recolored in the background. */
	public void setPalette(IntUnaryOperator palette) {
		this.palette = palette;
		paletteVersion++;
	}

	/** Turns the terrain relief on or off; cached tiles are repainted in the background. */
	public void setRelief(boolean on) {
		if (relief != on) {
			relief = on;
			paletteVersion++;
		}
	}

	/** Render thread only. Draws the view into the GUI rectangle and schedules missing tiles. */
	public void draw(GuiGraphicsExtractor graphics, int x, int y, int width, int height, MapView view, int guiScale) {
		frame++;
		uploadFinished();

		double bpp = view.blocksPerPixel();
		int scale = scaleFor(bpp / guiScale);
		double left = view.centerX() - width / 2.0 * bpp;
		double top = view.centerZ() - height / 2.0 * bpp;
		double right = left + width * bpp;
		double bottom = top + height * bpp;

		List<TileKey> visible = tilesIn(scale, left, top, right, bottom);
		Set<TileKey> nowWanted = new HashSet<>(visible);
		nowWanted.addAll(tilesIn(BASE_SCALE, left, top, right, bottom));
		wanted = nowWanted;
		for (TileKey key : nowWanted) {
			request(key, key.distanceSq(view.centerX(), view.centerZ()) + (key.scale() == BASE_SCALE ? -1e9 : 0));
		}

		graphics.enableScissor(x, y, x + width, y + height);
		for (TileKey key : visible) {
			drawTile(graphics, key, x + width / 2.0, y + height / 2.0, view, bpp);
		}
		graphics.disableScissor();
		evict();
	}

	private void drawTile(GuiGraphicsExtractor graphics, TileKey key, double originX, double originY, MapView view, double bpp) {
		int sx0 = screen(originX, key.minX(), view.centerX(), bpp), sx1 = screen(originX, key.maxX(), view.centerX(), bpp);
		int sy0 = screen(originY, key.minZ(), view.centerZ(), bpp), sy1 = screen(originY, key.maxZ(), view.centerZ(), bpp);
		if (sx0 >= sx1 || sy0 >= sy1) {
			return;
		}

		Tile tile = tiles.get(key);
		if (tile != null && tile.texture != null) {
			tile.lastUsed = frame;
			blit(graphics, tile, sx0, sy0, sx1, sy1, 0, 0, 1, 1);
			return;
		}

		// Not ready: show the matching part of the nearest ready coarser tile.
		for (int coarser : SCALES) {
			if (coarser <= key.scale()) {
				continue;
			}
			TileKey ancestor = TileKey.containing(coarser, key.minX(), key.minZ());
			Tile fallback = tiles.get(ancestor);
			if (fallback != null && fallback.texture != null) {
				fallback.lastUsed = frame;
				double span = ancestor.blocks();
				float u0 = (float) ((key.minX() - ancestor.minX()) / span), u1 = (float) ((key.maxX() - ancestor.minX()) / span);
				float v0 = (float) ((key.minZ() - ancestor.minZ()) / span), v1 = (float) ((key.maxZ() - ancestor.minZ()) / span);
				blit(graphics, fallback, sx0, sy0, sx1, sy1, u0, v0, u1, v1);
				return;
			}
		}
		graphics.fill(sx0, sy0, sx1, sy1, PLACEHOLDER);
	}

	private static void blit(GuiGraphicsExtractor graphics, Tile tile, int x0, int y0, int x1, int y1, float u0, float v0, float u1, float v1) {
		graphics.blit(tile.texture.getTextureView(), tile.texture.getSampler(), x0, y0, x1, y1, u0, u1, v0, v1);
	}

	/** GUI coordinate of a world coordinate; shared tile edges round identically, so there are no seams. */
	private static int screen(double origin, long world, double center, double bpp) {
		return (int) Math.floor(origin + (world - center) / bpp + 0.5);
	}

	/** Biome id at a block from the finest ready tile, or -1 when none covers it yet. */
	public int cachedBiomeAt(int blockX, int blockZ) {
		for (int scale : SCALES) {
			Tile tile = tiles.get(TileKey.containing(scale, blockX, blockZ));
			if (tile != null && tile.ids != null) {
				TileKey key = tile.key;
				int tx = (int) ((blockX - key.minX()) / scale), tz = (int) ((blockZ - key.minZ()) / scale);
				return tile.ids[tz * TileKey.TEXELS + tx];
			}
		}
		return -1;
	}

	public int readyCount() {
		return (int) tiles.values().stream().filter(t -> t.texture != null).count();
	}

	public int pendingCount() {
		return pending.size();
	}

	/** The level of detail for a zoom: the coarsest whose texels are not larger than a screen pixel. */
	static int scaleFor(double blocksPerScreenPixel) {
		int best = SCALES[0];
		for (int scale : SCALES) {
			if (scale <= blocksPerScreenPixel) {
				best = scale;
			}
		}
		return best;
	}

	private static List<TileKey> tilesIn(int scale, double left, double top, double right, double bottom) {
		long blocks = (long) TileKey.TEXELS * scale;
		long tx0 = Math.floorDiv((long) Math.floor(left), blocks), tx1 = Math.floorDiv((long) Math.ceil(right) - 1, blocks);
		long tz0 = Math.floorDiv((long) Math.floor(top), blocks), tz1 = Math.floorDiv((long) Math.ceil(bottom) - 1, blocks);
		List<TileKey> keys = new ArrayList<>();
		for (long tz = tz0; tz <= tz1; tz++) {
			for (long tx = tx0; tx <= tx1; tx++) {
				keys.add(new TileKey(scale, (int) tx, (int) tz));
			}
		}
		return keys;
	}

	private void request(TileKey key, double priority) {
		Tile tile = tiles.get(key);
		boolean upToDate = tile != null && tile.paletteVersion == paletteVersion;
		if (upToDate || !pending.add(key)) {
			return;
		}
		int[] ids = tile != null ? tile.ids : null;
		byte[] shade = tile != null ? tile.shade : null;
		WORKERS.execute(new Job(this, key, ids, shade, priority));
	}

	/** Runs on a worker thread. */
	private void generate(TileKey key, int[] knownIds, byte[] knownShade) {
		try {
			if (closed || !wanted.contains(key)) {
				pending.remove(key); // scrolled out of view before its turn; it is requested again if needed
				return;
			}
			int version = paletteVersion;
			IntUnaryOperator colors = palette;
			int[] ids = knownIds;
			if (ids == null && store != null) {
				ids = store.loadTile(key); // computed in an earlier session
			}
			if (ids == null) {
				ids = world.biomes(key.scale(), key.tx() * TileKey.TEXELS, key.tz() * TileKey.TEXELS, TileKey.TEXELS, TileKey.TEXELS);
				if (store != null) {
					store.saveTile(key, ids);
				}
			}
			byte[] shade = knownShade;
			boolean drawRelief = relief && world.hasRelief(key.scale());
			if (drawRelief && shade == null) {
				shade = loadOrComputeShade(key);
			}
			byte[] applied = drawRelief ? shade : null; // null: draw the plain biome colors
			NativeImage image = new NativeImage(TileKey.TEXELS, TileKey.TEXELS, false);
			for (int tz = 0; tz < TileKey.TEXELS; tz++) {
				for (int tx = 0; tx < TileKey.TEXELS; tx++) {
					int i = tz * TileKey.TEXELS + tx;
					int color = colors.applyAsInt(ids[i]);
					image.setPixel(tx, tz, applied != null ? Relief.apply(color, applied[i]) : color);
				}
			}
			finished.add(new Finished(key, ids, shade, image, version));
			if (closed) {
				releaseFinished(); // closed while this tile was being made: nobody will upload it
			}
		} catch (RuntimeException e) {
			KiroxMap.LOGGER.warn("Failed to generate map tile {}", key, e);
			pending.remove(key);
		}
	}

	/** Relief shade of a tile from the disk cache or computed from heights; null if that fails (the tile is then drawn flat). */
	private byte[] loadOrComputeShade(TileKey key) {
		try {
			byte[] shade = store != null ? store.loadShade(key) : null;
			if (shade == null) {
				int texels = TileKey.TEXELS;
				// One extra texel on every side, so tile edges are shaded exactly like their neighbours'.
				float[] heights = world.heights(key.scale(), key.tx() * texels - 1, key.tz() * texels - 1, texels + 2, texels + 2);
				if (heights == null) {
					return null;
				}
				shade = Relief.shade(heights, texels, key.scale());
				if (store != null) {
					store.saveShade(key, shade);
				}
			}
			return shade;
		} catch (RuntimeException e) {
			KiroxMap.LOGGER.warn("Failed to compute terrain relief for map tile {}", key, e);
			return null;
		}
	}

	private void uploadFinished() {
		for (int i = 0; i < UPLOADS_PER_FRAME; i++) {
			Finished done = finished.poll();
			if (done == null) {
				return;
			}
			pending.remove(done.key);
			if (closed) {
				done.image.close();
				continue;
			}
			Tile tile = tiles.computeIfAbsent(done.key, Tile::new);
			if (tile.texture != null) {
				tile.texture.close();
			}
			tile.texture = new DynamicTexture(() -> "kirox-map tile " + done.key, done.image);
			tile.ids = done.ids;
			tile.shade = done.shade;
			tile.paletteVersion = done.paletteVersion;
			tile.lastUsed = frame;
		}
	}

	private void evict() {
		if (tiles.size() <= MAX_TEXTURES) {
			return;
		}
		List<Tile> candidates = new ArrayList<>(tiles.values());
		candidates.removeIf(t -> t.lastUsed >= frame);
		candidates.sort(Comparator.comparingLong(t -> t.lastUsed));
		for (int i = 0; i < candidates.size() && tiles.size() > MAX_TEXTURES * 3 / 4; i++) {
			Tile tile = candidates.get(i);
			tiles.remove(tile.key);
			if (tile.texture != null) {
				tile.texture.close();
			}
		}
	}

	/** Render thread only. Releases every texture. */
	@Override
	public void close() {
		closed = true;
		wanted = Set.of();
		for (Tile tile : tiles.values()) {
			if (tile.texture != null) {
				tile.texture.close();
			}
		}
		tiles.clear();
		releaseFinished();
	}

	private void releaseFinished() {
		Finished done;
		while ((done = finished.poll()) != null) {
			done.image.close();
		}
	}

	private static ExecutorService createWorkers() {
		int threads = Math.clamp(Runtime.getRuntime().availableProcessors() - 2, 1, 6);
		return new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, new PriorityBlockingQueue<>(), runnable -> {
			Thread thread = new Thread(runnable, "Kirox Map tile worker " + THREAD_ID.incrementAndGet());
			thread.setDaemon(true);
			thread.setPriority(Thread.NORM_PRIORITY - 1);
			return thread;
		});
	}

	private static final class Tile {
		final TileKey key;
		volatile int[] ids;
		volatile byte[] shade;
		DynamicTexture texture;
		int paletteVersion = -1;
		long lastUsed;

		Tile(TileKey key) {
			this.key = key;
		}
	}

	private record Finished(TileKey key, int[] ids, byte[] shade, NativeImage image, int paletteVersion) {
	}

	/** Queue entries are ordered by priority: lowest first (coarse tiles, then nearest the view center). */
	private record Job(TileCache owner, TileKey key, int[] ids, byte[] shade, double priority) implements Runnable, Comparable<Job> {
		@Override
		public void run() {
			owner.generate(key, ids, shade);
		}

		@Override
		public int compareTo(Job other) {
			return Double.compare(priority, other.priority);
		}
	}
}
