package com.kiroxtm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kiroxtm.config.ServerKey;
import org.junit.jupiter.api.Test;

/** The same server must always map to the same profile, however the address was typed. */
class ServerKeyTest {
	@Test
	void spellingsOfTheSameServerShareAKey() {
		String key = ServerKey.forAddress("play.example.com");
		assertEquals(key, ServerKey.forAddress("Play.Example.com"));
		assertEquals(key, ServerKey.forAddress(" play.example.com. "));
		assertEquals(key, ServerKey.forAddress("play.example.com:25565"));
		assertNotEquals(key, ServerKey.forAddress("play.example.com:25566"));
	}

	@Test
	void ipv6KeepsItsBrackets() {
		assertEquals("[::1]", ServerKey.normalizeAddress("[::1]:25565"));
		assertEquals("[::1]:3000", ServerKey.normalizeAddress("[::1]:3000"));
		assertEquals("[::1]", ServerKey.hostOf("[::1]:3000"));
	}

	@Test
	void lanWorldIgnoresItsChangingPort() {
		assertEquals(ServerKey.forLan("192.168.1.5:50001", "Ana - Mundo"), ServerKey.forLan("192.168.1.5:61234", "ana - mundo"));
		assertNotEquals(ServerKey.forLan("192.168.1.5:50001", "Ana - Mundo"), ServerKey.forLan("192.168.1.5:50001", "Ana - Otro"));
	}

	@Test
	void realmUsesItsNameAndFallsBackToTheAddress() {
		assertEquals(ServerKey.forRealm("Mi Realm", "a.b:1"), ServerKey.forRealm(" mi realm ", "c.d:2"));
		assertEquals(ServerKey.forAddress("z.y"), ServerKey.forRealm("", "Z.Y"));
	}

	@Test
	void fileIdsAreSafeAndDistinct() {
		assertTrue(ServerKey.fileId("mp:lan:192.168.1.5/ana - mundo").matches("[a-z0-9._\\-]+"));
		assertNotEquals(ServerKey.fileId("mp:a b"), ServerKey.fileId("mp:a_b"));
	}
}
