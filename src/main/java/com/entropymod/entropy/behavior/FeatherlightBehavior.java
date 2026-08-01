package com.entropymod.entropy.behavior;

import com.entropymod.entropy.EffectBehavior;
import com.entropymod.entropy.EffectContext;

/**
 * Featherlight (GOOD / MOVEMENT / 2400t) -- "No fall damage for 2 min".
 *
 * <p>STUB -- announces only, touches no game state. This class is the single
 * place the real implementation goes; nothing outside it needs to change.
 * Real version: cancel fall damage in a damage event/mixin while active, rather than applying Slow Falling, so the movement feel is unchanged.
 */
public final class FeatherlightBehavior implements EffectBehavior {

	@Override
	public void apply(EffectContext ctx) {
		ctx.announceApply();
	}

	@Override
	public void remove(EffectContext ctx) {
		ctx.announceRemove();
	}
}
