package com.kiroxtm.client;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.kiroxtm.config.AtlasConfig;
import com.kiroxtm.config.Waypoint;
import com.kiroxtm.screen.AtlasScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Name tags for the waypoints, drawn on the HUD over the game view: a dark rectangle with the waypoint's
 * name and, under it, the distance. Away from the crosshair a tag is small and half transparent; when the
 * player looks straight at it, it grows and becomes fully opaque, and it eases back when the view leaves.
 *
 * <p>Each tag sits above the waypoint's own X/Y/Z. Each loader calls {@link #render} from its own HUD event. Switch off with
 * {@code showWaypointTags} (button in the map screen, or the config screen).
 */
public final class WaypointTags {
	/** Scale of a tag nobody is looking at. */
	private static final float BASE_SCALE = 0.288f;
	/** Scale of the tag the player is looking at. */
	private static final float FOCUS_SCALE = 0.504f;
	private static final float BASE_ALPHA = 0.4f;
	private static final float FOCUS_ALPHA = 0.9f;
	/** Extra screen pixels around a tag that still count as "looking at it". */
	private static final float FOCUS_MARGIN = 8f;
	/** How fast a tag grows / shrinks (higher = faster). */
	private static final float EASE_SPEED = 14f;
	private static final int BACKGROUND_ALPHA = 0xB0;
	private static final int PAD_X = 3;
	private static final int PAD_Y = 2;
	private static final int LINE_H = 9;
	private static final int LINE_GAP = 1;
	/** A tag disappears when the player is this close (in blocks) or closer. */
	private static final double HIDE_DISTANCE = 20;
	/** Longest name shown, in characters; a longer one is cut and gets {@link #ELLIPSIS}. */
	private static final int MAX_NAME_LENGTH = 11;
	/** The tag floats this many blocks above the waypoint's Y (roughly where a player's head would be). */
	private static final double TAG_HEIGHT = 1.5;
	private static final String ELLIPSIS = "....";
	private static final long WORLD_KEY_REFRESH_MS = 1000;

	/** Focus amount per waypoint, 0 (not looked at) to 1 (looked at), animated between frames. */
	private static final Map<Waypoint, Float> FOCUS = new IdentityHashMap<>();
	private static long lastFrameNanos;
	private static String cachedWorldKey = "none";
	private static long cachedWorldKeyAt;

	private WaypointTags() {
	}

	private record Tag(Waypoint waypoint, float x, float y, double distance, String name, String distanceText, int nameWidth, int fullWidth) {
	}

	/** {@code partialTick}: how far the current frame is into the tick, so the view matches the camera exactly. */
	public static void render(GuiGraphicsExtractor graphics, float partialTick) {
		AtlasConfig config = AtlasConfig.get();
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		if (!config.showWaypointTags || player == null || minecraft.gui.screen() != null) {
			FOCUS.clear();
			lastFrameNanos = 0;
			return;
		}
		List<Waypoint> waypoints = config.waypoints.get(worldKey(minecraft));
		if (waypoints == null || waypoints.isEmpty()) {
			FOCUS.clear();
			return;
		}

		int screenW = graphics.guiWidth();
		int screenH = graphics.guiHeight();
		Font font = minecraft.font;

		// Camera basis from the view angles (yaw 0 looks along +Z, positive pitch looks down).
		// The same interpolated position and angles the game camera uses this frame, so a tag stays put in
		// the world while the view turns (the raw getYRot/getXRot differ from the camera by up to one tick).
		Vec3 eye = player.getEyePosition(partialTick);
		double yaw = Math.toRadians(player.getViewYRot(partialTick));
		double pitch = Math.toRadians(player.getViewXRot(partialTick));
		double cosPitch = Math.cos(pitch);
		double fx = -Math.sin(yaw) * cosPitch, fy = -Math.sin(pitch), fz = Math.cos(yaw) * cosPitch; // forward
		double rx = -Math.cos(yaw), rz = -Math.sin(yaw); // right (no vertical part)
		double ux = -rz * fy, uy = rz * fx - rx * fz, uz = rx * fy; // up = right x forward
		double tanHalfFov = Math.tan(Math.toRadians(minecraft.options.fov().get()) / 2.0);
		double aspect = (double) screenW / screenH;

		String dimension = AtlasScreen.dimOf(player).key();
		List<Tag> tags = new ArrayList<>();
		for (Waypoint w : waypoints) {
			if (!w.visible || !dimension.equals(w.dim)) {
				continue;
			}
			double dx = w.x + 0.5 - eye.x, dy = w.y + TAG_HEIGHT - eye.y, dz = w.z + 0.5 - eye.z;
			double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (distance <= HIDE_DISTANCE) {
				continue; // close enough to see the place itself
			}
			double depth = dx * fx + dy * fy + dz * fz;
			if (depth <= 0.05) {
				continue; // behind the player
			}
			double right = dx * rx + dz * rz;
			double up = dx * ux + dy * uy + dz * uz;
			float sx = (float) (screenW / 2.0 + right / (depth * tanHalfFov * aspect) * screenW / 2.0);
			float sy = (float) (screenH / 2.0 - up / (depth * tanHalfFov) * screenH / 2.0);
			String name = shortName(w.name);
			String distanceText = formatDistance(distance);
			int nameWidth = font.width(name) + PAD_X * 2;
			int fullWidth = Math.max(nameWidth, font.width(distanceText) + PAD_X * 2);
			tags.add(new Tag(w, sx, sy, distance, name, distanceText, nameWidth, fullWidth));
		}

		long now = System.nanoTime();
		float dt = lastFrameNanos == 0 ? 0.016f : Math.min(0.1f, (now - lastFrameNanos) / 1_000_000_000f);
		lastFrameNanos = now;

		// The tag whose rectangle holds the crosshair; the one closest to the centre wins.
		float cx = screenW / 2f, cy = screenH / 2f;
		Tag looked = null;
		double bestOffset = Double.MAX_VALUE;
		for (Tag tag : tags) {
			float tagFocus = FOCUS.getOrDefault(tag.waypoint(), 0f);
			float scale = scaleOf(tagFocus);
			float offsetX = Math.abs(cx - tag.x()), offsetY = Math.abs(cy - tag.y());
			if (offsetX <= widthOf(tag, tagFocus) * scale / 2f + FOCUS_MARGIN && offsetY <= heightOf(tagFocus) * scale / 2f + FOCUS_MARGIN) {
				double offset = offsetX * offsetX + offsetY * offsetY;
				if (offset < bestOffset) {
					bestOffset = offset;
					looked = tag;
				}
			}
		}

		// Far tags first so near ones draw over them; the tag being looked at is always on top.
		tags.sort((a, b) -> Double.compare(b.distance(), a.distance()));
		Map<Waypoint, Float> next = new IdentityHashMap<>();
		float lookedFocus = 0f;
		for (Tag tag : tags) {
			float focus = FOCUS.getOrDefault(tag.waypoint(), 0f);
			float target = tag == looked ? 1f : 0f;
			focus += (target - focus) * Math.min(1f, dt * EASE_SPEED);
			if (Math.abs(target - focus) < 0.002f) {
				focus = target;
			}
			next.put(tag.waypoint(), focus);
			if (tag == looked) {
				lookedFocus = focus;
			} else {
				draw(graphics, font, tag, focus, screenW, screenH);
			}
		}
		if (looked != null) {
			draw(graphics, font, looked, lookedFocus, screenW, screenH);
		}
		FOCUS.clear();
		FOCUS.putAll(next);
	}

	/** Box width: just the name when not looked at, wide enough for the distance too when focused. */
	private static float widthOf(Tag tag, float focus) {
		return tag.nameWidth() + (tag.fullWidth() - tag.nameWidth()) * focus;
	}

	/** Box height: one line (name) when not looked at, two (name and distance) when focused. */
	private static float heightOf(float focus) {
		return PAD_Y * 2 + LINE_H + (LINE_H + LINE_GAP) * focus;
	}

	private static float scaleOf(float focus) {
		return BASE_SCALE + (FOCUS_SCALE - BASE_SCALE) * focus;
	}

	private static void draw(GuiGraphicsExtractor graphics, Font font, Tag tag, float focus, int screenW, int screenH) {
		float scale = scaleOf(focus);
		float alpha = BASE_ALPHA + (FOCUS_ALPHA - BASE_ALPHA) * focus;
		int w = Math.round(widthOf(tag, focus));
		int h = Math.round(heightOf(focus));
		if (tag.x() < -w * scale || tag.x() > screenW + w * scale
				|| tag.y() < -h * scale || tag.y() > screenH + h * scale) {
			return; // off screen
		}
		int background = Math.round(BACKGROUND_ALPHA * alpha) << 24;
		int textAlpha = Math.max(4, Math.round(255 * alpha)) << 24; // text alpha under 4 is drawn opaque
		int nameColor = textAlpha | (tag.waypoint().color & 0xFFFFFF);
		// The distance line only shows (and fades in) while the tag is being looked at.
		float distanceAlpha = alpha * focus;
		int distanceColor = Math.max(4, Math.round(255 * distanceAlpha)) << 24 | 0xE0E0E0;

		var pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(tag.x(), tag.y());
		pose.scale(scale, scale);
		graphics.fill(-w / 2, -h / 2, -w / 2 + w, -h / 2 + h, background);
		graphics.text(font, tag.name(), -font.width(tag.name()) / 2, -h / 2 + PAD_Y, nameColor);
		if (distanceAlpha >= 0.05f) {
			graphics.text(font, tag.distanceText(), -font.width(tag.distanceText()) / 2,
					-h / 2 + PAD_Y + LINE_H + LINE_GAP, distanceColor);
		}
		pose.popMatrix();
	}

	/** Distance in blocks ("B"), not meters. */
	private static String formatDistance(double blocks) {
		return String.format(Locale.ROOT, "%.1fB", blocks);
	}

	private static String shortName(String name) {
		if (name == null || name.isBlank()) {
			return "Waypoint";
		}
		String trimmed = name.strip();
		return trimmed.length() > MAX_NAME_LENGTH ? trimmed.substring(0, MAX_NAME_LENGTH) + ELLIPSIS : trimmed;
	}

	private static String worldKey(Minecraft minecraft) {
		long now = System.currentTimeMillis();
		if (now - cachedWorldKeyAt > WORLD_KEY_REFRESH_MS) {
			cachedWorldKey = AtlasScreen.worldKeyOf(minecraft);
			cachedWorldKeyAt = now;
		}
		return cachedWorldKey;
	}
}
