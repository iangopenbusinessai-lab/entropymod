package com.entropymod.entropy;

/**
 * What an effect actually <em>does</em>. One implementation per effect, in
 * {@code com.entropymod.entropy.behavior}, registered by id in
 * {@link EffectBehaviors}.
 *
 * <p>This is the per-effect-class pattern (CLAUDE.md Open Question 9, option b)
 * rather than one big switch: adding effect #47 is a new file plus one line in
 * {@link EffectBehaviors}, and never an edit to an existing behavior. The timer,
 * GUI, and networking layers never see this interface at all -- they only ever
 * deal in {@link EffectDefinition} data.
 *
 * <p>Contract, driven by {@link EffectDefinition#durationTicks()}:
 * <ul>
 *   <li>{@code 0} -- {@link #apply} is called once and {@link #remove} is
 *       <em>never</em> called. The effect is not tracked. One-shot effects must
 *       do all their work in {@code apply}.</li>
 *   <li>{@code -1} -- {@code apply} now, {@code remove} when the next interval
 *       fires, whenever that turns out to be.</li>
 *   <li>{@code >0} -- {@code apply} now, {@code remove} after that many server
 *       ticks.</li>
 * </ul>
 *
 * <p>{@code remove} must be safe to call on state that {@code apply} may not
 * have fully established (the server can restart mid-effect once persistence
 * lands), so write it defensively rather than assuming a matching apply ran.
 */
public interface EffectBehavior {

	/** Called once when the player picks this effect. */
	void apply(EffectContext ctx);

	/**
	 * Called once when the effect expires. Never called for duration-0 effects.
	 * Must undo exactly what {@link #apply} did and nothing else.
	 */
	void remove(EffectContext ctx);
}
