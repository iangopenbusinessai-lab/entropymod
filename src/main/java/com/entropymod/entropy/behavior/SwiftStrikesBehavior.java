package com.entropymod.entropy.behavior;

import com.entropymod.entropy.AttributeEffectBehavior;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Swift Strikes (GOOD / COMBAT) -- +20% attack speed.
 *
 * <p>Base attack speed is 4.0, so this yields 4.8. ATTACK_SPEED is clamped to [0.0, 1024.0] -- see SluggishStrikesBehavior for why that floor is the one to watch.
 */
public final class SwiftStrikesBehavior extends AttributeEffectBehavior {

	public static final String ID = "swift_strikes";

	public SwiftStrikesBehavior() {
		super(Attributes.ATTACK_SPEED, 0.20, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
	}
}
