package com.entropymod.entropy.behavior;

import com.entropymod.entropy.HookEffectBehavior;

/**
 * Frost Walker Innate (GOOD / MOVEMENT) -- water freezes under your feet, with no
 * boots and no enchantment.
 *
 * <p><b>The vanilla implementation is not Java any more.</b> There is no
 * {@code FrostWalkerEnchantment} class in this version — the old
 * {@code onEntityMoved(LivingEntity, Level, BlockPos, int)} that every older guide
 * hooks does not exist. Frost Walker is a <b>data-driven enchantment</b>:
 * {@code data/minecraft/enchantment/frost_walker.json} declares a
 * {@code minecraft:location_changed} effect of type {@code minecraft:replace_disk}.
 *
 * <p>So this effect does not reimplement freezing. It reaches into the registry
 * for <b>vanilla's own Frost Walker enchantment</b> and runs its real
 * location-changed effects, at level 1, from
 * {@code LivingEntityFrostWalkerMixin}. That is deliberate and is the honest
 * version of "hook the same utility": every number — the radius, the block
 * predicate, the frosted-ice state, the game event, the on-ground and
 * not-riding requirements — comes from vanilla's own definition rather than from
 * a copy of it here. If a datapack retunes Frost Walker, this follows it for
 * free, and it cannot drift out of sync with the enchantment the way a
 * transcribed constant table would.
 *
 * <p>For the record, since the values are not visible in this file: vanilla's
 * radius is {@code 3.0} at level 1 (+1 per level above first, clamped to 16), the
 * disk is 1 block high at offset {@code (0, -1, 0)}, it replaces water with
 * {@code frosted_ice[age=0]} where the block above is air and the position is
 * unobstructed, and it fires a {@code block_place} game event.
 *
 * <p>Frosted ice still melts on its normal vanilla schedule. This grants the
 * enchantment's behaviour, not permanent ice.
 *
 * <p>apply()/remove() are empty by design -- see {@link HookEffectBehavior}.
 */
public final class FrostWalkerInnateBehavior extends HookEffectBehavior {

	public static final String ID = "frost_walker_innate";

	/**
	 * The enchantment level to run vanilla's effect at. Level 1 gives radius 3,
	 * matching a normal Frost Walker I boot rather than an enchanted-book maximum.
	 */
	public static final int LEVEL = 1;
}
