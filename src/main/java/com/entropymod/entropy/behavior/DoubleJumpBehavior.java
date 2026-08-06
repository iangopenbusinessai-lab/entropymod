package com.entropymod.entropy.behavior;

import com.entropymod.entropy.HookEffectBehavior;

/**
 * Double Jump (GOOD / MOVEMENT, Tier 2) -- one extra mid-air jump, recharged on
 * landing.
 *
 * <h2>Random Jump's mechanism does NOT transfer, and that is the first finding</h2>
 *
 * <p>The obvious move is Random Jump's: fake a jump press via
 * {@code ClientInput.makeJump()} and let vanilla do the rest. <b>That cannot work
 * mid-air.</b> {@code LivingEntity.aiStep}'s jump block is, javap-verified:
 *
 * <pre>
 *   if (this.jumping &amp;&amp; this.isAffectedByFluids()) {
 *       ...
 *       else if ((this.onGround() || (inWater &amp;&amp; fluidHeight &lt;= threshold))
 *                &amp;&amp; this.noJumpDelay == 0) {
 *           this.jumpFromGround();
 *           this.noJumpDelay = 10;
 *       }
 *   } else {
 *       this.noJumpDelay = 0;
 *   }
 * </pre>
 *
 * <p>The jump is gated on {@code onGround()}. Setting the jump bit while airborne
 * reaches that branch and is discarded -- so a forced press produces nothing, and
 * an implementation built on it would look like a broken effect rather than a
 * missing one.
 *
 * <p><b>{@code jumpFromGround()} itself has no such gate.</b> Its only guard is
 * {@code getJumpPower() &lt;= 1e-5}; it then sets
 * {@code deltaMovement.y = max(jumpPower, y)} and adds the sprint impulse. So
 * calling it directly mid-air is a real jump -- correct height, correct sprint
 * behaviour, and on {@code ServerPlayer} the jump statistic and food exhaustion
 * -- rather than a hand-rolled velocity poke. Same discipline as Green Thumb:
 * drive the schedule ourselves, perform the change through vanilla's own
 * transition.
 *
 * <h2>Why this is client-side, and why that is not a compromise</h2>
 *
 * <p><b>Player jumping is entirely client-driven in this version.</b>
 * {@code LivingEntity.jumping} is written in exactly one place for a player --
 * {@code LocalPlayer.applyInput()} -- and {@code ServerPlayer} never writes it at
 * all (zero {@code putfield}s, javap-verified). The server therefore never runs
 * the jump block for a player and has no jump state machine to keep in step with.
 *
 * <p>So there is no server half to write, and no two-sided scoping problem of the
 * kind Slippery Grip's mixins needed. The resulting motion reaches the server as
 * ordinary movement packets, exactly as a normal jump does.
 *
 * <h2>The charge, and the states where a jump is refused</h2>
 *
 * <p>One charge, spent on a rising edge of the jump key while airborne, restored
 * whenever the player is on the ground. Reading the charge from
 * {@code onGround()} rather than counting jumps is what makes a third jump
 * impossible by construction: there is no counter to get out of step, and falling
 * off a ledge without jumping still grants exactly one air jump.
 *
 * <p><b>Every state below was decided explicitly rather than left to fall out of
 * the code</b>, since "does nothing" and "is broken" are indistinguishable to a
 * player:
 *
 * <table border="1">
 *   <caption>Behaviour of a mid-air jump press, by state</caption>
 *   <tr><th>state</th><th>decision</th><th>why</th></tr>
 *   <tr><td>ordinary fall / after a normal jump</td><td><b>jumps</b></td>
 *       <td>the effect</td></tr>
 *   <tr><td>creative flight ({@code abilities.flying})</td><td><b>refused</b></td>
 *       <td>vertical movement is already unlimited; spending a charge would be a
 *           no-op the player cannot see, and Creative Flight is itself an
 *           acquirable effect</td></tr>
 *   <tr><td>elytra gliding ({@code isFallFlying()})</td><td><b>refused</b></td>
 *       <td>an upward velocity poke mid-glide fights the elytra's own flight
 *           model and would read as a stutter, not a jump</td></tr>
 *   <tr><td>in water / lava</td><td><b>refused</b></td>
 *       <td>vanilla already routes a held jump to {@code jumpInLiquid}, which
 *           gives continuous upward motion -- there is nothing to add, and
 *           spending the charge there would silently disarm the effect for the
 *           moment the player surfaces</td></tr>
 *   <tr><td>on a ladder or vine ({@code onClimbable()})</td><td><b>refused</b></td>
 *       <td>climbing is already free vertical movement, and a charge spent on a
 *           ladder is one not available on the fall afterwards</td></tr>
 *   <tr><td>riding an entity</td><td><b>refused</b></td>
 *       <td>the jump would apply to the passenger, not the mount, and detach
 *           nothing -- a confusing non-event</td></tr>
 * </table>
 *
 * <p>In every refused state the charge is <b>not</b> consumed, which is the half
 * that matters: the effect is intact the instant the state ends.
 */
public final class DoubleJumpBehavior extends HookEffectBehavior {

	public static final String ID = "double_jump";

	/**
	 * Air jumps available between landings. <b>One</b>, i.e. two jumps total.
	 *
	 * <p>Held as a constant rather than inlined so the harness can assert the
	 * "no third jump" rule against the shipped number instead of restating it.
	 */
	public static final int AIR_JUMPS = 1;
}
