package com.entropymod.entropy.behavior;

import com.entropymod.entropy.EffectBehavior;
import com.entropymod.entropy.EffectContext;

/**
 * Heavy Boots (BAD / MOVEMENT / 3600t) -- "15% slowness".
 *
 * <p>STUB -- announces only, touches no game state. This class is the single
 * place the real implementation goes; nothing outside it needs to change.
 * Real version: the inverse of Sure Footing -- a negative movement-speed attribute modifier, not the Slowness mob effect, so the two stay symmetric and cancel cleanly.
 */
public final class HeavyBootsBehavior implements EffectBehavior {

	@Override
	public void apply(EffectContext ctx) {
		ctx.announceApply();
	}

	@Override
	public void remove(EffectContext ctx) {
		ctx.announceRemove();
	}
}
