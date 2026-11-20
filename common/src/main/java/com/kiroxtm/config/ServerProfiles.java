package com.kiroxtm.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.kiroxtm.KiroxMap;
import com.kiroxtm.platform.Services;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;

/**
 * A small profile per multiplayer server: its seed and its waypoints, remembered between sessions.
 *
 * <p>Each server gets {@code <game dir>/kirox-map/servers/<id>.json} next to the shared
 * {@code config/kirox-map.json}. On joining, the profile is found by a <em>stable</em> identity (see
 * {@link ServerKey}: case, trailing dot and the default port do not matter; LAN worlds ignore their changing
 * port; Realms use their name), data saved under older address spellings is moved over, and the seed and
 * waypoints are restored. Every {@link AtlasConfig#save()} rewrites the profile, so there are two copies and
 * losing one (a broken config file, say) does not lose the data.
 */
public final class ServerProfiles {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String DIRECTORY = "kirox-map";
	private static final String SUBDIRECTORY = "servers";

	/** What is written to disk. Plain fields, so the file can be read and edited by hand. */
	private static final class Profile {
		int version = 1;
		String name = "";
		String address = "";
		String seed = "";
		long lastJoined;
		List<Waypoint> waypoints = new ArrayList<>();
	}

	/** The connection we already loaded for; a new connection (joining a server) triggers a new load. */
	private static ClientPacketListener syncedConnection;
	/** Key of the server we are on, or null when it cannot be identified (or we are not on a server). */
	private static String syncedKey;
	private static String serverName = "";
	private static String serverAddress = "";
	private static long joinedAt;

	private ServerProfiles() {
	}

	/** The key a multiplayer world is stored under, or {@code "none"} when the game gives no server data. */
	public static String keyOf(Minecraft minecraft) {
		ServerData data = minecraft.getCurrentServer();
		if (data == null) {
			return "none";
		}
		String key;
		if (data.isRealm()) {
			key = ServerKey.forRealm(data.name, data.ip);
		} else if (data.isLan()) {
			key = ServerKey.forLan(data.ip, data.name);
		} else {
			key = ServerKey.forAddress(data.ip);
		}
		return key.equals(ServerKey.PREFIX) ? "none" : key;
	}

	/** Loads (or creates) the profile of the server we are on, once per connection. Cheap to call every tick. */
	public static void sync(Minecraft minecraft, AtlasConfig config) {
		ClientPacketListener connection = minecraft.getConnection();
		if (minecraft.getSingleplayerServer() != null || connection == null) {
			syncedConnection = null; // not on a server any more: the next join loads again
			syncedKey = null;
			return;
		}
		if (connection == syncedConnection) {
			return;
		}
		syncedConnection = connection;
		String key = keyOf(minecraft);
		ServerData data = minecraft.getCurrentServer();
		if (data == null || "none".equals(key)) {
			syncedKey = null; // cannot tell which server this is: nothing to remember it under
			return;
		}
		syncedKey = key;
		serverName = data.name == null ? "" : data.name;
		serverAddress = data.ip == null ? "" : data.ip;
		joinedAt = System.currentTimeMillis();

		migrateLegacy(config, key);
		Profile saved = read(fileOf(key));
		if (saved != null) {
			merge(config, key, saved);
		}
		config.save(); // also writes the profile file through saveCurrent
	}

	/** Called by {@link AtlasConfig#save()}: mirrors the current server's seed and waypoints into its profile. */
	static void saveCurrent(AtlasConfig config) {
		if (syncedKey == null || Minecraft.getInstance().getConnection() != syncedConnection) {
			return;
		}
		Profile profile = new Profile();
		profile.name = serverName;
		profile.address = serverAddress;
		profile.seed = config.seeds.getOrDefault(syncedKey, "");
		profile.lastJoined = joinedAt;
		profile.waypoints = new ArrayList<>(config.waypointsFor(syncedKey));
		write(fileOf(syncedKey), profile);
	}

	/**
	 * Earlier versions keyed servers by the raw address as typed. Anything saved under a spelling of this
	 * same server (other case, trailing dot, explicit default port) moves to the stable key. Only for normal
	 * servers: a LAN or Realm entry cannot be matched to an old key reliably, so those are left alone.
	 */
	private static void migrateLegacy(AtlasConfig config, String key) {
		if (key.startsWith(ServerKey.PREFIX + "lan:") || key.startsWith(ServerKey.PREFIX + "realm:")) {
			return;
		}
		Set<String> legacyKeys = new LinkedHashSet<>(config.seeds.keySet());
		legacyKeys.addAll(config.waypoints.keySet());
		for (String legacy : legacyKeys) {
			if (legacy.equals(key) || !legacy.startsWith(ServerKey.PREFIX)
					|| legacy.startsWith(ServerKey.PREFIX + "lan:") || legacy.startsWith(ServerKey.PREFIX + "realm:")) {
				continue;
			}
			if (!ServerKey.forAddress(legacy.substring(ServerKey.PREFIX.length())).equals(key)) {
				continue;
			}
			String seed = config.seeds.remove(legacy);
			if (isBlank(config.seeds.get(key)) && !isBlank(seed)) {
				config.seeds.put(key, seed);
			}
			List<Waypoint> old = config.waypoints.remove(legacy);
			if (old != null) {
				addMissing(config.waypointsFor(key), old);
			}
			KiroxMap.LOGGER.info("Moved the saved data of server '{}' to '{}'", legacy, key);
		}
	}

	/** The profile file adds what the config lacks: the config wins for the seed, waypoints are joined. */
	private static void merge(AtlasConfig config, String key, Profile profile) {
		if (isBlank(config.seeds.get(key)) && !isBlank(profile.seed)) {
			config.seeds.put(key, profile.seed);
		}
		if (profile.waypoints != null) {
			addMissing(config.waypointsFor(key), profile.waypoints);
		}
	}

	private static void addMissing(List<Waypoint> target, List<Waypoint> extra) {
		for (Waypoint w : extra) {
			if (w == null) {
				continue;
			}
			w.sanitize();
			boolean known = false;
			for (Waypoint existing : target) {
				if (existing.x == w.x && existing.y == w.y && existing.z == w.z
						&& Objects.equals(existing.dim, w.dim) && Objects.equals(existing.name, w.name)) {
					known = true;
					break;
				}
			}
			if (!known) {
				target.add(w);
			}
		}
	}

	private static boolean isBlank(String text) {
		return text == null || text.isBlank();
	}

	private static Profile read(Path file) {
		if (!Files.exists(file)) {
			return null;
		}
		try {
			return GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Profile.class);
		} catch (IOException | JsonParseException e) {
			KiroxMap.LOGGER.warn("Could not read {}; keeping a copy of it and starting from the config", file, e);
			try {
				Files.copy(file, file.resolveSibling(file.getFileName() + ".bad-" + System.currentTimeMillis()));
			} catch (IOException copyFailure) {
				KiroxMap.LOGGER.warn("Could not keep a copy of {}", file, copyFailure);
			}
			return null;
		}
	}

	private static void write(Path file, Profile profile) {
		String json = GSON.toJson(profile, Profile.class);
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, json, StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			try { // some systems refuse the rename (a locked file): write in place instead
				Files.writeString(file, json, StandardCharsets.UTF_8);
			} catch (IOException second) {
				KiroxMap.LOGGER.warn("Could not save {}", file, second);
			}
		}
	}

	private static Path fileOf(String key) {
		return Services.PLATFORM.getGameDir().resolve(DIRECTORY).resolve(SUBDIRECTORY)
				.resolve(ServerKey.fileId(key) + ".json");
	}
}
