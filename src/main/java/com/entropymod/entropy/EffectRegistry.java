package com.entropymod.entropy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Central list of all effect definitions. This is the ONLY place new effects
 * need to be added -- the timer, GUI, and networking code never change when you
 * add effect #47.
 *
 * <p><b>Current content: the first real Tier 1 batch — 20 effects, 10 GOOD and
 * 10 BAD, all permanent, all entropy 0-25.</b> This replaced the original
 * 11-effect placeholder set. It is a baseline, not the finished game: Tiers 2-4
 * and the odd/signature effects still slot in the same way, and the entropy
 * ranges here deliberately all overlap because there is only one tier so far.
 *
 * <p>Every effect is permanent — see {@link EffectDefinition} for why there is
 * no duration field.
 */
public final class EffectRegistry {
	private static final List<EffectDefinition> ALL = new ArrayList<>();

	static {
		// =================================================================
		// TIER 1 GOOD (Entropy 0-25) -- 10 effects
		// =================================================================

		// --- attribute-driven ---
		register("sure_footing", "Sure Footing", "+15% movement speed",
				EffectCategory.MOVEMENT, EffectPhase.GOOD, 0, 25, true);
		register("thick_hide", "Thick Hide", "+2 hearts of maximum health",
				EffectCategory.SURVIVAL, EffectPhase.GOOD, 0, 25, true);
		register("steady_hands", "Steady Hands", "+2 attack damage",
				EffectCategory.COMBAT, EffectPhase.GOOD, 0, 25, true);
		register("lucky_find", "Lucky Find", "+2 Luck",
				EffectCategory.UTILITY, EffectPhase.GOOD, 0, 25, true);
		register("swift_strikes", "Swift Strikes", "+20% attack speed",
				EffectCategory.COMBAT, EffectPhase.GOOD, 0, 25, true);
		register("featherlight", "Featherlight", "Fall damage reduced by 40%",
				EffectCategory.MOVEMENT, EffectPhase.GOOD, 0, 25, true);
		register("efficient_miner", "Efficient Miner", "+15% mining speed",
				EffectCategory.TOOL, EffectPhase.GOOD, 0, 25, true);

		// --- hook-driven ---
		register("iron_stomach", "Iron Stomach", "Hunger drains 25% slower",
				EffectCategory.SURVIVAL, EffectPhase.GOOD, 0, 25, true);
		register("iron_skin", "Iron Skin", "All damage taken reduced by 20%",
				EffectCategory.GEAR, EffectPhase.GOOD, 0, 25, true);
		register("fast_learner", "Fast Learner", "+50% experience gained",
				EffectCategory.META, EffectPhase.GOOD, 0, 25, true);

		// =================================================================
		// TIER 1 BAD (Entropy 0-25) -- 10 effects
		//
		// Every one is counterplay = true: none can kill you on its own, and
		// each has an in-game answer (armour, food, care around ledges).
		// Required below entropy 40 -- see CLAUDE.md Part 2.
		// =================================================================

		// --- attribute-driven ---
		register("heavy_boots", "Heavy Boots", "-15% movement speed",
				EffectCategory.MOVEMENT, EffectPhase.BAD, 0, 25, true);
		register("brittle_bones", "Brittle Bones", "-2 hearts of maximum health",
				EffectCategory.SURVIVAL, EffectPhase.BAD, 0, 25, true);
		register("weak_grip", "Weak Grip", "-2 attack damage",
				EffectCategory.COMBAT, EffectPhase.BAD, 0, 25, true);
		register("unlucky", "Unlucky", "-2 Luck",
				EffectCategory.UTILITY, EffectPhase.BAD, 0, 25, true);
		register("sluggish_strikes", "Sluggish Strikes", "-20% attack speed",
				EffectCategory.COMBAT, EffectPhase.BAD, 0, 25, true);
		register("glass_jaw", "Glass Jaw", "Fall damage increased by 40%",
				EffectCategory.MOVEMENT, EffectPhase.BAD, 0, 25, true);
		register("dull_blade", "Dull Blade", "-20% mining speed",
				EffectCategory.TOOL, EffectPhase.BAD, 0, 25, true);

		// --- hook-driven ---
		register("growling_stomach", "Growling Stomach", "Hunger drains 25% faster",
				EffectCategory.SURVIVAL, EffectPhase.BAD, 0, 25, true);
		register("fragile", "Fragile", "All damage taken increased by 25%",
				EffectCategory.GEAR, EffectPhase.BAD, 0, 25, true);
		register("slow_learner", "Slow Learner", "-50% experience gained",
				EffectCategory.META, EffectPhase.BAD, 0, 25, true);

		// TODO: Tiers 2-4 + odd/signature effects go here, same pattern.
		// See entropy-modpack-effects.md for the full list to port over.
	}

	private static void register(String id, String displayName, String description,
								  EffectCategory category, EffectPhase phase,
								  int minEntropy, int maxEntropy, boolean counterplay) {
		ALL.add(new EffectDefinition(id, displayName, description, category, phase,
				minEntropy, maxEntropy, counterplay));
	}

	/** Every registered effect, in registration order. Unmodifiable. */
	public static List<EffectDefinition> all() {
		return Collections.unmodifiableList(ALL);
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
	 * @param choices          up to 3 effects; fewer if the pool is small, empty only
	 *                         if no effect of this phase is eligible at this entropy at all
	 * @param repeatFallback   true if the no-repeat rule had to be abandoned to produce
	 *                         any choices -- the player is about to be re-offered
	 *                         something already taken
	 * @param categoryFallback true if anti-stacking had to be abandoned as well
	 */
	public record RollResult(List<EffectDefinition> choices,
							 boolean repeatFallback,
							 boolean categoryFallback) {}

	/**
	 * Rolls up to 3 choices, applying both exclusion rules in priority order.
	 *
	 * <p><b>1. No-repeat (hard rule).</b> Anything in {@code alreadyPicked} is
	 * removed from the pool. Because effects are permanent, being offered a effect
	 * you already have is meaningless — there is nothing to refresh. This rule is
	 * absolute and is applied first.
	 *
	 * <p><b>2. Anti-stacking (soft rule, lower priority).</b> Effects whose
	 * category is already occupied are removed as well. If that leaves nothing,
	 * anti-stacking is dropped and the no-repeat-filtered pool is used, flagged via
	 * {@link RollResult#categoryFallback()}. Dropping this one first is deliberate:
	 * a second MOVEMENT effect is a real, playable outcome; a duplicate of an effect
	 * you already own is not.
	 *
	 * <p><b>3. Repeat fallback (last resort).</b> Only if no-repeat itself empties
	 * the pool -- every eligible effect of this phase already taken -- is the full
	 * eligible pool used, flagged via {@link RollResult#repeatFallback()}. With only
	 * 10 effects per phase this is genuinely reachable today, after 10 picks of one
	 * phase, so it is a tested path rather than a theoretical one. It exists so the
	 * loop cannot strand; it is not a considered design answer.
	 *
	 * <p>PLACEHOLDER POLICY, same status as the anti-stacking fallback: CLAUDE.md
	 * Open Question 6 is still open. More content is the real fix.
	 *
	 * <p>A partial result (1 or 2 choices) is not a fallback and is not flagged --
	 * it is the honest answer when the pool is genuinely that small.
	 *
	 * @param alreadyPicked      effect ids taken at any point this run (no-repeat)
	 * @param excludedCategories categories that already have an effect (anti-stacking)
	 */
	public static RollResult roll(EffectPhase phase, int entropy, Random random,
								  Set<String> alreadyPicked,
								  Set<EffectCategory> excludedCategories) {
		List<EffectDefinition> eligible = eligible(phase, entropy);

		List<EffectDefinition> unpicked = eligible.stream()
				.filter(e -> !alreadyPicked.contains(e.id()))
				.collect(Collectors.toList());

		boolean repeatFallback = unpicked.isEmpty() && !eligible.isEmpty();
		List<EffectDefinition> pool = repeatFallback ? eligible : unpicked;

		List<EffectDefinition> categoryFiltered = pool.stream()
				.filter(e -> !excludedCategories.contains(e.category()))
				.collect(Collectors.toList());

		boolean categoryFallback = categoryFiltered.isEmpty() && !pool.isEmpty();
		List<EffectDefinition> source = new ArrayList<>(categoryFallback ? pool : categoryFiltered);

		Collections.shuffle(source, random);
		return new RollResult(
				source.stream().limit(3).collect(Collectors.toList()),
				repeatFallback,
				categoryFallback);
	}

	public static EffectDefinition byId(String id) {
		return ALL.stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
	}

	private EffectRegistry() {}
}
