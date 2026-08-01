package com.entropymod.entropy.behavior;

import com.entropymod.entropy.HookEffectBehavior;

/**
 * Fragile (BAD / GEAR) -- all damage taken increased by 25%.
 *
 * <p>Exact counterpart to Iron Skin, sharing the same mixin. Deliberately +25% against Iron Skin's -20% so the pair is symmetric in effect (0.8 and 1.25 are reciprocals) rather than symmetric in the number printed. Counterplay: better armour.
 *
 * <p>apply()/remove() are empty by design -- see {@link HookEffectBehavior}.
 * The multiplier below is read by the mixin via EffectHooks.
 */
public final class FragileBehavior extends HookEffectBehavior {

	public static final String ID = "fragile";

	/** Incoming damage multiplied by 1.25, i.e. 25% more. */
	public static final float MULTIPLIER = 1.25f;
}
