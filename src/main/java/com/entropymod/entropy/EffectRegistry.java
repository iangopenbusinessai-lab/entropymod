package com.entropymod.entropy;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Central list of all effect definitions. This is the ONLY place new effects
 * need to be added -- the timer, GUI, and networking code never change when
 * you add effect #47. Currently seeded with the Tier 1 (Entropy 0-25) pool
 * from the design doc as a proof of concept; Tiers 2-4 slot in the same way.
 */
public final class EffectRegistry {
	private static final List<EffectDefinition> ALL = new ArrayList<>();

	static {
		// --- TIER 1 GOOD (Entropy 0-25) ---
		register("sure_footing", "Sure Footing", "+10% movement speed for 3 min",
				EffectCategory.MOVEMENT, EffectPhase.GOOD, 0, 25, true, 3600);
		register("iron_stomach", "Iron Stomach", "Hunger drains 25% slower for 3 min",
				EffectCategory.SURVIVAL, EffectPhase.GOOD, 0, 25, true, 3600);
		register("featherlight", "Featherlight", "No fall damage for 2 min",
				EffectCategory.MOVEMENT, EffectPhase.GOOD, 0, 25, true, 2400);
		register("prospectors_eye", "Prospector's Eye", "Nearby ores glow for 90 sec",
				EffectCategory.UTILITY, EffectPhase.GOOD, 0, 25, true, 1800);
		register("field_repair", "Field Repair", "Free full-durability repair on held item (one-time)",
				EffectCategory.TOOL, EffectPhase.GOOD, 0, 25, true, 0);
		register("night_owl", "Night Owl", "Night vision for 3 min",
				EffectCategory.UTILITY, EffectPhase.GOOD, 0, 25, true, 3600);

		// --- TIER 1 BAD (Entropy 0-25) ---
		register("butterfingers", "Butterfingers", "10% chance to drop held item on use",
				EffectCategory.TOOL, EffectPhase.BAD, 0, 25, true, 3600);
		register("heavy_boots", "Heavy Boots", "15% slowness",
				EffectCategory.MOVEMENT, EffectPhase.BAD, 0, 25, true, 3600);
		register("growling_stomach", "Growling Stomach", "Hunger drains 25% faster",
				EffectCategory.SURVIVAL, EffectPhase.BAD, 0, 25, true, 3600);
		register("foggy_head", "Foggy Head", "Reduced FOV + slight nausea for 90 sec",
				EffectCategory.DEBUFF, EffectPhase.BAD, 0, 25, true, 1800);
		register("dull_blade", "Dull Blade", "Mining speed -20%",
				EffectCategory.TOOL, EffectPhase.BAD, 0, 25, true, 3600);

		// TODO: Tiers 2-4 + odd/signature effects go here, same pattern.
		// See entropy-modpack-effects.md for the full list to port over.
	}

	private static void register(String id, String displayName, String description,
								  EffectCategory category, EffectPhase phase,
								  int minEntropy, int maxEntropy, boolean counterplay, int durationTicks) {
		ALL.add(new EffectDefinition(id, displayName, description, category, phase,
				minEntropy, maxEntropy, counterplay, durationTicks));
	}

	/** Every registered effect, in registration order. Unmodifiable. */
	public static List<EffectDefinition> all() {
		return java.util.Collections.unmodifiableList(ALL);
	}

	/** All effects matching the given phase and eligible at the given entropy value. */
	public static List<EffectDefinition> eligible(EffectPhase phase, int entropy) {
		return ALL.stream()
				.filter(e -> e.phase() == phase && e.eligibleAt(entropy))
				.collect(Collectors.toList());
	}

	/**
	 * The outcome of a roll.
	 *
	 * @param choices          up to 3 effects; may be fewer if the pool is small,
	 *                         and empty only if no effect of this phase is eligible at all
	 * @param categoryFallback true if anti-stacking had to be abandoned to produce
	 *                         any choices at all -- the caller should log this, since
	 *                         it means the player is about to be offered an effect that
	 *                         collides with one already running
	 */
	public record RollResult(List<EffectDefinition> choices, boolean categoryFallback) {}

	/** Picks 3 distinct random eligible effects for the given phase/entropy, ignoring anti-stacking. */
	public static List<EffectDefinition> rollThree(EffectPhase phase, int entropy, Random random) {
		return rollThree(phase, entropy, random, Set.of()).choices();
	}

	/**
	 * Picks up to 3 distinct random eligible effects, excluding any whose category
	 * already has an active effect (CLAUDE.md's anti-stacking rule, exclude-from-pool
	 * form: a conflicting option never even appears in the three cards).
	 *
	 * <p>Partial results are fine and are NOT a fallback -- if only two effects
	 * survive the filter, two is the honest answer and the player sees two cards.
	 * The fallback fires only when the filter leaves <em>nothing</em>, in which case
	 * the unfiltered pool is used and {@link RollResult#categoryFallback()} is set.
	 *
	 * <p>PLACEHOLDER POLICY. CLAUDE.md Open Question 6 ("what happens when the world
	 * runs out of legal effects") is still open; allowing a collision is simply the
	 * least-bad option that never strands the loop. The alternatives on the table --
	 * extending the incumbent's duration, force-expiring the oldest active effect,
	 * or skipping the interval entirely -- all remain live. This is a shape to
	 * replace, not a decision.
	 *
	 * @param excludedCategories categories that already have an active effect
	 */
	public static RollResult rollThree(EffectPhase phase, int entropy, Random random,
									   Set<EffectCategory> excludedCategories) {
		List<EffectDefinition> pool = eligible(phase, entropy);
		List<EffectDefinition> filtered = pool.stream()
				.filter(e -> !excludedCategories.contains(e.category()))
				.collect(Collectors.toList());

		boolean fallback = filtered.isEmpty() && !pool.isEmpty();
		List<EffectDefinition> source = new ArrayList<>(fallback ? pool : filtered);
		java.util.Collections.shuffle(source, random);
		return new RollResult(source.stream().limit(3).collect(Collectors.toList()), fallback);
	}

	public static EffectDefinition byId(String id) {
		return ALL.stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
	}

	private EffectRegistry() {}
}
