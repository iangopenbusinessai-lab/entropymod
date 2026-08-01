package com.entropymod.entropy.behavior;

import com.entropymod.entropy.AttributeEffectBehavior;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Unlucky (BAD / UTILITY) -- -2 Luck.
 *
 * <p>LUCK's minimum is -1024.0, so negative luck is fully representable and needs no floor handling -- the one attribute in this batch where that is true.
 */
public final class UnluckyBehavior extends AttributeEffectBehavior {

	public static final String ID = "unlucky";

	public UnluckyBehavior() {
		super(Attributes.LUCK, -2.0, AttributeModifier.Operation.ADD_VALUE);
	}
}
