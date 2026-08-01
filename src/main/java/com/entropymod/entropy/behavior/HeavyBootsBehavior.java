package com.entropymod.entropy.behavior;

import com.entropymod.entropy.AttributeEffectBehavior;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Heavy Boots (BAD / MOVEMENT) -- -15% movement speed.
 *
 * <p>Exact inverse of Sure Footing. MOVEMENT_SPEED floors at 0.0, but -15% of base cannot approach it, and the no-repeat rule means this can never stack with itself.
 */
public final class HeavyBootsBehavior extends AttributeEffectBehavior {

	public static final String ID = "heavy_boots";

	public HeavyBootsBehavior() {
		super(Attributes.MOVEMENT_SPEED, -0.15, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
	}
}
