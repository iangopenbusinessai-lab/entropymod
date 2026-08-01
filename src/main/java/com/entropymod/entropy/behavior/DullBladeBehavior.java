package com.entropymod.entropy.behavior;

import com.entropymod.entropy.EffectBehavior;
import com.entropymod.entropy.EffectContext;

/**
 * Dull Blade (BAD / TOOL / 3600t) -- "Mining speed -20%".
 *
 * <p>STUB -- announces only, touches no game state. This class is the single
 * place the real implementation goes; nothing outside it needs to change.
 * Real version: Mining Fatigue is too coarse (it is a 30% step); prefer a mining-speed attribute modifier or a break-speed event so the -20% is exact.
 */
public final class DullBladeBehavior implements EffectBehavior {

	@Override
	public void apply(EffectContext ctx) {
		ctx.announceApply();
	}

	@Override
	public void remove(EffectContext ctx) {
		ctx.announceRemove();
	}
}
