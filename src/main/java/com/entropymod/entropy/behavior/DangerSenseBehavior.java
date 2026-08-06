package com.entropymod.entropy.behavior;

import com.entropymod.entropy.HookEffectBehavior;

/**
 * Danger Sense (GOOD / UTILITY, Tier 2) -- hostile mobs within
 * {@value #RADIUS} blocks glow, outlines visible through terrain.
 *
 * <h2>The mechanism is vanilla's Glowing effect on the MOB, not on the observer</h2>
 *
 * <p>Confirmed rather than assumed, and the trace matters because it is what
 * rules out a per-observer implementation:
 *
 * <pre>
 *   LivingEntity.isCurrentlyGlowing()          // override
 *       = (!level().isClientSide &amp;&amp; hasEffect(MobEffects.GLOWING))
 *         || super.isCurrentlyGlowing();
 *
 *   Entity.isCurrentlyGlowing()
 *       = level().isClientSide ? getSharedFlag(6) : hasGlowingTag;
 *
 *   LivingEntity.updateDirtyEffects() -&gt; updateGlowingStatus()
 *       = setSharedFlag(6, isCurrentlyGlowing());
 * </pre>
 *
 * <p>So on the server the Glowing {@code MobEffectInstance} drives shared entity
 * flag 6, and that flag is <b>synced entity data</b>. {@code updateGlowingStatus}
 * is reached from {@code updateDirtyEffects}, i.e. exactly when the effect set
 * changes -- so adding the effect is enough and no per-tick push of our own is
 * needed.
 *
 * <h2>Known limitation, reported rather than silently decided</h2>
 *
 * <p><b>The glow is visible to every player who can see the mob, not only to the
 * player holding this effect.</b> Flag 6 lives in the entity's
 * {@code SHARED_FLAGS_ID} synced data, which is broadcast to every client
 * tracking that entity; vanilla has no per-viewer glow anywhere. Making it
 * private would mean rewriting the tracked-data packet per connection -- real
 * work, not a flag, and outside this effect's scope.
 *
 * <p>This project is singleplayer-scoped, so in practice there is no observable
 * difference. On a shared world, a second player standing nearby sees the
 * outlines too.
 *
 * <h2>Why a refreshed short effect rather than add/remove bookkeeping</h2>
 *
 * <p>The glow is granted as a {@value #GLOW_DURATION_TICKS}-tick instance and
 * re-granted every {@value #SCAN_INTERVAL_TICKS} ticks while the mob is in
 * range. When the player walks away the effect simply expires. <b>This is
 * deliberately not "remove the effect when out of range"</b>: a blanket
 * {@code removeEffect(GLOWING)} would also strip a glow that came from a
 * spectral arrow or a command, and this effect has no way to tell whose glow it
 * is.
 *
 * <p>Re-granting cannot shorten someone else's longer glow either, because
 * {@code MobEffectInstance.update} keeps the stronger/longer of the two -- so a
 * spectral arrow's 10 seconds survives our 1-second refreshes intact.
 *
 * <p><b>The visible cost is a trailing glow of up to
 * {@value #GLOW_DURATION_TICKS} ticks</b> (1 second) after a mob leaves the
 * radius. That is the honest trade for not clobbering other sources, and it also
 * removes any flicker at the boundary.
 *
 * <h2>Cost -- an entity query, NOT a volume scan</h2>
 *
 * <p>This is the part worth stating plainly, because the radius invites the
 * wrong comparison with Green Thumb. A {@value #RADIUS}-block radius is a
 * 65x65x65 box -- <b>274,625 block positions</b>, 56x Green Thumb's 4,913, and
 * completely infeasible as a block sweep.
 *
 * <p>It is not one. {@code ServerLevel.getEntitiesOfClass(AABB, ...)} is served
 * by the level's entity section storage, so the cost is
 * <b>O(entities actually present in the box)</b> -- typically a handful, and
 * bounded by the world's mob cap rather than by the volume. The radius is
 * therefore nearly free to widen, which is the opposite of the block case.
 *
 * <ul>
 *   <li><b>O(players holding the effect x entities in a 65-block box) every
 *       {@value #SCAN_INTERVAL_TICKS} ticks.</b></li>
 *   <li>Each hit costs one {@code instanceof}, one squared-distance compare and,
 *       if in range, one {@code addEffect}.</li>
 *   <li>Nothing runs at all when no player holds the effect -- the acquired set
 *       is checked once per pass, before any query.</li>
 * </ul>
 *
 * <p>The box query is followed by a spherical {@code distanceToSqr} filter, so
 * the radius is a real 32-block sphere and not a box whose corners reach 55
 * blocks. That is what {@link #RADIUS_SQR} is for, and the harness asserts the
 * boundary in both directions.
 */
public final class DangerSenseBehavior extends HookEffectBehavior {

	public static final String ID = "danger_sense";

	/** Radius in blocks. The brief's 64-block diameter. */
	public static final double RADIUS = 32.0;

	/** Precomputed, because the filter runs per entity per scan. */
	public static final double RADIUS_SQR = RADIUS * RADIUS;

	/** How often the radius is swept. Half a second -- mobs move. */
	public static final int SCAN_INTERVAL_TICKS = 10;

	/**
	 * Granted glow duration. Must exceed {@link #SCAN_INTERVAL_TICKS} or the glow
	 * would flicker between scans; the surplus is the trailing glow after a mob
	 * leaves the radius. 2x the interval is the balance chosen.
	 */
	public static final int GLOW_DURATION_TICKS = 20;

	/**
	 * Glowing carries no attribute template and no amplifier-sensitive behaviour
	 * -- vanilla registers it as a bare {@code MobEffect(NEUTRAL, 9740385)}, the
	 * same shape as Blindness. So the amplifier is 0 because no other value would
	 * mean anything.
	 */
	public static final int AMPLIFIER = 0;

	/**
	 * Whether a squared distance is inside the radius. Free of Minecraft types so
	 * the harness can drive the boundary directly.
	 */
	public static boolean inRange(double distanceSqr) {
		return distanceSqr <= RADIUS_SQR;
	}
}
