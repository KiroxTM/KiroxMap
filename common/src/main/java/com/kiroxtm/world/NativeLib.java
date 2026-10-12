package com.kiroxtm.world;

import com.kiroxtm.KiroxMap;

import dev.xpple.cubiomes.CubiomesInit;

/**
 * Loads the cubiomes native library that ships inside the {@code dev.xpple:cubiomes} bindings jar.
 *
 * <p>The generated bindings resolve their symbols with {@code SymbolLookup.loaderLookup()}, which
 * only sees libraries loaded by the <em>same class loader as the bindings themselves</em>. On NeoForge
 * the mod and its jar-in-jar libraries live in separate modules, so loading the library from this
 * mod's own classes (as the Fabric build does) can end up in a different loader and the bindings then
 * fail to initialise. {@link CubiomesInit#load()} calls {@code System.load} from inside the bindings,
 * so it always matches. It also picks the right file name for the platform (x86/arm).
 */
public final class NativeLib {
	private static boolean loaded;

	private NativeLib() {
	}

	public static synchronized void load() {
		if (loaded) {
			return;
		}
		CubiomesInit.load();
		loaded = true;
		KiroxMap.LOGGER.info("Loaded cubiomes native library");
	}
}
