package com.entropymod.mixin;

import com.entropymod.entropy.EffectHooks;
import com.entropymod.entropy.behavior.SlipperyGripSprint;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Slippery Grip, bonus 3 of 3: airborne acceleration, server side.
 *
 * <p><b>{@code MOVEMENT_SPEED} is not consulted while airborne at all</b>, which
 * is the finding that turned "sprint-jumping is a bit fast" into "the curse is
 * off while jumping". {@code LivingEntity.getFrictionInfluencedSpeed} is:
 *
 * <pre>
 *   onGround() ? getSpeed() * (0.21600002f / (friction*friction*friction))
 *              : getFlyingSpeed();
 * </pre>
 *
 * <p>and {@code Player.getFlyingSpeed}'s non-flying branch is
 * {@code isSprinting() ? 0.025999999f : 0.02f}. So for every airborne tick the
 * speed compensator is bypassed and vanilla's own sprint bonus -- a factor of
 * 1.3, the same 1.3 as the attribute modifier -- applies unopposed.
 *
 * <p><b>Target {@code Player}, not {@code LivingEntity}.</b> Both declare
 * {@code getFlyingSpeed}, and {@code Player}'s override never calls {@code super}
 * -- injecting into {@code LivingEntity}'s would build green and never run for a
 * player. {@code Avatar}, {@code ServerPlayer} and {@code LocalPlayer} do not
 * override it again, so {@code Player} is the outermost and only version players
 * ever reach. Its single consumer is the {@code getFrictionInfluencedSpeed}
 * branch above, so nothing else can be disturbed by this.
 *
 * <p><b>The {@code isSprinting} guard is load-bearing.</b> Without it the walking
 * value 0.02 would be scaled too, and "walking is untouched" is the other half of
 * what this effect claims.
 *
 * <p><b>Creative flight is deliberately excluded.</b> The {@code abilities.flying}
 * branch rewards sprinting on a different basis (x2, not x1.3) and ignores
 * {@code MOVEMENT_SPEED} entirely, so it was never under this curse to begin
 * with; scaling it here would be extending the effect rather than repairing it.
 */
@Mixin(Player.class)
public abstract class PlayerFlyingSpeedMixin {

	@Inject(method = "getFlyingSpeed", at = @At("RETURN"), cancellable = true)
	private void entropymod$slipperyGripAirControl(CallbackInfoReturnable<Float> cir) {
		Player player = (Player) (Object) this;
		if (player.getAbilities().flying
				|| !player.isSprinting()
				|| !(player.level() instanceof ServerLevel)
				|| !EffectHooks.halvesSprintSpeed(player)) {
			return;
		}
		cir.setReturnValue(
				(float) (cir.getReturnValueF() * SlipperyGripSprint.sprintScaleFor(player)));
	}
}
