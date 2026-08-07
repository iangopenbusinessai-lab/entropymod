package com.entropymod.entropy.behavior;

import com.entropymod.entropy.HookEffectBehavior;

/**
 * Unstable (BAD / SURVIVAL, Tier 2) -- a stick of primed TNT appears near you
 * every 30 seconds, permanently.
 *
 * <p>The first effect in this project that spawns a threatening entity. The
 * position it spawns at comes from {@code SafeSpawn}, shared with Creeper Magnet;
 * this class holds the cadence, the fuse and the distance band, and the reasoning
 * behind each.
 *
 * <h2>It is real vanilla TNT, and it is real for a reason</h2>
 *
 * <p>{@code UnstableSpawner} constructs {@code new PrimedTnt(level, x, y, z,
 * null)} -- the same public constructor {@code TntBlock.prime} uses -- adds it
 * with {@code addFreshEntity}, and follows with the same
 * {@code SoundEvents.TNT_PRIMED} and {@code GameEvent.PRIME_FUSE} that block does.
 * Nothing is imitated: the entity carries vanilla's {@code explosionPower} of
 * {@code 4.0F}, explodes through {@code Level.explode(..., ExplosionInteraction
 * .TNT)}, respects the {@code tntExplodes} gamerule, destroys terrain, damages
 * mobs, can be shoved by other explosions and can be caught in water. A fake
 * would have had to reproduce all of that and would have got some of it wrong.
 *
 * <p>The owner is deliberately {@code null}, which is what
 * {@code TntBlock.prime(Level, BlockPos)} passes for TNT nobody lit.
 * {@code EntityReference.of(null)} returns null cleanly (verified in bytecode),
 * and the consequence is the death message: an unowned explosion reads
 * "<i>Player blew up</i>" rather than "<i>Player was blown up by Player</i>".
 * The first is the honest description of this curse.
 *
 * <h2>The fuse is VANILLA'S 80 ticks, and that is the argument for it</h2>
 *
 * <p>{@value #FUSE_TICKS} ticks is 4.0 seconds, and it is not a number chosen for
 * this effect -- it is the number {@code PrimedTnt}'s own constructor sets and the
 * number every Minecraft player has already internalised from lighting TNT
 * themselves. Reusing it means the reaction budget is one the player <em>already
 * has</em>; any other value would make correctly-remembered TNT timing wrong and
 * read as broken rather than as difficult. It is set explicitly from this
 * constant anyway, so the value is stated here rather than inherited silently.
 *
 * <h2>The distance band, derived from vanilla's own damage formula</h2>
 *
 * <p>{@code ExplosionDamageCalculator.getEntityDamageAmount} is, read off its
 * bytecode:
 *
 * <pre>
 *   maxDist = 2 * radius                     // radius 4.0 -> 8.0
 *   d       = distance(entity, centre) / maxDist
 *   impact  = (1 - d) * seenFraction
 *   damage  = (impact*impact + impact) / 2 * 7 * maxDist + 1
 * </pre>
 *
 * <p>At full exposure that gives, for a player who does <em>nothing at all</em>:
 *
 * <table border="1">
 *   <caption>Damage taken by a stationary, unarmoured player</caption>
 *   <tr><th>distance</th><th>damage</th><th>hearts</th></tr>
 *   <tr><td>4.0</td><td>22.00</td><td>11.0 -- lethal from full</td></tr>
 *   <tr><td><b>5.0 (min)</b></td><td><b>15.44</b></td><td><b>7.7</b></td></tr>
 *   <tr><td>6.0</td><td>9.75</td><td>4.9</td></tr>
 *   <tr><td><b>7.0 (max)</b></td><td><b>4.94</b></td><td><b>2.5</b></td></tr>
 *   <tr><td>8.0</td><td>1.00</td><td>0.5</td></tr>
 * </table>
 *
 * <p><b>{@value #MIN_DISTANCE} is the minimum because 4.0 kills and 5.0 does
 * not.</b> That is the design criterion, stated so a retune cannot lose it:
 * <i>ignoring the TNT completely, from full health, with no armour, must cost
 * most of the bar and must not kill.</i> One block closer and the effect becomes
 * an execution for being in a menu; the boundary is that sharp, which is why it
 * is asserted rather than remembered.
 *
 * <p><b>{@value #MAX_DISTANCE} is the maximum because 8.0 is nothing.</b> At the
 * blast's outer edge the formula's trailing {@code + 1} is all that is left, so
 * a wider band would spend half its triggers doing no personal damage at all and
 * the curse would read as random terrain vandalism. 7.0 still costs two and a
 * half hearts for ignoring it.
 *
 * <h2>Why this is genuinely counterplay-survivable, at the whole of Tier 2</h2>
 *
 * <p>Escaping means reaching 8.0 blocks, where damage is 1. From the worst case
 * -- the {@value #MIN_DISTANCE}-block minimum -- that is 3 blocks of travel, which
 * at the project's own measured walking speed of <b>4.3172 blocks/second</b>
 * (see {@code SprintModel}, validated against three published vanilla figures)
 * takes <b>0.70 s</b>. Sprinting takes 0.53 s. Against a 4.0-second fuse that
 * leaves over <b>three full seconds of slack</b> even after half a second of
 * reaction time. A player who notices at all escapes with room to spare.
 *
 * <p>And the notice is loud: {@code TNT_PRIMED} plays at volume 1.0, whose
 * {@code SoundEvent.getRange} is 16 blocks -- more than twice the maximum spawn
 * distance -- it is omnidirectional so it does not depend on facing, and vanilla's
 * own {@code PrimedTnt.tick} adds a smoke plume every tick. On top of that
 * {@code SafeSpawn} requires line of sight, so the TNT is never placed behind a
 * wall.
 *
 * <p><b>Verdict: {@code counterplay = true}, across the whole 25-50 band, not
 * only its upper end.</b> The two properties that carry it are independent of
 * entropy: the blast at the minimum distance is non-lethal from full health, and
 * the escape costs a fifth of the time available. Neither gets worse as the run
 * goes on. What does get worse is the accumulation -- two triggers per minute,
 * forever, cratering terrain -- and that is the effect, not a fairness problem.
 *
 * <p>Stated rather than hidden: a player already below four hearts who ignores a
 * minimum-distance trigger dies. That is the same bar every BAD effect in this
 * project is held to -- Flamboyant is {@code counterplay = true} and kills
 * outright on contact with fire -- and it is a bar about whether an answer exists,
 * not about whether the effect can ever be fatal.
 *
 * <h2>Category</h2>
 *
 * <p>SURVIVAL, shared with Creeper Magnet on purpose. Anti-stacking is keyed on
 * category, and "a lethal hazard materialises next to you on a timer" is one kind
 * of thing, not two -- a run holding both would be qualitatively different from a
 * run holding either. Sharing the category is what keeps the roll from offering
 * the second while the first is held.
 *
 * <p>apply()/remove() are empty by design -- see {@link HookEffectBehavior}. The
 * effect is entirely {@code UnstableSpawner} reading the acquired set.
 */
public final class UnstableBehavior extends HookEffectBehavior {

	public static final String ID = "unstable";

	/**
	 * 30 seconds, fixed. Unlike Creeper Magnet this is deliberately <em>not</em>
	 * randomised: a metronome is something the player can plan around -- finish
	 * the placement, then step back -- and that plannability is a real part of the
	 * counterplay. A random TNT interval would remove it for no gain.
	 */
	public static final int INTERVAL_TICKS = 600;

	/** Vanilla's own fuse: 80 ticks, 4.0 seconds. See the class javadoc. */
	public static final int FUSE_TICKS = 80;

	/** Closest the TNT may appear. Below this, ignoring it kills from full health. */
	public static final double MIN_DISTANCE = 5.0;

	/** Furthest it may appear. Above this, the blast reaches the player for ~1 damage. */
	public static final double MAX_DISTANCE = 7.0;

	/**
	 * Vanilla's TNT blast radius, restated here only so the damage table above can
	 * be re-derived and asserted. {@code PrimedTnt.explosionPower} is set to
	 * {@code 4.0F} in its own constructor and is not touched by this effect.
	 */
	public static final float BLAST_RADIUS = 4.0f;

	/**
	 * Vanilla's explosion damage at a given distance, for a fully exposed entity.
	 *
	 * <p>Free of Minecraft types so the harness can drive the table in the class
	 * javadoc against the real formula rather than against numbers typed next to
	 * it. This is a restatement of
	 * {@code ExplosionDamageCalculator.getEntityDamageAmount} with
	 * {@code seenFraction = 1}; it is not called by the effect.
	 */
	public static double blastDamageAt(double distance) {
		double maxDist = 2.0 * BLAST_RADIUS;
		double impact = 1.0 - distance / maxDist;
		return (impact * impact + impact) / 2.0 * 7.0 * maxDist + 1.0;
	}
}
