package com.entropymod.client;

import com.entropymod.entropy.MovementScramble;
import com.entropymod.entropy.behavior.RandomJumpBehavior;
import com.entropymod.entropy.behavior.RandomizedControlsBehavior;
import com.entropymod.entropy.behavior.UpsideDownCameraBehavior;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * The client's copy of which effects the run holds, fed by
 * {@code ClientEffectsPayload}.
 *
 * <p><b>A cache, not the truth.</b> The server owns the acquired set; this is
 * only what the client was last told, exactly like {@link EntropyHud}'s entropy
 * readout. It is cleared on disconnect so the next world cannot inherit it, and
 * the three effects that read it all degrade to vanilla behaviour when it is
 * empty -- which is the correct state for a vanilla server, a mod-less server,
 * or the moment before the first sync arrives.
 *
 * <p>Read from {@code KeyboardInputMixin} and {@code CameraMixin}, both of which
 * run on the render/client thread every tick or frame, so every accessor here is
 * a plain field read with no allocation.
 */
public final class ClientRunState {

	private static final Set<String> EFFECTS = new HashSet<>();
	private static String moveScramble = "";

	/** Ticks until the next forced jump. Only meaningful while Random Jump is held. */
	private static int jumpCountdown = 0;
	private static final Random RANDOM = new Random();

	/** Replaces the cached view. Called from the payload receiver on the client thread. */
	public static void update(List<String> effectIds, String scramble) {
		EFFECTS.clear();
		EFFECTS.addAll(effectIds);
		moveScramble = MovementScramble.isValid(scramble) ? scramble : "";
		if (hasRandomJump() && jumpCountdown <= 0) {
			scheduleNextJump();
		}
	}

	/** Drops everything. Registered on DISCONNECT -- see {@link EntropyHud#reset}. */
	public static void reset() {
		EFFECTS.clear();
		moveScramble = "";
		jumpCountdown = 0;
	}

	// ------------------------------------------------------------------

	/**
	 * The active scramble, or {@code ""} for vanilla controls.
	 *
	 * <p>Requires <em>both</em> the effect and a valid permutation. A run that
	 * holds the effect but somehow has no scramble moves normally rather than
	 * throwing inside input handling.
	 */
	public static String moveScramble() {
		return EFFECTS.contains(RandomizedControlsBehavior.ID) ? moveScramble : "";
	}

	public static boolean hasUpsideDownCamera() {
		return EFFECTS.contains(UpsideDownCameraBehavior.ID);
	}

	public static boolean hasRandomJump() {
		return EFFECTS.contains(RandomJumpBehavior.ID);
	}

	/**
	 * Counts down the forced-jump timer and reports whether this tick should jump.
	 *
	 * <p>Called once per client tick from {@code KeyboardInputMixin}, which is the
	 * only place in the client that reliably runs exactly once per tick <em>and</em>
	 * immediately before vanilla reads the jump press. See that class for why the
	 * timing matters.
	 */
	public static boolean tickForcedJump() {
		if (!hasRandomJump()) {
			jumpCountdown = 0;
			return false;
		}
		if (jumpCountdown <= 0) {
			scheduleNextJump();
		}
		if (--jumpCountdown > 0) {
			return false;
		}
		scheduleNextJump();
		return true;
	}

	private static void scheduleNextJump() {
		jumpCountdown = RandomJumpBehavior.MIN_INTERVAL_TICKS
				+ RANDOM.nextInt(RandomJumpBehavior.MAX_INTERVAL_TICKS
						- RandomJumpBehavior.MIN_INTERVAL_TICKS + 1);
	}

	private ClientRunState() {}
}
