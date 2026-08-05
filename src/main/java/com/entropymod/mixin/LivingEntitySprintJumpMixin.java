package com.entropymod.mixin;

import com.entropymod.entropy.EffectHooks;
import com.entropymod.entropy.behavior.SlipperyGripSprint;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Slippery Grip, bonus 2 of 3: the sprint-jump impulse, server side.
 *
 * <p><b>This is a real bypass, not a rounding error.</b>
 * {@code LivingEntity.jumpFromGround()} is, javap-verified:
 *
 * <pre>
 *   setDeltaMovement(v.x, max(jumpPower, v.y), v.z);
 *   if (this.isSprinting()) {
 *       float g = getYRot() * 0.017453292f;
 *       addDeltaMovement(new Vec3(-sin(g) * 0.2, 0.0, cos(g) * 0.2));
 *   }
 * </pre>
 *
 * <p>That {@code 0.2} is a flat velocity added straight to delta movement. It
 * reads no attribute, so halving {@code MOVEMENT_SPEED} does nothing to it, and
 * it is applied on every single jump taken while sprinting. See
 * {@code SlipperyGripBehavior} for the measured cost of leaving it alone.
 *
 * <p><b>{@code LivingEntity} is the right level of the chain, and the override
 * was checked rather than assumed.</b> {@code ServerPlayer} <em>does</em> override
 * {@code jumpFromGround} -- exactly the subclass trap this project has been burned
 * by -- but its bytecode opens with
 * {@code invokespecial Player.jumpFromGround} before awarding the jump stat and
 * the food exhaustion, so the super call is unconditional and this injection is
 * always reached. {@code Player} and {@code Avatar} do not override it at all.
 *
 * <p><b>{@code @ModifyArg} rather than {@code @Redirect} is deliberate.</b> The
 * client twin targets the same call, and two {@code @Redirect}s on one
 * instruction is an apply-time conflict; {@code @ModifyArg} handlers chain, and
 * each side returns the vector untouched when it is not the authority. The
 * {@code addDeltaMovement(Vec3)} call occurs exactly once inside
 * {@code jumpFromGround}, so {@code defaultRequire: 1} is unambiguous.
 *
 * <p>The {@code ServerLevel} guard is the same one {@code LivingEntitySprintMixin}
 * carries and for the same reason -- {@code EffectHooks} answers "no effect" on
 * the client by design, so without it this half would silently un-scale what the
 * client half scaled, with the winner decided by mixin ordering between two
 * configs.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntitySprintJumpMixin {

	@ModifyArg(
			method = "jumpFromGround",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/world/entity/LivingEntity;addDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"),
			index = 0)
	private Vec3 entropymod$slipperyGripSprintJump(Vec3 impulse) {
		if ((Object) this instanceof Player player
				&& player.level() instanceof ServerLevel
				&& EffectHooks.halvesSprintSpeed(player)) {
			return impulse.scale(SlipperyGripSprint.sprintScaleFor(player));
		}
		return impulse;
	}
}
