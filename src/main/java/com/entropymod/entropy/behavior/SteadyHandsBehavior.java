package com.entropymod.entropy.behavior;

import com.entropymod.entropy.AttributeEffectBehavior;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Steady Hands (GOOD / COMBAT) -- +2 attack damage.
 *
 * <p>ATTACK_DAMAGE is clamped to [0.0, 2048.0]. Note this attribute is NOT client-syncable (verified), so the client tooltip will not show the change even though the server applies it.
 */
public final class SteadyHandsBehavior extends AttributeEffectBehavior {

	public static final String ID = "steady_hands";

	public SteadyHandsBehavior() {
		super(Attributes.ATTACK_DAMAGE, 2.0, AttributeModifier.Operation.ADD_VALUE);
	}
}
