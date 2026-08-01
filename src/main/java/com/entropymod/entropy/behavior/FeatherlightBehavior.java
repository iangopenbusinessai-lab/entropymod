package com.entropymod.entropy.behavior;

import com.entropymod.entropy.AttributeEffectBehavior;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Featherlight (GOOD / MOVEMENT) -- fall damage reduced by 40%.
 *
 * <p>Uses the real FALL_DAMAGE_MULTIPLIER attribute (base 1.0, clamped [0.0, 100.0]) rather than a damage-event mixin. -0.40 against base 1.0 gives a 0.6x multiplier. Vanilla already handles the interaction with feather falling and the safe-fall-distance attribute.
 */
public final class FeatherlightBehavior extends AttributeEffectBehavior {

	public static final String ID = "featherlight";

	public FeatherlightBehavior() {
		super(Attributes.FALL_DAMAGE_MULTIPLIER, -0.40, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
	}
}
