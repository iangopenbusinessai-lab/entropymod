package com.entropymod.entropy.behavior;

import com.entropymod.entropy.HookEffectBehavior;

/**
 * Heavy Footsteps (BAD / DEBUFF) -- your footsteps are loud and hostile mobs
 * notice you from farther away.
 *
 * <p>This effect was specced as two parts, and the investigation changed what the
 * second part had to be. Both are recorded here because the difference is the
 * kind of thing a later session would otherwise re-derive incorrectly.
 *
 * <h2>Part (a): louder footsteps — real, straightforward</h2>
 *
 * <p>{@code Entity.playStepSound(BlockPos, BlockState)} ends in
 * {@code playSound(soundType.getStepSound(), soundType.getVolume() * 0.15f,
 * soundType.getPitch())}. {@code EntityStepSoundMixin} redirects that call and
 * scales the volume. This is not purely cosmetic: vanilla derives a sound's
 * audible radius from its volume, so louder footsteps genuinely carry to players
 * farther away.
 *
 * <h2>Part (b): "mobs notice you from farther away" — NOT via sound</h2>
 *
 * <p><b>General hostile mob AI does not listen.</b> {@code Sensing}, the mob
 * perception class, has exactly one query — {@code hasLineOfSight(Entity)}. There
 * is no hearing, no noise radius, and no footstep-aware targeting anywhere in
 * ordinary mob AI. The only sound-driven perception in the game is the
 * vibration/sculk system (Warden, sculk sensors), which reacts to game events and
 * would have covered one mob rather than "mobs".
 *
 * <p>What <em>does</em> exist is a general detectability multiplier.
 * {@code TargetingConditions.test} — the shared gate behind essentially every
 * hostile targeting goal — computes its effective range as
 * {@code target.getVisibilityPercent(attacker) * range}, floored at 2.
 * {@code LivingEntity.getVisibilityPercent} is the same lever vanilla itself uses
 * to make sneaking work: crouching returns {@code 0.8}, i.e. mobs notice you from
 * 20% closer. {@code LivingEntityVisibilityMixin} pushes it the other way.
 *
 * <p>So part (b) is delivered through the real, general mechanism for exactly
 * this concept, and it applies to every mob whose targeting goes through
 * {@code TargetingConditions} rather than to one special case. It is honestly
 * <em>not</em> sound propagation, and the effect description says "notice you
 * from farther away" rather than claiming mobs hear the footsteps.
 */
public final class HeavyFootstepsBehavior extends HookEffectBehavior {

	public static final String ID = "heavy_footsteps";

	/**
	 * Footstep volume multiplier. 2.5x the vanilla {@code volume * 0.15f}, which
	 * is clearly audible without being comical.
	 */
	public static final float VOLUME_MULTIPLIER = 2.5f;

	/**
	 * Detectability multiplier folded into {@code getVisibilityPercent}. 1.35 is a
	 * deliberate mirror of the scale vanilla uses for sneaking (0.8): noticeable,
	 * and in the same order of magnitude as an existing vanilla effect on the same
	 * value, rather than an invented number.
	 */
	public static final float DETECTION_MULTIPLIER = 1.35f;
}
