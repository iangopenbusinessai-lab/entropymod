package com.entropymod.entropy;

import com.entropymod.entropy.behavior.DoubleJumpBehavior;

/**
 * Double Jump's charge machine: rising-edge detection plus the landing recharge.
 *
 * <p><b>Deliberately free of Minecraft imports</b>, the same discipline
 * {@code MovementScramble}, {@code TramplePath} and {@code KeybindSnapshot}
 * follow. The two rules that are easy to get wrong -- "recharges on landing" and
 * "never a third jump" -- are invisible in play until they are wrong, so they are
 * driven directly by the harness rather than inspected.
 *
 * <p>See {@link DoubleJumpBehavior} for why the charge is read from
 * {@code onGround()} rather than counted, and for the state table behind the
 * {@code allowed} argument.
 */
public final class DoubleJumpState {

	private int chargesLeft;
	private boolean jumpHeldLastTick;

	/**
	 * Advances one tick and reports whether an air jump should fire now.
	 *
	 * <p><b>The edge is detected before anything else</b>, and the held flag is
	 * updated on every call regardless of the outcome. If it were only updated on
	 * the paths that can jump, holding the key through a refused state (in water,
	 * on a ladder) and then leaving that state would register as a fresh press and
	 * fire a jump the player never asked for.
	 *
	 * @param jumpHeld whether the jump key is down this tick
	 * @param onGround whether the player is on the ground
	 * @param allowed  whether the current state permits an air jump at all --
	 *                 false for flight, elytra, fluids, climbing and riding
	 * @return true if the caller should perform one jump
	 */
	public boolean tick(boolean jumpHeld, boolean onGround, boolean allowed) {
		boolean rising = jumpHeld && !jumpHeldLastTick;
		jumpHeldLastTick = jumpHeld;

		if (onGround) {
			// Recharge, and never jump from here -- the ground jump is vanilla's.
			// Note this also consumes the rising edge that started that jump, which
			// is what forces a release-and-press for the second one instead of
			// letting a held key spend the charge on the very next tick.
			chargesLeft = DoubleJumpBehavior.AIR_JUMPS;
			return false;
		}
		if (!rising || !allowed || chargesLeft <= 0) {
			return false;
		}
		chargesLeft--;
		return true;
	}

	/** Charges remaining before the next landing. */
	public int chargesLeft() {
		return chargesLeft;
	}

	/**
	 * Drops all state. Called on disconnect, so a charge cannot survive into the
	 * next world -- same rule {@code ClientRunState} follows.
	 */
	public void reset() {
		chargesLeft = 0;
		jumpHeldLastTick = false;
	}
}
