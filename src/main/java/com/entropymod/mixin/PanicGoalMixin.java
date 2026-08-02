package com.entropymod.mixin;

import com.entropymod.entropy.EffectHooks;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stops animals panicking at a Beast Whisperer -- the first of that effect's two
 * mechanisms.
 *
 * <p>{@code PanicGoal.shouldPanic()} is
 * {@code mob.getLastDamageSource() != null && mob.getLastDamageSource().is(<tag>)}.
 * Because it keys on the damage <em>source</em>, the attacker is reachable via
 * {@code getEntity()}, which is what lets this be scoped to one specific player
 * instead of disabling panic outright.
 *
 * <p><b>Targeting {@code shouldPanic} rather than {@code canUse} is the whole
 * point.</b> {@code canUse()} is {@code shouldPanic() || mob.isOnFire() || ...},
 * so cancelling there would also stop a burning animal running for water.
 * Injecting into the narrower method leaves fire and freezing panic completely
 * intact and suppresses only the "you hit me" reaction.
 *
 * <p><b>Nothing changes for players without the effect.</b> The method returns
 * early unless the last damage source's attacker is a player who has it; damage
 * from anything else -- another mob, a cactus, another player -- panics the
 * animal exactly as vanilla does.
 */
@Mixin(PanicGoal.class)
public abstract class PanicGoalMixin {

	@Shadow
	@org.spongepowered.asm.mixin.Final
	protected PathfinderMob mob;

	@Inject(method = "shouldPanic", at = @At("RETURN"), cancellable = true)
	private void entropymod$calmNearBeastWhisperer(CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValue()) {
			return;
		}
		DamageSource source = this.mob.getLastDamageSource();
		if (source != null && EffectHooks.suppressesFleeing(source.getEntity())) {
			cir.setReturnValue(false);
		}
	}
}
