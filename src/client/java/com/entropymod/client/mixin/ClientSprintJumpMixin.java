package com.entropymod.client.mixin;

import com.entropymod.client.ClientRunState;
import com.entropymod.entropy.behavior.SlipperyGripSprint;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Slippery Grip's sprint-jump impulse, client side. The twin of
 * {@code LivingEntitySprintJumpMixin}, and <b>not redundant with it.</b>
 *
 * <p>Unlike the speed compensator -- which is an attribute modifier and therefore
 * reaches the client on its own through {@code ClientboundUpdateAttributesPacket}
 * -- this impulse is a one-shot addition to delta movement with nothing to sync.
 * Each side computes it independently in its own {@code jumpFromGround}, so a
 * server-only fix would leave the player visibly lurching forward at vanilla
 * speed and be corrected only by rubber-banding. <b>This half is authoritative
 * for what the player sees.</b>
 *
 * <p>{@code EffectHooks} answers "no effect" on the client by design, so the
 * common mixin is scoped to {@code ServerLevel} and this one to
 * {@code LocalPlayer}: exactly one of them ever scales a given jump, which is
 * order-independent rather than merely lucky. Other players' entities are
 * untouched -- their movement arrives as positions from the server and is never
 * simulated locally.
 */
@Mixin(LivingEntity.class)
public abstract class ClientSprintJumpMixin {

	@ModifyArg(
			method = "jumpFromGround",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/world/entity/LivingEntity;addDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"),
			index = 0)
	private Vec3 entropymod$slipperyGripSprintJumpClient(Vec3 impulse) {
		if ((Object) this instanceof LocalPlayer player && ClientRunState.halvesSprintSpeed()) {
			return impulse.scale(SlipperyGripSprint.sprintScaleFor(player));
		}
		return impulse;
	}
}
