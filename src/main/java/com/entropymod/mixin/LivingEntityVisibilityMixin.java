package com.entropymod.mixin;

import com.entropymod.entropy.EffectHooks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes hostile mobs notice the player from farther away -- part (b) of Heavy
 * Footsteps.
 *
 * <p><b>This is deliberately not a sound mechanic, because there isn't one.</b>
 * {@code Sensing}, the mob perception class, exposes exactly one query --
 * {@code hasLineOfSight(Entity)}. General mob AI has no hearing and no noise
 * radius; the only sound-driven perception in the game is the vibration/sculk
 * system used by the Warden and sculk sensors, which would have covered one mob
 * rather than "mobs".
 *
 * <p>{@code LivingEntity.getVisibilityPercent(Entity)} is the real general lever
 * for this concept. {@code TargetingConditions.test} -- the shared gate behind
 * essentially every hostile targeting goal -- computes its effective range as
 * {@code target.getVisibilityPercent(attacker) * range}, floored at 2. Vanilla
 * already uses this exact value to implement sneaking, returning {@code 0.8} when
 * the entity is crouching. This pushes it the other way, so the change composes
 * with sneaking and armour-based invisibility rather than overriding them.
 *
 * <p><b>Players without the effect are unaffected.</b>
 * {@code detectionRangeMultiplier} returns {@code 1.0f} for them, and for
 * everything that is not a player the method returns before doing anything --
 * {@code getVisibilityPercent} is on {@code LivingEntity} and is called for every
 * mob, so that guard matters.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityVisibilityMixin {

	@Inject(method = "getVisibilityPercent", at = @At("RETURN"), cancellable = true)
	private void entropymod$amplifyVisibility(Entity lookingEntity, CallbackInfoReturnable<Double> cir) {
		if (!((Object) this instanceof Player player)) {
			return;
		}
		float scale = EffectHooks.detectionRangeMultiplier(player);
		if (scale != 1.0f) {
			cir.setReturnValue(cir.getReturnValue() * scale);
		}
	}
}
