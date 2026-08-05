package com.entropymod.client.mixin;

import com.entropymod.client.ClientRunState;
import com.entropymod.entropy.behavior.SlipperyGripSprint;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Slippery Grip, client side. The twin of {@code LivingEntitySprintMixin}, and
 * <b>not redundant with it.</b>
 *
 * <p>{@code EffectHooks} answers "no effect" on the client by design -- the
 * acquired set is server state -- so the common mixin cannot see the effect here,
 * and is explicitly scoped to {@code ServerLevel} so the two never both act on
 * one entity. Without this half the client would predict movement using vanilla's
 * full +30% sprint bonus while the server applied the halved value, which
 * surfaces as rubber-banding rather than as a curse. The client half is
 * load-bearing, unlike Slashed Pockets' cosmetic gap.
 *
 * <p>The server's own modifier set does reach the client eventually --
 * {@code MOVEMENT_SPEED} is client-syncable, so {@code ClientboundUpdateAttributesPacket}
 * carries both vanilla's sprint modifier and this effect's compensator. That
 * makes this mixin a prediction fix rather than the authority: it stops the
 * one-packet-latency speed pop at the start of every sprint, and it computes
 * exactly what the incoming sync will confirm.
 *
 * <p>Targets the same {@code LivingEntity.setSprinting(boolean)} at TAIL because
 * {@code LocalPlayer} does not override it -- it calls {@code this.setSprinting}
 * from four separate places, so intercepting the method catches the sprint key,
 * double-tap-forward and the sprint toggle alike. Clearing the sprint bit in the
 * input record would have caught only the first of those three.
 *
 * <p>Guarded on {@code LocalPlayer} specifically, so other players' entities --
 * whose sprint state arrives from the server and whose speed is never simulated
 * locally -- are untouched.
 */
@Mixin(LivingEntity.class)
public abstract class ClientSprintMixin {

	@Inject(method = "setSprinting", at = @At("TAIL"))
	private void entropymod$slipperyGripClient(boolean sprinting, CallbackInfo ci) {
		if ((Object) this instanceof LocalPlayer player) {
			SlipperyGripSprint.update(player, ClientRunState.halvesSprintSpeed());
		}
	}
}
