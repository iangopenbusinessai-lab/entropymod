package com.entropymod.entropy.spawn;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * A per-key countdown that fires on a fixed or randomly re-rolled interval.
 *
 * <p>This is Random Jump's scheduling mechanism, lifted out of
 * {@code ClientRunState} and made reusable rather than reinvented. That effect
 * counts a single client-side timer down and re-rolls it in
 * {@code [MIN_INTERVAL_TICKS, MAX_INTERVAL_TICKS]} every time it fires; the two
 * spawn effects need exactly that, but server-side and one timer per player, so
 * the same shape is parameterised here instead of being written a third time.
 *
 * <p><b>The tick sequence is deliberately identical to Random Jump's.</b> A key
 * that has never been seen schedules on its first tick, so the first trigger
 * lands a full interval after the effect starts running rather than immediately
 * -- a curse that fires the instant it is picked reads as part of the pick, not
 * as a recurring hazard.
 *
 * <h2>Fixed and random are the same code path</h2>
 *
 * <p>A fixed cadence is {@code min == max}, which makes the re-roll
 * {@code nextInt(1)} and therefore always 0. There is no separate fixed-interval
 * branch to keep in step with the random one -- Unstable's 30 seconds and Creeper
 * Magnet's 30-120 seconds run through the identical arithmetic.
 *
 * <h2>Free of Minecraft imports, on purpose</h2>
 *
 * <p>Same discipline as {@code CropSchedule}, {@code TramplePath},
 * {@code MovementScramble} and {@code DoubleJumpState}. A cadence that is subtly
 * wrong -- fires every tick, never re-rolls, drifts by one -- is close to
 * invisible in play, because "a hazard appeared" looks the same whatever the gap
 * was. So it has to be drivable by the headless harness against the real shipped
 * class, which means no server, no level and no {@code RandomSource}.
 *
 * <p>The {@link Random} is injectable for that reason as well. Nothing here is
 * persisted: an interval part-way through is transient state about the current
 * session, exactly like {@code EntropyManager.tickCounter}, and losing it on a
 * reload costs at most one interval.
 *
 * @param <K> whatever identifies a timer -- {@code UUID} in both shipped uses
 */
public final class SpawnSchedule<K> {

	private final int minIntervalTicks;
	private final int maxIntervalTicks;
	private final Random random;
	private final Map<K, Integer> remaining = new HashMap<>();

	/** Fixed or random cadence, with a private RNG. */
	public SpawnSchedule(int minIntervalTicks, int maxIntervalTicks) {
		this(minIntervalTicks, maxIntervalTicks, new Random());
	}

	/** As above, with the RNG supplied -- this is what makes the cadence harness-drivable. */
	public SpawnSchedule(int minIntervalTicks, int maxIntervalTicks, Random random) {
		if (minIntervalTicks <= 0 || maxIntervalTicks < minIntervalTicks) {
			throw new IllegalArgumentException(
					"Bad interval range: " + minIntervalTicks + ".." + maxIntervalTicks);
		}
		this.minIntervalTicks = minIntervalTicks;
		this.maxIntervalTicks = maxIntervalTicks;
		this.random = random;
	}

	public int minIntervalTicks() {
		return minIntervalTicks;
	}

	public int maxIntervalTicks() {
		return maxIntervalTicks;
	}

	/** True if this schedule re-rolls its interval rather than repeating one number. */
	public boolean isRandomised() {
		return maxIntervalTicks > minIntervalTicks;
	}

	/**
	 * Counts one tick off this key's timer and reports whether it fired.
	 *
	 * <p>Firing re-rolls immediately, so the gap between two consecutive triggers
	 * is exactly one freshly drawn interval -- there is no accumulation and no
	 * drift.
	 */
	public boolean tick(K key) {
		int countdown = remaining.getOrDefault(key, 0);
		if (countdown <= 0) {
			countdown = draw();
		}
		countdown--;
		if (countdown > 0) {
			remaining.put(key, countdown);
			return false;
		}
		remaining.put(key, draw());
		return true;
	}

	/** Ticks remaining for a key, or 0 if it has no timer yet. Inspection only. */
	public int remainingFor(K key) {
		return remaining.getOrDefault(key, 0);
	}

	/**
	 * Overrides this key's timer with a short delay, replacing whatever
	 * {@link #tick} just drew.
	 *
	 * <p><b>For the case where a trigger fired but could not be acted on.</b> Both
	 * spawn effects can fire and then find nowhere valid to put anything; without
	 * this the trigger is spent and the player gets nothing for a whole interval,
	 * which is exactly how "fires reliably but often finds nowhere" presented in
	 * play as "stopped firing altogether".
	 *
	 * <p>This deliberately does <em>not</em> change the cadence when things are
	 * working -- it is only reachable on a failed attempt, and a successful
	 * trigger keeps the interval {@code tick} drew.
	 */
	public void rearm(K key, int ticks) {
		if (ticks <= 0) {
			throw new IllegalArgumentException("Retry delay must be positive: " + ticks);
		}
		remaining.put(key, ticks);
	}

	/**
	 * Drops timers for keys no longer present, so the map cannot grow without
	 * bound as players come and go.
	 *
	 * <p><b>Unconditional, deliberately.</b> The obvious optimisation -- only prune
	 * when {@code remaining.size() > live.size()} -- is wrong, and the harness
	 * caught it: one player leaving while another joins leaves the two sizes equal
	 * and the departed timer behind forever. The saving was one set comparison per
	 * tick against a handful of players; the cost was a leak that nothing in play
	 * would ever surface.
	 *
	 * <p>Pass a {@link Set} to keep the {@code retainAll} a hash lookup per entry.
	 */
	public void retainAll(Collection<K> live) {
		if (remaining.isEmpty()) {
			return;
		}
		remaining.keySet().retainAll(live instanceof Set<K> set ? set : new HashSet<>(live));
	}

	/** Drops every timer. Called when nobody holds the effect, and on server stop. */
	public void reset() {
		remaining.clear();
	}

	public boolean isEmpty() {
		return remaining.isEmpty();
	}

	/**
	 * A fresh interval in {@code [min, max]}, inclusive at both ends.
	 *
	 * <p>{@code nextInt(span + 1)} rather than {@code nextInt(span)} so the
	 * maximum is actually reachable -- the same off-by-one Random Jump's
	 * {@code scheduleNextJump} gets right, and the reason this is asserted rather
	 * than assumed.
	 */
	private int draw() {
		return minIntervalTicks + random.nextInt(maxIntervalTicks - minIntervalTicks + 1);
	}
}
