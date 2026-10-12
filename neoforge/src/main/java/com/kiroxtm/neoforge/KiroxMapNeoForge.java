package com.kiroxtm.neoforge;

import com.kiroxtm.KiroxMap;

import net.neoforged.fml.common.Mod;

/** Common NeoForge entry point (loads on both sides; the mod itself is client-only). */
@Mod(KiroxMap.MOD_ID)
public class KiroxMapNeoForge {
	public KiroxMapNeoForge() {
		KiroxMap.init();
	}
}
