package com.entropymod.entropy.behavior;

import com.entropymod.entropy.AttributeEffectBehavior;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Stone Feet (BAD / MOVEMENT) -- 40% weaker jump.
 *
 * <p>Uses the real JUMP_STRENGTH attribute (base 0.42, clamped [0.0, 32.0]).
 *
 * <p><b>This is player-applicable in 26.1.2, verified rather than assumed.</b>
 * The name is historically associated with horses, and that association does not
 * carry over: {@code LivingEntity.createLivingAttributes()} grants JUMP_STRENGTH
 * to every living entity, {@code LivingEntity.getJumpPower(float)} computes
 * {@code getAttributeValue(JUMP_STRENGTH) * scale * getBlockJumpFactor() +
 * getJumpBoostPower()}, and neither Player, Avatar nor ServerPlayer overrides
 * {@code getJumpPower} -- {@code ServerPlayer.jumpFromGround} calls
 * {@code super} and only adds the stat award and food exhaustion. So the player's
 * jump really does come from this attribute.
 *
 * <p>See {@link LeadenLegsBehavior} for why this is a genuinely different effect
 * from that one rather than a second name for it: this changes only the initial
 * upward impulse and leaves falling identical to vanilla.
 *
 * <h2>Known consequence: this blocks 1-block jumps</h2>
 *
 * <p>Apex scales with the square of the initial velocity, so -40% jump strength
 * is a 0.36x height, not 0.6x: simulated apex is <b>0.514 blocks</b> against
 * vanilla's 1.252. The player cannot jump onto a full block at all. Slabs and
 * stairs still work untouched (STEP_HEIGHT auto-steps anything up to 0.6), so the
 * world stays traversable via ramps, but this is the harshest effect in Tier 1 by
 * some margin and is the one most likely to need retuning after play. The cliff
 * is at -10%: -10% clears a 1-block ledge at 1.047, -15% does not at 0.947.
 *
 * <p>It remains {@code counterplay = true} -- it cannot kill the player and the
 * answer (build a ramp, carry slabs) is always available -- but the CLAUDE.md
 * invariant is about survivability, not about comfort, and this is uncomfortable
 * on purpose.
 */
public final class StoneFeetBehavior extends AttributeEffectBehavior {

	public static final String ID = "stone_feet";

	public StoneFeetBehavior() {
		super(Attributes.JUMP_STRENGTH, -0.40, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
	}
}
