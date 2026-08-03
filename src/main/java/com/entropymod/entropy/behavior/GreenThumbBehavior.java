package com.entropymod.entropy.behavior;

import com.entropymod.entropy.HookEffectBehavior;

/**
 * Green Thumb (GOOD / UTILITY) -- crops within {@value #RADIUS} blocks of you
 * reach full maturity in about 90 seconds.
 *
 * <h2>This effect no longer uses the growth-speed multiplier hook</h2>
 *
 * <p>It used to scale {@code CropBlock.getGrowthSpeed}, and that approach has a
 * ceiling no tuning can move: the hook changes only the <em>chance</em> a random
 * tick succeeds, never the <em>rate</em> at which random ticks reach a block,
 * which is fixed at one per 68.27 seconds by chunk mechanics and the
 * {@code random_tick_speed} gamerule. Even with the roll saturated to "always
 * succeeds", wheat's seven stages took 478 seconds. The mechanism now lives in
 * {@code GreenThumbGrowth}, which grants stage advances on a schedule of its own
 * and hits 90 seconds for every covered crop.
 *
 * <p><b>Green Thumb contributes nothing to any growth multiplier any more</b>,
 * and this class deliberately no longer declares a multiplier constant so it
 * cannot be quietly re-wired into a shared hook and double-applied. The shared
 * hook itself has since been deleted as well: Blight Touched was the only other
 * effect on it and has moved to destroying crops the player walks through.
 *
 * <h2>Why the coverage is what it is</h2>
 *
 * <p>The new mechanism covers exactly the same crops the old hook did --
 * {@code CropBlock}, {@code StemBlock} and {@code PitcherCropBlock} were
 * {@code getGrowthSpeed}'s only three callers, and they are the three types
 * {@code GreenThumbGrowth} classifies. So wheat, carrots, potatoes, beetroot and
 * torchflower, plus pumpkin and melon stems, plus the pitcher crop. Changing the
 * mechanism did not change the scope.
 *
 * <h2>Known scope limit, stated rather than hidden</h2>
 *
 * <p>Plants outside those three types are unaffected: sugar cane, cactus, bamboo,
 * saplings, nether wart, cocoa, and sweet berry bushes each have their own
 * unrelated growth logic. "Crops" here means farmland crops and stems, which is
 * the ordinary reading of the word and is what the description says.
 *
 * <p>apply()/remove() are empty by design -- see {@link HookEffectBehavior}. The
 * effect is driven entirely by membership in the acquired set, which both the
 * growth service and the proximity check read.
 */
public final class GreenThumbBehavior extends HookEffectBehavior {

	public static final String ID = "green_thumb";

	/**
	 * Blocks from the player within which crops are affected.
	 *
	 * <p>Used by both halves of the effect and they must agree: the growth
	 * service's radius sweep, and the nearest-player check that re-verifies each
	 * crop at the moment of its advance
	 * ({@link com.entropymod.entropy.EffectHooks#hasGreenThumbNearby}). Nothing
	 * else reads it -- Blight Touched used to share it and no longer has a radius
	 * at all.
	 */
	public static final double RADIUS = 8.0;
}
