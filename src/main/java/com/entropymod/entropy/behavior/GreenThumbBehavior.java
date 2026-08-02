package com.entropymod.entropy.behavior;

import com.entropymod.entropy.HookEffectBehavior;

/**
 * Green Thumb (GOOD / UTILITY) -- crops within {@value #RADIUS} blocks of you
 * grow faster.
 *
 * <p>Implemented by scaling {@code CropBlock.getGrowthSpeed}, the value vanilla
 * divides into its growth-chance roll:
 * {@code random.nextInt((int)(25.0F / speed) + 1) == 0}. A higher speed means a
 * smaller divisor and therefore a higher chance per random tick.
 *
 * <h2>Why the hook is {@code getGrowthSpeed} and not {@code randomTick}</h2>
 *
 * <p>{@code CropBlock.randomTick} looks like the obvious target and is the worse
 * one. Two subclasses -- {@code BeetrootBlock} and {@code TorchflowerCropBlock}
 * -- override {@code randomTick} (they gate it behind their own extra
 * {@code nextInt} roll before calling {@code super}), which is exactly the
 * subclass-override trap recorded in CLAUDE.md. They happen to delegate, so a
 * {@code randomTick} mixin would have survived, but only by luck.
 *
 * <p>{@code getGrowthSpeed} is strictly better: it is a single static method and
 * it is called by <b>{@code CropBlock}, {@code StemBlock} and
 * {@code PitcherCropBlock}</b>. One hook therefore covers wheat, carrots,
 * potatoes, beetroot and torchflower (all through {@code CropBlock.randomTick}),
 * plus pumpkin and melon stems, plus the pitcher crop -- more coverage than the
 * obvious target, with no override risk at all.
 *
 * <h2>Known scope limit, stated rather than hidden</h2>
 *
 * <p>Plants that do not use {@code getGrowthSpeed} are unaffected: sugar cane,
 * cactus, bamboo, saplings, nether wart, cocoa, and sweet berry bushes each have
 * their own unrelated growth logic. "Crops" here means farmland crops and stems,
 * which is the ordinary reading of the word and is what the description says.
 *
 * <p>apply()/remove() are empty by design -- see {@link HookEffectBehavior}.
 */
public final class GreenThumbBehavior extends HookEffectBehavior {

	public static final String ID = "green_thumb";

	/**
	 * Blocks from the player within which crops are affected. Checked with a
	 * nearest-player query from the block's own position, since a random tick has
	 * no notion of "whose" crop it is -- see
	 * {@link com.entropymod.entropy.EffectHooks#cropGrowthMultiplier}.
	 */
	public static final double RADIUS = 8.0;

	/** Growth speed multiplied by 2.0, i.e. crops mature roughly twice as fast. */
	public static final float MULTIPLIER = 2.0f;
}
