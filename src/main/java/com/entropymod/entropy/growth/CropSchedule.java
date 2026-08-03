package com.entropymod.entropy.growth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * When each tracked crop is next due a bonus growth advance.
 *
 * <p>Split out from {@link GreenThumbGrowth} and kept <b>free of Minecraft
 * imports</b> on purpose, the same discipline {@code AcquiredEffects} and
 * {@code EntropyPalette} follow: the two rules that are easy to get wrong --
 * "a crop that leaves the radius stops receiving advances" and "a fully-grown
 * crop stops being tracked" -- are then drivable by a plain headless harness
 * against the real shipped class rather than a copy of it.
 *
 * <p>Both of those rules fall out of one mechanism: {@link #refresh} keeps only
 * the keys the caller says are currently eligible. A crop that walked out of
 * range, was harvested, was replaced, or hit max age is simply absent from the
 * next eligible set and is forgotten. There is no separate expiry path to keep
 * in sync.
 *
 * <p>Not persisted. A bonus advance is scheduling state for a player standing in
 * a field right now, not part of the run, and re-deriving it on the next scan
 * costs one interval at worst.
 *
 * @param <K> whatever identifies a crop block to the caller
 */
public final class CropSchedule<K> {

	private final Map<K, Long> dueAt = new HashMap<>();

	/**
	 * Brings the tracked set in line with what is currently eligible.
	 *
	 * <p>Newly-seen crops are scheduled one full interval out, so a crop planted
	 * under the player's feet waits its first interval rather than being advanced
	 * the instant it is noticed. Crops already tracked keep their existing due time
	 * -- a rescan must not push their next advance further away, or a player
	 * standing still would starve them.
	 *
	 * @param eligible      every crop that should be tracked right now
	 * @param now           current server tick
	 * @param intervalTicks how long this particular crop waits between advances
	 */
	public void refresh(Set<K> eligible, long now, ToIntFunction<K> intervalTicks) {
		for (K key : eligible) {
			dueAt.computeIfAbsent(key, k -> now + intervalTicks.applyAsInt(k));
		}
		// Anything not in the eligible set is dropped: out of radius, harvested,
		// or fully grown. This is the whole of both untracking rules.
		dueAt.keySet().retainAll(eligible);
	}

	/**
	 * Crops whose advance is due at or before {@code now}.
	 *
	 * <p>Returns a copy so the caller can drop entries while iterating -- it has to,
	 * because eligibility is re-verified at this point and a crop that fails
	 * verification is removed rather than advanced.
	 */
	public List<K> due(long now) {
		List<K> ready = new ArrayList<>();
		for (Map.Entry<K, Long> entry : dueAt.entrySet()) {
			if (entry.getValue() <= now) {
				ready.add(entry.getKey());
			}
		}
		return ready;
	}

	/** Records that an advance just happened and schedules the next one. */
	public void reschedule(K key, long now, int intervalTicks) {
		dueAt.put(key, now + intervalTicks);
	}

	/** Stops tracking one crop -- used when it fails re-verification at advance time. */
	public void drop(K key) {
		dueAt.remove(key);
	}

	public boolean isTracking(K key) {
		return dueAt.containsKey(key);
	}

	public int size() {
		return dueAt.size();
	}

	public void clear() {
		dueAt.clear();
	}
}
