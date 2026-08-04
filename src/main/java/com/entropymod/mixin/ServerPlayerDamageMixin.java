package com.entropymod.mixin;

import com.entropymod.entropy.EffectHooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Crouch Invincibility (cancel the hit) and Flamboyant (make fire lethal).
 *
 * <h2>Why this targets ServerPlayer and not LivingEntity</h2>
 *
 * <p>{@code LivingEntityDamageMixin} -- Iron Skin and Fragile -- targets
 * {@code LivingEntity.hurtServer} and needs an {@code instanceof Player} guard
 * because that method runs for every mob. This one targets
 * {@code ServerPlayer.hurtServer} instead, and the choice is deliberate:
 *
 * <ul>
 *   <li><b>{@code hurtServer} is overridden twice on the way down</b> --
 *       {@code ServerPlayer} overrides it, and {@code Player} overrides it again.
 *       Exactly the subclass-override trap recorded in CLAUDE.md. Both do call
 *       through ({@code ServerPlayer} -&gt; {@code Player} -&gt; {@code Avatar}
 *       -&gt; {@code LivingEntity}), which is why the Iron Skin mixin works at
 *       all -- but <b>{@code ServerPlayer.hurtServer} has three early returns
 *       before it reaches that super call</b>, so the outermost override is the
 *       only place that sees every hit.</li>
 *   <li>Targeting the player class directly makes the effect player-only by
 *       construction, with no guard to forget.</li>
 *   <li>{@code ServerPlayer} is concrete and has no subclass in the jar, so
 *       there is nothing further down that could shadow it.</li>
 * </ul>
 *
 * <h2>Ordering between the two injectors is load-bearing</h2>
 *
 * <p>The cancel runs at HEAD and returns {@code false} for a crouching player,
 * so a run holding both effects survives fire while sneaking: Flamboyant's
 * amplification may already have been applied to the local, but the method
 * returns before anything reads it. See {@code CrouchInvincibilityBehavior} for
 * why that is the intended reading rather than an accident.
 *
 * <p>Flamboyant amplifies rather than killing directly so vanilla keeps
 * ownership of the death: correct cause-specific death message, sound, drops,
 * statistics and advancements. Iron Skin's multiplier lands later and cannot
 * rescue the player -- the amplified value is a multiple of max health.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerDamageMixin {

	@Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
	private void entropymod$crouchInvincibility(ServerLevel level, DamageSource source, float amount,
											   CallbackInfoReturnable<Boolean> cir) {
		if (EffectHooks.ignoresAllDamage((ServerPlayer) (Object) this)) {
			// false == "no damage was taken", which is what vanilla returns when a
			// hit is refused. Returning true would make attackers believe they
			// connected and would still play the hurt effects.
			cir.setReturnValue(false);
		}
	}

	@ModifyVariable(method = "hurtServer", at = @At("HEAD"), argsOnly = true)
	private float entropymod$flamboyant(float amount, ServerLevel level, DamageSource source) {
		return EffectHooks.fireDamage((ServerPlayer) (Object) this, source, amount);
	}
}
