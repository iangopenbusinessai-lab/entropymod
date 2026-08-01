package com.entropymod.mixin;

import com.entropymod.entropy.EffectHooks;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Scales hunger drain for Iron Stomach / Growling Stomach.
 *
 * <p><b>Targets {@code Player.causeFoodExhaustion}, not {@code FoodData}.</b>
 * The obvious target looks like {@code FoodData.addExhaustion(float)}, but
 * {@code FoodData} holds no reference to the player it belongs to, so a mixin
 * there could not tell whose hunger it was scaling. {@code Player} is the lowest
 * point in the call chain that still knows.
 *
 * <p>Kept to a single multiply. All the decision-making is in
 * {@link EffectHooks}, which is ordinary testable Java and is contractually
 * unable to throw -- an exception escaping a mixin surfaces as a crash blaming
 * vanilla rather than this mod.
 */
@Mixin(Player.class)
public abstract class PlayerExhaustionMixin {

	@ModifyVariable(method = "causeFoodExhaustion", at = @At("HEAD"), argsOnly = true)
	private float entropymod$scaleExhaustion(float exhaustion) {
		return exhaustion * EffectHooks.exhaustionMultiplier((Player) (Object) this);
	}
}
