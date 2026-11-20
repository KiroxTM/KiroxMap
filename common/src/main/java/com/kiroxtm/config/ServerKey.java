package com.kiroxtm.config;

import java.util.Locale;

/**
 * Turns whatever the player typed as a server address into a stable identity, so the same server is always
 * the same profile: {@code Play.Example.com}, {@code play.example.com.} and {@code play.example.com:25565}
 * are one server. No Minecraft types in here, so it can be unit-tested on its own.
 */
public final class ServerKey {
	public static final String PREFIX = "mp:";
	private static final String DEFAULT_PORT = "25565";

	private ServerKey() {
	}

	/** Key for a normal (dedicated) server, from the address as typed. */
	public static String forAddress(String address) {
		return PREFIX + normalizeAddress(address);
	}

	/** Key for a world opened to LAN: the host (its port changes on every session) plus the world's name. */
	public static String forLan(String address, String name) {
		String world = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
		return PREFIX + "lan:" + hostOf(address) + (world.isEmpty() ? "" : "/" + world);
	}

	/** Key for a Realm: its address changes between sessions, its name does not. Falls back to the address. */
	public static String forRealm(String name, String address) {
		String realm = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
		return realm.isEmpty() ? forAddress(address) : PREFIX + "realm:" + realm;
	}

	/** Lower case, no spaces, no trailing dot on the host, and no port when it is the default one. */
	public static String normalizeAddress(String address) {
		if (address == null) {
			return "";
		}
		String text = address.strip().toLowerCase(Locale.ROOT);
		String host = text;
		String port = "";
		if (text.startsWith("[")) { // [IPv6]:port
			int end = text.indexOf(']');
			if (end > 0) {
				host = text.substring(0, end + 1);
				if (text.startsWith(":", end + 1)) {
					port = text.substring(end + 2);
				}
			}
		} else {
			int colon = text.indexOf(':');
			if (colon >= 0 && colon == text.lastIndexOf(':')) { // host:port (a bare IPv6 has several colons)
				host = text.substring(0, colon);
				port = text.substring(colon + 1);
			}
		}
		while (host.endsWith(".")) {
			host = host.substring(0, host.length() - 1);
		}
		boolean defaultPort = port.isEmpty() || port.equals(DEFAULT_PORT);
		return defaultPort ? host : host + ":" + port;
	}

	/** The host part only (no port). */
	public static String hostOf(String address) {
		String normalized = normalizeAddress(address);
		if (normalized.startsWith("[")) {
			int end = normalized.indexOf(']');
			return end > 0 ? normalized.substring(0, end + 1) : normalized;
		}
		int colon = normalized.indexOf(':');
		return colon >= 0 && colon == normalized.lastIndexOf(':') ? normalized.substring(0, colon) : normalized;
	}

	/** A file name for a key: readable, safe on every OS and unique (a hash keeps lookalike keys apart). */
	public static String fileId(String key) {
		String readable = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.\\-]+", "_");
		if (readable.length() > 60) {
			readable = readable.substring(0, 60);
		}
		return readable + "-" + Integer.toHexString(key.hashCode());
	}
}
