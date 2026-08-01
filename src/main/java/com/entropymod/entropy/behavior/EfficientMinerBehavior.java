package com.entropymod.entropy.behavior;

import com.entropymod.entropy.AttributeEffectBehavior;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Efficient Miner (GOOD / TOOL) -- +15% mining speed.
 *
 * <p>Uses the real BLOCK_BREAK_SPEED attribute (base 1.0, clamped [0.0, 1024.0]) rather than a mixin. This matters: the attribute is client-syncable, so the block-breaking animation on the client matches the server. A server-only mixin would have desynced the crack overlay from the actual break time.
 */
public final class EfficientMinerBehavior extends AttributeEffectBehavior {

	public static final String ID = "efficient_miner";

	public EfficientMinerBehavior() {
		super(Attributes.BLOCK_BREAK_SPEED, 0.15, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
	}
}
