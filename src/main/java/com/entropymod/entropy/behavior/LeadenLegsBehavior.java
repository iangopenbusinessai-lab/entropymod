package com.entropymod.entropy.behavior;

import com.entropymod.entropy.AttributeEffectBehavior;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Leaden Legs (BAD / MOVEMENT) -- 40% higher gravity.
 *
 * <p>Exact inverse of Moon Walker, on the same javap-verified GRAVITY attribute.
 * ADD_MULTIPLIED_BASE of +0.40 against base 0.08 gives 0.112. The ceiling is 1.0,
 * nowhere near reachable from this batch.
 *
 * <h2>Why this is NOT the same effect as Stone Feet</h2>
 *
 * <p>Both shorten a jump, so it is worth being explicit that they are different
 * mechanics and not two names for one thing. Gravity is applied every tick to
 * vertical motion in <em>both</em> directions: Leaden Legs also makes the player
 * <b>fall faster</b>, reach terminal velocity sooner, and accumulate fall
 * distance faster, so it interacts with fall damage, ledges and water landings.
 * Stone Feet changes only the initial upward impulse and leaves falling exactly
 * as vanilla. Confirmed distinct in the bytecode: JUMP_STRENGTH is read once, in
 * {@code LivingEntity.getJumpPower(float)}; GRAVITY is read in
 * {@code getDefaultGravity()} and consumed by {@code Entity.applyGravity()} on
 * every airborne tick.
 *
 * <h2>Known consequence: this blocks 1-block jumps</h2>
 *
 * <p>Simulated apex at +40% gravity is <b>0.980 blocks</b> -- roughly 2% short of
 * the 1.0 needed to hop onto a full block. The player can still walk up slabs and
 * stairs (STEP_HEIGHT is untouched), so terrain remains traversable, but plain
 * 1-block ledges have to be ramped or built around. Counterplay is real (place a
 * slab, dig a ramp), which is why this stays {@code counterplay = true}, but the
 * threshold is deliberately recorded here because it is a cliff rather than a
 * gradient: +35% clears the ledge at 1.004 and +40% does not. If this reads as
 * broken rather than heavy in play, +30% (apex 1.027) is the tuning that keeps
 * the effect while preserving normal traversal.
 */
public final class LeadenLegsBehavior extends AttributeEffectBehavior {

	public static final String ID = "leaden_legs";

	public LeadenLegsBehavior() {
		super(Attributes.GRAVITY, 0.40, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
	}
}
