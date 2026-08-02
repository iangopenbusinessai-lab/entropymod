package com.entropymod.mixin;

import com.entropymod.entropy.EntropyAttributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Widens the item/XP pickup box for Magnetic Boots.
 *
 * <p>Pickup range is not an attribute in vanilla -- see
 * {@link EntropyAttributes} -- it is hardcoded inside {@code Player.aiStep()} as
 * {@code getBoundingBox().inflate(1.0, 0.5, 1.0)}. This redirect is the only way
 * to change it without reimplementing the collection loop.
 *
 * <p><b>Scoped to {@code aiStep} on purpose.</b> {@code Player} calls
 * {@code AABB.inflate} in {@code doSweepAttack} as well; a class-wide redirect
 * would silently widen sweep-attack reach too. The {@code method = "aiStep"}
 * scope is load-bearing, not decoration.
 *
 * <p>{@code aiStep} contains <b>two</b> inflate calls -- one for the riding
 * branch, one for the normal branch -- and this redirect deliberately covers both
 * so pickup behaves the same in a boat as on foot.
 *
 * <p><b>Players without the effect are unaffected by construction.</b> The
 * attribute's base value is 1.0, so the multiplication below is by exactly 1.0
 * and reproduces vanilla's arguments bit for bit. There is no acquired-set lookup
 * on this path at all, which also keeps it cheap -- {@code aiStep} runs every
 * tick for every player.
 */
@Mixin(Player.class)
public abstract class PlayerItemPickupMixin {

	@Redirect(
			method = "aiStep",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/AABB;inflate(DDD)Lnet/minecraft/world/phys/AABB;"))
	private AABB entropymod$widenPickupBox(AABB box, double x, double y, double z) {
		double scale = ((Player) (Object) this).getAttributeValue(EntropyAttributes.PICKUP_RANGE);
		return box.inflate(x * scale, y * scale, z * scale);
	}
}
