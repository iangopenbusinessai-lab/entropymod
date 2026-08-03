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

	/**
	 * Growth speed multiplied by 26, which saturates vanilla's growth roll: every
	 * random tick a covered crop receives advances it one stage. This is the
	 * <b>fastest growth this hook can produce</b>, and every larger value behaves
	 * identically -- see the derivation below for why, and why the obvious target
	 * of "90 seconds for wheat" is not reachable through this lever at all.
	 *
	 * <h2>Derivation</h2>
	 *
	 * <p>Two independent probabilities decide how fast a crop grows, and only the
	 * second one is ours.
	 *
	 * <p><b>1. How often a block is offered a growth roll.</b>
	 * {@code ServerLevel.tickChunk} draws {@code random_tick_speed} positions per
	 * game tick from each 16x16x16 section, uniformly over its 4096 blocks
	 * (verified in bytecode; the gamerule's registered default is 3). So one
	 * particular crop block expects {@code 4096 / 3 = 1365.33} game ticks --
	 * <b>68.27 seconds</b> -- between random ticks. Nothing this mod does changes
	 * that number.
	 *
	 * <p><b>2. Whether a growth roll succeeds.</b> Vanilla rolls
	 * {@code random.nextInt((int)(25.0F / speed) + 1) == 0}, i.e. it succeeds with
	 * probability {@code 1 / ((int)(25 / speed) + 1)}. Scaling {@code speed} is the
	 * whole of this effect.
	 *
	 * <p>Because of the integer truncation, that denominator bottoms out at
	 * <b>1</b> as soon as {@code speed > 25}: {@code (int)(25 / 25.1) == 0}, so the
	 * roll becomes {@code nextInt(1) == 0}, which is always true. At that point the
	 * crop grows on <em>every</em> random tick and no further increase does
	 * anything -- the same saturation shape as Exposed's 1.25 detection multiplier.
	 *
	 * <p><b>Why 26 specifically.</b> The base speed vanilla computes is roughly 5.0
	 * for a fully-planted hydrated field and 10.0 for row planting, but its floor
	 * over real farmland configurations is 1.0 (dry farmland with no other farmland
	 * in the 3x3, halved for crops on both axes). Saturating even that case needs
	 * {@code 1.0 * M > 25}, so 26 is the smallest whole multiplier that guarantees
	 * one stage per random tick for <em>every</em> layout, wet or dry, packed or
	 * spaced. 25 is not enough: it lands exactly on 25.0, and {@code (int)(25/25)}
	 * is 1, which halves the rate.
	 *
	 * <h2>The 90-second target is not reachable here, and this is why</h2>
	 *
	 * <p>Wheat needs 7 stage advances (age 0 to {@code MAX_AGE} 7). Even at one
	 * stage per random tick the expected time is
	 * {@code 7 * 68.27s = 477.9s} -- about eight minutes. 90 seconds (1800 ticks)
	 * would need a stage every 257 ticks, which is 5.3x more often than random
	 * ticks arrive at all. <b>No value of this constant can reach it</b>, because
	 * the binding constraint is the random-tick rate rather than the growth
	 * chance. Reaching 90s would take a second mechanism -- extra random ticks
	 * near the player, or a direct growth tick -- which is a deliberately larger
	 * change than a multiplier tune and is not in this class's remit. For
	 * reference, {@code /gamerule random_tick_speed 16} lands wheat at 89.6s with
	 * this multiplier.
	 *
	 * <h2>Expected time to maturity at this multiplier</h2>
	 *
	 * <p>Mean, not guaranteed -- this is a geometric distribution, so individual
	 * plants vary widely around these numbers. Stage counts differ per crop, so a
	 * single multiplier deliberately does <em>not</em> equalise them.
	 *
	 * <table border="1">
	 * <caption>Expected time from freshly planted to mature, random_tick_speed 3</caption>
	 * <tr><th>Crop</th><th>Stages</th><th>Extra gate</th><th>Green Thumb</th><th>Vanilla (packed field)</th></tr>
	 * <tr><td>Wheat / carrots / potatoes</td><td>7</td><td>--</td><td>7m 58s</td><td>47m 47s</td></tr>
	 * <tr><td>Beetroot</td><td>3</td><td>2/3 per tick</td><td>5m 07s</td><td>30m 43s</td></tr>
	 * <tr><td>Torchflower</td><td>2</td><td>2/3 per tick</td><td>3m 25s</td><td>20m 29s</td></tr>
	 * <tr><td>Pumpkin / melon stem</td><td>7 + 1 to set fruit</td><td>--</td><td>9m 06s</td><td>54m 37s</td></tr>
	 * <tr><td>Pitcher crop</td><td>4</td><td>--</td><td>4m 33s</td><td>27m 18s</td></tr>
	 * </table>
	 *
	 * <p>Beetroot and torchflower override {@code randomTick} with an extra
	 * {@code nextInt(3) != 0} gate before delegating, so they need 1.5 random ticks
	 * per stage on average; that is already folded into the numbers above. The stem
	 * row includes one further successful roll after age 7, which is when the fruit
	 * is placed -- and that roll is wasted if no adjacent block can host it, so a
	 * cramped spot runs longer.
	 *
	 * <h2>Overflow safety at the larger value</h2>
	 *
	 * <p>The {@code Infinity} hazard runs the other way and a bigger multiplier
	 * moves away from it, not toward it: the crash mode is {@code speed} near
	 * <em>zero</em> making {@code (int)(25.0F / speed)} overflow to
	 * {@code Integer.MAX_VALUE}. A larger speed drives that quotient toward 0, and
	 * {@code (int)(25/speed) + 1} is bounded below by 1 for every positive speed,
	 * which {@code nextInt} accepts. Checked explicitly rather than assumed, since
	 * the guard was written against the small end.
	 */
	public static final float MULTIPLIER = 26.0f;
}
