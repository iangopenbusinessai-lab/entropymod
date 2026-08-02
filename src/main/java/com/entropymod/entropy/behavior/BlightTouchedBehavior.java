package com.entropymod.entropy.behavior;

import com.entropymod.entropy.HookEffectBehavior;

/**
 * Blight Touched (BAD / UTILITY) -- crops within
 * {@value GreenThumbBehavior#RADIUS} blocks of you grow slower.
 *
 * <p>Exact inverse of {@link GreenThumbBehavior}, on the same
 * {@code CropBlock.getGrowthSpeed} hook -- see that class for why
 * {@code getGrowthSpeed} is the correct target rather than {@code randomTick},
 * and for the list of plants this does and does not cover.
 *
 * <p><b>The multiplier must never reach zero.</b> Vanilla's roll is
 * {@code random.nextInt((int)(25.0F / speed) + 1)}; a speed of 0 makes that
 * divisor {@code Infinity}, {@code (int)Infinity} is {@code Integer.MAX_VALUE},
 * and {@code nextInt(Integer.MAX_VALUE + 1)} overflows to
 * {@code nextInt(Integer.MIN_VALUE)}, which throws. At 0.5 against a base speed
 * of at least 1.0 there is no way to approach that, and
 * {@link com.entropymod.entropy.EffectHooks#cropGrowthMultiplier} additionally
 * floors the result -- but any future growth penalty must respect the same rule.
 *
 * <p>Counterplay: bone meal ignores this entirely (it goes through
 * {@code performBonemeal}, not the growth-speed roll), and simply walking away
 * from the field removes the penalty, since the radius check is proximity-based.
 *
 * <p>apply()/remove() are empty by design -- see {@link HookEffectBehavior}.
 */
public final class BlightTouchedBehavior extends HookEffectBehavior {

	public static final String ID = "blight_touched";

	/** Growth speed multiplied by 0.5, i.e. crops take roughly twice as long. */
	public static final float MULTIPLIER = 0.5f;
}
