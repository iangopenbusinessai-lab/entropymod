package com.entropymod.entropy.behavior;

import com.entropymod.entropy.AttributeEffectBehavior;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.List;

/**
 * Embrace the Moon (GOOD / MOVEMENT, Tier 2) -- lunar gravity, a slightly
 * stronger jump, and twice the safe landing distance.
 *
 * <p>Renamed from "Extreme Gravity"; the old id {@code extreme_gravity} no
 * longer exists, so a save from before the rename carries an id this build does
 * not define. That is handled the same way as any unknown id -- skipped with a
 * warning at load, rest of the run intact -- exactly as when
 * {@code heavy_footsteps} became {@code exposed}.
 *
 * <h2>The numbers, all from the validated per-tick model</h2>
 *
 * <p>The simulation is {@code y += v; v = (v - gravity) * 0.98} per tick, which
 * reproduces vanilla's known 1.2522-block jump and is therefore the thing every
 * value below is derived against rather than estimated from.
 *
 * <table border="1">
 *   <caption>Jump apex</caption>
 *   <tr><th>case</th><th>gravity</th><th>jump</th><th>apex</th><th>airtime</th><th>terminal v</th></tr>
 *   <tr><td>vanilla</td><td>0.0800</td><td>0.42000</td><td>1.252</td><td>12t</td><td>3.92/t</td></tr>
 *   <tr><td>Moon Walker (Tier 1)</td><td>0.0560</td><td>0.42000</td><td>1.657</td><td>16t</td><td>2.74/t</td></tr>
 *   <tr><td>gravity change alone</td><td>0.0176</td><td>0.42000</td><td>4.065</td><td>44t</td><td>0.86/t</td></tr>
 *   <tr><td><b>as shipped, with the jump bonus</b></td><td><b>0.0176</b></td><td><b>0.44940</b></td><td><b>4.567</b></td><td><b>47t</b></td><td><b>0.86/t</b></td></tr>
 * </table>
 *
 * <p>So a jump clears about <b>4.5 blocks</b> and hangs for 2.35 seconds.
 *
 * <h2>Why the jump bonus is +7% and not "+12% for +12% apex"</h2>
 *
 * <p><b>Apex is not linear in jump strength -- it goes roughly as the square of
 * the launch velocity.</b> {@link #JUMP_BONUS} is {@code +0.07} on the attribute
 * and produces <b>+12.35%</b> apex (4.065 -> 4.567), landing inside the intended
 * 10-15% band. Solving the real integration backwards: +10% apex needs +5.68%,
 * +15% needs +8.48%. Taking the apex percentage as the attribute percentage
 * would have overshot by roughly double.
 *
 * <h2>Safe fall distance is a REAL attribute, not a hardcoded constant</h2>
 *
 * <p>This was the open question and it resolved cleanly. {@code SAFE_FALL_DISTANCE}
 * is a registered {@code RangedAttribute} (default 3.0, min -1024, max 1024) and
 * is on the player. Verified consumer, in bytecode:
 *
 * <pre>
 *   calculateFallPower(d)        = d + 1e-6 - getAttributeValue(SAFE_FALL_DISTANCE)
 *   calculateFallDamage(d, mult) = floor(calculateFallPower(d) * mult
 *                                        * getAttributeValue(FALL_DAMAGE_MULTIPLIER))
 * </pre>
 *
 * <p>So it is exactly "the distance subtracted before any damage exists" -- a
 * genuinely different mechanic from {@code FALL_DAMAGE_MULTIPLIER}, which scales
 * what is left afterwards and is what Featherlight and Glass Jaw use.
 * <b>No mixin needed, and nothing is faked through the multiplier.</b>
 *
 * <p>Doubled, 3.0 -&gt; 6.0:
 *
 * <table border="1">
 *   <caption>Fall damage</caption>
 *   <tr><th>fall</th><th>vanilla</th><th>with this</th></tr>
 *   <tr><td>3 blocks</td><td>0</td><td>0</td></tr>
 *   <tr><td>6 blocks</td><td>3</td><td><b>0</b></td></tr>
 *   <tr><td>10 blocks</td><td>7</td><td>4</td></tr>
 *   <tr><td>20 blocks</td><td>17</td><td>14</td></tr>
 * </table>
 *
 * <p>It composes with Featherlight rather than duplicating it: this moves the
 * threshold, that scales the remainder.
 *
 * <h2>ADD_MULTIPLIED_TOTAL is now load-bearing, not merely preferable</h2>
 *
 * <p>Recomputed at this value rather than inherited from the -65% version, and
 * <b>the previous safety margin did not survive the increase</b>:
 *
 * <table border="1">
 *   <caption>Stacked with Moon Walker (-30%, ADD_MULTIPLIED_BASE)</caption>
 *   <tr><th>operation</th><th>resulting gravity</th><th>outcome</th></tr>
 *   <tr><td><b>ADD_MULTIPLIED_TOTAL (shipped)</b></td><td><b>+0.01232</b></td><td>apex 5.845, airtime 63t -- floaty, playable</td></tr>
 *   <tr><td>ADD_MULTIPLIED_BASE</td><td><b>-0.00640</b></td><td><b>NEGATIVE -- launched upward, permanently</b></td></tr>
 * </table>
 *
 * <p>At the old -65% the additive form still landed at +0.004, barely positive.
 * At -78% it crosses zero. Since {@code GRAVITY}'s clamp floors at -1.0 rather
 * than 0, {@code sanitizeValue} would pass that straight through. Multiplicative
 * composition cannot reach zero for any amount greater than -1, whatever else
 * stacks. Anti-stacking would normally keep two MOVEMENT effects apart, but it
 * is a <em>soft</em> rule dropped first when the pool empties, so this pairing is
 * genuinely reachable.
 */
public final class EmbraceTheMoonBehavior extends AttributeEffectBehavior {

	public static final String ID = "embrace_the_moon";

	/** -78%: gravity 0.0176, i.e. 22% of vanilla. ADD_MULTIPLIED_TOTAL -- see the class javadoc. */
	public static final double GRAVITY_AMOUNT = -0.78;

	/** +7% on JUMP_STRENGTH, which is +12.35% apex. Derived, not guessed. */
	public static final double JUMP_BONUS = 0.07;

	/** +3.0 on a base of 3.0: the safe landing distance is doubled to 6 blocks. */
	public static final double SAFE_FALL_BONUS = 3.0;

	public EmbraceTheMoonBehavior() {
		super(List.of(
				new Change(Attributes.GRAVITY, GRAVITY_AMOUNT,
						AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL),
				new Change(Attributes.JUMP_STRENGTH, JUMP_BONUS,
						AttributeModifier.Operation.ADD_MULTIPLIED_BASE),
				new Change(Attributes.SAFE_FALL_DISTANCE, SAFE_FALL_BONUS,
						AttributeModifier.Operation.ADD_VALUE)));
	}
}
