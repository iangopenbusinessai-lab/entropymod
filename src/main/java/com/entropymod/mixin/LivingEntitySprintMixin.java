package com.entropymod.mixin;

import com.entropymod.entropy.EffectHooks;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Slippery Grip, server side: refuses the sprint state rather than undoing it.
 *
 * <p>Targets {@code LivingEntity.setSprinting(boolean)} and forces the argument
 * to false. That method is where vanilla adds and removes
 * {@code SPEED_MODIFIER_SPRINTING}, so passing false makes vanilla's own code
 * strip the speed bonus -- nothing has to be reversed by hand.
 *
 * <p><b>{@code LivingEntity} is the right level of the chain:</b> it overrides
 * {@code Entity.setSprinting}, and neither {@code Player} nor {@code ServerPlayer}
 * overrides it again -- so this single target covers players completely. The
 * {@code instanceof Player} guard is load-bearing, since the method runs for
 * every mob in the world.
 *
 * <p>{@code @ModifyVariable} rather than a cancellable {@code @Inject} so this
 * composes with the client-side twin instead of racing it -- see
 * {@code SlipperyGripBehavior} for why both exist.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntitySprintMixin {

	@ModifyVariable(method = "setSprinting", at = @At("HEAD"), argsOnly = true)
	private boolean entropymod$slipperyGrip(boolean sprinting) {
		if (!sprinting) {
			return false; // never turn a stop into a start
		}
		return !((Object) this instanceof Player player) || !EffectHooks.preventsSprinting(player);
	}
}
