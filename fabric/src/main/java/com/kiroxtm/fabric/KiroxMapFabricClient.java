package com.kiroxtm.fabric;

import com.kiroxtm.KiroxMap;
import com.kiroxtm.client.KiroxClient;
import com.kiroxtm.client.KiroxKeys;
import com.kiroxtm.client.WaypointTags;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/** Fabric client entry point: registers the key and forwards ticks to the shared client logic. */
public class KiroxMapFabricClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		KiroxMap.init();
		// The category must be registered with vanilla before the key that uses it.
		KeyMapping.Category.register(KiroxKeys.CATEGORY.id());
		KeyMappingHelper.registerKeyMapping(KiroxKeys.OPEN_MAP);
		ClientTickEvents.END_CLIENT_TICK.register(KiroxClient::tick);
		// Waypoint name tags over the game view (after the vanilla overlays, so F1 hides them too).
		HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS, KiroxMap.id("waypoint_tags"),
				(graphics, deltaTracker) -> WaypointTags.render(graphics, deltaTracker.getGameTimeDeltaPartialTick(true)));
	}
}
