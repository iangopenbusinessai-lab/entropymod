package com.entropymod.entropy.behavior;

import com.entropymod.entropy.HookEffectBehavior;

/**
 * Random Jump (BAD / MOVEMENT, Tier 2) -- you jump on your own, every 5 to 30
 * seconds, forever.
 *
 * <p><b>Implemented as a synthetic jump key-press, not as velocity injection.</b>
 * {@code KeyboardInputMixin} sets this tick's {@code Input.jump()} true, which is
 * exactly what vanilla's own {@code ClientInput.makeJump()} does for auto-jump.
 * Everything after that is vanilla: {@code LivingEntity.aiStep}'s jump block
 * reads {@code jumping} and dispatches on the player's actual situation.
 *
 * <p><b>That is what makes the edge cases correct rather than assumed.</b>
 * Verified in {@code LivingEntity.aiStep}'s bytecode, the dispatch is:
 *
 * <ul>
 *   <li><b>In lava</b> -> {@code jumpInLiquid(FluidTags.LAVA)} -- bob upward, the
 *       same as holding space.</li>
 *   <li><b>In water</b> -> {@code jumpInLiquid(FluidTags.WATER)} -- swim up. Not
 *       a jump, and not a no-op.</li>
 *   <li><b>On ground</b> -> {@code jumpFromGround()} -- the real jump, so it gets
 *       the correct height, the sprint boost, and the jump-boost/exhaustion
 *       handling for free.</li>
 *   <li><b>Mid-air</b> -> nothing at all. The ground branch is gated on
 *       {@code onGround()}, so this cannot double-jump or cancel a fall.</li>
 *   <li><b>On a ladder or vine</b> -> nothing, for the same reason: climbing is
 *       not {@code onGround()}, and vanilla's climb handling lives in
 *       {@code travel}, untouched. The player keeps climbing.</li>
 * </ul>
 *
 * <p>Also handled by using the real input: {@code noJumpDelay} still rate-limits
 * to one jump per 10 ticks, and a forced press ORs with the player's own key so
 * it can never cancel a jump they were already making.
 *
 * <p>Client-side, because the jump has to originate where movement is
 * authoritative. A server-side {@code setJumping} would be fought by the client's
 * own position updates.
 *
 * <p>apply()/remove() are empty by design -- see {@link HookEffectBehavior}.
 */
public final class RandomJumpBehavior extends HookEffectBehavior {

	public static final String ID = "random_jump";

	/** 5 seconds. */
	public static final int MIN_INTERVAL_TICKS = 100;

	/** 30 seconds. */
	public static final int MAX_INTERVAL_TICKS = 600;
}
