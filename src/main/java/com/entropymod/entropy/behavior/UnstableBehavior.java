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
 *   <tr><td><b>0.0 (min)</b></td><td><b>57.00</b></td><td>37.75</td><td>22.00</td><td>9.75</td></tr>
 *   <tr><td>1.0</td><td><b>46.94</b></td><td>31.43</td><td>18.61</td><td>8.46</td></tr>
 *   <tr><td><b>2.0 (max)</b></td><td><b>37.75</b></td><td>25.61</td><td>15.44</td><td>7.23</td></tr>
 *   <tr><td>4.29</td><td>20.01 -- the old threshold</td><td>14.13</td><td>9.00</td><td>4.62</td></tr>
 *   <tr><td>8.0</td><td>1.00</td><td>1.00</td><td>1.00</td><td>1.00</td></tr>
 *   <tr><td>8.5</td><td colspan="4">0 -- culled by hurtEntities</td></tr>
 * </table>
 *
 * <p><b>Every point in the band is lethal at full exposure</b> -- 1.9x a health
 * bar at the far end and 2.9x at the near end. That is now the intended design,
 * not a tuning miss: an unavoidable-if-ignored blast every 30 seconds.
 *
 * <h2>counterplay = FALSE -- and the precedent it was justified by DOES NOT EXIST</h2>
 *
 * <p>This flag was requested on the stated grounds that it matches Flamboyant.
 * <b>Checked, and it does not: Flamboyant is registered
 * {@code counterplay = true}</b>, despite its description being "catching fire
 * kills you outright". Grepping the registry, <b>this effect is now the only
 * {@code counterplay = false} entry among all 51</b>.
 *
 * <p>So the flag has never meant "cannot kill you" in this project -- it has meant
 * "an in-game answer exists", which is why an effect that kills outright still
 * carries {@code true}. Under that reading Unstable would qualify as {@code true}
 * as well: the answer is "move", and there are 2.65 s of slack even from zero.
 *
 * <p><b>It ships as {@code false} anyway, because that is the more honest flag</b>
 * -- an effect that kills a stationary player at every point in its band should
 * not advertise itself as survivable -- and because it was explicitly asked for.
 * But the justification is "this is a stricter label than the codebase has used
 * so far", not "Flamboyant already did this".
 *
 * <h2>Entropy 40-60 -- the invariant conflict, RESOLVED</h2>
 *
 * <p>CLAUDE.md Part 2 states: <i>"Bad effects below entropy 40 must be
 * counterplay-survivable (no unavoidable-death effects until later tiers)."</i>
 * This effect shipped briefly at 25-50 with {@code counterplay = false}, which
 * violated that outright -- and could not be excused by precedent, since it is the
 * only {@code false} effect in the registry.
 *
 * <p><b>The floor is now 40, which honours the invariant exactly and changes
 * nothing else.</b> The damage model, the 0-2 distance band, the 100-tick fuse and
 * the spherical placement are all untouched; only the entropy window moved. This is
 * the same shape as Glass Cannon Pact, which already sits above the rest of Tier 2
 * because its cost compounds with a long run.
 *
 * <p>Note the flag itself remains stricter than the codebase's own convention:
 * Flamboyant is registered {@code counterplay = true} despite "catching fire kills
 * you outright", so {@code true} has historically meant "an in-game answer exists"
 * rather than "cannot kill you". Unstable is labelled {@code false} because an
 * effect that kills a stationary player at every point in its band should not
 * advertise itself as survivable.
 *
 * <p>What genuinely does hold on its own merits, independent of either choice, is
 * that the effect is <b>avoidable</b>: {@code TNT_PRIMED} carries 16 blocks --
 * eight times the maximum spawn distance -- omnidirectionally, vanilla adds a
 * per-tick smoke plume, {@code SafeSpawn} requires line of sight, and reaching the
 * 8.0-block cull from the worst case (TNT at the player's feet) is 8 blocks,
 * <b>1.85 s</b> walking at 4.3172 b/s, leaving <b>2.65 s of slack</b> after half a
 * second of reaction. From the 2.0 maximum it is 1.39 s and 3.11 s of slack.
 *
 * <p>One consequence worth stating: at 0.0 the TNT can spawn <em>inside</em> the
 * player's own block. It is placed by the same {@code SafeSpawn} ON_GROUND test as
 * before, so it still lands on solid ground and never inside terrain -- but the
 * band no longer keeps it at arm's length, and that is the point.
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
	 * Closest the TNT may appear: <b>right on top of the player</b>.
	 *
	 * <p>0.0 is deliberate and is a change of what this effect IS. Every previous
	 * value was pinned to {@link #lethalThresholdDistance} so that ignoring the TNT
	 * could never kill from full health; that constraint has been dropped, and with
	 * it {@code counterplay}. See the class javadoc.
	 */
	public static final double MIN_DISTANCE = 0.0;

	/** Furthest it may appear. Still deep inside the blast -- 37.75 damage at full exposure. */
	public static final double MAX_DISTANCE = 2.0;

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
