package com.entropymod.mixin;

import com.entropymod.entropy.EffectHooks;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Scales incoming damage for Iron Skin / Fragile.
 *
 * <p>There is no vanilla attribute for a general incoming-damage multiplier --
 * {@code ARMOR} is type-dependent and capped, and is not the same thing -- so
 * this is one of the three effects that genuinely needs a mixin.
 *
 * <p>Targets {@code LivingEntity.hurtServer} at HEAD, so the multiplier lands
 * <em>before</em> armour, enchantments and absorption are applied. That is
 * deliberate: it composes with those systems multiplicatively instead of
 * overriding them, so Iron Skin stays useful in full diamond rather than being
 * swallowed by the armour cap.
 *
 * <p>{@code hurtServer} is declared on {@code LivingEntity} and therefore runs
 * for every mob, so the {@code instanceof Player} guard is load-bearing, not
 * defensive tidiness -- without it every zombie in the world would inherit the
 * player's damage modifier.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityDamageMixin {

	@ModifyVariable(method = "hurtServer", at = @At("HEAD"), argsOnly = true)
	private float entropymod$scaleIncomingDamage(float amount) {
		if (!((Object) this instanceof Player player)) {
			return amount;
		}
		return amount * EffectHooks.damageTakenMultiplier(player);
	}
}
