package com.entropymod.entropy;

import com.entropymod.entropy.behavior.DoubleJumpBehavior;

/**
 * Double Jump's charge machine: rising-edge detection plus the landing recharge.
 *
 * <p><b>Deliberately free of Minecraft imports</b>, the same discipline
 * {@code MovementScramble}, {@code TramplePath} and {@code KeybindSnapshot}
 * follow. The two rules that are easy to get wrong -- "recharges on landing" and
 * "never a third jump" -- are invisible in play until they are wrong, so they are
 * driven directly by the harness rather than inspected.
 *
 * <p>See {@link DoubleJumpBehavior} for why the charge is read from
 * {@code onGround()} rather than counted, and for the state table behind the
 * {@code allowed} argument.
 */
public final class DoubleJumpState {

	private int chargesLeft;
	private boolean jumpHeldLastTick;
	private boolean wasOnGround;

	/**
	 * Advances one tick and reports whether an air jump should fire now.
	 *
	 * <p><b>The edge is detected before anything else</b>, and both memory flags
	 * are updated on every call regardless of the outcome. If they were only
	 * updated on the paths that can jump, holding the key through a refused state
	 * (in water, on a ladder) and then leaving that state would register as a
	 * fresh press and fire a jump the player never asked for.
	 *
	 * <h2>{@code leftGroundThisTick} is the whole bug fix -- do not remove it</h2>
	 *
	 * <p>The first version had no such guard and produced "it just instantly
	 * jumps, and doesn't let me double jump". The state machine was right; the
	 * assumption about <em>when it is sampled</em> was wrong.
	 *
	 * <p>This is driven from {@code END_CLIENT_TICK}, i.e. after
	 * {@code LivingEntity.aiStep} has run. Inside that method, javap-verified,
	 * {@code jumpFromGround()} is at offset 460 and {@code travel()} -- which
	 * reaches {@code move()}, which is what writes {@code onGround} -- is at
	 * offset 615. <b>So on the very tick the player jumps from the ground,
	 * {@code onGround()} already reads false by the time this runs.</b> The
	 * caller therefore observes:
	 *
	 * <table border="1">
	 *   <caption>What END_CLIENT_TICK actually sees</caption>
	 *   <tr><th>tick</th><th>in game</th><th>observed</th><th>without the guard</th></tr>
	 *   <tr><td>N-1</td><td>standing, key up</td><td>held=false, ground=true</td>
	 *       <td>recharge</td></tr>
	 *   <tr><td>N</td><td>key pressed; vanilla jumps; travel lifts the player</td>
	 *       <td>held=<b>true</b>, ground=<b>false</b></td>
	 *       <td><b>rising edge + airborne + charge -&gt; air jump fires here</b></td></tr>
	 *   <tr><td>N+1</td><td>rising, key still held</td><td>held=true, ground=false</td>
	 *       <td>not rising; charge already spent</td></tr>
	 * </table>
	 *
	 * <p>So both jumps landed on the same tick. The visible result is not a
	 * doubled leap, because {@code jumpFromGround} sets
	 * {@code deltaMovement.y = max(jumpPower, y)} -- it re-raises y to full jump
	 * power one tick in rather than adding to it, giving a slightly longer,
	 * stronger-feeling single jump. (The sprint impulse <em>is</em> additive, so a
	 * sprinting player also got that twice.) And the charge was gone before the
	 * player was ever meaningfully airborne, which is the "doesn't let me double
	 * jump" half.
	 *
	 * <p>The rule the guard encodes: <b>a press first observed on the tick the
	 * player left the ground belongs to the ground jump.</b> It is consumed, not
	 * spent. Note the cost is bounded and correct rather than a fudge -- pressing
	 * jump on the exact tick you walk off a ledge is a press vanilla itself turned
	 * into a ground jump, because {@code onGround()} was still true when
	 * {@code aiStep}'s jump block ran.
	 *
	 * @param jumpHeld whether the jump key is down this tick
	 * @param onGround whether the player is on the ground
	 * @param allowed  whether the current state permits an air jump at all --
	 *                 false for flight, elytra, fluids, climbing and riding
	 * @return true if the caller should perform one jump
	 */
	public boolean tick(boolean jumpHeld, boolean onGround, boolean allowed) {
		boolean rising = jumpHeld && !jumpHeldLastTick;
		boolean leftGroundThisTick = wasOnGround && !onGround;
		jumpHeldLastTick = jumpHeld;
		wasOnGround = onGround;

		if (onGround) {
			// Recharge, and never jump from here -- the ground jump is vanilla's.
			chargesLeft = DoubleJumpBehavior.AIR_JUMPS;
			return false;
		}
		if (leftGroundThisTick) {
			// See the javadoc. This tick's press, if any, is the ground jump's own;
			// the assignments above have already consumed its edge, so holding the
			// key from here cannot fire either.
			return false;
		}
		if (!rising || !allowed || chargesLeft <= 0) {
			return false;
		}
		chargesLeft--;
		return true;
	}

	/** Charges remaining before the next landing. */
	public int chargesLeft() {
		return chargesLeft;
	}

	/**
	 * Drops all state. Called on disconnect, so a charge cannot survive into the
	 * next world -- same rule {@code ClientRunState} follows.
	 */
	public void reset() {
		chargesLeft = 0;
		jumpHeldLastTick = false;
		wasOnGround = false;
	}
}
