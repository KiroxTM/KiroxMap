package com.kiroxtm.neoforge.platform;

import java.nio.file.Path;

import com.kiroxtm.platform.services.IPlatformHelper;

import net.neoforged.fml.loading.FMLPaths;

public class NeoForgePlatformHelper implements IPlatformHelper {
	@Override
	public String getPlatformName() {
		return "NeoForge";
	}

	@Override
	public Path getConfigDir() {
		return FMLPaths.CONFIGDIR.get();
	}

	@Override
	public Path getGameDir() {
		return FMLPaths.GAMEDIR.get();
	}
}
