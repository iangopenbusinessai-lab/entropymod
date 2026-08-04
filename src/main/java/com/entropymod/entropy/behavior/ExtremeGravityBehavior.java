package com.entropymod.entropy.behavior;

import com.entropymod.entropy.AttributeEffectBehavior;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Extreme Gravity (GOOD / MOVEMENT, Tier 2) -- gravity cut to 35% of normal.
 *
 * <p>The heavier sibling of {@link MoonWalkerBehavior} (Tier 1, -30%). Values
 * below are from the same per-tick simulation that reproduces vanilla's known
 * 1.2522-block jump, not from estimation:
 *
 * <table border="1">
 *   <caption>Jump apex by gravity</caption>
 *   <tr><th>effect</th><th>gravity</th><th>apex</th><th>airtime</th><th>terminal velocity</th></tr>
 *   <tr><td>vanilla</td><td>0.0800</td><td>1.252</td><td>12t</td><td>3.92/t</td></tr>
 *   <tr><td>Moon Walker</td><td>0.0560</td><td>1.657</td><td>16t</td><td>2.74/t</td></tr>
 *   <tr><td><b>Extreme Gravity</b></td><td><b>0.0280</b></td><td><b>2.866</b></td><td><b>29t</b></td><td><b>1.37/t</b></td></tr>
 * </table>
 *
 * <p>So this clears a <b>2.5-block</b> ledge where Moon Walker clears 1.5 and
 * vanilla clears 1 -- 1.73x Moon Walker's apex and nearly double its airtime.
 * The two are meaningfully different rather than two points on the same slider.
 *
 * <h2>Why ADD_MULTIPLIED_TOTAL and not ADD_MULTIPLIED_BASE</h2>
 *
 * <p><b>This is a safety property, not a style choice, and it is the reason the
 * operation differs from Moon Walker's.</b> Verified in
 * {@code AttributeInstance.calculateValue}'s bytecode:
 *
 * <ul>
 *   <li>{@code ADD_MULTIPLIED_BASE} accumulates <em>additively</em>
 *       ({@code value += base * amount}). Two such reductions sum, so
 *       -30% and -65% together give -95%.</li>
 *   <li>{@code ADD_MULTIPLIED_TOTAL} composes <em>multiplicatively</em>
 *       ({@code value *= 1 + amount}), which for any amount &gt; -1 can never
 *       reach zero no matter how many stack.</li>
 * </ul>
 *
 * <p>That matters because <b>GRAVITY's clamp offers no protection at all in the
 * dangerous direction</b> -- its floor is -1.0, so {@code sanitizeValue} passes
 * 0.0 straight through (float forever, no way down) and negative values through
 * unchanged (launched upward permanently). Confirmed against the live registry.
 *
 * <p>Anti-stacking normally keeps this and Moon Walker apart, since both are
 * MOVEMENT -- but that is a <em>soft</em> rule that is dropped first when the
 * pool empties, so the pairing is genuinely reachable. Measured:
 *
 * <ul>
 *   <li>As shipped (TOTAL): stacked gravity 0.0196, apex 3.755, airtime 40t.
 *       Floaty, clearly still playable.</li>
 *   <li>Had this used BASE: stacked gravity 0.0040, apex <b>9.891</b>, airtime
 *       <b>150 ticks</b> -- 7.5 seconds per jump -- and one further gravity
 *       reduction would cross zero.</li>
 * </ul>
 *
 * <p>Any future gravity effect should use ADD_MULTIPLIED_TOTAL for the same
 * reason. Moon Walker is left on BASE deliberately: it ships, it is tuned, and
 * changing it would alter an effect players have already played.
 */
public final class ExtremeGravityBehavior extends AttributeEffectBehavior {

	public static final String ID = "extreme_gravity";

	/** -65%: gravity 0.028, i.e. 35% of vanilla. See the class javadoc for the derivation. */
	public static final double AMOUNT = -0.65;

	public ExtremeGravityBehavior() {
		super(Attributes.GRAVITY, AMOUNT, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
	}
}
