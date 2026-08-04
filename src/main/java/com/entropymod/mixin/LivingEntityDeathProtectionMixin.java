package com.entropymod.mixin;

import com.entropymod.entropy.EffectHooks;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Second Chance: rides the Totem of Undying's own escape hatch.
 *
 * <p>Targets {@code LivingEntity.checkTotemDeathProtection(DamageSource)}, whose
 * caller in {@code hurtServer} is, javap-verified:
 *
 * <pre>
 *   if (this.isDeadOrDying()) {
 *       if (!this.checkTotemDeathProtection(source)) {
 *           ... death sound, die(source) ...
 *       }
 *   }
 * </pre>
 *
 * <p>So returning {@code true} skips the death branch outright -- no death
 * message, no drops, no respawn screen -- because vanilla's own control flow
 * says so, rather than because each of those was individually suppressed.
 *
 * <p><b>The health restore is not optional.</b> This runs after health has
 * already been reduced to zero, so a {@code true} return with no
 * {@code setHealth} would leave the player alive on an empty bar and dead again
 * next tick. That restore lives in {@code EntropyManager.consumeSecondChance},
 * alongside spending the run flag, so the two can never happen separately.
 *
 * <p>Injected at HEAD and cancelling, so Second Chance is checked <em>before</em>
 * a totem in hand -- the run's one-shot is spent first and the totem is kept.
 * Both would otherwise fire for the same blow.
 *
 * <p>{@code checkTotemDeathProtection} is {@code private}, so it cannot be
 * overridden and the subclass-override trap cannot apply here. It does run for
 * every living entity, which is what the {@code instanceof Player} guard is for.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityDeathProtectionMixin {

	@Inject(method = "checkTotemDeathProtection", at = @At("HEAD"), cancellable = true)
	private void entropymod$secondChance(DamageSource source, CallbackInfoReturnable<Boolean> cir) {
		if (!((Object) this instanceof Player player)) {
			return;
		}
		if (EffectHooks.trySecondChance(player, source)) {
			cir.setReturnValue(true);
		}
	}
}
