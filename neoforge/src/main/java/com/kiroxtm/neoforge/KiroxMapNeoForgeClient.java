package com.kiroxtm.neoforge;

import com.kiroxtm.KiroxMap;
import com.kiroxtm.client.KiroxClient;
import com.kiroxtm.client.KiroxKeys;
import com.kiroxtm.screen.KiroxConfigScreen;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * NeoForge client entry point: registers the key and forwards ticks to the shared client logic. The
 * event bus (mod bus for key registration, game bus for ticks) is picked from each event's type.
 */
@Mod(value = KiroxMap.MOD_ID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = KiroxMap.MOD_ID, value = Dist.CLIENT)
public class KiroxMapNeoForgeClient {
	/** Gives the Mods screen its Config button. */
	public KiroxMapNeoForgeClient(ModContainer container) {
		container.registerExtensionPoint(IConfigScreenFactory.class, (modContainer, parent) -> new KiroxConfigScreen(parent));
	}

	@SubscribeEvent
	static void registerKeys(RegisterKeyMappingsEvent event) {
		event.registerCategory(KiroxKeys.CATEGORY);
		event.register(KiroxKeys.OPEN_MAP);
	}

	@SubscribeEvent
	static void onClientTick(ClientTickEvent.Post event) {
		KiroxClient.tick(Minecraft.getInstance());
	}
}
