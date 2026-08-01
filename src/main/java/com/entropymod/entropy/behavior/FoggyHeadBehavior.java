package com.entropymod.entropy.behavior;

import com.entropymod.entropy.EffectBehavior;
import com.entropymod.entropy.EffectContext;

/**
 * Foggy Head (BAD / DEBUFF / 1800t) -- "Reduced FOV + slight nausea for 90 sec".
 *
 * <p>STUB -- announces only, touches no game state. This class is the single
 * place the real implementation goes; nothing outside it needs to change.
 * Real version: FOV is client-side, so this needs a client mixin driven by synced state -- it cannot be done from the server alone. Nausea maps to a vanilla effect; the FOV half does not.
 */
public final class FoggyHeadBehavior implements EffectBehavior {

	@Override
	public void apply(EffectContext ctx) {
		ctx.announceApply();
	}

	@Override
	public void remove(EffectContext ctx) {
		ctx.announceRemove();
	}
}
