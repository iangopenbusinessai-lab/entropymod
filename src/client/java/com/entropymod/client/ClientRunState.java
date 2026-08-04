package com.entropymod.client;

import com.entropymod.entropy.KeybindSnapshot;
import com.entropymod.entropy.MovementScramble;
import com.entropymod.entropy.RunState;
import com.entropymod.entropy.behavior.RandomJumpBehavior;
import com.entropymod.entropy.behavior.RandomizedControlsBehavior;
import com.entropymod.entropy.behavior.SlipperyGripBehavior;
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

	/**
	 * The run's lifecycle state, as last reported by {@code RunSyncPayload}.
	 *
	 * <p>Defaults to {@code IN_PROGRESS}, <b>not</b> {@code NOT_STARTED</b>, and
	 * the asymmetry is deliberate: this value's only job on the client is deciding
	 * whether to throw up a modal, un-escapable start panel. Defaulting to
	 * "not started" would mean any server that never sends this payload -- a
	 * vanilla server, an older Entropy Mod server, or the gap before the first
	 * packet arrives -- traps the player behind a panel whose button talks to
	 * nobody. Defaulting the other way degrades to ordinary vanilla play.
	 */
	private static RunState runState = RunState.IN_PROGRESS;

	/**
	 * This player's movement keys as frozen at Start, mirrored from the server.
	 *
	 * <p>{@link KeybindSnapshot#EMPTY} means "the server holds none for me", which
	 * is both the cue to capture one and the signal that Randomized Movement
	 * Controls must fall back to permuting live bindings.
	 */
	private static KeybindSnapshot keybinds = KeybindSnapshot.EMPTY;

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

	/** Replaces the cached run state and this player's frozen keybinds. */
	public static void updateRun(RunState state, KeybindSnapshot snapshot) {
		runState = state == null ? RunState.IN_PROGRESS : state;
		keybinds = snapshot == null ? KeybindSnapshot.EMPTY : snapshot;
	}

	public static RunState runState() {
		return runState;
	}

	/** This player's frozen movement keys, or {@link KeybindSnapshot#EMPTY}. */
	public static KeybindSnapshot keybinds() {
		return keybinds;
	}

	/**
	 * Whether the server is waiting for this client to report its keybinds: the
	 * run is live and the server holds no snapshot for this player.
	 *
	 * <p>This one predicate covers every capture case except the player who
	 * actually clicks Start -- a second player already online when the run began,
	 * and anyone joining afterwards -- which is why no "send me your keybinds"
	 * request packet exists.
	 */
	public static boolean needsKeybindCapture() {
		return runState == RunState.IN_PROGRESS && !keybinds.isPresent();
	}

	/** Drops everything. Registered on DISCONNECT -- see {@link EntropyHud#reset}. */
	public static void reset() {
		EFFECTS.clear();
		moveScramble = "";
		jumpCountdown = 0;
		// Back to the permissive defaults, for the same reason they are the
		// defaults: the next server may not speak this protocol at all.
		runState = RunState.IN_PROGRESS;
		keybinds = KeybindSnapshot.EMPTY;
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

	/**
	 * Slippery Grip, read by {@code ClientSprintMixin}.
	 *
	 * <p>The client needs its own answer here rather than deferring to the server:
	 * refusing the sprint locally is what stops the client applying a speed
	 * modifier the server does not have. See that mixin.
	 */
	public static boolean preventsSprinting() {
		return EFFECTS.contains(SlipperyGripBehavior.ID);
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
