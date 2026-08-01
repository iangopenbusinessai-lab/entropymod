package com.entropymod.entropy;

/**
 * A single effect definition. This is pure data -- the picker, GUI, and timer
 * never need to know what an effect *does*, only its metadata. Actual behavior
 * lives in EffectExecutor (apply/remove logic), keyed by id.
 *
 * @param id            unique string id, e.g. "sure_footing"
 * @param displayName   flavor name shown in the GUI, e.g. "Sure Footing"
 * @param description   short player-facing description, e.g. "+10% movement speed"
 * @param category      used for the anti-stacking rule (max 1 active per category)
 * @param phase         GOOD or BAD
 * @param minEntropy    lowest entropy value this effect is eligible to appear at (inclusive)
 * @param maxEntropy    highest entropy value this effect is eligible to appear at (inclusive)
 * @param counterplay   true if the player has some meaningful way to mitigate/survive this
 *                      effect. Bad effects below entropy 40 MUST be counterplay = true.
 * @param durationTicks how long the effect lasts once applied, in ticks (20 ticks = 1 sec).
 *                      Use -1 for "lasts until the next interval" and 0 for instantaneous/one-time.
 */
public record EffectDefinition(
		String id,
		String displayName,
		String description,
		EffectCategory category,
		EffectPhase phase,
		int minEntropy,
		int maxEntropy,
		boolean counterplay,
		int durationTicks
) {
	public boolean eligibleAt(int entropy) {
		return entropy >= minEntropy && entropy <= maxEntropy;
	}
}
