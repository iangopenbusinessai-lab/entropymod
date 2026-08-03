package com.entropymod.entropy.behavior;

import com.entropymod.entropy.EffectBehavior;
import com.entropymod.entropy.EffectContext;
import com.entropymod.entropy.EntropyManager;
import com.entropymod.entropy.MovementScramble;

/**
 * Randomized Movement Controls (BAD / MOVEMENT, Tier 2) -- your four movement
 * keys are permanently rewired to a random permutation.
 *
 * <p>The permutation is rolled <b>once, when the effect is acquired</b>, and is
 * then fixed for the rest of the run. That is the whole design: a scramble that
 * re-rolled would be unlearnable, and learning the new layout is the counterplay.
 *
 * <p><b>This is the first effect with a non-empty {@code apply}</b> that is not
 * an attribute. It cannot extend {@link HookEffectBehavior}, whose {@code apply}
 * is final and empty, because it has run state to establish. It is still
 * idempotent, which is mandatory -- {@code apply} runs again on every respawn,
 * rejoin and dimension change, and {@link EntropyManager#assignMoveScrambleIfAbsent}
 * assigns only when nothing is assigned yet.
 *
 * <p><b>Client-side effect, but not client-side state.</b> The permutation is
 * generated and persisted server-side (it belongs to the run, and only the
 * server has the {@code SavedData}); the client is told it via
 * {@code ClientEffectsPayload} and applies it in {@code KeyboardInputMixin}.
 * There is no server-side movement change at all -- the client sends the already
 * permuted input, so the server sees an ordinary player walking in the direction
 * they appear to walk.
 *
 * <p>Scope: forward, back, left and right only. Jump, sneak and sprint are
 * untouched.
 */
public final class RandomizedControlsBehavior implements EffectBehavior {

	public static final String ID = "randomized_controls";

	@Override
	public void apply(EffectContext ctx) {
		// Idempotent: assigns on the first call of the run and is a no-op on every
		// respawn/rejoin thereafter. See MovementScramble for why the identity
		// permutation is excluded from the roll.
		EntropyManager manager = EntropyManager.get(ctx.server());
		if (manager.assignMoveScrambleIfAbsent()) {
			ctx.tell("[Entropy] Your movement keys have been rewired. Good luck.");
		}
	}

	@Override
	public void remove(EffectContext ctx) {
		// Nothing to undo -- effects are permanent, and the scramble is run state
		// rather than per-player state. Deliberately does NOT clear the scramble:
		// re-acquiring must not re-roll it.
	}

	/** The identity string, exposed so the harness can assert it is never the assigned value. */
	public static String identity() {
		return MovementScramble.IDENTITY;
	}
}
