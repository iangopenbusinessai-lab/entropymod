package com.entropymod.entropy.behavior;

import com.entropymod.entropy.AttributeEffectBehavior;
import com.entropymod.entropy.EntropyAttributes;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

/**
 * Magnetic Boots (GOOD / UTILITY) -- items and XP are picked up from 50% farther.
 *
 * <p>This is the effect that was <b>deferred out of</b> the movement/physics
 * session, and it is worth recording why it could not be done there. That session
 * verified in bytecode that no vanilla attribute governs item pickup:
 * {@code ENTITY_INTERACTION_RANGE} / {@code BLOCK_INTERACTION_RANGE} feed only
 * attack and use <em>reach</em>, and the pickup box is hardcoded in
 * {@code Player.aiStep()} as {@code getBoundingBox().inflate(1.0, 0.5, 1.0)}.
 *
 * <p>So it needed a mixin, and it now has one. The attribute it modifies,
 * {@link EntropyAttributes#PICKUP_RANGE}, is registered by this mod rather than
 * vanilla — see that class for why a registered attribute is not a way of
 * dodging the mixin, but a way of letting this effect reuse the ordinary
 * {@link AttributeEffectBehavior} machinery (idempotency, respawn/rejoin
 * survival, removal) instead of inventing a parallel path.
 *
 * <p>ADD_MULTIPLIED_BASE of +0.50 against the attribute's base of 1.0 gives 1.5,
 * so the mixin inflates by {@code (1.5, 0.75, 1.5)} against vanilla's
 * {@code (1.0, 0.5, 1.0)}. That is a modest, felt change rather than a vacuum
 * cleaner: the box is measured outward from the player's own bounding box, so
 * +50% on a 1-block margin is +0.5 blocks of reach on each horizontal side, not
 * a 50% increase in some larger absolute radius. Anything much larger starts
 * hoovering items through walls, since the pickup test is a box intersection with
 * no line-of-sight check.
 */
public final class MagneticBootsBehavior extends AttributeEffectBehavior {

	public static final String ID = "magnetic_boots";

	public MagneticBootsBehavior() {
		super(EntropyAttributes.PICKUP_RANGE, 0.50, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
	}
}
