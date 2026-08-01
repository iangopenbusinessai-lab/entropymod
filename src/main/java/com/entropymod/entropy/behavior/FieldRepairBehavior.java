package com.entropymod.entropy.behavior;

import com.entropymod.entropy.EffectBehavior;
import com.entropymod.entropy.EffectContext;

/**
 * Field Repair (GOOD / TOOL / duration 0) -- "Free full-durability repair on held item (one-time)".
 *
 * <p>STUB -- announces only, touches no game state. This class is the single
 * place the real implementation goes; nothing outside it needs to change.
 * Duration 0: apply runs once and remove is NEVER called -- see EffectBehavior's contract. All the work happens in apply. Real version: reset the held stack's damage value, handling the empty-hand and non-damageable cases.
 */
public final class FieldRepairBehavior implements EffectBehavior {

	@Override
	public void apply(EffectContext ctx) {
		ctx.announceApply();
	}

	@Override
	public void remove(EffectContext ctx) {
		// Intentionally empty: durationTicks is 0, so this effect is never added
		// to the active list and this method is unreachable in normal operation.
		// Deliberately does NOT announce -- a "has worn off" message here would
		// mean the duration-0 contract had been broken somewhere upstream.
	}
}
