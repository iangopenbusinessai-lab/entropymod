package com.entropymod.entropy.behavior;

import com.entropymod.entropy.HookEffectBehavior;

/**
 * Clumsy Digger (BAD / TOOL) -- your tools occasionally take extra wear.
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
 * <p>The extra damage is a flat {@value #EXTRA_DAMAGE} on top of whatever vanilla
 * asked for, applied on a {@value #CHANCE} roll, rather than a multiplier --
 * durability costs are usually 1, and a multiplier would round to either no
 * change or a doubling with nothing in between.
 *
 * <p>apply()/remove() are empty by design -- see {@link HookEffectBehavior}.
 */
public final class ClumsyDiggerBehavior extends HookEffectBehavior {

	public static final String ID = "clumsy_digger";

	/**
	 * Chance per durability-consuming action. Raised from 0.04 to match Leaky
	 * Pockets, the other "small chance per action" curse.
	 *
	 * <p><b>Read the magnitude note below before tuning this again</b> -- the
	 * chance is not the binding constraint on whether this effect is noticeable,
	 * and raising it further has sharply diminishing returns.
	 */
	public static final float CHANCE = 0.07f;

	/**
	 * Extra durability points consumed when the roll succeeds.
	 *
	 * <p><b>This, not {@link #CHANCE}, is the lever that decides whether the effect
	 * can be felt.</b> The two multiply into a single number -- expected durability
	 * spent per action is {@code 1 + CHANCE * EXTRA_DAMAGE} -- so the effect's whole
	 * observable magnitude is the percentage by which a tool's lifetime shortens:
	 *
	 * <ul>
	 *   <li>0.04 x 1 (the original): an iron pickaxe lasts 240 blocks instead of
	 *       250. <b>4% shorter.</b></li>
	 *   <li>0.07 x 1 (now): 234 blocks instead of 250. <b>6.5% shorter.</b></li>
	 *   <li>1.00 x 1 (the ceiling of chance alone): 125 blocks. Even a
	 *       <em>guaranteed</em> trigger only doubles wear.</li>
	 * </ul>
	 *
	 * <p>So the chance can never carry this effect past "2x wear at most", and at
	 * any plausible value it lands well inside the range a player cannot detect
	 * without counting blocks -- CLAUDE.md's failure mode 3. If the retune to 0.07
	 * still reads as nothing in play, <b>raise this rather than the chance</b>:
	 * {@code 0.07 x 4} costs 22% of a tool's life, which is roughly one iron
	 * pickaxe in five, and is the smallest change that produces something a player
	 * can actually notice.
	 */
	public static final int EXTRA_DAMAGE = 1;
}
