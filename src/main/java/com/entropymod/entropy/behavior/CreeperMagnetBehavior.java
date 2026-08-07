package com.entropymod.entropy.behavior;

import com.entropymod.entropy.HookEffectBehavior;

/**
 * Creeper Magnet (BAD / SURVIVAL, Tier 2) -- a creeper blinks into existence near
 * you every 30 seconds to 2 minutes, permanently.
 *
 * <p>Shares {@code SafeSpawn} with Unstable; this class holds the cadence, the
 * distance band and the invisibility window, with the reasoning for each.
 *
 * <h2>It is an ordinary creeper, and nothing about it is stripped down</h2>
 *
 * <p>{@code CreeperMagnetSpawner} builds it with
 * {@code EntityType.CREEPER.create(level, EntitySpawnReason.EVENT)} and runs
 * vanilla's own {@code finalizeSpawn} against the position's real
 * {@code DifficultyInstance}, so it gets the full goal set from
 * {@code Creeper.registerGoals} -- {@code SwellGoal}, {@code MeleeAttackGoal},
 * {@code AvoidEntityGoal} for cats and ocelots, {@code NearestAttackableTargetGoal},
 * {@code HurtByTargetGoal}, strolling and looking around. It swells over its
 * normal 30 ticks inside {@code SwellGoal}'s normal 3-block radius, can be hit,
 * bowed, shielded, blown up, lit by flint and steel, charged by lightning, and
 * scared off by a cat. Nothing is overridden.
 *
 * <p>Two things are deliberately <em>not</em> done, and both are the point:
 *
 * <ul>
 *   <li><b>{@code setPersistenceRequired()} is not called.</b> A persistent
 *       creeper never despawns, and one every 30-120 seconds forever would fill
 *       the world. Ordinary despawn rules are the pressure valve, and they are
 *       also what "a normal creeper" means.</li>
 *   <li><b>{@code checkSpawnRules} is not consulted.</b> That is the light-level
 *       and biome gate for <em>natural</em> spawning, and honouring it would turn
 *       this into "a creeper appears, but only in the dark" -- a different and
 *       much weaker effect. This is a summon, not a natural spawn, which is
 *       exactly what {@code EntitySpawnReason.EVENT} says.</li>
 * </ul>
 *
 * <p>The player is set as the creeper's initial target. Vanilla's own
 * {@code NearestAttackableTargetGoal} would acquire them within a tick or two
 * anyway at these distances, so this is not a new capability -- it is what makes
 * the trigger reliable instead of occasionally producing a creeper that wanders
 * off. Vanilla still owns dropping the target:
 * {@code TargetGoal.canContinueToUse} releases it on the ordinary range and
 * visibility rules.
 *
 * <h2>One second of invisibility CANNOT become a stealth kill -- derived, not assumed</h2>
 *
 * <p>{@value #INVISIBILITY_TICKS} ticks is exactly 1.0 second, granted with
 * particles off ({@code showParticles = false}) so the effect genuinely hides the
 * creeper rather than replacing it with a swirl of telltale motes. The intended
 * read is a creeper that pops into existence, not one that stalks you.
 *
 * <p>The question that decides whether that is true is: can it close from the
 * spawn distance to {@code SwellGoal}'s ignition radius while still invisible?
 * The arithmetic, from javap-verified constants:
 *
 * <ul>
 *   <li>{@code Creeper.createAttributes} sets {@code MOVEMENT_SPEED} to
 *       <b>0.25</b>, and {@code MeleeAttackGoal} drives it at
 *       {@code speedModifier} <b>1.0</b>.</li>
 *   <li><b>{@code Mob.setSpeed(f)} calls {@code super.setSpeed(f)} AND
 *       {@code setZza(f)}.</b> This is the non-obvious part: a mob's forward input
 *       is its speed value, not 1.0, so {@code moveRelative} multiplies the two
 *       and the effective ground acceleration is <b>speed squared</b> --
 *       {@code 0.25^2 = 0.0625} per tick. A player's is 0.1, with {@code zza}
 *       normalised to 1.0 by {@code ClientInput} and {@code Player.getSpeed()}
 *       overridden to return the bare attribute.</li>
 *   <li>So a chasing creeper's terminal ground speed is
 *       {@code 0.0625 / 0.1} of a walking player's <b>4.3172 blocks/second</b>,
 *       i.e. <b>~2.70 b/s</b> -- creepers really are slower than a walking player,
 *       and the factor is 0.625, not the 2.5 the raw attributes suggest.</li>
 * </ul>
 *
 * <p>In one second, from a standing start, that is <b>under 2.5 blocks</b> --
 * before any allowance for acquiring the target, turning, or pathing around
 * anything. From the {@value #MIN_DISTANCE}-block minimum the creeper is still
 * <b>at least 5.5 blocks</b> away when it becomes visible, against a 3-block
 * swell radius it then needs roughly two more seconds to reach. <b>The window is
 * far too short to be a threat in its own right, which is what makes it read as
 * an appearance.</b> That margin is the reason {@value #MIN_DISTANCE} is the
 * minimum, and it is asserted so a later retune cannot quietly erase it.
 *
 * <h2>The distance band</h2>
 *
 * <p><b>{@value #MIN_DISTANCE} blocks</b> is the invisibility-safety bound above,
 * with roughly a 5.5-block margin over the swell radius.
 *
 * <p><b>{@value #MAX_DISTANCE} blocks</b> is three quarters of the creeper's own
 * follow range, and the number it is three quarters of is not the obvious one.
 * <b>{@code Attributes.FOLLOW_RANGE}'s registered default is 32.0, but
 * {@code Mob.createMobAttributes()} overrides it to 16.0</b>, so a creeper's real
 * acquisition range is 16 -- verified against
 * {@code DefaultAttributes.getSupplier(EntityType.CREEPER)}, not read off the
 * attribute. A first pass at this effect used 16 blocks as the maximum on the
 * strength of the 32.0 default and the harness caught it.
 *
 * <p>That distinction is not cosmetic, because of the asymmetry already recorded
 * for Exposed: {@code TargetingConditions.test} scales the follow distance by
 * visibility when <em>acquiring</em>, but {@code TargetGoal.canContinueToUse}
 * compares against the <b>raw</b> follow range when <em>retaining</em>. A creeper
 * spawned at 16 blocks would therefore be handed a target and drop it on the next
 * tick, or on the first step the player took away. Twelve leaves a four-block
 * margin, and is still close enough that the model is unambiguously legible on
 * screen -- the appearance has to be <em>seen</em> to land. Danger Sense's 32
 * blocks was considered and rejected for exactly that: at 32 blocks a creeper is
 * a speck, and "popped into existence" stops reading. {@code SafeSpawn}
 * additionally requires line of sight, so the appearance is never behind a wall.
 *
 * <h2>The cadence</h2>
 *
 * <p>{@value #MIN_INTERVAL_TICKS} to {@value #MAX_INTERVAL_TICKS} ticks, 30
 * seconds to 2 minutes, re-rolled after every trigger -- the same scheduling
 * shape as Random Jump's 5-30 seconds, through the shared {@code SpawnSchedule}.
 * Randomised where Unstable's is fixed, and the difference is deliberate: a
 * creeper is a threat you have to <em>notice</em>, so unpredictability is the
 * whole texture of it, whereas TNT is a threat you have to <em>escape</em>, where
 * being able to plan around the clock is part of the counterplay.
 *
 * <h2>Category</h2>
 *
 * <p>SURVIVAL, shared with Unstable so anti-stacking keeps the two
 * hazard-on-a-timer curses apart. See {@link UnstableBehavior}.
 *
 * <p>apply()/remove() are empty by design -- see {@link HookEffectBehavior}.
 */
public final class CreeperMagnetBehavior extends HookEffectBehavior {

	public static final String ID = "creeper_magnet";

	/** 30 seconds. */
	public static final int MIN_INTERVAL_TICKS = 600;

	/** 2 minutes. */
	public static final int MAX_INTERVAL_TICKS = 2400;

	/**
	 * Exactly 1.0 second. Long enough to hide the spawn flash, far too short to
	 * close the distance -- see the derivation in the class javadoc.
	 */
	public static final int INVISIBILITY_TICKS = 20;

	/** Closest it may appear -- the invisibility-safety bound. */
	public static final double MIN_DISTANCE = 8.0;

	/**
	 * Furthest it may appear -- three quarters of the creeper's <em>real</em>
	 * 16-block follow range, which {@code Mob.createMobAttributes} sets and which
	 * is NOT the attribute's own 32.0 default.
	 */
	public static final double MAX_DISTANCE = 12.0;

	/**
	 * A creeper's actual {@code FOLLOW_RANGE}. Recorded because the attribute's
	 * registered default is 32.0 and {@code Mob.createMobAttributes()} overrides
	 * it -- reading the attribute instead of the entity type's supplier is the
	 * trap the harness pins.
	 */
	public static final double CREEPER_FOLLOW_RANGE = 16.0;

	/**
	 * {@code Creeper.createAttributes}' movement speed, and {@code SwellGoal}'s
	 * ignition radius. Restated here so the harness can re-derive the "cannot
	 * close in one second" claim rather than trusting the prose.
	 */
	public static final double CREEPER_MOVEMENT_SPEED = 0.25;

	/** {@code SwellGoal.canUse}: {@code distanceToSqr(target) < 9.0}. */
	public static final double SWELL_RADIUS = 3.0;
}
