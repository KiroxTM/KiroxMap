package com.kiroxtm.platform.services;

import java.nio.file.Path;

/**
 * Everything the shared code needs from the mod loader. Each loader module provides one
 * implementation, registered in {@code META-INF/services/com.kiroxtm.platform.services.IPlatformHelper}.
 */
public interface IPlatformHelper {
	/** Display name of the loader, e.g. "Fabric" or "NeoForge". */
	String getPlatformName();

	/** The loader's config directory ({@code <game dir>/config}). */
	Path getConfigDir();

	/** The game directory (the instance folder). */
	Path getGameDir();
}
