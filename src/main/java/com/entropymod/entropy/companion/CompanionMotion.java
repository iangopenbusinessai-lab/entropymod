package com.entropymod.entropy.companion;

import java.util.HashMap;
import java.util.Map;

/**
 * Every movement DECISION the companion effects make, with no Minecraft types.
 *
 * <p>Same discipline as {@code SpawnSchedule}, {@code TramplePath},
 * {@code CropSchedule} and {@code DoubleJumpState}, and for the reason the spawn
 * effects learned the hard way: <b>the component that broke last time was the one
 * that needed a {@code ServerLevel} for every line and therefore had no test.</b>
 * Distance bands, hysteresis and stall detection are exactly the kind of rule that
 * looks right, plays subtly wrong, and cannot be told apart from working by
 * watching -- so they live here, where the harness can drive them.
 *
 * <p>{@code CompanionService} keeps only the parts that genuinely need the world:
 * resolving entities, reading line of sight, and issuing navigation.
 *
 * <h2>Catch-up: the threshold is VANILLA'S OWN pet-teleport distance</h2>
 *
 * <p>{@code TamableAnimal.shouldTryTeleportToOwner()} is
 * {@code distanceToSqr(owner) >= 144.0} -- <b>12 blocks</b>. Reusing that number
 * rather than inventing one means the catch-up fires at the distance a player's
 * pet-teleport intuition is already calibrated on, and it means wolves (which
 * self-teleport at exactly this distance through {@code FollowOwnerGoal}) and
 * golems and the llama all behave alike instead of at three different ranges.
 *
 * <h2>Stall detection: closure rate, not position equality</h2>
 *
 * <p>"Stuck" cannot be "hasn't moved", because a companion pathing in circles
 * around a fence moves constantly and gets nowhere, and one riding a boat moves
 * without walking. The signal that matters is <b>is the gap to the owner actually
 * shrinking</b>, so the tracker samples the distance once a second and asks
 * whether it fell by at least {@link #MIN_PROGRESS_PER_SAMPLE}.
 *
 * <p>The bar is set from real travel speeds. A mob's ground acceleration is its
 * {@code MOVEMENT_SPEED} <em>squared</em> (see CLAUDE.md 1e), so an iron golem or
 * llama at modifier 1.0 manages {@code 0.25^2 = 0.0625} -> <b>~2.70 blocks/second</b>
 * and a wolf {@code 0.3^2 = 0.09} -> ~3.9 b/s. <b>One block of closure per second
 * is therefore about 37% of a golem's flat-out speed</b> -- comfortably achievable
 * by anything genuinely walking toward the player even while detouring, and far
 * above the noise of a companion shuffling in place.
 *
 * <p>It also, deliberately, treats "the player is outrunning me" as a stall: a
 * player sprinting at 5.6 b/s away from a 2.7 b/s golem makes the gap grow, closure
 * is negative, and the catch-up fires. That is the case the feature exists for.
 *
 * <p>{@link #STALL_SAMPLES} consecutive failures are required -- <b>three seconds,
 * not one</b> -- so rounding a tree or waiting on a door does not teleport anyone.
 */
public final class CompanionMotion {

	/** Vanilla's own pet-teleport distance: {@code sqrt(144)}. */
	public static final double CATCH_UP_DISTANCE = 12.0;

	/** How often the distance is sampled for stall detection. One second. */
	public static final int STALL_SAMPLE_INTERVAL = 20;

	/** Blocks of closure a sample must show to count as progress. */
	public static final double MIN_PROGRESS_PER_SAMPLE = 1.0;

	/** Consecutive failing samples before a companion is considered stalled. */
	public static final int STALL_SAMPLES = 3;

	/** Where a caught-up companion is put down, via {@code SafeSpawn}. */
	public static final double TELEPORT_MIN_DISTANCE = 2.0;
	public static final double TELEPORT_MAX_DISTANCE = 4.0;

	private CompanionMotion() {}

	// ==================================================================
	// Catch-up
	// ==================================================================

	/**
	 * Per-companion closure history. Transient by design -- it describes the last
	 * three seconds, so losing it on a reload costs one stall window.
	 */
	public static final class StallTracker {

		private final Map<String, Double> lastDistance = new HashMap<>();
		private final Map<String, Integer> failedSamples = new HashMap<>();

		/**
		 * Records one sample and reports whether this companion should be caught up.
		 *
		 * @param distance current distance from the owner
		 * @return true when it is both far enough AND has failed to close the gap
		 *         {@link #STALL_SAMPLES} times running
		 */
		public boolean sample(String companionId, double distance) {
			Double previous = lastDistance.put(companionId, distance);

			// Never teleport something that is simply near. The distance gate is
			// checked first and independently, so a companion milling about at 3
			// blocks can fail every progress check forever and is left alone.
			if (distance <= CATCH_UP_DISTANCE) {
				failedSamples.remove(companionId);
				return false;
			}
			if (previous == null) {
				// First sighting: no closure to measure yet. Start the window rather
				// than counting the missing sample as a failure.
				return false;
			}

			double closed = previous - distance;
			if (closed >= MIN_PROGRESS_PER_SAMPLE) {
				failedSamples.remove(companionId);
				return false;
			}

			int failures = failedSamples.merge(companionId, 1, Integer::sum);
			if (failures >= STALL_SAMPLES) {
				// Consumed: the window restarts after a teleport rather than firing
				// again on the next sample while the companion is still settling.
				failedSamples.remove(companionId);
				lastDistance.remove(companionId);
				return true;
			}
			return false;
		}

		/** Drops a companion's history -- on death, or when it stops being tracked. */
		public void forget(String companionId) {
			lastDistance.remove(companionId);
			failedSamples.remove(companionId);
		}

		public void reset() {
			lastDistance.clear();
			failedSamples.clear();
		}

		public int failuresFor(String companionId) {
			return failedSamples.getOrDefault(companionId, 0);
		}

		public boolean isTracking(String companionId) {
			return lastDistance.containsKey(companionId);
		}
	}

	// ==================================================================
	// Escort band (The Entourage)
	// ==================================================================

	/** What an escort should do about its current distance from the owner. */
	public enum BandAction {
		/** Too far: walk to the ring. */
		APPROACH,
		/** In the band: stop navigating entirely. */
		HOLD,
		/** Too close: walk back out to the ring. */
		RETREAT
	}

	/**
	 * The escort's distance-holding rule.
	 *
	 * <p><b>APPROACH and RETREAT are the same instruction</b> -- move to the point
	 * at {@code idealDistance} along the line from the owner through the companion
	 * -- which is why this returns only a classification and the caller computes one
	 * target for both. That symmetry is what stops the two cases drifting apart.
	 *
	 * <p>Aiming at the <em>middle</em> of the band rather than at whichever edge was
	 * crossed is the anti-jitter property: a companion that arrives is not sitting on
	 * a boundary where the next sample could flip it back.
	 */
	public static BandAction bandAction(double distance, double minHold, double maxHold) {
		if (distance < minHold) {
			return BandAction.RETREAT;
		}
		if (distance > maxHold) {
			return BandAction.APPROACH;
		}
		return BandAction.HOLD;
	}

	/** The ring radius both APPROACH and RETREAT aim for: the middle of the band. */
	public static double idealHoldDistance(double minHold, double maxHold) {
		return (minHold + maxHold) / 2.0;
	}

	// ==================================================================
	// The Audience
	// ==================================================================

	/** What an audience villager should be doing this tick. */
	public enum AudienceState {
		/**
		 * The owner can be seen. Stand perfectly still, at any distance -- unless
		 * that would mean standing too close, which {@link #BACK_OFF} handles.
		 */
		FROZEN,
		/** Lost track and far away: close the gap, but only to the minimum ring. */
		APPROACHING,
		/** Closer than the minimum: move back out, seen or not. */
		BACK_OFF,
		/** Not visible, not far enough to bother, not too close: do nothing. */
		IDLE
	}

	/**
	 * The Audience's whole state machine.
	 *
	 * <p>Priority order is the design, and it is deliberate:
	 *
	 * <ol>
	 *   <li><b>BACK_OFF outranks everything, including FROZEN.</b> The brief's
	 *       freeze rule is "regardless of distance, as long as it is not so close it
	 *       violates the minimum" -- so the minimum-distance rule is the one thing
	 *       that can override a freeze. Without this a villager the player walked
	 *       into would freeze in their face, which is the opposite of the effect.</li>
	 *   <li><b>FROZEN next.</b> Being watched stops them dead, at any distance.</li>
	 *   <li><b>APPROACHING</b> only when unseen and genuinely lost.</li>
	 *   <li><b>IDLE</b> otherwise -- the ordinary state, where the villager's own
	 *       brain is left entirely alone.</li>
	 * </ol>
	 *
	 * <p><b>Hysteresis is why {@code resumeDistance} exists and is a separate
	 * number.</b> If APPROACHING both started and ended at {@code lostTrack}, a
	 * villager at exactly that distance would flip between approaching and idle on
	 * alternate samples and visibly stutter. Entering at {@code lostTrack} and only
	 * leaving once inside {@code resumeDistance} gives the state a corridor to cross.
	 *
	 * @param wasApproaching this villager's state on the previous evaluation
	 */
	public static AudienceState audienceState(double distance, boolean canBeSeen,
											  boolean wasApproaching,
											  double minDistance, double resumeDistance,
											  double lostTrackDistance) {
		if (distance < minDistance) {
			return AudienceState.BACK_OFF;
		}
		if (canBeSeen) {
			return AudienceState.FROZEN;
		}
		if (distance > lostTrackDistance) {
			return AudienceState.APPROACHING;
		}
		if (wasApproaching && distance > resumeDistance) {
			// Mid-approach and not yet inside the resume ring: keep going rather than
			// stopping the moment the entry threshold is no longer met.
			return AudienceState.APPROACHING;
		}
		return AudienceState.IDLE;
	}
}
