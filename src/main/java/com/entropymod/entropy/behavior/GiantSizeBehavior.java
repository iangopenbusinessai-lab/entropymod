package com.entropymod.entropy.behavior;

import com.entropymod.EntropyMod;
import com.entropymod.entropy.AttributeEffectBehavior;
import com.entropymod.entropy.EffectContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;

/**
 * Giant Size (BAD / DEBUFF, Tier 2) -- the player becomes five times their
 * normal size, permanently.
 *
 * <h2>The attribute exists and is player-applicable -- verified, not assumed</h2>
 *
 * <p>{@code minecraft:scale} is a real, registered {@code RangedAttribute}:
 * <b>default 1.0, min 0.0625, max 16.0</b>, read out of the live registry rather
 * than a wiki. It is granted by {@code LivingEntity.createLivingAttributes()},
 * which {@code Player.createAttributes()} builds on, so every player has it --
 * confirmed by querying {@code DefaultAttributes.getSupplier(EntityType.PLAYER)}
 * directly. 5.0 sits comfortably inside the range, so no clamping concern
 * exists in either direction. This needed no mixin and no faked scaling.
 *
 * <p>It is also <b>client-syncable</b> (checked via
 * {@code Attribute.isClientSyncable()}), so the model, hitbox and camera height
 * all follow on the client with no payload of this mod's own. Unlike Tier 2's
 * input and camera effects, this needs no client-side awareness at all.
 *
 * <h2>The apply-time collision risk is REAL, and vanilla will not fix it</h2>
 *
 * <p><b>{@code Entity.refreshDimensions()} explicitly skips its own rescue
 * behaviour for players.</b> Verified in bytecode: it calls
 * {@code fudgePositionAfterSizeChange(...)} -- the routine that nudges a
 * grown entity out of the blocks it now overlaps -- only after an
 * {@code instanceof Player} check that <em>branches past it</em> for players.
 * So growing a player 5x inside a 2-block-tall corridor leaves them embedded in
 * terrain, taking suffocation damage, with the mod entirely to blame.
 *
 * <p>Mitigation, in {@link #afterApply}: refresh the dimensions, and if the new
 * bounding box collides, walk upward a block at a time looking for a position
 * where it does not, then snap there. Notes on the shape of this:
 *
 * <ul>
 *   <li><b>It is idempotent</b>, which it must be -- {@code apply} runs again on
 *       every respawn, rejoin and dimension change. A player who is already free
 *       collides with nothing and is not moved at all.</li>
 *   <li><b>Upward only.</b> Downward would push the player into the ground, and
 *       sideways would need a search order that has no principled answer. Up is
 *       where the free space is when the problem is "I just got taller".</li>
 *   <li><b>Giving up is a valid outcome.</b> Deep underground there may be no
 *       free spot within the search limit; the player then takes suffocation
 *       damage, which is the honest consequence of becoming enormous in a tunnel
 *       and is the curse working. It is logged rather than silently ignored.</li>
 * </ul>
 */
public final class GiantSizeBehavior extends AttributeEffectBehavior {

	public static final String ID = "giant_size";

	/** ADD_VALUE against a base of 1.0, so the final scale is exactly 5.0. */
	public static final double AMOUNT = 4.0;

	/** How far up to look for room. Beyond this, the player is genuinely walled in. */
	public static final int MAX_LIFT_BLOCKS = 8;

	public GiantSizeBehavior() {
		super(Attributes.SCALE, AMOUNT, AttributeModifier.Operation.ADD_VALUE);
	}

	@Override
	protected void afterApply(EffectContext ctx) {
		ServerPlayer player = ctx.target();
		if (!(player.level() instanceof ServerLevel level)) {
			return;
		}

		// The attribute moved, but the entity's cached dimensions and bounding box
		// have not. Nothing below is meaningful until they do.
		player.refreshDimensions();

		if (level.noCollision(player, player.getBoundingBox())) {
			return; // already free -- the common case, and what makes this idempotent
		}

		AABB box = player.getBoundingBox();
		for (int lift = 1; lift <= MAX_LIFT_BLOCKS; lift++) {
			if (level.noCollision(player, box.move(0.0, lift, 0.0))) {
				player.snapTo(player.getX(), player.getY() + lift, player.getZ());
				EntropyMod.LOGGER.info("Giant Size: lifted {} by {} block(s) to avoid suffocating on apply.",
						player.getName().getString(), lift);
				return;
			}
		}

		EntropyMod.LOGGER.warn("Giant Size: no free space within {} blocks above {} -- they are enclosed "
						+ "and will suffocate until they dig out.",
				MAX_LIFT_BLOCKS, player.getName().getString());
	}
}
