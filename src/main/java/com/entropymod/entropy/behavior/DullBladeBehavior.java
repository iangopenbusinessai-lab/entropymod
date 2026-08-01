package com.entropymod.entropy.behavior;

import com.entropymod.entropy.AttributeEffectBehavior;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Dull Blade (BAD / TOOL) -- -20% mining speed.
 *
 * <p>Inverse of Efficient Miner, 0.8x. BLOCK_BREAK_SPEED floors at 0.0; at 0 the player could never break a block, so any future mining penalty must not be able to reach -1.0 in total.
 */
public final class DullBladeBehavior extends AttributeEffectBehavior {

	public static final String ID = "dull_blade";

	public DullBladeBehavior() {
		super(Attributes.BLOCK_BREAK_SPEED, -0.20, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
	}
}
