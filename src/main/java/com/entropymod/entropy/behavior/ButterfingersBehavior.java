package com.entropymod.entropy.behavior;

import com.entropymod.entropy.EffectBehavior;
import com.entropymod.entropy.EffectContext;

/**
 * Butterfingers (BAD / TOOL / 3600t) -- "10% chance to drop held item on use".
 *
 * <p>STUB -- announces only, touches no game state. This class is the single
 * place the real implementation goes; nothing outside it needs to change.
 * Real version: a use-item event hook that rolls 10% and drops the stack. Needs a flag the hook can read, since the hook itself outlives the effect.
 */
public final class ButterfingersBehavior implements EffectBehavior {

	@Override
	public void apply(EffectContext ctx) {
		ctx.announceApply();
	}

	@Override
	public void remove(EffectContext ctx) {
		ctx.announceRemove();
	}
}
