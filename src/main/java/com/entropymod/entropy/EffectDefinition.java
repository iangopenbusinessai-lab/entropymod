package com.entropymod.entropy;

/**
 * A single effect definition. This is pure data -- the picker, GUI, and timer
 * never need to know what an effect *does*, only its metadata. Actual behavior
 * lives in an {@link EffectBehavior}, one class per effect, keyed by id in
 * {@link EffectBehaviors}.
 *
 * <p><b>All effects are permanent.</b> There is deliberately no duration field.
 * Once picked, an effect lasts for the rest of the run; the only thing that ever
 * removes one is the run ending. This replaced an earlier {@code durationTicks}
 * int whose {@code 0} / {@code -1} / {@code >0} encoding meant three different
 * lifetimes -- see CLAUDE.md for why that was retired rather than kept.
 *
 * <p><b>If temporary effects ever come back, they must not come back as a magic
 * number.</b> Add an explicit {@code sealed interface EffectDuration} with
 * {@code Permanent} and {@code Ticks(int)} cases so the compiler forces every
 * call site to handle both. The whole point of the removal was that an int field
 * cannot tell you which of its meanings is in play at a glance.
 *
 * @param id            unique string id, e.g. "sure_footing"
 * @param displayName   flavor name shown in the GUI, e.g. "Sure Footing"
 * @param description   short player-facing description, e.g. "+15% movement speed"
 * @param category      used for the anti-stacking rule (max 1 active per category)
 * @param phase         GOOD or BAD
 * @param minEntropy    lowest entropy value this effect is eligible to appear at (inclusive)
 * @param maxEntropy    highest entropy value this effect is eligible to appear at (inclusive)
 * @param counterplay   true if the player has some meaningful way to mitigate/survive this
 *                      effect. Bad effects below entropy 40 MUST be counterplay = true.
 */
public record EffectDefinition(
		String id,
		String displayName,
		String description,
		EffectCategory category,
		EffectPhase phase,
		int minEntropy,
		int maxEntropy,
		boolean counterplay
) {
	public boolean eligibleAt(int entropy) {
		return entropy >= minEntropy && entropy <= maxEntropy;
	}
}
