package com.entropymod.entropy.behavior;

import com.entropymod.entropy.AttributeEffectBehavior;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Moon Walker (GOOD / MOVEMENT) -- 30% lower gravity.
 *
 * <p>Uses the real GRAVITY attribute (base 0.08, clamped [-1.0, 1.0]), which was
 * javap-verified to be player-applicable in this version rather than assumed:
 * {@code LivingEntity.createLivingAttributes()} grants it to every living entity,
 * and {@code LivingEntity.getDefaultGravity()} is literally
 * {@code getAttributeValue(Attributes.GRAVITY)}, which {@code Entity.applyGravity}
 * subtracts from vertical motion each tick. So this is genuinely attribute-driven
 * for players -- no motion mixin needed.
 *
 * <p>ADD_MULTIPLIED_BASE of -0.30 against base 0.08 gives 0.056. Simulating the
 * real per-tick integration (add gravity, integrate, apply the 0.98 air drag)
 * puts the jump apex at <b>1.657 blocks</b>, up from vanilla's 1.252 -- enough to
 * hop a 1.5-block ledge, which is the point of the effect. Falling is slower too,
 * so this softens fall damage indirectly without touching
 * FALL_DAMAGE_MULTIPLIER; it composes multiplicatively with Featherlight rather
 * than duplicating it.
 *
 * <p><b>Do not stack gravity reductions.</b> GRAVITY floors at -1.0, not at 0 --
 * unlike MAX_HEALTH, whose 1.0 floor makes over-stacking safe. Driving gravity to
 * 0 leaves the player floating with no way down, and negative gravity launches
 * them upward permanently. The no-repeat rule is what prevents this today; any
 * future gravity effect must be checked against that floor, not assumed safe.
 */
public final class MoonWalkerBehavior extends AttributeEffectBehavior {

	public static final String ID = "moon_walker";

	public MoonWalkerBehavior() {
		super(Attributes.GRAVITY, -0.30, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
	}
}
