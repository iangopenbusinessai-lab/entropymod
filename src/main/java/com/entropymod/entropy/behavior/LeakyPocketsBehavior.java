package com.entropymod.entropy.behavior;

import com.entropymod.entropy.HookEffectBehavior;

/**
 * Leaky Pockets (BAD / GEAR) -- every jump has a small chance to spill an item
 * out of your inventory.
 *
 * <p>Hooked on {@code ServerPlayer.jumpFromGround}. That is the correct target
 * rather than {@code LivingEntity.jumpFromGround} for two reasons: it is
 * server-side only, so there is no client/server desync about whether the roll
 * happened, and it is the method vanilla itself uses as "one jump occurred" --
 * it is where the JUMP statistic is awarded and where jump exhaustion is applied.
 * It calls {@code super} first, so hooking it does not disturb the physics.
 *
 * <p>One item is dropped, not the whole stack, and the slot is chosen at random
 * from the occupied ones. The dropped item is a normal {@code ItemEntity} with
 * the usual pickup delay, so it is recoverable if you notice -- that is the
 * counterplay, and it is why the effect stays {@code counterplay = true}.
 *
 * <p>apply()/remove() are empty by design -- see {@link HookEffectBehavior}.
 */
public final class LeakyPocketsBehavior extends HookEffectBehavior {

	public static final String ID = "leaky_pockets";

	/**
	 * Chance per jump. Still deliberately low -- jumping is extremely frequent in
	 * normal play, so a high rate stops reading as "occasionally annoying" and
	 * starts reading as unplayable.
	 *
	 * <p>Raised from the original 4% to 7% after play testing: at 4% the mean gap
	 * was 25 jumps, which was rare enough that the effect could be held for a long
	 * stretch without ever being noticed. At 7% the mean gap is ~14 jumps, roughly
	 * every other minute of ordinary movement, which is often enough to read as a
	 * curse and still far from constant.
	 */
	public static final float CHANCE = 0.07f;
}
