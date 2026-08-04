package com.entropymod.mixin;

import com.entropymod.entropy.EffectHooks;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Behemoth Gauntlets: rewrites the damage of one melee swing.
 *
 * <p>Targets the {@code Entity.hurtOrSimulate(DamageSource, float)} call inside
 * {@code Player.attack} -- javap-verified as the single point where the fully
 * computed melee damage is handed to the target. Everything vanilla does to
 * build that number (the {@code ATTACK_DAMAGE} attribute including the held
 * weapon's own modifier, the attack-cooldown scale, enchantments, the crit
 * multiplier, {@code Item.getAttackDamageBonus}) has already happened, so this
 * composes with all of it instead of replacing any of it.
 *
 * <p>{@code index = 1} is the {@code float}; index 0 is the {@code DamageSource}.
 *
 * <p><b>No {@code instanceof} guard is needed</b> -- unlike the
 * {@code LivingEntity} damage mixins, this targets {@code Player.attack}, which
 * only players call. The empty-hand test itself lives in {@link EffectHooks} so
 * this stays the one-line shape the mixin discipline in CLAUDE.md asks for.
 */
@Mixin(Player.class)
public abstract class PlayerAttackDamageMixin {

	@ModifyArg(
			method = "attack",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/entity/Entity;hurtOrSimulate"
							+ "(Lnet/minecraft/world/damagesource/DamageSource;F)Z"),
			index = 1)
	private float entropymod$behemothGauntlets(float damage) {
		return EffectHooks.meleeDamage((Player) (Object) this, damage);
	}
}
