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

	/** Chance per durability-consuming action. */
	public static final float CHANCE = 0.04f;

	/** Extra durability points consumed when the roll succeeds. */
	public static final int EXTRA_DAMAGE = 1;
}
