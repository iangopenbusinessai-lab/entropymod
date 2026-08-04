package com.entropymod.entropy.behavior;

import com.entropymod.entropy.HookEffectBehavior;

/**
 * Second Chance (GOOD / SURVIVAL, Tier 2) -- once per run, a killing blow leaves
 * you at one heart instead of dead.
 *
 * <h2>The death-prevention hook is vanilla's own, not a new one</h2>
 *
 * <p>The hook is {@code LivingEntity.checkTotemDeathProtection(DamageSource)} --
 * the Totem of Undying's escape hatch. Verified in {@code hurtServer}'s bytecode,
 * the surrounding shape is:
 *
 * <pre>
 *   if (this.isDeadOrDying()) {
 *       if (!this.checkTotemDeathProtection(source)) {
 *           ... death sound, die(source) ...
 *       }
 *   }
 * </pre>
 *
 * <p>So <b>returning {@code true} skips the entire death branch</b>, and it is
 * evaluated <em>after</em> health has already been reduced to zero. Two
 * consequences that decide the implementation:
 *
 * <ul>
 *   <li><b>Restoring health is mandatory, not decoration.</b> The player is at 0
 *       when this runs; returning true without a {@code setHealth} leaves them
 *       alive on an empty bar and dead again on the next tick. Vanilla's own
 *       totem branch does exactly the same {@code setHealth} for the same
 *       reason.</li>
 *   <li>Everything downstream -- no death message, no drops, no respawn screen,
 *       statistics untouched -- follows from vanilla's own control flow rather
 *       than being suppressed piece by piece.</li>
 * </ul>
 *
 * <p><b>{@code checkTotemDeathProtection} is {@code private}</b>, so it cannot be
 * overridden and the subclass-override trap cannot apply. It does run for every
 * living entity, so the {@code instanceof Player} guard is load-bearing.
 *
 * <p>The {@code BYPASSES_INVULNERABILITY} check is kept, matching vanilla's own
 * first line: {@code /kill} and the void still kill. Making a mod effect immune
 * to {@code /kill} would be a debugging trap.
 *
 * <h2>"Once per run" is a run flag, not the acquired set</h2>
 *
 * <p>Exactly the pattern Second Guess established, and reused rather than
 * reinvented: one {@code optionalFieldOf("second_chance_used", false)} in
 * {@code EntropyManager}'s existing codec. Consuming the effect does two things,
 * and <b>only the first is what enforces "once"</b>:
 *
 * <ol>
 *   <li>The persisted flag is set. This survives respawn, relog and a full
 *       save/reload, and <b>re-acquiring the effect cannot refund it</b> because
 *       the flag is on the run rather than on the effect.</li>
 *   <li>The id is dropped from {@link com.entropymod.entropy.AcquiredEffects} so
 *       the effect stops being listed as held. That is presentation, not
 *       enforcement -- and it deliberately makes the effect eligible to be
 *       offered again, which the flag then renders inert.</li>
 * </ol>
 *
 * <p>A design resting on the acquired set alone would eventually be wrong: the
 * repeat fallback can legitimately re-offer an already-taken effect once a
 * phase's pool empties.
 */
public final class SecondChanceBehavior extends HookEffectBehavior {

	public static final String ID = "second_chance";

	/** Health left after the save. 2.0 raw = 1 heart. */
	public static final float SURVIVE_HEALTH = 2.0f;

	/**
	 * Ticks of immunity afterwards, so the blow that would have killed cannot
	 * simply land again on the next tick. {@code Entity.invulnerableTime} is
	 * vanilla's own damage-cooldown field -- the same one a normal hit sets to 20
	 * -- so this is 3 seconds of the mechanic that already exists rather than a
	 * bespoke invulnerability state.
	 */
	public static final int INVULNERABLE_TICKS = 60;
}
