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
 * <h2>Magnitude, and why the first value was too small to notice</h2>
 *
 * <p>Originally +0.50 (a 1.5x box). In-game testing reported it as barely
 * distinguishable from vanilla, and the arithmetic explains why: the inflation is
 * measured outward from the player's own bounding box, which is only 0.6 blocks
 * wide. Vanilla pickup reach is therefore {@code 0.3 + 1.0 = 1.3} blocks from the
 * player's centre, and 1.5x moved that to just {@code 1.8}. A half-block of extra
 * reach is real but sits inside the noise of ordinary movement.
 *
 * <p><b>Root cause was confirmed before retuning, not assumed.</b> The runtime log
 * showed the attribute registered, no "targets an attribute the player does not
 * have" error from {@link AttributeEffectBehavior} (which is what a missing
 * attribute would produce), and {@code ServerPlayer} does not override
 * {@code aiStep}, so the redirect is genuinely reachable. The plumbing was
 * working; the number was simply too small.
 *
 * <p>Now +1.00, i.e. a 2.0x box: the mixin inflates by {@code (2.0, 1.0, 2.0)}
 * against vanilla's {@code (1.0, 0.5, 1.0)}, putting horizontal reach at
 * {@code 2.3} blocks and doubling vertical reach. That is clearly felt without
 * becoming a vacuum cleaner -- worth keeping in mind, since the pickup test is a
 * plain box intersection with <b>no line-of-sight check</b>, so a very large
 * value would start pulling items through walls and floors.
 */
public final class MagneticBootsBehavior extends AttributeEffectBehavior {

	public static final String ID = "magnetic_boots";

	public MagneticBootsBehavior() {
		super(EntropyAttributes.PICKUP_RANGE, 1.00, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
	}
}
