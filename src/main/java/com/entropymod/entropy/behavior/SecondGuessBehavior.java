package com.entropymod.entropy.behavior;

import com.entropymod.entropy.HookEffectBehavior;

/**
 * Second Guess (GOOD / META) -- once per run, reroll the three options you are
 * currently being offered.
 *
 * <p>The first effect in the project that carries <b>run state of its own</b>
 * rather than being a pure function of membership in the acquired set. That state
 * is a single boolean, {@code rerollUsed}, and it lives in
 * {@link com.entropymod.entropy.EntropyManager} alongside entropy, pick count,
 * the acquired set and the history -- <b>not</b> in a parallel store. It is
 * therefore persisted, backed up and restored by exactly the same
 * {@code SavedData} codec as everything else, with no second source of truth.
 *
 * <h2>What rerolling does and does not do</h2>
 *
 * <ul>
 *   <li>It calls the real {@code triggerPick} -- the same private method the tick
 *       loop and {@code /entropyforcepick} use -- so the new options come from the
 *       real roll, with no-repeat and anti-stacking applied exactly as normal.
 *       It does not reimplement any of that.</li>
 *   <li>It does <b>not</b> spend entropy, advance the pick count, or write a
 *       history entry. It replaces the pending offer; it does not advance the
 *       loop. Those counters only move in {@code onChoiceMade}.</li>
 *   <li>It does <b>not</b> reset the interval timer.</li>
 * </ul>
 *
 * <h2>Why "once" really means once</h2>
 *
 * <p>The flag is on the run, not on the effect. Re-acquiring this effect
 * therefore cannot restore the reroll -- there is nothing in
 * {@code EffectBehavior.apply} that touches {@code rerollUsed}, and
 * {@link HookEffectBehavior}'s {@code apply} is {@code final} and empty, so a
 * future subclass cannot quietly add that either.
 *
 * <p>Re-acquisition is separately prevented by the no-repeat rule, but that is
 * treated as a second line of defence rather than the guarantee: the repeat
 * fallback can legitimately re-offer an already-taken effect once a phase's pool
 * is exhausted, so a design that relied on no-repeat alone would eventually be
 * wrong. Both properties are asserted in the headless harness.
 *
 * <p>apply()/remove() are empty by design -- see {@link HookEffectBehavior}.
 */
public final class SecondGuessBehavior extends HookEffectBehavior {

	public static final String ID = "second_guess";
}
