package com.entropymod.entropy;

/**
 * Where a run is in its lifecycle.
 *
 * <p><b>Only two states exist here, and the omission is deliberate.</b> The
 * recorded architecture is {@code NOT_STARTED -> IN_PROGRESS -> ENDED}, but the
 * {@code ENDED} half -- the end screen, dragon-death win detection, Become
 * Hardcore's death-triggered loss, and the death counter -- is explicitly not
 * built. Adding an {@code ENDED} constant "ready for later" would invite code to
 * branch on a state nothing can ever produce, and would make the build status of
 * that half ambiguous to read. See CLAUDE.md's run-lifecycle section for what is
 * still outstanding.
 *
 * <p>Note {@code EntropyManager.gameOver} is <b>not</b> this and must not be
 * confused with it: that flag is the pre-existing entropy-cap stop, it predates
 * this enum, and folding the two together is part of the unbuilt {@code ENDED}
 * work rather than something to do opportunistically.
 *
 * <p>Persisted <b>by name</b> in {@code EntropyManager}'s codec, the same rule
 * {@code EffectPhase} follows on the wire: reordering or inserting a constant
 * must not silently change what a saved run means.
 */
public enum RunState {

	/**
	 * A world exists but the player has not started the run. The entropy loop is
	 * hard-gated off -- no interval counting, no picks.
	 */
	NOT_STARTED,

	/** The run is live. The loop behaves exactly as it always has. */
	IN_PROGRESS;

	/**
	 * Parses a persisted or wire name, falling back to {@code NOT_STARTED} for
	 * anything unrecognised.
	 *
	 * <p>Degrading rather than throwing matters in both places this is used: a
	 * hand-edited or newer save must not make the world unloadable, and a wire
	 * value must not throw inside a network handler. {@code NOT_STARTED} is the
	 * safe fallback specifically because it stops the loop rather than starting
	 * one the player never asked for.
	 */
	public static RunState parse(String name) {
		if (name != null) {
			for (RunState state : values()) {
				if (state.name().equals(name)) {
					return state;
				}
			}
		}
		return NOT_STARTED;
	}
}
