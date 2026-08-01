package com.entropymod.entropy.behavior;

import com.entropymod.entropy.HookEffectBehavior;

/**
 * Iron Skin (GOOD / GEAR) -- all damage taken reduced by 20%.
 *
 * <p>There is no vanilla attribute for a generic incoming-damage multiplier (ARMOR is not the same thing -- it is type-dependent and capped), so this is a LivingEntity.hurtServer mixin. It scales the damage BEFORE armour and enchantments are applied, so it composes multiplicatively with them rather than replacing them.
 *
 * <p>apply()/remove() are empty by design -- see {@link HookEffectBehavior}.
 * The multiplier below is read by the mixin via EffectHooks.
 */
public final class IronSkinBehavior extends HookEffectBehavior {

	public static final String ID = "iron_skin";

	/** Incoming damage multiplied by 0.8, i.e. 20% less. */
	public static final float MULTIPLIER = 0.80f;
}
