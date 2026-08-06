package com.entropymod.client;

import com.entropymod.entropy.behavior.OreSenseBehavior;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Ore Sense's client-side cache of nearby ore positions.
 *
 * <p>Rescanned every {@value com.entropymod.entropy.behavior.OreSenseBehavior#RESCAN_INTERVAL_TICKS}
 * ticks from the client tick loop; the renderer only ever reads the cache.
 * <b>Scanning per frame would be 4,913 block reads x 60fps = ~295,000 a
 * second</b>, which is the whole reason the cache exists.
 *
 * <p>See {@link OreSenseBehavior} for the tag, the cost model, and why the
 * sphere-vs-box correction below is required rather than tidy.
 */
public final class OreSenseScan {

	/**
	 * Positions found by the last scan. Replaced wholesale rather than mutated, so
	 * the render thread can never observe a half-rebuilt list.
	 */
	private static volatile List<BlockPos> cached = List.of();

	private static int ticksUntilRescan = 0;

	private OreSenseScan() {}

	/** Called once per client tick. Cheap on the ticks that are not a rescan. */
	public static void tick(LocalPlayer player) {
		if (!ClientRunState.hasOreSense()) {
			if (!cached.isEmpty()) {
				cached = List.of();
			}
			ticksUntilRescan = 0;
			return;
		}
		if (--ticksUntilRescan > 0) {
			return;
		}
		ticksUntilRescan = OreSenseBehavior.RESCAN_INTERVAL_TICKS;
		cached = scan(player);
	}

	/** The last scan's results. Never null; safe to iterate from the render thread. */
	public static List<BlockPos> cachedPositions() {
		return cached;
	}

	/** Drops the cache. Registered on DISCONNECT with the other client caches. */
	public static void reset() {
		cached = List.of();
		ticksUntilRescan = 0;
	}

	private static List<BlockPos> scan(LocalPlayer player) {
		if (!(player.level() instanceof ClientLevel level)) {
			return List.of();
		}
		BlockPos centre = player.blockPosition();
		int reach = OreSenseBehavior.REACH;
		List<BlockPos> found = new ArrayList<>();

		for (BlockPos pos : BlockPos.betweenClosed(
				centre.offset(-reach, -reach, -reach),
				centre.offset(reach, reach, reach))) {
			// Spherical, not the raw box. Without this the corners reach
			// 8 * sqrt(3) = 13.86 blocks -- a 73% over-range that would look like
			// the effect simply having a bigger radius than advertised.
			if (!OreSenseBehavior.inRange(
					centre.distSqr(pos))) {
				continue;
			}
			if (!level.getBlockState(pos).is(OreSenseBehavior.ORE_TARGETS)) {
				continue;
			}
			// betweenClosed yields a MUTABLE cursor -- storing it without
			// immutable() would make every entry alias one position. Same trap
			// Green Thumb's sweep documents.
			found.add(pos.immutable());
		}
		return Collections.unmodifiableList(found);
	}
}
