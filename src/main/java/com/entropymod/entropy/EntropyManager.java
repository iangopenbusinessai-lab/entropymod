package com.entropymod.entropy;

import com.entropymod.EntropyMod;
import com.entropymod.network.OpenChoicePayload;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Runs the core game loop: every intervalTicks, alternate GOOD/BAD, roll 3
 * choices, and broadcast them to all players. One EntropyManager per server
 * (world). Currently in-memory only -- NOT saved across server restarts yet.
 * TODO (next milestone): back this with a PersistentState so entropy/pickCount
 * /history/active effects survive a save/reload, instead of resetting to 0 each
 * session.
 *
 * <p>Also owns the three pieces of tracking state built on top of that loop:
 * the {@link ActiveEffectTracker} (expiry + anti-stacking), the pick
 * {@link PickRecord} history, and the dispatch into {@link EffectBehaviors}.
 * It never contains per-effect logic itself -- that lives in the behavior
 * classes, one per effect.
 */
public class EntropyManager {
	// 20 ticks/sec * 60 sec * 3 min = 3600 ticks
	public static final int DEFAULT_INTERVAL_TICKS = 3600;
	public static final int DEFAULT_ENTROPY_CAP = 100;

	private static final Map<MinecraftServer, EntropyManager> INSTANCES = new WeakHashMap<>();

	public static EntropyManager get(MinecraftServer server) {
		return INSTANCES.computeIfAbsent(server, s -> new EntropyManager());
	}

	private final Random random = new Random();
	private final ActiveEffectTracker activeEffects = new ActiveEffectTracker();
	private final List<PickRecord> history = new ArrayList<>();

	private int entropy = 0;
	private int pickCount = 0;
	private int tickCounter = 0;
	private boolean waitingOnChoice = false;
	private boolean gameOver = false;

	// Config, settable at world creation (wire up to a config screen later).
	private int intervalTicks = DEFAULT_INTERVAL_TICKS;
	private int entropyCap = DEFAULT_ENTROPY_CAP;

	public void tick(MinecraftServer server) {
		if (gameOver) {
			return;
		}

		// Expiry runs every tick and is deliberately NOT gated on waitingOnChoice.
		// A duration is elapsed-time from when the effect was applied, not a count
		// of "ticks the interval clock happened to be running" -- so an effect that
		// would have ended during an open GUI ends, rather than being held alive by
		// someone else's pending pick. (In singleplayer this is moot: the choice
		// screen is a pause screen, so the server isn't ticking at all.)
		for (ActiveEffect expired : activeEffects.tickAndCollectExpired()) {
			fireRemove(server, expired, "duration elapsed");
		}

		if (waitingOnChoice) {
			return; // interval clock is paused while a choice is pending
		}

		tickCounter++;
		if (tickCounter >= intervalTicks) {
			tickCounter = 0;
			triggerPick(server);
		}
	}

	/**
	 * Why a pick attempt did or didn't open. Returned so callers that aren't the
	 * tick loop -- currently only {@code /entropyforcepick} -- can report the
	 * reason instead of silently doing nothing. {@link #tick} ignores it.
	 */
	public enum PickTrigger {
		/** Choices rolled and sent; the loop is now waiting on the player. */
		OPENED,
		/** The run had already ended before this attempt. */
		RUN_ALREADY_OVER,
		/** A pick is already open and unanswered. */
		CHOICE_PENDING,
		/** This attempt hit the entropy cap and ended the run. */
		RUN_ENDED_NOW,
		/** Nothing in the registry is eligible for this phase at this entropy. */
		NO_ELIGIBLE_EFFECTS
	}

	/**
	 * Opens a pick right now instead of waiting out the interval.
	 *
	 * <p>This is the <em>real</em> path, not a debug imitation of it: the entropy
	 * cap check, interval-scoped expiry, phase alternation, anti-stacking roll and
	 * payload broadcast all happen because this delegates straight to
	 * {@link #triggerPick}. The only thing it bypasses is the clock.
	 *
	 * <p>The two guards below are exactly the ones {@link #tick} applies before it
	 * would ever reach {@code triggerPick}, so forcing a pick cannot reach a state
	 * a real interval firing couldn't.
	 */
	public PickTrigger forcePick(MinecraftServer server) {
		if (gameOver) {
			return PickTrigger.RUN_ALREADY_OVER;
		}
		if (waitingOnChoice) {
			return PickTrigger.CHOICE_PENDING;
		}
		// Reset the countdown as a real firing does, so the natural interval doesn't
		// arrive moments later on top of the forced one.
		tickCounter = 0;
		return triggerPick(server);
	}

	private PickTrigger triggerPick(MinecraftServer server) {
		if (entropy >= entropyCap) {
			gameOver = true;
			server.getPlayerList().broadcastSystemMessage(
					Component.literal(
							"[Entropy] Entropy has reached " + entropy + ". The run is over -- did you beat the dragon in time?"),
					false);
			return PickTrigger.RUN_ENDED_NOW;
		}

		// "Lasts until the next interval" (durationTicks == -1) means exactly this
		// moment. Expiring them HERE rather than on a precomputed countdown is what
		// keeps them correct when the interval length changes mid-run, and it also
		// frees their categories before the roll below reads occupancy.
		for (ActiveEffect expired : activeEffects.expireIntervalScoped()) {
			fireRemove(server, expired, "interval elapsed");
		}

		EffectPhase phase = currentPhase();
		Set<EffectCategory> occupied = activeEffects.occupiedCategories();
		EffectRegistry.RollResult roll = EffectRegistry.rollThree(phase, entropy, random, occupied);

		if (roll.categoryFallback()) {
			EntropyMod.LOGGER.warn(
					"[anti-stacking] Every eligible {} effect at entropy {} belongs to an already-occupied "
							+ "category {} -- falling back to offering a colliding effect. This is a PLACEHOLDER "
							+ "policy, not a decided rule (CLAUDE.md Open Question 6). Active: {}",
					phase, entropy, occupied, activeEffects.active());
		}

		List<EffectDefinition> choices = roll.choices();
		if (choices.isEmpty()) {
			EntropyMod.LOGGER.warn("No eligible {} effects found at entropy {} -- add more to EffectRegistry!", phase, entropy);
			return PickTrigger.NO_ELIGIBLE_EFFECTS;
		}

		waitingOnChoice = true;
		EntropyMod.LOGGER.info("Pick #{}: phase={} entropy={} occupied={} choices={}",
				pickCount + 1, phase, entropy, occupied, choices);

		// Send the configured cap, not a constant: the client tints its accent
		// ramp by entropy/entropyCap, so a non-default cap here would otherwise
		// make every colour on the client wrong.
		OpenChoicePayload payload = OpenChoicePayload.fromChoices(phase, entropy, entropyCap, choices);
		for (var player : PlayerLookup.all(server)) {
			ServerPlayNetworking.send(player, payload);
		}
		return PickTrigger.OPENED;
	}

	/** True while a pick is open and unanswered. */
	public boolean isWaitingOnChoice() { return waitingOnChoice; }

	/** True once the run has ended (entropy cap reached). */
	public boolean isGameOver() { return gameOver; }

	/** Ticks elapsed in the current interval. Reported by the debug command; not used by the loop. */
	public int getTicksIntoInterval() { return tickCounter; }

	/** Called by the server-side network receiver when a player submits their pick. */
	public void onChoiceMade(MinecraftServer server, String chosenEffectId) {
		if (!waitingOnChoice) {
			return; // ignore stray/duplicate submissions
		}
		EffectDefinition chosen = EffectRegistry.byId(chosenEffectId);
		if (chosen == null) {
			EntropyMod.LOGGER.warn("Unknown effect id submitted: {}", chosenEffectId);
			return;
		}

		// Record BEFORE the counters move: entropyAtPick is the number the player was
		// looking at when they chose, and pickNumber is 1-based.
		history.add(new PickRecord(pickCount + 1, currentPhase(), chosen.id(),
				chosen.displayName(), chosen.description(), entropy));

		// All player-facing feedback now comes from the behavior's apply(), not from
		// here -- this method must not know anything effect-specific.
		EffectBehaviors.get(chosen.id()).apply(new EffectContext(server, chosen));

		ActiveEffect tracked = activeEffects.add(chosen);
		if (tracked == null) {
			EntropyMod.LOGGER.info("{} is instantaneous (duration 0) -- not tracked, remove() will never fire.",
					chosen.id());
		} else {
			EntropyMod.LOGGER.info("Now active: {} (active set: {})", tracked, activeEffects.active());
		}

		entropy++;
		pickCount++;
		waitingOnChoice = false;
	}

	/**
	 * The phase the NEXT pick will use. Strict GOOD -&gt; BAD alternation, derived
	 * from how many picks have been made.
	 */
	public EffectPhase currentPhase() {
		return (pickCount % 2 == 0) ? EffectPhase.GOOD : EffectPhase.BAD;
	}

	private void fireRemove(MinecraftServer server, ActiveEffect expired, String reason) {
		EffectDefinition definition = EffectRegistry.byId(expired.effectId());
		if (definition == null) {
			// Only reachable if an effect is deleted from the registry while active
			// (i.e. a mod update mid-run, once persistence lands). Log rather than
			// throw: a stale entry must not be able to crash the server tick.
			EntropyMod.LOGGER.warn("Active effect '{}' expired but has no EffectDefinition -- cannot remove it.",
					expired.effectId());
			return;
		}
		EntropyMod.LOGGER.info("Effect expired ({}): {}", reason, definition.id());
		EffectBehaviors.get(definition.id()).remove(new EffectContext(server, definition));
	}

	/** Full pick history for this run, oldest first. Unmodifiable. */
	public List<PickRecord> getHistory() {
		return Collections.unmodifiableList(history);
	}

	/** Currently-running effects. Unmodifiable; exposed for debugging and the history/status surfaces. */
	public List<ActiveEffect> getActiveEffects() {
		return activeEffects.active();
	}

	public int getEntropy() { return entropy; }
	public int getPickCount() { return pickCount; }
	public int getEntropyCap() { return entropyCap; }
	public void setEntropyCap(int cap) { this.entropyCap = cap; }
	public int getIntervalTicks() { return intervalTicks; }
	public void setIntervalTicks(int ticks) { this.intervalTicks = ticks; }
}
