package com.entropymod.entropy;

/**
 * What the client should draw for Second Guess on a pending pick.
 *
 * <p><b>Three states, not a boolean</b>, because "no button" and "spent button"
 * are different things to a player and a single {@code rerollAvailable} flag
 * cannot tell them apart. A player who never took Second Guess should see
 * nothing; a player who took it and used it should see that it is gone, or the
 * effect looks like it silently stopped existing.
 *
 * <p>This is the shape CLAUDE.md's {@code EffectDuration} note argues for: when
 * one value carries several meanings, make it an enum so every call site is
 * forced by the compiler to handle each case, rather than an int or a boolean
 * whose meaning has to be remembered.
 *
 * <p>Sent on {@code OpenChoicePayload} <b>by name</b> (see {@code EntropyCodecs})
 * so reordering these constants cannot silently change what the client renders.
 */
public enum RerollState {

	/** The player does not have Second Guess. No button, and no space reserved for one. */
	NOT_OWNED,

	/** Owned and unspent, with a pick pending. The button is live. */
	AVAILABLE,

	/** Owned but already spent this run. The button is drawn disabled, for the rest of the run. */
	SPENT
}
