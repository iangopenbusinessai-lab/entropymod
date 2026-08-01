package com.entropymod.entropy.behavior;

import com.entropymod.entropy.AttributeEffectBehavior;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Lucky Find (GOOD / UTILITY) -- +2 Luck.
 *
 * <p>LUCK is clamped to [-1024.0, 1024.0], so unlike most attributes it has real headroom in both directions and the negative twin needs no special handling. Affects loot-table quality rolls (fishing, chests).
 */
public final class LuckyFindBehavior extends AttributeEffectBehavior {

	public static final String ID = "lucky_find";

	public LuckyFindBehavior() {
		super(Attributes.LUCK, 2.0, AttributeModifier.Operation.ADD_VALUE);
	}
}
