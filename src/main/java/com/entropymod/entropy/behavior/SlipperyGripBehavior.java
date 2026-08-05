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
 * <h2>The speed modifier is only ONE of three sprint bonuses -- the other two
 * were bypasses</h2>
 *
 * <p>The first version of this effect scaled {@code MOVEMENT_SPEED} and stopped
 * there, and it was reported in play as "sprint-jumping ignores the curse". It
 * did. A javap sweep of every {@code isSprinting()} branch in the movement path
 * found <b>three</b> places vanilla rewards sprinting, and the attribute is only
 * the first:
 *
 * <table border="1">
 *   <caption>Sprint-conferred bonuses in 26.1.2</caption>
 *   <tr><th>#</th><th>where</th><th>what</th></tr>
 *   <tr><td>1</td><td>{@code LivingEntity.setSprinting}</td>
 *       <td>{@code MOVEMENT_SPEED} x1.3 -- handled by the compensator above</td></tr>
 *   <tr><td>2</td><td>{@code LivingEntity.jumpFromGround}</td>
 *       <td>a <b>flat {@code 0.2} blocks/tick</b> forward impulse, added straight to
 *           delta movement and <em>completely independent of the attribute</em></td></tr>
 *   <tr><td>3</td><td>{@code Player.getFlyingSpeed}</td>
 *       <td>airborne acceleration {@code 0.02 -> 0.025999999}</td></tr>
 * </table>
 *
 * <p><b>Bonus 3 is why bonus 2 alone did not explain the report.</b>
 * {@code LivingEntity.getFrictionInfluencedSpeed} is:
 *
 * <pre>
 *   onGround() ? getSpeed() * (0.21600002f / (friction*friction*friction))
 *              : getFlyingSpeed();
 * </pre>
 *
 * <p>so while airborne {@code MOVEMENT_SPEED} <em>is not consulted at all</em>.
 * For the twelve-odd ticks of every jump the entire compensator is inert, and a
 * player who jumps continuously spends most of their time in that state. The
 * curse was not being partly bypassed by jumping; it was being switched off.
 *
 * <h2>The fix is the same factor applied to all three, and that is exact</h2>
 *
 * <p>Every term in vanilla's horizontal movement is linear and homogeneous in the
 * triple (ground acceleration, air acceleration, jump impulse) -- the friction
 * recurrence {@code v <- 0.546 v + a} is linear, the airborne one
 * {@code v <- 0.91 v + a} is linear, and the impulse is added, while the vertical
 * motion that sets the airtime is untouched. So scaling all three by one factor
 * scales <em>every</em> sprinting motion by exactly that factor, whatever the
 * player is doing. {@link #sprintScale} is that factor.
 *
 * <p>Measured against a per-tick simulation that reproduces vanilla's published
 * walking (4.3172 b/s), sprinting (5.6123 b/s) and sprint-jumping (7.1263 b/s)
 * figures from the javap-verified constants:
 *
 * <table border="1">
 *   <caption>Blocks per second, straight line, flat default ground</caption>
 *   <tr><th></th><th>vanilla</th><th>curse, speed modifier only</th><th>as shipped</th></tr>
 *   <tr><td>walking</td><td>4.3172</td><td>4.3172</td><td>4.3172</td></tr>
 *   <tr><td>sprinting, on the ground</td><td>5.6123</td><td>2.1586</td><td>2.1586</td></tr>
 *   <tr><td><b>sprint-jumping</b></td><td>7.1263</td><td><b>6.3298</b></td><td><b>2.7409</b></td></tr>
 * </table>
 *
 * <p>The middle column is the bug: sprint-jumping kept <b>89%</b> of vanilla's
 * sprint-jump speed and was <b>2.93x</b> the cursed ground sprint -- half again
 * faster than simply walking, so the curse was not merely evaded but inverted
 * into a reason to sprint. Scaling only the jump impulse and leaving bonus 3
 * alone gives 5.0794 b/s, still faster than walking; both are needed.
 *
 * <p>The shipped column preserves vanilla's own internal ratio exactly:
 * {@code 7.1263 / 5.6123 = 1.2698} and {@code 2.7409 / 2.1586 = 1.2698}.
 * Sprint-jumping is still worth the same 27% over flat-out sprinting that it is
 * in vanilla -- the whole sprint system is scaled by one number rather than
 * having one of its parts clipped, which is what keeps the movement legible
 * instead of merely slow.
 *
 * <p><b>Known limit, stated rather than left to be found:</b> creative flight is
 * untouched. {@code Player.getFlyingSpeed}'s {@code abilities.flying} branch
 * doubles for sprinting on a different basis (x2, not x1.3), and flight ignores
 * {@code MOVEMENT_SPEED} entirely -- so a run holding Creative Flight has always
 * been able to fly out from under this curse, and still can. Fixing that is a
 * separate decision about how two effects should compose, not part of repairing
 * this one.
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
	 * The single factor every sprint-conferred bonus is multiplied by.
	 *
	 * <p><b>This is the whole effect, and the other two accessors here are views of
	 * it.</b> Vanilla hands a sprinting player {@code (1 + vanillaSprintAmount)}
	 * times what walking would have given; this effect wants
	 * {@link #SPRINT_FRACTION} times it instead, so every sprint bonus is worth
	 * this ratio of what vanilla made it. At the shipped numbers that is
	 * {@code 0.5 / 1.3 = 0.3846...}.
	 *
	 * <p>Deliberately free of Minecraft types so the harness can drive the
	 * arithmetic directly against whatever vanilla's amount turns out to be.
	 *
	 * @param vanillaSprintAmount the amount of the sprint modifier actually present
	 */
	public static double sprintScale(double vanillaSprintAmount) {
		return SPRINT_FRACTION / (1.0 + vanillaSprintAmount);
	}

	/**
	 * The {@code ADD_MULTIPLIED_TOTAL} amount that turns vanilla's sprint bonus
	 * into {@link #SPRINT_FRACTION} of the walking total.
	 *
	 * <p>Derived from {@link #sprintScale} rather than restated, so the speed
	 * compensator and the two physics scalings below cannot drift apart.
	 *
	 * @param vanillaSprintAmount the amount of the sprint modifier actually present
	 * @return {@code c} such that {@code (1 + vanillaSprintAmount)(1 + c) == SPRINT_FRACTION}
	 */
	public static double compensatorAmount(double vanillaSprintAmount) {
		return sprintScale(vanillaSprintAmount) - 1.0;
	}

	/**
	 * Vanilla's flat sprint-jump impulse, javap-verified from
	 * {@code LivingEntity.jumpFromGround}: {@code 0.2} blocks/tick added along the
	 * facing direction whenever the jump starts while sprinting.
	 *
	 * <p>Recorded for the harness. It is <b>not</b> read by the mixin, which scales
	 * whatever vector vanilla actually builds.
	 */
	public static final double VANILLA_SPRINT_JUMP_IMPULSE = 0.2;

	/**
	 * Vanilla's airborne acceleration while walking and while sprinting, from
	 * {@code Player.getFlyingSpeed}'s non-flying branch.
	 *
	 * <p>Note {@code 0.025999999 / 0.02} is {@code 1.3} to within float precision --
	 * the same {@code 1.3} as the speed modifier, so scaling the sprinting value by
	 * {@link #sprintScale} lands on {@code 0.02 x SPRINT_FRACTION} either way.
	 */
	public static final float VANILLA_AIR_ACCEL_WALKING = 0.02f;

	/** @see #VANILLA_AIR_ACCEL_WALKING */
	public static final float VANILLA_AIR_ACCEL_SPRINTING = 0.025999999f;

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
