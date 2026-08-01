package com.entropymod.entropy.behavior;

import com.entropymod.entropy.HookEffectBehavior;

/**
 * Slow Learner (BAD / META) -- -50% experience gained.
 *
 * <p>Inverse of Fast Learner, same mixin. Counterplay: kill more things.
 *
 * <p>apply()/remove() are empty by design -- see {@link HookEffectBehavior}.
 * The multiplier below is read by the mixin via EffectHooks.
 */
public final class SlowLearnerBehavior extends HookEffectBehavior {

	public static final String ID = "slow_learner";

	/** Experience points multiplied by 0.5. */
	public static final float MULTIPLIER = 0.50f;
}
