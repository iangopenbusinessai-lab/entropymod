package com.entropymod.mixin;

import com.entropymod.entropy.EffectHooks;
import com.entropymod.entropy.behavior.SlipperyGripSprint;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Slippery Grip, server side: sprinting is allowed, and made slow.
 *
 * <p>Injected at the <b>TAIL</b> of {@code LivingEntity.setSprinting(boolean)},
 * which is vanilla's single choke point for the sprint speed bonus -- it removes
 * {@code SPEED_MODIFIER_SPRINTING} unconditionally and re-adds it when starting.
 * Running after that leaves vanilla's modifier already in place, so the
 * compensator can be derived from the real value rather than from a constant,
 * and the two are added and removed together by the same call.
 *
 * <p><b>{@code LivingEntity} is the right level of the chain:</b> it overrides
 * {@code Entity.setSprinting}, and neither {@code Player} nor {@code ServerPlayer}
 * overrides it again -- so this single target covers players completely. The
 * {@code instanceof Player} guard is load-bearing, since the method runs for
 * every mob in the world.
 *
 * <p><b>The {@code ServerLevel} guard is what makes the client twin safe.</b>
 * Both this mixin and {@code ClientSprintMixin} target the same method, and on
 * the client both are present. {@code EffectHooks} answers "no effect" there by
 * design, so without this guard the two would fight: whichever ran second would
 * either add or remove the modifier, and mixin ordering between two configs is
 * not something to depend on. Scoping each side to the levels it is the
 * authority for means exactly one of them ever acts on a given entity, which is
 * order-independent instead of merely lucky.
 *
 * <p>Note the previous version of this mixin was a {@code @ModifyVariable} that
 * forced the argument to false. Chaining was safe there precisely because both
 * halves only ever forced the same value; that is not true of an action that
 * both adds and removes, which is why the shape changed with the mechanism.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntitySprintMixin {

	@Inject(method = "setSprinting", at = @At("TAIL"))
	private void entropymod$slipperyGrip(boolean sprinting, CallbackInfo ci) {
		if ((Object) this instanceof Player player && player.level() instanceof ServerLevel) {
			SlipperyGripSprint.update(player, EffectHooks.halvesSprintSpeed(player));
		}
	}
}
