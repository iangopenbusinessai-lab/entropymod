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
 * <h2>The fuse is vanilla's 80 ticks PLUS ONE SECOND</h2>
 *
 * <p>{@value #FUSE_TICKS} ticks, 5.0 seconds. The first version used vanilla's own
 * 80 and argued for it on the grounds that the player's TNT intuition would
 * transfer unchanged -- a good argument, and it is being traded away deliberately
 * rather than forgotten. The band moved closer, so the reaction budget moved up to
 * pay for it.
 *
 * <p>The direction matters: a player calibrated on vanilla TNT now has a second
 * <em>in hand</em> rather than a second short, so mis-calibration is safe rather
 * than fatal. It is set explicitly from this constant, so the value is stated here
 * rather than inherited from whatever {@code PrimedTnt}'s constructor happens to
 * do.
 *
 * <h2>THE BAND IS SPHERICAL, and the first version's table was an upper bound</h2>
 *
 * <p>Both of these were got wrong once and are recorded so they are not got wrong
 * again. The effect shipped dealing about half a heart where the table said 15.44,
 * and there were two compounding causes:
 *
 * <ol>
 *   <li><b>The spawn band was decoupled from the quantity the damage depends on.</b>
 *       A later session moved {@code SafeSpawn}'s band from 3D to horizontal and
 *       widened the vertical search to 8 -- correct for Creeper Magnet, wrong here,
 *       because explosion damage reads the <em>3D</em> distance and
 *       {@code hurtEntities} discards an entity past {@code 2 * radius} entirely.
 *       A "5-7 block" TNT could sit 10.63 blocks away in 3D. Measured against the
 *       terrain it was played on, <b>25.9% of spawns landed outside the blast and
 *       did nothing at all</b>, and the median was 6.82 blocks rather than 6.0.
 *       Fixed by {@link com.entropymod.entropy.spawn.SafeSpawn.DistanceMode#SPHERICAL}.</li>
 *   <li><b>The damage table assumed full exposure.</b> Vanilla multiplies
 *       {@code impact} by {@code ServerExplosion.getSeenPercent}, a grid of rays
 *       from the player's bounding box to the explosion centre. The first
 *       derivation set that to 1.0 and presented the result as the damage rather
 *       than as its ceiling. See {@link #blastDamage}.</li>
 * </ol>
 *
 * <p><b>Both produce exactly the reported symptom</b>, because a blast at the
 * cull edge and a fully-obstructed blast both drive {@code impact} to 0, and the
 * formula's trailing {@code + 1} is then the entire result: 1.0 damage, half a
 * heart.
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
 *   <caption>Damage to a stationary, unarmoured player (20 HP), by exposure</caption>
 *   <tr><th>distance</th><th>exp 1.00</th><th>exp 0.75</th><th>exp 0.50</th><th>exp 0.25</th></tr>
 *   <tr><td>2.0</td><td><b>37.75 -- kills</b></td><td>25.61</td><td>15.44</td><td>7.23</td></tr>
 *   <tr><td>3.0</td><td><b>29.44 -- kills</b></td><td>20.28</td><td>12.48</td><td>6.06</td></tr>
 *   <tr><td>4.0</td><td><b>22.00 -- kills</b></td><td>15.44</td><td>9.75</td><td>4.94</td></tr>
 *   <tr><td>4.29</td><td>20.01 -- the threshold</td><td>14.13</td><td>9.00</td><td>4.62</td></tr>
 *   <tr><td><b>4.5 (min)</b></td><td><b>18.61</b></td><td>13.20</td><td>8.46</td><td>4.40</td></tr>
 *   <tr><td>5.5</td><td>12.48</td><td>9.10</td><td>6.06</td><td>3.36</td></tr>
 *   <tr><td><b>6.5 (max)</b></td><td><b>7.23</b></td><td>5.49</td><td>3.87</td><td>2.37</td></tr>
 *   <tr><td>8.0</td><td>1.00</td><td>1.00</td><td>1.00</td><td>1.00</td></tr>
 *   <tr><td>8.5</td><td colspan="4">0 -- culled by hurtEntities, no damage at all</td></tr>
 * </table>
 *
 * <p><b>Only the exposure-1.00 column may be used for tuning</b>, because it is
 * the worst case for the player and the counterplay rule is a worst-case rule. The
 * other columns exist to explain what play actually feels like: the same TNT does
 * very different damage depending on what is between it and you.
 *
 * <p><b>{@value #MIN_DISTANCE} is the minimum because 4.29 is where the blast
 * exactly kills</b> -- solved from the quadratic in
 * {@link #lethalThresholdDistance}, not read off the table. A player who ignores a
 * minimum-distance TNT entirely survives on 1.39 HP. <b>A closer band was
 * requested and could not be shipped:</b> 4.0, 3.0 and 2.0 blocks deal 22.0, 29.4
 * and 37.75 against a 20 HP bar, so the whole of a 2-4 range is a guaranteed kill
 * for anyone who does not react, which is not what {@code counterplay = true}
 * means.
 *
 * <p><b>{@value #MAX_DISTANCE} is the maximum because the blast is culled at
 * 8.0.</b> Past {@code 2 * radius} vanilla skips the entity outright, so a band
 * reaching that far would spend its tail doing literally nothing -- which is
 * precisely the failure this session diagnosed.
 *
 * <h2>Why this is genuinely counterplay-survivable, at the whole of Tier 2</h2>
 *
 * <p><b>Recomputed for the new fuse and the new band, not carried over.</b>
 * Escaping means reaching 8.0 blocks, where the blast is culled. From the worst
 * case -- the {@value #MIN_DISTANCE}-block minimum -- that is <b>3.5</b> blocks of
 * travel, which at the project's own measured walking speed of <b>4.3172
 * blocks/second</b> (see {@code SprintModel}, validated against three published
 * vanilla figures) takes <b>0.81 s</b>; sprinting at 5.6123 b/s takes 0.62 s.
 * Against the 5.0-second fuse that leaves <b>3.69 s of slack</b> after half a
 * second of reaction time.
 *
 * <p>So the escape budget <em>improved</em> even though the TNT moved closer --
 * 3.69 s of slack against the old band's 2.81 s -- because the extra second of
 * fuse more than pays for the extra half block of running. That is the trade the
 * two changes make together, and it is why they were made together.
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

	/**
	 * 100 ticks, 5.0 seconds -- vanilla's own 80 plus one second.
	 *
	 * <p>No longer vanilla's number, and that is a deliberate trade made with the
	 * distance change: the band moved closer, so the reaction budget moved up to
	 * pay for it. A player whose TNT intuition is calibrated on vanilla now has a
	 * second in hand rather than a second short, which is the safe direction to be
	 * wrong in.
	 */
	public static final int FUSE_TICKS = 100;

	/**
	 * Closest the TNT may appear -- and it is pinned to
	 * {@link #lethalThresholdDistance}, not chosen.
	 *
	 * <p><b>4.29 blocks is where a fully-exposed blast exactly kills a full-health,
	 * unarmoured player.</b> 4.5 is the nearest sensible value above it, and leaves
	 * 1.39 HP for a player who ignores the TNT completely. Anything closer breaks
	 * the counterplay rule outright: 4.0 blocks deals 22.0, 3.0 deals 29.4 and 2.0
	 * deals 37.75 -- nearly twice a health bar.
	 */
	public static final double MIN_DISTANCE = 4.5;

	/** Furthest it may appear. At 8.0 the blast is culled entirely; 6.5 still costs 3.6 hearts. */
	public static final double MAX_DISTANCE = 6.5;

	/**
	 * Vanilla's TNT blast radius, restated here only so the damage table above can
	 * be re-derived and asserted. {@code PrimedTnt.explosionPower} is set to
	 * {@code 4.0F} in its own constructor and is not touched by this effect.
	 */
	public static final float BLAST_RADIUS = 4.0f;

	/** {@code 2 * radius} -- the blast's outer edge, beyond which vanilla skips the entity. */
	public static final double BLAST_REACH = 2.0 * BLAST_RADIUS;

	/**
	 * Vanilla's explosion damage, including the EXPOSURE factor the first
	 * derivation left out.
	 *
	 * <p>Free of Minecraft types so the harness can drive it against the real
	 * formula rather than against numbers typed next to it. It is not called by the
	 * effect -- it exists to keep the tuning honest.
	 *
	 * <p>Two things here that the single-argument first version got wrong:
	 *
	 * <ul>
	 *   <li><b>The hard cull.</b> {@code ServerExplosion.hurtEntities} computes
	 *       {@code d = distance / (2 * radius)} and {@code continue}s when
	 *       {@code d > 1.0}. Past 8.0 blocks the entity is not damaged at all --
	 *       not "one damage", none. The old function happily returned values there.</li>
	 *   <li><b>The exposure factor.</b> {@code getSeenPercent(centre, entity)} fires
	 *       a grid of rays from the entity's bounding box at the explosion centre
	 *       and returns the unobstructed fraction; that fraction multiplies
	 *       {@code impact} before the quadratic. It is 1.0 only with nothing in
	 *       between.</li>
	 * </ul>
	 *
	 * @param distance 3D distance from the player's feet to the explosion centre
	 * @param exposure {@code getSeenPercent}'s result, in [0, 1]
	 */
	public static double blastDamage(double distance, double exposure) {
		if (distance / BLAST_REACH > 1.0) {
			return 0.0;
		}
		double impact = (1.0 - distance / BLAST_REACH) * exposure;
		return (impact * impact + impact) / 2.0 * 7.0 * BLAST_REACH + 1.0;
	}

	/**
	 * The damage an unobstructed blast does -- i.e. the WORST case for the player,
	 * and therefore the only figure the counterplay rule may be checked against.
	 *
	 * <p>Kept as its own name because the first version called this
	 * {@code blastDamageAt} and presented it as "the damage", which is what let a
	 * table of upper bounds be read as expected values.
	 */
	public static double maxBlastDamage(double distance) {
		return blastDamage(distance, 1.0);
	}

	/**
	 * The closest a fully-exposed blast may be and still leave a full-health,
	 * unarmoured player alive. Solved from the quadratic, not tabulated.
	 *
	 * <p>{@code 4.29} blocks. This is the number the distance band's minimum has to
	 * respect, and it is why the requested 2-4 range could not be shipped as asked.
	 */
	public static double lethalThresholdDistance(double health) {
		// (i^2 + i)/2 * 7 * R + 1 = health  ->  i^2 + i - 2*(health-1)/(7*R) = 0
		double c = 2.0 * (health - 1.0) / (7.0 * BLAST_REACH);
		double impact = (-1.0 + Math.sqrt(1.0 + 4.0 * c)) / 2.0;
		return BLAST_REACH * (1.0 - impact);
	}
}
