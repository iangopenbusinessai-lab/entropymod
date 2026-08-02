package com.entropymod.entropy.behavior;

import com.entropymod.entropy.HookEffectBehavior;

/**
 * Iron Will (GOOD / COMBAT) -- projectiles never knock you back. Melee and
 * explosions are completely unaffected.
 *
 * <p>Implemented by redirecting the single {@code knockback(DDD)} call inside
 * {@code LivingEntity.hurtServer}, gated on
 * {@code DamageSource.is(DamageTypeTags.IS_PROJECTILE)}. The damage source is
 * available there because it is a parameter of the enclosing method, which is
 * exactly why the redirect targets the call site rather than
 * {@code LivingEntity.knockback} itself — <b>{@code knockback(double, double,
 * double)} receives no {@code DamageSource}</b>, so a mixin on that method could
 * not tell a projectile hit from a melee hit and would necessarily be blanket
 * knockback immunity. That distinction is the whole effect.
 *
 * <h2>Why melee and explosions are safe, verified rather than assumed</h2>
 *
 * <p><b>Explosions cannot be affected by this redirect at all.</b> Vanilla's
 * {@code no_knockback} damage-type tag already contains {@code explosion} and
 * {@code player_explosion}, and the {@code knockback} call in {@code hurtServer}
 * sits inside the branch that tag excludes — explosion shove is applied
 * separately by the explosion itself, never through this path. So explosion
 * knockback is untouched by construction, not merely by the gate below.
 *
 * <p><b>Melee flows through this exact call and is left alone by the gate.</b>
 * {@code player_attack} / {@code mob_attack} are in neither {@code no_knockback}
 * nor {@code is_projectile}, so they reach the redirect and the tag check passes
 * them straight through to the original knockback.
 *
 * <p>{@code is_projectile} covers arrow, trident, mob_projectile, fireball,
 * unattributed_fireball, wither_skull, thrown and wind_charge — read from the
 * shipped tag, not guessed.
 *
 * <p><b>Known limit, deliberately not papered over:</b> the extra knockback from
 * the Punch enchantment is applied by a separate call and is <em>not</em>
 * suppressed. A Punch bow will still move the player. Blocking that too would
 * mean a second hook on a path that has no damage source in scope; it was left
 * for a future session rather than guessed at.
 *
 * <p>apply()/remove() are empty by design -- see {@link HookEffectBehavior}.
 */
public final class IronWillBehavior extends HookEffectBehavior {

	public static final String ID = "iron_will";
}
