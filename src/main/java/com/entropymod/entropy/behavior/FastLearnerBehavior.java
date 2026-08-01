package com.entropymod.entropy.behavior;

import com.entropymod.entropy.HookEffectBehavior;

/**
 * Fast Learner (GOOD / META) -- +50% experience gained.
 *
 * <p>Player.giveExperiencePoints mixin. Applies to every XP source that routes through it (mobs, ore, smelting, breeding, bottles).
 *
 * <p>apply()/remove() are empty by design -- see {@link HookEffectBehavior}.
 * The multiplier below is read by the mixin via EffectHooks.
 */
public final class FastLearnerBehavior extends HookEffectBehavior {

	public static final String ID = "fast_learner";

	/** Experience points multiplied by 1.5. */
	public static final float MULTIPLIER = 1.50f;
}
