package com.entropymod.entropy.behavior;

import com.entropymod.entropy.EffectBehavior;
import com.entropymod.entropy.EffectContext;

/**
 * Iron Stomach (GOOD / SURVIVAL / 3600t) -- "Hunger drains 25% slower for 3 min".
 *
 * <p>STUB -- announces only, touches no game state. This class is the single
 * place the real implementation goes; nothing outside it needs to change.
 * Real version: hunger rate is not an attribute, so this needs either a FoodData mixin or a per-tick exhaustion refund. remove must restore the normal rate exactly.
 */
public final class IronStomachBehavior implements EffectBehavior {

	@Override
	public void apply(EffectContext ctx) {
		ctx.announceApply();
	}

	@Override
	public void remove(EffectContext ctx) {
		ctx.announceRemove();
	}
}
