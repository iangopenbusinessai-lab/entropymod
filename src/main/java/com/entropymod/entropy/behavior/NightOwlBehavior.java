package com.entropymod.entropy.behavior;

import com.entropymod.entropy.EffectBehavior;
import com.entropymod.entropy.EffectContext;

/**
 * Night Owl (GOOD / UTILITY / 3600t) -- "Night vision for 3 min".
 *
 * <p>STUB -- announces only, touches no game state. This class is the single
 * place the real implementation goes; nothing outside it needs to change.
 * Real version: the one Tier 1 effect that maps cleanly onto a vanilla MobEffectInstance. remove must clear it rather than let it run out, or the durations desync.
 */
public final class NightOwlBehavior implements EffectBehavior {

	@Override
	public void apply(EffectContext ctx) {
		ctx.announceApply();
	}

	@Override
	public void remove(EffectContext ctx) {
		ctx.announceRemove();
	}
}
