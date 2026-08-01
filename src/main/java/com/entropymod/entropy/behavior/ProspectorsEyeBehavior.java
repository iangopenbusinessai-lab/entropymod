package com.entropymod.entropy.behavior;

import com.entropymod.entropy.EffectBehavior;
import com.entropymod.entropy.EffectContext;

/**
 * Prospector's Eye (GOOD / UTILITY / 1800t) -- "Nearby ores glow for 90 sec".
 *
 * <p>STUB -- announces only, touches no game state. This class is the single
 * place the real implementation goes; nothing outside it needs to change.
 * Real version: needs a client-side render path (glowing outlines on ore blocks are not a vanilla capability for blocks), so this one is a genuine chunk of work, not a one-liner.
 */
public final class ProspectorsEyeBehavior implements EffectBehavior {

	@Override
	public void apply(EffectContext ctx) {
		ctx.announceApply();
	}

	@Override
	public void remove(EffectContext ctx) {
		ctx.announceRemove();
	}
}
