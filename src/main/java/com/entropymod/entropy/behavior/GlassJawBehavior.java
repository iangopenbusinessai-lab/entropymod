package com.entropymod.entropy.behavior;

import com.entropymod.entropy.AttributeEffectBehavior;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Glass Jaw (BAD / MOVEMENT) -- fall damage increased by 40%.
 *
 * <p>Exact inverse of Featherlight, 1.4x multiplier. FALL_DAMAGE_MULTIPLIER's ceiling is 100.0, far above anything this batch can reach. Counterplay: don't fall.
 */
public final class GlassJawBehavior extends AttributeEffectBehavior {

	public static final String ID = "glass_jaw";

	public GlassJawBehavior() {
		super(Attributes.FALL_DAMAGE_MULTIPLIER, 0.40, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
	}
}
