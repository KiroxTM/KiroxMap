package com.kiroxtm.map;

import com.kiroxtm.KiroxMap;
import com.kiroxtm.platform.Services;
import com.kiroxtm.world.Dim;
import com.kiroxtm.world.SeedWorld;
import com.kiroxtm.world.StructureKind;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import java.util.stream.Stream;

/**
 * Saves what the map computes so it is not computed again: biome ids and relief shade of tiles and the structure
 * attempts of each placement region. Everything here is a pure function of seed, dimension and
 * Minecraft version, so a saved value never goes stale because of anything the player does (waypoints
 * live in the config file and never touch this cache).
 *
 * <p>Layout: {@code <game dir>/kirox-map-cache/<seed>/meta.txt} plus one folder per dimension with
 * one file per tile ({@code t<scale>_<tx>_<tz>.z}), one per tile of relief shade ({@code r<scale>_<tx>_<tz>.z}) and per structure kind ({@code s_<kind>.z}).
 * Files are written to a temporary name and renamed, read defensively (a damaged file is deleted and
 * recomputed), and the whole cache is trimmed to {@link #MAX_BYTES}. If the format or the Minecraft
 * version in {@code meta.txt} differs from this build, that seed's cache is wiped.
 *
 * <p>It can be switched off with {@code "diskCache": false} in {@code config/kirox-map.json}.
 */
public final class DiskCache {
	/** Bump when the file format or anything that changes computed results (e.g. cubiomes) changes. */
	private static final int FORMAT = 2; // 2: relief shade files, cubiomes 2026-10-02
	private static final int TILE_MAGIC = 0x4B544C31; // "KTL1"
	private static final int STRUCT_MAGIC = 0x4B535431; // "KST1"
	private static final int RELIEF_MAGIC = 0x4B524C31; // "KRL1"
	private static final long MAX_BYTES = 512L * 1024 * 1024;
	/** Marks "no structure in this region" in a saved region list. */
	private static final int NO_STRUCTURE = Integer.MIN_VALUE;
	private static final AtomicBoolean TRIMMED = new AtomicBoolean();

	private DiskCache() {
	}

	private static Path root() {
		return Services.PLATFORM.getConfigDir().resolveSibling("kirox-map-cache");
	}

	/** The store for a seed and dimension, or null when the disk cache is off or cannot be created. */
	public static Store open(long seed, Dim dim, boolean enabled) {
		if (!enabled) {
			return null;
		}
		try {
			Path seedDir = root().resolve(Long.toString(seed));
			String meta = "kirox-map-cache format=" + FORMAT + " mc=" + SeedWorld.mcVersion();
			Path metaFile = seedDir.resolve("meta.txt");
			if (Files.exists(seedDir) && !(Files.isRegularFile(metaFile) && meta.equals(Files.readString(metaFile, StandardCharsets.UTF_8).trim()))) {
				deleteTree(seedDir); // written by another format or Minecraft version
			}
			Path dir = seedDir.resolve(dim.key());
			Files.createDirectories(dir);
			if (!Files.exists(metaFile)) {
				Files.writeString(metaFile, meta, StandardCharsets.UTF_8);
			}
			if (TRIMMED.compareAndSet(false, true)) {
				Thread thread = new Thread(DiskCache::trim, "Kirox Map cache trim");
				thread.setDaemon(true);
				thread.setPriority(Thread.MIN_PRIORITY);
				thread.start();
			}
			return new Store(dir);
		} catch (IOException | RuntimeException e) {
			KiroxMap.LOGGER.warn("Map disk cache unavailable", e);
			return null;
		}
	}

	/** Deletes everything saved for a seed (all dimensions). */
	public static void clearSeed(long seed) {
		try {
			deleteTree(root().resolve(Long.toString(seed)));
		} catch (IOException | RuntimeException e) {
			KiroxMap.LOGGER.warn("Could not clear the map disk cache", e);
		}
	}

	/** Keeps the whole cache under the size limit by deleting the oldest files first. */
	private static void trim() {
		try {
			Path root = root();
			if (!Files.isDirectory(root)) {
				return;
			}
			List<Path> files = new ArrayList<>();
			long total = 0;
			try (Stream<Path> walk = Files.walk(root)) {
				for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
					files.add(p);
					total += Files.size(p);
				}
			}
			if (total <= MAX_BYTES) {
				return;
			}
			Map<Path, Long> times = new HashMap<>();
			for (Path p : files) {
				times.put(p, Files.getLastModifiedTime(p).toMillis());
			}
			files.sort(Comparator.comparingLong(times::get));
			long target = MAX_BYTES * 8 / 10;
			for (Path p : files) {
				if (total <= target) {
					break;
				}
				if (p.getFileName().toString().equals("meta.txt")) {
					continue;
				}
				total -= Files.size(p);
				Files.deleteIfExists(p);
			}
		} catch (IOException | RuntimeException e) {
			KiroxMap.LOGGER.warn("Could not trim the map disk cache", e);
		}
	}

	private static void deleteTree(Path dir) throws IOException {
		if (!Files.exists(dir)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(dir)) {
			for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
				Files.deleteIfExists(p);
			}
		}
	}

	/** The cache of one seed and dimension. Thread-safe; every method is a no-op after {@link #invalidate()}. */
	public static final class Store {
		private final Path dir;
		private volatile boolean valid = true;

		private Store(Path dir) {
			this.dir = dir;
		}

		/** After this nothing is read or written, so work still in flight cannot recreate deleted files. */
		public void invalidate() {
			valid = false;
		}

		// ------------------------------------------------------------ tiles

		/** Biome ids of a tile (TEXELS x TEXELS), or null when not saved. */
		public int[] loadTile(TileKey key) {
			if (!valid) {
				return null;
			}
			Path file = dir.resolve("t" + key.scale() + "_" + key.tx() + "_" + key.tz() + ".z");
			if (!Files.isRegularFile(file)) {
				return null;
			}
			try (DataInputStream in = open(file)) {
				if (in.readInt() != TILE_MAGIC) {
					throw new IOException("bad magic");
				}
				int n = TileKey.TEXELS * TileKey.TEXELS;
				if (in.readInt() != n) {
					throw new IOException("bad size");
				}
				int[] ids = new int[n];
				for (int i = 0; i < n; i++) {
					ids[i] = in.readShort();
				}
				return ids;
			} catch (IOException | RuntimeException e) {
				discard(file);
				return null;
			}
		}

		public void saveTile(TileKey key, int[] ids) {
			Path file = dir.resolve("t" + key.scale() + "_" + key.tx() + "_" + key.tz() + ".z");
			write(file, out -> {
				out.writeInt(TILE_MAGIC);
				out.writeInt(ids.length);
				for (int id : ids) {
					out.writeShort(id);
				}
			});
		}

		// ------------------------------------------------------------ relief

		/** Relief shade of a tile (TEXELS x TEXELS signed bytes, see {@link Relief}), or null when not saved. */
		public byte[] loadShade(TileKey key) {
			if (!valid) {
				return null;
			}
			Path file = dir.resolve("r" + key.scale() + "_" + key.tx() + "_" + key.tz() + ".z");
			if (!Files.isRegularFile(file)) {
				return null;
			}
			try (DataInputStream in = open(file)) {
				if (in.readInt() != RELIEF_MAGIC) {
					throw new IOException("bad magic");
				}
				int n = TileKey.TEXELS * TileKey.TEXELS;
				if (in.readInt() != n) {
					throw new IOException("bad size");
				}
				byte[] shade = new byte[n];
				in.readFully(shade);
				return shade;
			} catch (IOException | RuntimeException e) {
				discard(file);
				return null;
			}
		}

		public void saveShade(TileKey key, byte[] shade) {
			Path file = dir.resolve("r" + key.scale() + "_" + key.tx() + "_" + key.tz() + ".z");
			write(file, out -> {
				out.writeInt(RELIEF_MAGIC);
				out.writeInt(shade.length);
				out.write(shade);
			});
		}

		// ------------------------------------------------------------ structures

		/** Saved placement-region results of a kind: region key -> {x, z}, or an empty array for "none". */
		public Map<Long, int[]> loadRegions(StructureKind kind, int[] none) {
			Map<Long, int[]> out = new HashMap<>();
			if (!valid) {
				return out;
			}
			Path file = dir.resolve("s_" + kind.key() + ".z");
			if (!Files.isRegularFile(file)) {
				return out;
			}
			try (DataInputStream in = open(file)) {
				if (in.readInt() != STRUCT_MAGIC) {
					throw new IOException("bad magic");
				}
				int count = in.readInt();
				if (count < 0 || count > 50_000_000) {
					throw new IOException("bad count");
				}
				for (int i = 0; i < count; i++) {
					long key = in.readLong();
					int x = in.readInt(), z = in.readInt();
					out.put(key, x == NO_STRUCTURE ? none : new int[] {x, z});
				}
				return out;
			} catch (IOException | RuntimeException e) {
				discard(file);
				return new HashMap<>();
			}
		}

		public void saveRegions(StructureKind kind, Map<Long, int[]> regions) {
			Path file = dir.resolve("s_" + kind.key() + ".z");
			write(file, out -> {
				List<Map.Entry<Long, int[]>> entries = new ArrayList<>(regions.entrySet()); // a snapshot: the map may grow meanwhile
				out.writeInt(STRUCT_MAGIC);
				out.writeInt(entries.size());
				for (Map.Entry<Long, int[]> e : entries) {
					int[] pos = e.getValue();
					out.writeLong(e.getKey());
					out.writeInt(pos.length == 2 ? pos[0] : NO_STRUCTURE);
					out.writeInt(pos.length == 2 ? pos[1] : 0);
				}
			});
		}

		/** Saved stronghold positions (x, z pairs), or null. */
		public int[] loadStrongholds(int maxLength) {
			if (!valid) {
				return null;
			}
			Path file = dir.resolve("strongholds.z");
			if (!Files.isRegularFile(file)) {
				return null;
			}
			try (DataInputStream in = open(file)) {
				if (in.readInt() != STRUCT_MAGIC) {
					throw new IOException("bad magic");
				}
				int n = in.readInt();
				if (n < 0 || n > maxLength || n % 2 != 0) {
					throw new IOException("bad length");
				}
				int[] all = new int[n];
				for (int i = 0; i < n; i++) {
					all[i] = in.readInt();
				}
				return all;
			} catch (IOException | RuntimeException e) {
				discard(file);
				return null;
			}
		}

		public void saveStrongholds(int[] all) {
			write(dir.resolve("strongholds.z"), out -> {
				out.writeInt(STRUCT_MAGIC);
				out.writeInt(all.length);
				for (int v : all) {
					out.writeInt(v);
				}
			});
		}

		// ------------------------------------------------------------ files

		private interface Writer {
			void write(DataOutputStream out) throws IOException;
		}

		private static DataInputStream open(Path file) throws IOException {
			return new DataInputStream(new BufferedInputStream(new InflaterInputStream(new BufferedInputStream(Files.newInputStream(file)))));
		}

		private void write(Path file, Writer writer) {
			if (!valid) {
				return;
			}
			Path tmp = null;
			try {
				tmp = Files.createTempFile(dir, "w", ".tmp");
				try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new DeflaterOutputStream(Files.newOutputStream(tmp))))) {
					writer.write(out);
				}
				if (!valid) {
					Files.deleteIfExists(tmp); // cleared while writing
					return;
				}
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException | RuntimeException e) {
				// Best effort: a failed save only means the data is computed again next time.
				if (tmp != null) {
					try {
						Files.deleteIfExists(tmp);
					} catch (IOException ignored) {
						// nothing more to do
					}
				}
			}
		}

		private static void discard(Path file) {
			try {
				Files.deleteIfExists(file);
			} catch (IOException ignored) {
				// nothing more to do
			}
		}
	}
}
