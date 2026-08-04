package com.entropymod.client.mixin;

import com.entropymod.client.ClientRunState;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Slippery Grip, client side. The twin of {@code LivingEntitySprintMixin}, and
 * <b>not redundant with it.</b>
 *
 * <p>{@code EffectHooks} answers "no effect" on the client by design -- the
 * acquired set is server state -- so the common mixin cannot see the effect here.
 * Without this one the client would still decide to sprint, apply its own
 * {@code SPEED_MODIFIER_SPRINTING} locally, and move faster than the server
 * believes it should, which surfaces as rubber-banding rather than as a curse.
 * The client half is load-bearing, unlike Slashed Pockets' cosmetic gap.
 *
 * <p>Targets the same {@code LivingEntity.setSprinting(boolean)} because
 * {@code LocalPlayer} does not override it -- it calls {@code this.setSprinting}
 * from four separate places, so intercepting the method catches the sprint key,
 * double-tap-forward and the sprint toggle alike. Clearing the sprint bit in the
 * input record would have caught only the first of those three.
 *
 * <p>Guarded on {@code LocalPlayer} specifically, so other players' entities --
 * whose sprint state arrives from the server and is only used for rendering --
 * are untouched.
 *
 * <p>Both halves are {@code @ModifyVariable}, deliberately: they chain, each
 * independently forcing false, rather than one cancelling before the other runs
 * as two cancellable {@code @Inject}s would.
 */
@Mixin(LivingEntity.class)
public abstract class ClientSprintMixin {

	@ModifyVariable(method = "setSprinting", at = @At("HEAD"), argsOnly = true)
	private boolean entropymod$slipperyGripClient(boolean sprinting) {
		if (!sprinting) {
			return false;
		}
		return !((Object) this instanceof LocalPlayer) || !ClientRunState.preventsSprinting();
	}
}
