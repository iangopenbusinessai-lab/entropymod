package com.entropymod.entropy.behavior;

import com.entropymod.entropy.EffectBehavior;
import com.entropymod.entropy.EffectContext;

/**
 * Sure Footing (GOOD / MOVEMENT / 3600t) -- "+10% movement speed for 3 min".
 *
 * <p>STUB -- announces only, touches no game state. This class is the single
 * place the real implementation goes; nothing outside it needs to change.
 * Real version: a movement-speed attribute modifier with a stable UUID, added in apply and removed by that same UUID in remove.
 */
public final class SureFootingBehavior implements EffectBehavior {

	@Override
	public void apply(EffectContext ctx) {
		ctx.announceApply();
	}

	@Override
	public void remove(EffectContext ctx) {
		ctx.announceRemove();
	}
}
