package com.kiroxtm.client;

import java.nio.file.Path;

import com.kiroxtm.config.AtlasConfig;
import com.kiroxtm.config.ServerProfiles;
import com.kiroxtm.config.WorldWaypoints;
import com.kiroxtm.screen.AtlasScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Client behaviour shared by every loader: each one just calls {@link #tick} once per client tick. */
public final class KiroxClient {
	private KiroxClient() {
	}

	public static void tick(Minecraft client) {
		if (client.player != null) {
			WorldWaypoints.sync(client, AtlasConfig.get()); // loads this world's waypoint file the first time
			ServerProfiles.sync(client, AtlasConfig.get()); // multiplayer: loads this server's profile on joining
		}
		while (KiroxKeys.OPEN_MAP.consumeClick()) {
			if (client.gui.screen() == null && client.player != null) {
				try {
					client.gui.setScreen(new AtlasScreen());
				} catch (Throwable t) {
					// Includes LinkageError (e.g. ExceptionInInitializerError): report instead of crashing the game.
					Path file = ErrorLog.report("Kirox Map failed to open", t);
					client.gui.setScreen(null);
					client.player.sendSystemMessage(Component.literal("[Kirox Map] Failed to open the map: " + t
							+ (file != null ? " (details in " + file.getFileName() + ")" : "")));
				}
			}
		}
	}
}
