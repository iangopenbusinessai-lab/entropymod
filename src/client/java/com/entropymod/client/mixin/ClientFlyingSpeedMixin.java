package com.entropymod.client.mixin;

import com.entropymod.client.ClientRunState;
import com.entropymod.entropy.behavior.SlipperyGripSprint;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Slippery Grip's airborne acceleration, client side. The twin of
 * {@code PlayerFlyingSpeedMixin}.
 *
 * <p>Load-bearing for the same reason as {@code ClientSprintJumpMixin}: airborne
 * acceleration comes from a method return, not from an attribute, so there is
 * nothing for the server to sync and each side must compute it. Without this half
 * the client would predict every jump at vanilla's full 0.025999999 air control
 * while the server ran at 0.01, and the whole arc of every jump would fight the
 * server rather than merely starting slightly wrong.
 *
 * <p>See {@code PlayerFlyingSpeedMixin} for why {@code Player} is the correct
 * target class, why the {@code isSprinting} guard is required, and why creative
 * flight is out of scope.
 */
@Mixin(Player.class)
public abstract class ClientFlyingSpeedMixin {

	@Inject(method = "getFlyingSpeed", at = @At("RETURN"), cancellable = true)
	private void entropymod$slipperyGripAirControlClient(CallbackInfoReturnable<Float> cir) {
		if (!((Object) this instanceof LocalPlayer player)) {
			return;
		}
		if (player.getAbilities().flying
				|| !player.isSprinting()
				|| !ClientRunState.halvesSprintSpeed()) {
			return;
		}
		cir.setReturnValue(
				(float) (cir.getReturnValueF() * SlipperyGripSprint.sprintScaleFor(player)));
	}
}
