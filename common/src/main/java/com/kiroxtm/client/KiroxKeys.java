package com.kiroxtm.client;

import com.kiroxtm.KiroxMap;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;

/**
 * The key binding, built from vanilla classes only. Registering it is loader specific: Fabric registers
 * the category and the key in its client initializer, NeoForge in RegisterKeyMappingsEvent.
 */
public final class KiroxKeys {
	public static final KeyMapping.Category CATEGORY = new KeyMapping.Category(KiroxMap.id("main"));
	public static final KeyMapping OPEN_MAP = new KeyMapping(
			"key.kirox_map.open_map", InputConstants.Type.KEYBOARD, InputConstants.KEY_M, CATEGORY);

	private KiroxKeys() {
	}
}
