package com.entropymod.entropy.behavior;

import com.entropymod.entropy.HookEffectBehavior;

/**
 * Heavy Footsteps (BAD / DEBUFF) -- loud footsteps, and sneaking no longer hides
 * you from hostile mobs.
 *
 * <p>This effect has been through two corrections, both from real in-game
 * testing, and both are recorded here because the underlying facts are not
 * guessable from the call sites alone.
 *
 * <h2>Part (a): louder footsteps</h2>
 *
 * <p>{@code Entity.playStepSound} ends in
 * {@code playSound(soundType.getStepSound(), soundType.getVolume() * 0.15f,
 * soundType.getPitch())}. {@code EntityStepSoundMixin} redirects that call and
 * scales the volume.
 *
 * <p><b>Correction: this does NOT increase the audible radius.</b> An earlier
 * version of this javadoc claimed it did. {@code SoundEvent.getRange(volume)} is
 * {@code volume > 1.0f ? 16.0f * volume : 16.0f}, so the broadcast radius is a
 * flat 16 blocks for any volume at or below 1.0. Vanilla footsteps are around
 * {@code 0.15}; at {@link #VOLUME_MULTIPLIER} they reach {@code 0.375}, still far
 * below the threshold. Louder, same distance. Actually widening the radius would
 * need a multiplier above ~6.7x, which would be comical rather than heavy.
 *
 * <p>Note also that {@code Player} overrides {@code playStepSound} and only falls
 * through to {@code Entity}'s implementation on ordinary ground -- the in-water
 * and combination-block branches play their sounds by other routes, so this does
 * not scale those.
 *
 * <h2>Part (b): mob detection -- what it actually does</h2>
 *
 * <p>{@code LivingEntityVisibilityMixin} scales
 * {@code LivingEntity.getVisibilityPercent}, which
 * {@code TargetingConditions.test} multiplies into the mob's follow range. That
 * much works. But <b>it cannot make mobs notice you from farther away than
 * normal</b>, and the reason is a genuine asymmetry in vanilla:
 *
 * <ul>
 *   <li><b>Acquisition</b> ({@code TargetingConditions.test}) uses
 *       {@code max(followDistance * visibilityPercent, 2.0)}.</li>
 *   <li><b>Retention</b> ({@code TargetGoal.canContinueToUse}) re-checks
 *       {@code mob.distanceToSqr(target) > followDistance * followDistance}
 *       using the <b>raw</b> follow distance, with no visibility term at all.</li>
 * </ul>
 *
 * <p>So a target acquired beyond the raw follow distance is dropped again
 * immediately. Effective detection is therefore clamped at {@code followDistance}
 * no matter how large the multiplier is. This was measured in game: a zombie
 * (follow range 35) still acquired at 35 blocks with the multiplier at 1.35,
 * exactly as this predicts.
 *
 * <p><b>The general law: {@code getVisibilityPercent} can only ever reduce
 * effective detection range, never extend it.</b> That is why vanilla only uses
 * values at or below 1.0 -- sneaking's 0.8, and invisibility's armour-based
 * fraction. Anything above 1.0 is silently discarded by the retention check.
 *
 * <p>What the effect therefore really does is <b>cancel the sneaking discount</b>,
 * and that is a real, observable, worthwhile curse: stealth stops working.
 */
public final class HeavyFootstepsBehavior extends HookEffectBehavior {

	public static final String ID = "heavy_footsteps";

	/**
	 * Footstep volume multiplier. 2.5x the vanilla {@code volume * 0.15f} -- clearly
	 * audible, still well under the 1.0 threshold where radius would change.
	 */
	public static final float VOLUME_MULTIPLIER = 2.5f;

	/**
	 * Detectability multiplier folded into {@code getVisibilityPercent}.
	 *
	 * <p><b>1.25 is the exact inverse of vanilla's sneaking value (0.8), and that
	 * is the entire reasoning.</b> {@code 0.8 * 1.25 == 1.0}, so sneaking under this
	 * curse returns you to exactly normal standing visibility: the curse removes the
	 * stealth advantage rather than punishing you for attempting to use it. The
	 * previous value of 1.35 was not anchored to anything and left a sneaking player
	 * at 1.08, i.e. marginally worse off than not sneaking at all.
	 *
	 * <p>It is also the <b>saturation point</b>, per the retention cap described in
	 * the class javadoc. Standing behaviour is identical for every multiplier at or
	 * above 1.0 (the raw follow distance clamps it), and sneaking behaviour is
	 * identical for every multiplier at or above 1.25 (0.8x is already fully
	 * cancelled). So values above 1.25 change nothing whatsoever -- they are not a
	 * stronger curse, just a number that does not do anything.
	 */
	public static final float DETECTION_MULTIPLIER = 1.25f;
}
