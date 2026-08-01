package com.entropymod.entropy.behavior;

import com.entropymod.entropy.AttributeEffectBehavior;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Sluggish Strikes (BAD / COMBAT) -- -20% attack speed.
 *
 * <p>Base 4.0 becomes 3.2. ATTACK_SPEED is clamped to a floor of 0.0 (javap-verified) and 0 would mean the attack cooldown never refills at all -- an unrecoverable state. -20% of base is nowhere near it, and no-repeat prevents stacking, but any future attack-speed penalty must be checked against that floor before being added.
 */
public final class SluggishStrikesBehavior extends AttributeEffectBehavior {

	public static final String ID = "sluggish_strikes";

	public SluggishStrikesBehavior() {
		super(Attributes.ATTACK_SPEED, -0.20, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
	}
}
