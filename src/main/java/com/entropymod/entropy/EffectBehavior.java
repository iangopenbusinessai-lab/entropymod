package com.entropymod.entropy;

/**
 * What an effect actually <em>does</em>. One implementation per effect, in
 * {@code com.entropymod.entropy.behavior}, registered by id in
 * {@link EffectBehaviors}.
 *
 * <p>This is the per-effect-class pattern (CLAUDE.md Open Question 9, option b)
 * rather than one big switch: adding effect #47 is a new file plus one line in
 * {@link EffectBehaviors}, and never an edit to an existing behavior.
 *
 * <h2>The contract, now that effects are permanent</h2>
 *
 * <p><b>{@code apply} MUST be idempotent.</b> This is the single most important
 * rule in this interface and the easiest one to get wrong. {@code apply} is
 * called once when the effect is picked and then <em>again every time</em> the
 * player respawns, rejoins, or otherwise has their state rebuilt — an unbounded
 * number of times over a run. Calling it twice must leave the player in exactly
 * the same state as calling it once.
 *
 * <p>Concretely, for attribute modifiers that means
 * {@code addOrUpdateTransientModifier} (replaces by id) and never
 * {@code addTransientModifier} (adds a second copy). {@link AttributeEffectBehavior}
 * already does this correctly — extend it rather than hand-rolling the call.
 * For anything stateful, "set to X" is safe and "increment by X" is not.
 *
 * <p><b>{@code remove} is not an expiry hook any more.</b> Nothing expires.
 * It exists for two narrower cases: tearing an effect down when a run is reset,
 * and being the readable inverse of {@code apply} so the pair documents what the
 * effect touched. It must be safe to call on a player who never had the effect
 * applied — the server can restart mid-run and rebuild state in any order.
 *
 * <p>Both methods receive a specific {@link EffectContext#target()}; do not
 * reach for "the" player.
 */
public interface EffectBehavior {

	/**
	 * Establishes the effect on {@link EffectContext#target()}.
	 *
	 * <p>Called on a fresh pick AND on every respawn/rejoin. See the class javadoc:
	 * this MUST be idempotent. Check {@link EffectContext#isFreshPick()} if you
	 * need to do something once-only (a sound, a one-time grant) — but prefer not
	 * to need it.
	 */
	void apply(EffectContext ctx);

	/**
	 * Undoes exactly what {@link #apply} did, and nothing else. Must tolerate being
	 * called when the effect was never applied.
	 */
	void remove(EffectContext ctx);
}
