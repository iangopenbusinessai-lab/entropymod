package com.entropymod.harness;

/**
 * A per-tick reproduction of vanilla's horizontal player movement, used to turn
 * Slippery Grip's attribute arithmetic into blocks per second.
 *
 * <p><b>Every constant here was javap-verified against the 26.1.2 jars</b>, and
 * the model is validated in the harness against three independently published
 * vanilla figures -- walking 4.317 b/s, sprinting 5.612 b/s and sprint-jumping
 * ~7.12 b/s -- before any conclusion is drawn from it. That validation is the
 * point: it is what makes this evidence rather than arithmetic about itself, the
 * same discipline the jump-apex simulation follows for Embrace the Moon.
 *
 * <p>Where each constant comes from:
 *
 * <table border="1">
 *   <caption>Sources</caption>
 *   <tr><td>{@link #GROUND_FRICTION}</td><td>{@code Block.getFriction()}, default 0.6</td></tr>
 *   <tr><td>{@link #DRAG}</td><td>{@code travelInAir}: {@code f4 = f * 0.91f}</td></tr>
 *   <tr><td>{@link #INPUT_FRICTION}</td><td>{@code LivingEntity.applyInput()}, a named constant</td></tr>
 *   <tr><td>{@link #SPEED_CONST}</td><td>{@code getFrictionInfluencedSpeed}: {@code 0.21600002f / friction^3}</td></tr>
 *   <tr><td>{@link #SPRINT_JUMP_IMPULSE}</td><td>{@code jumpFromGround}'s {@code isSprinting} branch</td></tr>
 *   <tr><td>{@link #AIR_ACCEL_WALKING} / {@link #AIR_ACCEL_SPRINTING}</td>
 *       <td>{@code Player.getFlyingSpeed}'s non-flying branch</td></tr>
 * </table>
 *
 * <p>Deliberately free of Minecraft imports, like {@code CropSchedule},
 * {@code TramplePath} and {@code MovementScramble} -- it needs no registry and no
 * bootstrap, and a movement regression is exactly the kind of thing that is
 * invisible in play until it is measured.
 */
final class SprintModel {

	static final double GROUND_FRICTION = 0.6;
	static final double DRAG = 0.91;
	static final double INPUT_FRICTION = 0.98;
	static final double SPEED_CONST = 0.21600002;
	static final double GRAVITY = 0.08;
	static final double VERTICAL_DRAG = 0.98;
	static final double JUMP_POWER = 0.42;
	static final double SPRINT_JUMP_IMPULSE = 0.2;
	static final double AIR_ACCEL_WALKING = 0.02;
	static final double AIR_ACCEL_SPRINTING = 0.025999999;

	private static final int TICKS_PER_SECOND = 20;

	private SprintModel() {}

	/**
	 * Steady-state ground speed in blocks per second for a movement-speed
	 * attribute value.
	 *
	 * <p>The recurrence is {@code v <- (friction * 0.91) * v + a}: vanilla applies
	 * the previous tick's friction, then {@code moveRelative} adds the
	 * acceleration, and {@code move} displaces by the result. Getting that order
	 * the other way round is what makes a hand-derived figure come out at 2.4 b/s
	 * instead of 4.317.
	 */
	static double groundSpeedBps(double movementSpeedAttribute) {
		double a = groundAccel(movementSpeedAttribute);
		return a / (1.0 - GROUND_FRICTION * DRAG) * TICKS_PER_SECOND;
	}

	/**
	 * Ground distance covered from a standing start over a fixed number of ticks.
	 *
	 * <p>Same recurrence as {@link #groundSpeedBps}, run transiently rather than
	 * solved for its steady state, because the question Creeper Magnet asks is
	 * "how far can it get in one second from rest" -- and a mob that has just
	 * spawned is genuinely at rest, so the acceleration phase is most of the
	 * answer rather than a rounding detail.
	 *
	 * <p><b>For a MOB the argument is the speed SQUARED.</b>
	 * {@code Mob.setSpeed(f)} calls {@code setZza(f)} as well as
	 * {@code super.setSpeed(f)}, so a mob's forward input is its speed value
	 * rather than the normalised 1.0 a player's {@code ClientInput} supplies, and
	 * {@code moveRelative} multiplies the two. Passing the bare attribute would
	 * overstate a creeper by a factor of four.
	 */
	static double groundDistanceFromRest(double movementSpeedAttribute, int ticks) {
		double a = groundAccel(movementSpeedAttribute);
		double v = 0.0;
		double distance = 0.0;
		for (int t = 0; t < ticks; t++) {
			v = v * (GROUND_FRICTION * DRAG) + a;
			distance += v;
		}
		return distance;
	}

	private static double groundAccel(double movementSpeedAttribute) {
		return movementSpeedAttribute
				* (SPEED_CONST / (GROUND_FRICTION * GROUND_FRICTION * GROUND_FRICTION))
				* INPUT_FRICTION;
	}

	/**
	 * Mean speed in blocks per second of a player jumping continuously in a
	 * straight line over flat default ground.
	 *
	 * @param movementSpeedAttribute the ground movement-speed attribute total
	 * @param sprinting              whether the sprint bonuses apply at all
	 * @param impulseScale           what to multiply vanilla's 0.2 sprint-jump
	 *                               impulse by (1.0 for vanilla)
	 * @param airScale               what to multiply the sprinting airborne
	 *                               acceleration by (1.0 for vanilla)
	 */
	static double jumpingSpeedBps(double movementSpeedAttribute, boolean sprinting,
			double impulseScale, double airScale) {
		final int ticks = 8000;
		final int settle = ticks / 2;

		double vh = groundSpeedBps(movementSpeedAttribute) / TICKS_PER_SECOND;
		double vy = 0.0;
		double y = 0.0;
		boolean onGround = true;
		int noJumpDelay = 0;
		double distance = 0.0;

		for (int t = 0; t < ticks; t++) {
			// LivingEntity.aiStep runs the jump block before travel().
			if (onGround && noJumpDelay == 0) {
				vy = JUMP_POWER;
				if (sprinting) {
					vh += SPRINT_JUMP_IMPULSE * impulseScale;
				}
				noJumpDelay = 10;
			}
			if (noJumpDelay > 0) {
				noJumpDelay--;
			}

			// travelInAir captures the friction BEFORE move() updates onGround, so
			// the jump tick still gets ground acceleration. That is precisely why
			// bunny-hopping is fast in vanilla.
			double friction = onGround ? GROUND_FRICTION : 1.0;
			double accel = onGround
					? movementSpeedAttribute
							* (SPEED_CONST / (friction * friction * friction))
					: (sprinting ? AIR_ACCEL_SPRINTING * airScale : AIR_ACCEL_WALKING);
			vh += accel * INPUT_FRICTION;

			y += vy;
			if (t >= settle) {
				distance += vh;
			}

			if (y <= 0.0) {
				y = 0.0;
				onGround = true;
			} else {
				onGround = false;
			}

			vh *= friction * DRAG;
			vy = onGround ? 0.0 : (vy - GRAVITY) * VERTICAL_DRAG;
		}
		return distance / (ticks - settle) * TICKS_PER_SECOND;
	}
}
