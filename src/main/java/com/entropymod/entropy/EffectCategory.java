package com.entropymod.entropy;

/**
 * Categories used for the "max one active effect per category" anti-stacking rule.
 *
 * <p>The rule is enforced by EXCLUSION, not replacement: while an effect in a
 * category is active, every effect in that category is filtered out of the roll,
 * so a conflicting option never appears among the three cards in the first place.
 * A pick therefore never silently cancels something the player already has.
 * See {@link EffectRegistry#rollThree(EffectPhase, int, java.util.Random, java.util.Set)}
 * and {@link ActiveEffectTracker#occupiedCategories()}.
 *
 * <p>(An earlier draft of this javadoc described the opposite -- replace-on-pick.
 * That alternative was considered and rejected; do not reintroduce it without
 * revisiting the decision, since the two produce very different feel.)
 */
public enum EffectCategory {
	MOVEMENT,
	SURVIVAL,
	TOOL,
	COMBAT,
	GEAR,
	COMPANION,
	UTILITY,
	DEBUFF,
	META
}
