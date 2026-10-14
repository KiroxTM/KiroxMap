package com.kiroxtm;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kiroxtm.platform.Services;

import net.minecraft.resources.Identifier;

/** Constants and tiny helpers shared by every loader. */
public final class KiroxMap {
	/** Same id in fabric.mod.json and neoforge.mods.toml (NeoForge forbids '-', so it is kirox_map). */
	public static final String MOD_ID = "kirox_map";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static String version;

	private KiroxMap() {
	}

	/**
	 * The mod version, read from {@code assets/kirox_map/mod_info.properties} (Gradle fills in the
	 * project version at build time); "dev" when running from a source tree where it was not filled in.
	 */
	public static String version() {
		if (version == null) {
			String found = "dev";
			try (InputStream in = KiroxMap.class.getResourceAsStream("/assets/" + MOD_ID + "/mod_info.properties")) {
				if (in != null) {
					Properties props = new Properties();
					props.load(in);
					String v = props.getProperty("version", "").trim();
					if (!v.isEmpty() && !v.contains("$")) {
						found = v;
					}
				}
			} catch (IOException | RuntimeException e) {
				// keep "dev"
			}
			version = found;
		}
		return version;
	}

	/** Called once by each loader entry point. */
	public static void init() {
		LOGGER.info("Kirox Map loaded on {}", Services.PLATFORM.getPlatformName());
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
