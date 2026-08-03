package com.entropymod.entropy.behavior;

import com.entropymod.entropy.HookEffectBehavior;

/**
 * Clumsy Digger (BAD / TOOL) -- your tools occasionally take extra wear,
 * <b>proportional to how good they are</b>.
 *
 * <p>Hooked on {@code ItemStack.hurtAndBreak(int, ServerLevel, ServerPlayer,
 * Consumer)}. Verified as the single choke point: the other two overloads
 * ({@code (int, LivingEntity, EquipmentSlot)} and
 * {@code (int, LivingEntity, InteractionHand)}) both funnel into it, so one hook
 * covers mining, attacking, shears, flint and steel, armour damage -- every
 * source of durability loss.
 *
 * <p>Its {@code ServerPlayer} parameter is nullable, because durability damage
 * also happens to mob-held equipment. That null is the gate that keeps this
 * scoped to players.
 *
 * <h2>The extra damage scales with the tool, and that is the design</h2>
 *
 * <p>It was a flat +1, which made the effect's whole observable size
 * {@code CHANCE x 1} of a tool's lifetime -- around 6% at any plausible chance,
 * i.e. undetectable. It is now {@link #BASE_EXTRA} scaled by the tool's own
 * maximum durability against a {@value #REFERENCE_DURABILITY}-point reference:
 *
 * <pre>extraDamage = round(BASE_EXTRA * maxDamage / REFERENCE_DURABILITY)</pre>
 *
 * <p><b>This deliberately hits better tools harder, in percentage terms as well
 * as absolute ones.</b> That is the intent, not an artefact to normalise away: a
 * curse that costs a netherite pickaxe 97% of its life and a gold one 32% is a
 * curse that punishes the player for bringing out the good gear. Do not "fix"
 * the non-uniformity.
 *
 * <p><b>The consequence to understand before retuning: blocks survived is
 * asymptotically capped.</b> Since the extra damage grows linearly with
 * durability, the durability cancels out:
 *
 * <pre>blocks = maxDamage / (1 + CHANCE * BASE_EXTRA * maxDamage / REFERENCE)
 *        -> REFERENCE / (CHANCE * BASE_EXTRA)   as durability grows</pre>
 *
 * <p>At the shipped values that ceiling is {@code 200 / (0.08 * 40)} =
 * <b>62.5 blocks</b>, and diamond and netherite both sit within three blocks of
 * it. So {@code BASE_EXTRA} is really a control on "how many blocks does any
 * good tool get", not on a percentage -- see CLAUDE.md's per-tier table.
 *
 * <p>apply()/remove() are empty by design -- see {@link HookEffectBehavior}.
 */
public final class ClumsyDiggerBehavior extends HookEffectBehavior {

	public static final String ID = "clumsy_digger";

	/** Chance per durability-consuming action. */
	public static final float CHANCE = 0.08f;

	/**
	 * Extra durability consumed on a successful roll, for a tool of exactly
	 * {@value #REFERENCE_DURABILITY} durability. Everything else scales from here.
	 *
	 * <p>The most useful way to read this number is through the ceiling above:
	 * raising it lowers how many blocks <em>any</em> decent tool survives, on a
	 * {@code 200 / (CHANCE * BASE_EXTRA)} curve. At 40 that is ~62 blocks; at 20 it
	 * would be ~125; at 80, ~31.
	 */
	public static final int BASE_EXTRA = 40;

	/**
	 * The durability at which {@link #BASE_EXTRA} applies unscaled. Chosen as a
	 * round number near an iron tool (250), so "iron" is roughly the tier the base
	 * figure describes.
	 */
	public static final int REFERENCE_DURABILITY = 200;

	/**
	 * Extra durability damage for a tool with this maximum durability.
	 *
	 * <p>Free of Minecraft types on purpose, so the harness can drive the real
	 * shipped formula against real {@code ToolMaterial} durabilities rather than a
	 * copy of it.
	 *
	 * <p>Returns 0 for a non-damageable item ({@code maxDamage} 0), which costs
	 * nothing either way -- vanilla's {@code processDurabilityChange} discards the
	 * amount for those before it is used. Otherwise floors at 1, so a damageable
	 * item can never be exempt through rounding alone.
	 */
	public static int extraDamageFor(int maxDamage) {
		if (maxDamage <= 0) {
			return 0;
		}
		return Math.max(1, Math.round(BASE_EXTRA * (float) maxDamage / REFERENCE_DURABILITY));
	}

	/**
	 * Expected durability spent per action for a tool of this maximum durability,
	 * i.e. {@code 1 + CHANCE * extraDamageFor(maxDamage)}. The single number the
	 * whole effect's magnitude reduces to.
	 */
	public static double expectedCostPerUse(int maxDamage) {
		return 1.0 + CHANCE * extraDamageFor(maxDamage);
	}

	/** How many durability-consuming actions this tool survives under the curse. */
	public static double blocksSurvived(int maxDamage) {
		return maxDamage / expectedCostPerUse(maxDamage);
	}

	/** The block count every sufficiently durable tool converges on. See the class javadoc. */
	public static double blockCeiling() {
		return REFERENCE_DURABILITY / (CHANCE * (double) BASE_EXTRA);
	}
}
