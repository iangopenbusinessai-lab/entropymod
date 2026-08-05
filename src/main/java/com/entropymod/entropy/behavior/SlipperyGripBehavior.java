package com.entropymod.entropy.behavior;

import com.entropymod.entropy.EffectBehavior;
import com.entropymod.entropy.EffectContext;

/**
 * Slippery Grip (BAD / MOVEMENT, Tier 2) -- sprinting makes you slower, not
 * faster. Walking is untouched.
 *
 * <h2>What changed, and why the old version was replaced</h2>
 *
 * <p>The first version forced {@code LivingEntity.setSprinting(boolean)}'s
 * argument to {@code false}, so the player simply could never enter the sprint
 * state. That worked, but it made the curse a <em>removal</em> of a mechanic
 * rather than an inversion of one: the sprint key just went dead, which reads as
 * a broken keybind rather than as slippery footing. It now lets the sprint
 * happen and punishes it, which is legible the moment the player tries it.
 *
 * <h2>Vanilla's sprint bonus, javap-verified</h2>
 *
 * <p>{@code LivingEntity.setSprinting(boolean)} is exactly four steps:
 *
 * <pre>
 *   super.setSprinting(sprinting);                       // the shared entity flag
 *   AttributeInstance i = getAttribute(MOVEMENT_SPEED);
 *   i.removeModifier(SPRINTING_MODIFIER_ID);             // unconditionally
 *   if (sprinting) i.addTransientModifier(SPEED_MODIFIER_SPRINTING);
 * </pre>
 *
 * <p>and the modifier itself is, from {@code LivingEntity}'s {@code <clinit>}:
 *
 * <table border="1">
 *   <caption>{@code SPEED_MODIFIER_SPRINTING}</caption>
 *   <tr><th>id</th><td>{@code minecraft:sprinting}</td></tr>
 *   <tr><th>amount</th><td>{@code 0.30000001192092896}</td></tr>
 *   <tr><th>operation</th><td>{@code ADD_MULTIPLIED_TOTAL}</td></tr>
 * </table>
 *
 * <p>So sprinting is <b>not</b> a separate movement mode with its own speed --
 * it is one ordinary attribute modifier, added and removed by vanilla at a
 * single choke point. That is what makes this effect reachable through
 * attribute modifiers alone, with no movement mixin and no per-tick correction.
 *
 * <h2>Why one ADD_MULTIPLIED_TOTAL modifier is the whole implementation</h2>
 *
 * <p>{@code AttributeInstance.calculateValue} composes in three passes, verified
 * in bytecode:
 *
 * <pre>
 *   d = base;  for (ADD_VALUE m)            d += m.amount();
 *   e = d;     for (ADD_MULTIPLIED_BASE m)  e += d * m.amount();
 *              for (ADD_MULTIPLIED_TOTAL m) e *= 1.0 + m.amount();
 *   return sanitizeValue(e);
 * </pre>
 *
 * <p>The {@code ADD_MULTIPLIED_TOTAL} pass is a <b>product</b>, and every factor
 * in it is independent of every other. Write {@code W} for the player's walking
 * speed -- whatever it happens to be, including any other speed effects in any
 * of the three pools. Then while sprinting:
 *
 * <pre>
 *   sprint = W x (1 + 0.30000001192092896) x (1 + c)
 * </pre>
 *
 * <p>and choosing {@code c} so that {@code (1 + 0.3)(1 + c) = 0.5} makes the
 * whole bracket collapse to a constant {@code 0.5} <em>whatever {@code W} is</em>.
 * <b>That is the relativity, and it is structural rather than arithmetic</b> --
 * the same reason Glass Cannon Pact's health halving works on the real total
 * instead of a remembered baseline. It is also why one modifier does both jobs
 * the brief asks for: cancelling vanilla's +30% and applying the -50% are the
 * same multiplication, and separating them would be two modifiers that have to
 * agree.
 *
 * <p>{@link #compensatorAmount} therefore takes vanilla's amount as a parameter
 * rather than assuming it. {@code SlipperyGripSprint} reads the modifier vanilla
 * has just added straight off the instance, so a retune of vanilla's sprint
 * bonus -- or another mod's -- is absorbed automatically instead of silently
 * putting the effect off target. {@link #VANILLA_SPRINT_AMOUNT} is only the
 * fallback for the case where the modifier is somehow absent.
 *
 * <h2>Walking is untouched, by construction rather than by care</h2>
 *
 * <p>The compensating modifier exists <em>only</em> while sprinting: it is added
 * at the tail of the same {@code setSprinting} call that adds vanilla's, and
 * removed at the tail of the one that removes vanilla's. When the player is not
 * sprinting the attribute holds no modifier of this effect's at all, so the
 * walking value is not merely equal to an unaffected player's, it is computed by
 * the identical arithmetic over the identical modifier set. The harness asserts
 * that as raw bit equality rather than as a tolerance.
 *
 * <h2>Why the effect is not a {@code HookEffectBehavior} any more</h2>
 *
 * <p>It has one thing to do on {@code apply}: a player who acquires the curse
 * <em>while already sprinting</em> would otherwise keep vanilla's untouched
 * bonus until the next time the sprint state changed. {@code apply} re-runs the
 * same update the mixin runs, which is idempotent -- it is "set to X", not
 * "adjust by X" -- so the respawn/rejoin re-application rule is satisfied
 * without a freshness check.
 */
public final class SlipperyGripBehavior implements EffectBehavior {

	public static final String ID = "slippery_grip";

	/**
	 * Sprinting ends up at this fraction of the <em>same player's</em> current
	 * walking speed. Relative, never an absolute speed -- see the class javadoc.
	 */
	public static final double SPRINT_FRACTION = 0.5;

	/**
	 * Vanilla's {@code SPEED_MODIFIER_SPRINTING} amount, javap-verified from
	 * {@code LivingEntity}'s {@code <clinit>}.
	 *
	 * <p><b>This is a fallback, not the source of truth.</b> The live modifier is
	 * read off the attribute instance at the moment the compensator is applied;
	 * this value is used only if vanilla's modifier is unexpectedly absent, so a
	 * version bump cannot quietly leave the effect mistuned.
	 */
	public static final double VANILLA_SPRINT_AMOUNT = 0.30000001192092896;

	/** Path of vanilla's sprint modifier id ({@code minecraft:sprinting}). */
	public static final String VANILLA_SPRINT_ID_PATH = "sprinting";

	/**
	 * The {@code ADD_MULTIPLIED_TOTAL} amount that turns vanilla's sprint bonus
	 * into {@link #SPRINT_FRACTION} of the walking total.
	 *
	 * <p>Deliberately free of Minecraft types so the harness can drive the
	 * arithmetic directly against whatever vanilla's amount turns out to be.
	 *
	 * @param vanillaSprintAmount the amount of the sprint modifier actually present
	 * @return {@code c} such that {@code (1 + vanillaSprintAmount)(1 + c) == SPRINT_FRACTION}
	 */
	public static double compensatorAmount(double vanillaSprintAmount) {
		return SPRINT_FRACTION / (1.0 + vanillaSprintAmount) - 1.0;
	}

	@Override
	public void apply(EffectContext ctx) {
		// Covers acquiring the curse mid-sprint; a no-op otherwise. Idempotent,
		// which is what lets this run again on every respawn and rejoin.
		SlipperyGripSprint.update(ctx.target(), true);
	}

	@Override
	public void remove(EffectContext ctx) {
		SlipperyGripSprint.update(ctx.target(), false);
	}
}
