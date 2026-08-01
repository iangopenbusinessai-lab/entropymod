package com.entropymod.mixin;

import com.entropymod.entropy.EffectHooks;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Scales experience gained for Fast Learner / Slow Learner.
 *
 * <p>Targets {@code Player.giveExperiencePoints(int)}, which every XP source
 * funnels through -- mobs, ore, smelting, breeding, bottles -- so one mixin
 * covers all of them.
 *
 * <p>Uses {@link EffectHooks#scaleAmount} rather than a bare cast because the
 * value is an int: {@code (int)(1 * 1.5f)} truncates to 1, which would make Fast
 * Learner do nothing at all for the very common 1-XP orb. Rounding gives the
 * intended average instead.
 */
@Mixin(Player.class)
public abstract class PlayerExperienceMixin {

	@ModifyVariable(method = "giveExperiencePoints", at = @At("HEAD"), argsOnly = true)
	private int entropymod$scaleExperience(int amount) {
		Player player = (Player) (Object) this;
		return EffectHooks.scaleAmount(amount, EffectHooks.experienceMultiplier(player));
	}
}
