package com.entropymod.mixin;

import com.entropymod.entropy.EffectHooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Cancels knockback from projectiles only, for Iron Will.
 *
 * <p><b>Why this redirects a call site instead of mixing into
 * {@code knockback}.</b> {@code LivingEntity.knockback(double, double, double)}
 * receives no {@code DamageSource}, so a mixin on that method could not
 * distinguish an arrow from a sword and would necessarily produce blanket
 * knockback immunity. Redirecting the call inside {@code hurtServer} puts the
 * damage source in scope -- a redirect handler may capture the enclosing method's
 * parameters after its own -- which is what makes the projectile-only rule
 * expressible at all.
 *
 * <h2>The three-way split, verified against the shipped tags</h2>
 *
 * <ul>
 *   <li><b>Projectiles are cancelled.</b> {@code is_projectile} covers arrow,
 *       trident, mob_projectile, fireball, unattributed_fireball, wither_skull,
 *       thrown and wind_charge.</li>
 *   <li><b>Melee is untouched.</b> {@code player_attack} / {@code mob_attack} are
 *       in neither tag, so they reach this redirect and the gate passes them
 *       straight through to the original call.</li>
 *   <li><b>Explosions are untouched by construction.</b> {@code explosion} and
 *       {@code player_explosion} are in vanilla's {@code no_knockback} tag, and
 *       the redirected call sits inside the branch that tag excludes -- explosion
 *       shove is applied by the explosion itself and never passes through here.
 *       This mixin could not affect it even if the gate were wrong.</li>
 * </ul>
 *
 * <p><b>Players without the effect, and every non-player entity, are unaffected.</b>
 * Both the {@code instanceof Player} check and the acquired-set lookup must pass
 * before anything is skipped; every other path calls the original knockback with
 * the original arguments. {@code hurtServer} runs for every mob in the world, so
 * that first check is load-bearing.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityKnockbackMixin {

	@Redirect(
			method = "hurtServer",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;knockback(DDD)V"))
	private void entropymod$skipProjectileKnockback(LivingEntity target, double strength, double x, double z,
													ServerLevel level, DamageSource source, float amount) {
		if (target instanceof Player player
				&& source.is(DamageTypeTags.IS_PROJECTILE)
				&& EffectHooks.ignoresProjectileKnockback(player)) {
			return;
		}
		target.knockback(strength, x, z);
	}
}
