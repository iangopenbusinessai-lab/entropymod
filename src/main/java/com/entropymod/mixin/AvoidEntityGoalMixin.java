package com.entropymod.mixin;

import com.entropymod.entropy.EffectHooks;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stops animals keeping their distance from a Beast Whisperer -- the second of
 * that effect's two mechanisms.
 *
 * <p>Foxes, ocelots and similar avoid players without being provoked, which is a
 * different goal from {@link PanicGoalMixin}'s post-damage panic. The useful
 * discovery here is that this is <b>one generic class parameterised by the type
 * to avoid</b>, not a per-species reimplementation: {@code AvoidEntityGoal.canUse}
 * resolves the nearest matching entity into the protected {@code toAvoid} field.
 * So this single mixin covers every species that uses the goal, including any
 * added later, and no species-specific code was needed.
 *
 * <p>Injecting at RETURN rather than HEAD is required: {@code toAvoid} is only
 * populated during {@code canUse}, so at HEAD there would be no target to test.
 *
 * <p><b>Avoidance of anything other than an effect-holding player is untouched.</b>
 * The gate is on the resolved target, so a rabbit still flees wolves and a
 * villager still flees zombies -- those calls see a non-player {@code toAvoid},
 * and {@code suppressesFleeing} returns false for it. A player without the effect
 * is likewise ignored.
 */
@Mixin(AvoidEntityGoal.class)
public abstract class AvoidEntityGoalMixin {

	@Shadow
	protected LivingEntity toAvoid;

	@Inject(method = "canUse", at = @At("RETURN"), cancellable = true)
	private void entropymod$ignoreBeastWhisperer(CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValue() && EffectHooks.suppressesFleeing(this.toAvoid)) {
			cir.setReturnValue(false);
		}
	}
}
