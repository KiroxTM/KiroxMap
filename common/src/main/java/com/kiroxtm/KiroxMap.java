package com.kiroxtm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kiroxtm.platform.Services;

import net.minecraft.resources.Identifier;

/** Constants and tiny helpers shared by every loader. */
public final class KiroxMap {
	/** Same id in fabric.mod.json and neoforge.mods.toml (NeoForge forbids '-', so it is kirox_map). */
	public static final String MOD_ID = "kirox_map";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private KiroxMap() {
	}

	/** Called once by each loader entry point. */
	public static void init() {
		LOGGER.info("Kirox Map loaded on {}", Services.PLATFORM.getPlatformName());
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
