package com.entropymod.entropy.behavior;

import com.entropymod.entropy.HookEffectBehavior;

/**
 * Iron Stomach (GOOD / SURVIVAL) -- hunger drains 25% slower.
 *
 * <p>Implemented by scaling exhaustion in a Player.causeFoodExhaustion mixin. Note the mixin targets Player, not FoodData: FoodData.addExhaustion(float) has no reference to the player it belongs to, so there would be no way to ask whose hunger this is.
 *
 * <p>apply()/remove() are empty by design -- see {@link HookEffectBehavior}.
 * The multiplier below is read by the mixin via EffectHooks.
 */
public final class IronStomachBehavior extends HookEffectBehavior {

	public static final String ID = "iron_stomach";

	/** Exhaustion accumulates at 75% of normal, so hunger drains 25% slower. */
	public static final float MULTIPLIER = 0.75f;
}
