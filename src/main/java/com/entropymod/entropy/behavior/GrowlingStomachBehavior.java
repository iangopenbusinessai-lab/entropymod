package com.entropymod.entropy.behavior;

import com.entropymod.entropy.HookEffectBehavior;

/**
 * Growling Stomach (BAD / SURVIVAL) -- hunger drains 25% faster.
 *
 * <p>Exact inverse of Iron Stomach, sharing the same mixin. Counterplay: carry more food.
 *
 * <p>apply()/remove() are empty by design -- see {@link HookEffectBehavior}.
 * The multiplier below is read by the mixin via EffectHooks.
 */
public final class GrowlingStomachBehavior extends HookEffectBehavior {

	public static final String ID = "growling_stomach";

	/** Exhaustion accumulates at 125% of normal, so hunger drains 25% faster. */
	public static final float MULTIPLIER = 1.25f;
}
