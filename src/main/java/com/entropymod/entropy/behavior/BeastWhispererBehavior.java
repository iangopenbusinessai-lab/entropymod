package com.entropymod.entropy.behavior;

import com.entropymod.entropy.HookEffectBehavior;

/**
 * Beast Whisperer (GOOD / COMPANION) -- animals never flee from you.
 *
 * <p>"Fleeing" turned out to be <b>two</b> distinct mechanisms, not one and not
 * a dozen. The scope question in the brief was whether this would require
 * per-species AI patching; it does not, and the reason is worth recording.
 *
 * <h2>1. Panic after being hurt — {@code PanicGoal}</h2>
 *
 * <p>{@code PanicGoal.shouldPanic()} is
 * {@code mob.getLastDamageSource() != null && mob.getLastDamageSource().is(<tag>)}.
 * It is keyed on the <b>damage source</b>, so the attacker is reachable through
 * {@code getLastDamageSource().getEntity()}. This is the single general mechanism
 * behind cows, pigs, sheep and chickens bolting when you hit them, and every mob
 * that panics inherits it from this one class.
 *
 * <p>{@code PanicGoalMixin} suppresses it <b>only</b> when the last damage came
 * from a player holding this effect. {@code PanicGoal.canUse()} is
 * {@code shouldPanic() || mob.isOnFire() || ...}, so injecting into
 * {@code shouldPanic} specifically leaves fire and freezing panic completely
 * intact — a burning cow still runs for water. That is the correct behaviour and
 * it falls out of picking the narrower method.
 *
 * <h2>2. Standing avoidance — {@code AvoidEntityGoal}</h2>
 *
 * <p>Foxes, ocelots and similar keep their distance without being provoked, via
 * {@code AvoidEntityGoal}. Crucially this is <b>one generic class parameterised
 * by the type to avoid</b>, not a per-species reimplementation:
 * {@code canUse()} resolves the nearest matching entity into the protected
 * {@code toAvoid} field. So a single mixin on {@code canUse} covers every species
 * that uses it, present and future.
 *
 * <p>{@code AvoidEntityGoalMixin} cancels the goal only when {@code toAvoid} is a
 * player with this effect. Avoidance of anything else is untouched — a rabbit
 * still flees wolves, a villager still flees zombies — because the gate is on the
 * resolved target, not on the goal's existence.
 *
 * <p>Together these are the honest full scope of "animals never flee from you".
 * Neither is a per-species hack, and no species-specific code was needed.
 *
 * <p>apply()/remove() are empty by design -- see {@link HookEffectBehavior}.
 */
public final class BeastWhispererBehavior extends HookEffectBehavior {

	public static final String ID = "beast_whisperer";
}
