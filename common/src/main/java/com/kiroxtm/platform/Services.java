package com.kiroxtm.platform;

import java.util.ServiceLoader;

import com.kiroxtm.platform.services.IPlatformHelper;

/** Locates the loader-specific implementations of the abstractions declared in {@code common}. */
public final class Services {
	public static final IPlatformHelper PLATFORM = load(IPlatformHelper.class);

	private Services() {
	}

	public static <T> T load(Class<T> type) {
		return ServiceLoader.load(type, Services.class.getClassLoader())
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("No implementation found for service " + type.getName()));
	}
}
