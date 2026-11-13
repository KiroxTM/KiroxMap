package com.kiroxtm.map;

import com.kiroxtm.KiroxMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import com.kiroxtm.world.StructureIndex;
import com.kiroxtm.world.StructureKind;

/**
 * Structure markers for the visible area, searched on a background thread. A search covers the view
 * plus a margin, so small pans reuse the last result, and a newer request always supersedes an
 * older one. The render thread only reads the latest finished list.
 */
public final class StructureLayer {
	/** A kind is hidden when more placement regions than this would fit in the view (markers would pile up). */
	private static final double MAX_REGIONS_IN_VIEW = 12_000;

	private static final ExecutorService SEARCH = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Kirox Map structure search");
		thread.setDaemon(true);
		thread.setPriority(Thread.NORM_PRIORITY - 1);
		return thread;
	});

	private final StructureIndex index;
	private final AtomicLong generation = new AtomicLong();
	private volatile List<StructureIndex.Found> found = List.of();
	private volatile Query covered;
	private Query requested;

	public StructureLayer(StructureIndex index) {
		this.index = index;
	}

	/** Saves new results to the disk cache (on the search thread, so the caller never waits). */
	public void flush() {
		SEARCH.execute(index::flush);
	}

	/** Structures found so far; may lag one search behind the view. */
	public List<StructureIndex.Found> found() {
		return found;
	}

	/** Whether a kind is shown for a view of this size in blocks (rare, large-region kinds survive far zooms). */
	public boolean visibleFor(StructureKind kind, double viewBlocksX, double viewBlocksZ) {
		if (!index.supports(kind)) {
			return false;
		}
		if (kind == StructureKind.STRONGHOLD) {
			return true;
		}
		double size = index.regionBlocks(kind);
		return viewBlocksX / size * (viewBlocksZ / size) <= MAX_REGIONS_IN_VIEW;
	}

	/** Called every frame with the view rectangle in blocks; starts a search only when needed. */
	public void update(Set<StructureKind> enabled, double left, double top, double right, double bottom) {
		List<StructureKind> kinds = enabled.stream()
				.filter(kind -> visibleFor(kind, right - left, bottom - top))
				.sorted()
				.toList();
		boolean gateways = enabled.contains(StructureKind.END_GATEWAY) && index.supports(StructureKind.END_GATEWAY);
		if (covered != null && covered.contains(kinds, gateways, left, top, right, bottom)
				|| requested != null && requested.contains(kinds, gateways, left, top, right, bottom)) {
			return;
		}
		double marginX = (right - left) / 4, marginZ = (bottom - top) / 4;
		Query query = new Query(kinds, gateways, (int) Math.floor(left - marginX), (int) Math.floor(top - marginZ),
				(int) Math.ceil(right + marginX), (int) Math.ceil(bottom + marginZ));
		requested = query;
		long id = generation.incrementAndGet();
		SEARCH.execute(() -> search(query, id));
	}

	private void search(Query query, long id) {
		List<StructureIndex.Found> result = new ArrayList<>();
		try {
			if (query.gateways) {
				// The ring around the main island is always shown, however far out the view is zoomed.
				result.addAll(index.findFixedGateways(query.x0, query.z0, query.x1, query.z1));
			}
			for (StructureKind kind : query.kinds) {
				if (generation.get() != id) {
					return; // superseded by a newer view
				}
				result.addAll(index.find(kind, query.x0, query.z0, query.x1, query.z1));
			}
		} catch (RuntimeException e) {
			KiroxMap.LOGGER.warn("Structure search failed", e);
			return;
		}
		if (generation.get() == id) {
			found = List.copyOf(result);
			covered = query;
		}
	}

	private record Query(List<StructureKind> kinds, boolean gateways, int x0, int z0, int x1, int z1) {
		boolean contains(List<StructureKind> otherKinds, boolean otherGateways, double left, double top, double right, double bottom) {
			// A search for more kinds also answers a view that needs fewer (zooming out hides kinds), so
			// nothing is searched again, and nothing disappears and reappears while the wheel turns.
			return kinds.containsAll(otherKinds) && gateways == otherGateways && left >= x0 && top >= z0 && right <= x1 && bottom <= z1;
		}
	}
}
