package com.kiroxtm.config;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.kiroxtm.KiroxMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Keeps a singleplayer world's waypoints inside the world's own save folder, as
 * {@code <world>/kirox-map/waypoints.json}, so they travel with the save and can be edited by hand.
 *
 * <p>When a world is opened: if that file exists it is the source of truth and replaces the list held in
 * {@code config/kirox-map.json}; if it does not exist yet, the waypoints already in the config are copied
 * into it. After that, every {@link AtlasConfig#save()} also rewrites the file. Servers (multiplayer) have
 * no local save folder, so they keep using the config file only.
 */
public final class WorldWaypoints {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type LIST = new TypeToken<List<Waypoint>>() { }.getType();
	private static final String DIRECTORY = "kirox-map";
	private static final String FILE = "waypoints.json";

	/** The world we already loaded from; a new server instance (opening a world) triggers a new load. */
	private static IntegratedServer syncedServer;
	/** True when the file could not be read: it is then left alone instead of being overwritten. */
	private static boolean readFailed;

	private WorldWaypoints() {
	}

	/** Loads (or creates) the save's waypoint file once per opened world. Cheap to call every tick. */
	public static void sync(Minecraft minecraft, AtlasConfig config) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null || server == syncedServer) {
			return;
		}
		syncedServer = server;
		readFailed = false;
		Path file = fileOf(server);
		List<Waypoint> list = config.waypointsFor(keyOf(server));
		if (Files.exists(file)) {
			try {
				List<Waypoint> loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), LIST);
				if (loaded != null) {
					list.clear();
					for (Waypoint w : loaded) {
						if (w != null) {
							w.sanitize();
							list.add(w);
						}
					}
				}
			} catch (IOException | JsonParseException e) {
				readFailed = true;
				KiroxMap.LOGGER.warn("Could not read {}, keeping the waypoints of the config file", file, e);
			}
		} else {
			write(file, list);
		}
	}

	/** Called by {@link AtlasConfig#save()}: mirrors the current world's waypoints into its save folder. */
	static void saveCurrent(AtlasConfig config) {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null || server != syncedServer || readFailed) {
			return;
		}
		write(fileOf(server), config.waypointsFor(keyOf(server)));
	}

	private static void write(Path file, List<Waypoint> list) {
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, GSON.toJson(list, LIST), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			KiroxMap.LOGGER.warn("Could not save {}", file, e);
		}
	}

	private static Path fileOf(IntegratedServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve(DIRECTORY).resolve(FILE);
	}

	/** Same key as {@code AtlasScreen.worldKeyOf} gives for a singleplayer world. */
	private static String keyOf(IntegratedServer server) {
		return "sp:" + server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
	}
}
