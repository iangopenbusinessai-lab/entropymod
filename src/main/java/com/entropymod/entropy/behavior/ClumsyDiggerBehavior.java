package com.entropymod.entropy.behavior;

import com.entropymod.entropy.HookEffectBehavior;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Clumsy Digger (BAD / TOOL) -- your <b>mining tools</b> take extra wear,
 * proportional to how good they are.
 *
 * <p>Hooked on {@code ItemStack.hurtAndBreak(int, ServerLevel, ServerPlayer,
 * Consumer)}. That is the single choke point for durability loss: the other two
 * overloads ({@code (int, LivingEntity, EquipmentSlot)} and
 * {@code (int, LivingEntity, InteractionHand)}) both funnel into it.
 *
 * <p><b>Which is exactly why the item has to be filtered here.</b> "Every source
 * of durability loss" includes armour and the elytra, and this effect used to
 * apply to them -- a netherite chestplate lost 90% of its life and an elytra
 * managed under a minute of flight. That was never the intent; the hook's breadth
 * was mistaken for the effect's scope. See {@link #AFFECTED_ITEMS}.
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

	/**
	 * The items this effect wears out: {@code #minecraft:enchantable/mining}.
	 *
	 * <p><b>A vanilla tag, not a list of item classes.</b> There is no
	 * {@code DiggerItem}/{@code PickaxeItem} hierarchy to test against any more --
	 * tools are data-driven -- and there is no single {@code #minecraft:tools} tag
	 * either. This is the closest vanilla concept to "mining implement", it is one
	 * tag rather than a union of four, and a datapack or another mod adding a
	 * pickaxe to it is covered for free.
	 *
	 * <p><b>In:</b> {@code #axes}, {@code #pickaxes}, {@code #shovels},
	 * {@code #hoes}, and {@code shears}.
	 *
	 * <p><b>Out, and this is the point of the tag:</b> all four armour slots, the
	 * elytra, shield, swords, bow, crossbow, trident, mace, flint and steel, brush,
	 * fishing rod. Compare {@code #minecraft:enchantable/durability}, which is what
	 * "anything with durability" actually means and contains every one of those.
	 *
	 * <p>Shears are the one item here beyond the four digging tools. Kept
	 * deliberately -- shears are a tool and they break blocks. If they should be
	 * excluded, {@code MINING_LOOT_ENCHANTABLE} is the identical tag minus shears
	 * and is a one-word change.
	 */
	public static final TagKey<Item> AFFECTED_ITEMS = ItemTags.MINING_ENCHANTABLE;

	/**
	 * Whether this effect touches a given stack at all.
	 *
	 * <p><b>Note the accessor.</b> {@code ItemStack.is(TagKey)} does not exist in
	 * this version -- the only {@code is} overload takes a
	 * {@code Predicate<Holder<Item>>}, and {@code TagKey} is a plain record rather
	 * than a {@code Predicate}. Tag membership goes through the item's holder:
	 * {@code stack.typeHolder().is(tag)}. ({@code getItemHolder} is
	 * {@code typeHolder} here too.) This is the form vanilla itself uses.
	 */
	public static boolean appliesTo(ItemStack stack) {
		return stack.typeHolder().is(AFFECTED_ITEMS);
	}

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
