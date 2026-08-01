package com.entropymod.entropy.behavior;

import com.entropymod.entropy.AttributeEffectBehavior;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Thick Hide (GOOD / SURVIVAL) -- +2 hearts of maximum health.
 *
 * <p>4.0 raw health = 2 hearts. MAX_HEALTH is a RangedAttribute clamped to [1.0, 1024.0] (javap-verified), so the ceiling is far out of reach. Raising max health does NOT heal the player: they stay at their current health out of a larger pool, which is deliberate.
 */
public final class ThickHideBehavior extends AttributeEffectBehavior {

	public static final String ID = "thick_hide";

	public ThickHideBehavior() {
		super(Attributes.MAX_HEALTH, 4.0, AttributeModifier.Operation.ADD_VALUE);
	}
}
