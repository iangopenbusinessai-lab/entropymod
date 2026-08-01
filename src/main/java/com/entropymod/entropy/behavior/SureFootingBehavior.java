package com.entropymod.entropy.behavior;

import com.entropymod.entropy.AttributeEffectBehavior;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Sure Footing (GOOD / MOVEMENT) -- +15% movement speed.
 *
 * <p>ADD_MULTIPLIED_BASE against the player's base speed of 0.1, so this is exactly +15% and is the precise inverse of Heavy Boots. MOVEMENT_SPEED floors at 0.0, which only matters for the negative twin.
 */
public final class SureFootingBehavior extends AttributeEffectBehavior {

	public static final String ID = "sure_footing";

	public SureFootingBehavior() {
		super(Attributes.MOVEMENT_SPEED, 0.15, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
	}
}
