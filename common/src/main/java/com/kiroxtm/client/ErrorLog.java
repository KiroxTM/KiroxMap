package com.kiroxtm.client;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.kiroxtm.KiroxMap;
import com.kiroxtm.platform.Services;

/**
 * Last-resort diagnostics. A failure while opening the atlas must never take the game down, and it
 * must leave something to read: the full stack trace goes to the log and to
 * {@code <game dir>/kirox-map-error.txt}.
 */
public final class ErrorLog {
	private static boolean written;

	private ErrorLog() {
	}

	/** Returns the path of the error file, or null if it could not be written. */
	public static synchronized Path report(String what, Throwable error) {
		KiroxMap.LOGGER.error(what, error);
		if (written) {
			return null; // keep the first (root-cause) report; later ones are usually NoClassDefFoundError noise
		}
		try {
			StringWriter text = new StringWriter();
			error.printStackTrace(new PrintWriter(text));
			Path file = Services.PLATFORM.getGameDir().resolve("kirox-map-error.txt");
			Files.writeString(file, what + "\n"
					+ "java " + System.getProperty("java.version") + ", " + System.getProperty("os.name")
					+ " " + System.getProperty("os.arch") + "\n\n" + text, StandardCharsets.UTF_8);
			written = true;
			return file;
		} catch (Exception e) {
			KiroxMap.LOGGER.error("Could not write kirox-map-error.txt", e);
			return null;
		}
	}
}
