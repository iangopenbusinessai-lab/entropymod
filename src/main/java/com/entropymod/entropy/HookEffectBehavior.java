package com.entropymod.entropy;

/**
 * Base for effects whose behavior lives in a mixin rather than in an attribute.
 *
 * <p>Six of the twenty Tier 1 effects work this way: hunger rate, incoming
 * damage, and experience gain have no vanilla attribute to hang a modifier on,
 * so each is implemented as a small mixin that scales a value at the point it is
 * computed.
 *
 * <p><b>These have nothing to apply.</b> The mixin reads
 * {@link EffectHooks}, which reads the run's {@link AcquiredEffects} set — so
 * "having the effect" and "being in the acquired set" are the same fact, with no
 * second copy of the state to keep in sync. That makes {@code apply} and
 * {@code remove} genuinely empty, and makes idempotency and respawn/rejoin
 * survival automatic rather than something each effect has to get right.
 *
 * <p>The subclass exists to hold the effect's id and its multiplier constant
 * next to its definition, and to keep the "one class per effect" rule true so
 * these are found in the same place as every other effect.
 */
public abstract class HookEffectBehavior implements EffectBehavior {

	@Override
	public final void apply(EffectContext ctx) {
		// Intentionally empty. Membership in AcquiredEffects IS the effect;
		// see the class javadoc. Final so a subclass can't quietly introduce
		// per-player state that would then need its own respawn handling.
	}

	@Override
	public final void remove(EffectContext ctx) {
		// Intentionally empty -- same reason.
	}
}
