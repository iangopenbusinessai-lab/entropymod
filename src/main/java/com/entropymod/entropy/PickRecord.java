package com.entropymod.entropy;

/**
 * One entry in the run's pick history -- what was chosen, when, and at what
 * entropy. Append-only; nothing rewrites a record once made.
 *
 * <p>Stores the effect's name and description as strings rather than an id
 * reference on purpose: history is a record of what the player actually saw, so
 * it must survive an effect being retuned or removed from {@link EffectRegistry}
 * in a later version.
 *
 * @param pickNumber      1-based, in the order picks were made
 * @param phase           GOOD or BAD -- redundant with pickNumber's parity today,
 *                        but stored explicitly so the history stays readable if the
 *                        strict alternation rule is ever relaxed
 * @param effectId        the chosen effect's id
 * @param effectName      display name as shown in the GUI at the time
 * @param effectDescription description as shown in the GUI at the time
 * @param entropyAtPick   entropy BEFORE this pick's +1 was applied -- i.e. the
 *                        number the player was looking at when they chose
 */
public record PickRecord(
		int pickNumber,
		EffectPhase phase,
		String effectId,
		String effectName,
		String effectDescription,
		int entropyAtPick
) {}
