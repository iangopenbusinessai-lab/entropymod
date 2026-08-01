package com.entropymod.entropy;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
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

	/** All effects matching the given phase and eligible at the given entropy value. */
	public static List<EffectDefinition> eligible(EffectPhase phase, int entropy) {
		return ALL.stream()
				.filter(e -> e.phase() == phase && e.eligibleAt(entropy))
				.collect(Collectors.toList());
	}

	/** Picks 3 distinct random eligible effects for the given phase/entropy. */
	public static List<EffectDefinition> rollThree(EffectPhase phase, int entropy, Random random) {
		List<EffectDefinition> pool = new ArrayList<>(eligible(phase, entropy));
		java.util.Collections.shuffle(pool, random);
		return pool.stream().limit(3).collect(Collectors.toList());
	}

	public static EffectDefinition byId(String id) {
		return ALL.stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
	}

	private EffectRegistry() {}
}
