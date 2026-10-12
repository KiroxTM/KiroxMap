package com.kiroxtm.fabric.platform;

import java.nio.file.Path;

import com.kiroxtm.platform.services.IPlatformHelper;

import net.fabricmc.loader.api.FabricLoader;

public class FabricPlatformHelper implements IPlatformHelper {
	@Override
	public String getPlatformName() {
		return "Fabric";
	}

	@Override
	public Path getConfigDir() {
		return FabricLoader.getInstance().getConfigDir();
	}

	@Override
	public Path getGameDir() {
		return FabricLoader.getInstance().getGameDir();
	}
}
