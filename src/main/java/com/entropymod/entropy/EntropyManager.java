package com.entropymod.entropy;

import com.entropymod.EntropyMod;
import com.entropymod.entropy.behavior.SecondGuessBehavior;
import com.entropymod.network.OpenChoicePayload;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Runs the core game loop: every intervalTicks, alternate GOOD/BAD, roll 3
 * choices, and broadcast them. One EntropyManager per world.
 *
 * <p><b>This is now a {@link SavedData}</b>, replacing the old in-memory
 * {@code WeakHashMap<MinecraftServer, EntropyManager>}. That mattered as soon as
 * effects became permanent: in singleplayer, quitting to the title screen stops
 * the integrated server, so without this every acquired effect, the entropy
 * count, and the whole run vanished on the trip through the main menu. Effects
 * surviving a rejoin is a stated requirement, and re-application alone cannot
 * deliver it if the list of what to re-apply is gone.
 *
 * <p>Also owns the state built on top of the loop: {@link AcquiredEffects}
 * (which doubles as the active set, the no-repeat set, and the anti-stacking
 * source), the {@link PickRecord} history, and dispatch into
 * {@link EffectBehaviors}. It never contains per-effect logic itself.
 *
 * <p><b>Every mutation must call {@link #setDirty()}</b> or the change is not
 * written to disk. That is the one easy-to-miss rule in this file.
 */
public class EntropyManager extends SavedData {
	// 20 ticks/sec * 60 sec * 3 min = 3600 ticks
	public static final int DEFAULT_INTERVAL_TICKS = 3600;
	public static final int DEFAULT_ENTROPY_CAP = 100;

	/**
	 * Public so a headless harness can round-trip it against the real class. A
	 * broken codec here is not a subtle bug -- it makes the world fail to load --
	 * so it gets the same encode/decode verification the network payloads do.
	 *
	 * <p>Every field is {@code optionalFieldOf} with a default. That is what lets a
	 * save written by an older build load in a newer one that added a field, and it
	 * is why adding a field here is a safe, backward-compatible change. Adding a
	 * <em>required</em> field would make every existing save unloadable.
	 */
	public static final Codec<EntropyManager> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.INT.optionalFieldOf("entropy", 0).forGetter(m -> m.entropy),
			Codec.INT.optionalFieldOf("pick_count", 0).forGetter(m -> m.pickCount),
			Codec.BOOL.optionalFieldOf("game_over", false).forGetter(m -> m.gameOver),
			Codec.INT.optionalFieldOf("entropy_cap", DEFAULT_ENTROPY_CAP).forGetter(m -> m.entropyCap),
			Codec.INT.optionalFieldOf("interval_ticks", DEFAULT_INTERVAL_TICKS).forGetter(m -> m.intervalTicks),
			Codec.STRING.listOf().optionalFieldOf("acquired", List.of())
					.forGetter(m -> List.copyOf(m.acquired.ids())),
			PickRecord.CODEC.listOf().optionalFieldOf("history", List.of()).forGetter(m -> m.history),
			Codec.BOOL.optionalFieldOf("reroll_used", false).forGetter(m -> m.rerollUsed)
	).apply(instance, EntropyManager::new));

	private static final SavedDataType<EntropyManager> TYPE = new SavedDataType<>(
			EntropyMod.id("entropy_run"),
			EntropyManager::new,
			CODEC,
			DataFixTypes.LEVEL);

	/**
	 * Stored on the overworld's data storage -- the run is a property of the world,
	 * not of a dimension, so it must not be looked up from whichever level the
	 * caller happens to hold or the Nether would get its own separate run.
	 */
	public static EntropyManager get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	private final Random random = new Random();
	private final AcquiredEffects acquired = new AcquiredEffects();
	private final List<PickRecord> history = new ArrayList<>();

	private int entropy = 0;
	private int pickCount = 0;
	private boolean gameOver = false;

	/**
	 * Second Guess's one-shot reroll, spent for the whole run.
	 *
	 * <p><b>Persisted here deliberately, in the same codec as everything else.</b>
	 * A separate store for one boolean would be a second source of truth about the
	 * run, which is the thing this class exists to prevent. Because it lives on the
	 * run rather than on the effect, re-acquiring Second Guess cannot refund it.
	 */
	private boolean rerollUsed = false;

	// Config, settable at world creation (wire up to a config screen later).
	private int intervalTicks = DEFAULT_INTERVAL_TICKS;
	private int entropyCap = DEFAULT_ENTROPY_CAP;

	/**
	 * Not persisted. Restarting the interval on load is deliberate: saving a
	 * partial countdown would mean a player who reloads repeatedly could sit just
	 * below the threshold forever, and losing at most one interval of progress on
	 * load is a far better failure than that.
	 */
	private int tickCounter = 0;

	/** Also not persisted -- a pending choice cannot survive the client that was answering it. */
	private boolean waitingOnChoice = false;

	public EntropyManager() {}

	/** Codec constructor. */
	private EntropyManager(int entropy, int pickCount, boolean gameOver, int entropyCap, int intervalTicks,
						   List<String> acquiredIds, List<PickRecord> history, boolean rerollUsed) {
		this.entropy = entropy;
		this.pickCount = pickCount;
		this.gameOver = gameOver;
		this.entropyCap = entropyCap;
		this.intervalTicks = intervalTicks;
		acquiredIds.forEach(this.acquired::add);
		this.history.addAll(history);
		this.rerollUsed = rerollUsed;
	}

	// ------------------------------------------------------------------
	// Loop
	// ------------------------------------------------------------------

	public void tick(MinecraftServer server) {
		if (gameOver || waitingOnChoice) {
			return;
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
		OPENED,
		RUN_ALREADY_OVER,
		CHOICE_PENDING,
		RUN_ENDED_NOW,
		NO_ELIGIBLE_EFFECTS
	}

	/**
	 * Opens a pick right now instead of waiting out the interval. The real path,
	 * not a debug imitation of it -- see CLAUDE.md's debug-command table.
	 */
	public PickTrigger forcePick(MinecraftServer server) {
		if (gameOver) {
			return PickTrigger.RUN_ALREADY_OVER;
		}
		if (waitingOnChoice) {
			return PickTrigger.CHOICE_PENDING;
		}
		tickCounter = 0;
		return triggerPick(server);
	}

	private PickTrigger triggerPick(MinecraftServer server) {
		if (entropy >= entropyCap) {
			gameOver = true;
			setDirty();
			server.getPlayerList().broadcastSystemMessage(
					Component.literal("[Entropy] Entropy has reached " + entropy
							+ ". The run is over -- did you beat the dragon in time?"),
					false);
			return PickTrigger.RUN_ENDED_NOW;
		}

		EffectPhase phase = currentPhase();
		Set<EffectCategory> occupied = acquired.occupiedCategories();
		EffectRegistry.RollResult roll =
				EffectRegistry.roll(phase, entropy, random, acquired.ids(), occupied);

		if (roll.repeatFallback()) {
			EntropyMod.LOGGER.warn(
					"[no-repeat] Every eligible {} effect at entropy {} has already been picked -- "
							+ "falling back to re-offering one. PLACEHOLDER policy (CLAUDE.md Open Question 6); "
							+ "the real fix is more content. Acquired: {}",
					phase, entropy, acquired);
		}
		if (roll.categoryFallback()) {
			EntropyMod.LOGGER.warn(
					"[anti-stacking] Every remaining {} effect at entropy {} is in an already-occupied "
							+ "category {} -- offering a colliding effect. PLACEHOLDER policy.",
					phase, entropy, occupied);
		}

		List<EffectDefinition> choices = roll.choices();
		if (choices.isEmpty()) {
			EntropyMod.LOGGER.warn("No eligible {} effects at entropy {} -- add more to EffectRegistry!",
					phase, entropy);
			return PickTrigger.NO_ELIGIBLE_EFFECTS;
		}

		waitingOnChoice = true;
		EntropyMod.LOGGER.info("Pick #{}: phase={} entropy={} occupied={} choices={}",
				pickCount + 1, phase, entropy, occupied, choices);

		// rerollState() is read AFTER waitingOnChoice is set and after requestReroll
		// has already spent the reroll -- see that method for why the ordering there
		// matters to what this sends.
		OpenChoicePayload payload =
				OpenChoicePayload.fromChoices(phase, entropy, entropyCap, rerollState(), choices);
		for (ServerPlayer player : PlayerLookup.all(server)) {
			ServerPlayNetworking.send(player, payload);
		}
		return PickTrigger.OPENED;
	}

	/**
	 * True if Second Guess can be spent right now: the effect is owned, the reroll
	 * is unspent, and there is actually a pending offer to replace. Sent to the
	 * client on {@code OpenChoicePayload} so the screen knows whether to show the
	 * button; re-checked server-side in {@link #requestReroll} because a client can
	 * send anything.
	 */
	public boolean isRerollAvailable() {
		return waitingOnChoice && !rerollUsed && acquired.contains(SecondGuessBehavior.ID);
	}

	/**
	 * What the client should draw for the Second Guess button, derived from the
	 * same persisted {@code rerollUsed} flag {@link #isRerollAvailable} reads.
	 *
	 * <p><b>Distinct from {@code isRerollAvailable} on purpose, and not a
	 * replacement for it.</b> This answers a rendering question and is sent on the
	 * wire; that one answers an authorisation question and is re-checked
	 * server-side in {@link #requestReroll}, because a client can send anything.
	 * A client told {@code SPENT} that sends a reroll request anyway is still
	 * rejected.
	 *
	 * <p>Deliberately omits the {@code waitingOnChoice} term the authorisation
	 * check has: this is only ever read while building a pending pick's payload, so
	 * a pick is pending by construction, and leaving it out keeps the state a pure
	 * function of the run rather than of the moment it was asked.
	 */
	public RerollState rerollState() {
		if (!acquired.contains(SecondGuessBehavior.ID)) {
			return RerollState.NOT_OWNED;
		}
		return rerollUsed ? RerollState.SPENT : RerollState.AVAILABLE;
	}

	/**
	 * Spends Second Guess: throws away the pending three options and rolls three
	 * more.
	 *
	 * <p><b>Delegates to {@link #triggerPick} rather than rolling here.</b> That is
	 * the same rule {@code /entropyforcepick} follows and for the same reason -- a
	 * reroll that built its own choices would stop exercising no-repeat,
	 * anti-stacking, the fallback warnings and the payload construction, and would
	 * silently drift from the real path. This method contains exactly one call to
	 * {@code triggerPick} and no roll logic; that is verifiable in its bytecode.
	 *
	 * <p><b>It does not advance the loop.</b> No entropy is spent, no pick is
	 * counted, no history entry is written and the interval timer is untouched --
	 * all of that happens in {@link #onChoiceMade}, which a reroll never reaches.
	 *
	 * <p>The reroll is consumed only if new choices actually opened, so a roll that
	 * produces nothing (an exhausted pool, or the run ending on this tick) leaves
	 * the player their reroll rather than eating it for nothing.
	 *
	 * @return true if new choices were sent
	 */
	public boolean requestReroll(MinecraftServer server) {
		if (!isRerollAvailable()) {
			return false;
		}

		// Spend the reroll BEFORE rolling, then refund it if nothing opened.
		//
		// This ordering is load-bearing, not stylistic. triggerPick builds the
		// OpenChoicePayload from rerollState(), which reads this flag -- so if the
		// flag were set afterwards, the replacement screen would advertise a reroll
		// that had just been spent, and the button would come back live on the very
		// screen the reroll produced. That was a real, reported bug.
		rerollUsed = true;

		PickTrigger result = triggerPick(server);
		if (result != PickTrigger.OPENED) {
			rerollUsed = false;
			EntropyMod.LOGGER.warn("Second Guess reroll produced no new choices ({}) -- not consumed.", result);
			return false;
		}
		setDirty();
		EntropyMod.LOGGER.info("Second Guess spent -- pick #{} rerolled. No entropy or pick consumed.",
				pickCount + 1);
		return true;
	}

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

		boolean isNew = acquired.add(chosen.id());
		if (!isNew) {
			EntropyMod.LOGGER.warn("'{}' was picked again -- only reachable via the repeat fallback.",
					chosen.id());
		}

		applyToAll(server, chosen, EffectContext.Reason.PICKED);

		// The announcement lives here, not in the behaviors: it is a statement about
		// the RUN ("you chose this"), and behaviors also run on every respawn, where
		// announcing would be pure spam.
		server.getPlayerList().broadcastSystemMessage(
				Component.literal("[Entropy] " + chosen.displayName() + " -- " + chosen.description()),
				false);

		entropy++;
		pickCount++;
		waitingOnChoice = false;
		setDirty();

		EntropyMod.LOGGER.info("Acquired {} effect(s): {}", acquired.size(), acquired);
	}

	// ------------------------------------------------------------------
	// Debug grant
	// ------------------------------------------------------------------

	/** Why a {@link #grantEffect} call did or didn't take. */
	public enum GrantResult {
		GRANTED,
		/** No {@link EffectDefinition} with that id. */
		UNKNOWN_EFFECT,
		/** Already in {@link AcquiredEffects} -- granting again would mask an idempotency bug. */
		ALREADY_ACQUIRED
	}

	/**
	 * Adds an effect to the run directly, as {@code /entropygrant} does. Debug tool:
	 * it exists so a specific effect can be tested without re-rolling until it
	 * happens to be offered.
	 *
	 * <p><b>It is the real acquisition path, not an imitation of one.</b> It writes
	 * to the same {@link AcquiredEffects} set, marks the same {@link SavedData}
	 * dirty, and dispatches through the same private {@link #applyToAll} that
	 * {@link #onChoiceMade} uses -- so the effect is persisted, re-applied on
	 * respawn and rejoin, and counts for anti-stacking, exactly as if it had been
	 * picked. Same rule as {@code /entropyforcepick}: if a future edit makes this
	 * build its own {@code EffectContext} or call a behavior directly, that is a
	 * bug, because it would stop testing the thing it exists to test.
	 *
	 * <p><b>It deliberately does not advance the run.</b> Entropy, the pick count,
	 * the interval timer and the history list are all untouched -- this is a debug
	 * tool, not a shortcut through the game loop, and a granted effect should not
	 * pollute the record of what the player actually chose. That claim is
	 * verifiable in this method's bytecode: it reads and writes none of those
	 * fields.
	 *
	 * <p><b>No-repeat is enforced rather than bypassed.</b> Re-granting something
	 * already acquired is rejected instead of silently re-applying, because a
	 * silent double-apply is exactly the shape of the idempotency bug the
	 * respawn/rejoin design rests on not having -- masking it here would make the
	 * debug tool hide the class of bug it is meant to help find.
	 */
	public GrantResult grantEffect(MinecraftServer server, String effectId) {
		EffectDefinition definition = EffectRegistry.byId(effectId);
		if (definition == null) {
			return GrantResult.UNKNOWN_EFFECT;
		}
		if (!acquired.add(definition.id())) {
			return GrantResult.ALREADY_ACQUIRED;
		}
		setDirty();
		applyToAll(server, definition, EffectContext.Reason.PICKED);
		// Deliberately does not log entropy/pickCount: this method's bytecode
		// referencing neither field at all is the check that it cannot move them.
		EntropyMod.LOGGER.info("Granted '{}' via debug command -- run counters untouched. Acquired {} effect(s): {}",
				definition.id(), acquired.size(), acquired);
		return GrantResult.GRANTED;
	}

	// ------------------------------------------------------------------
	// Application / re-application
	// ------------------------------------------------------------------

	private void applyToAll(MinecraftServer server, EffectDefinition definition, EffectContext.Reason reason) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			applyTo(server, player, definition, reason);
		}
	}

	private void applyTo(MinecraftServer server, ServerPlayer player, EffectDefinition definition,
						 EffectContext.Reason reason) {
		try {
			EffectBehaviors.get(definition.id())
					.apply(new EffectContext(server, player, definition, reason));
		} catch (RuntimeException e) {
			// One broken effect must not abort the loop or the join handler and leave
			// the player with a half-applied set.
			EntropyMod.LOGGER.error("Effect '{}' threw during apply({}) -- skipped.",
					definition.id(), reason, e);
		}
	}

	/**
	 * Re-establishes every acquired effect on one player. Call on join, on respawn,
	 * and after any dimension change that rebuilds the player entity.
	 *
	 * <p>This is not a safety net, it is the <b>only</b> thing that puts effects
	 * back. Attribute modifiers are applied transiently and are therefore never
	 * saved in player NBT; on top of that, a death respawn constructs a brand-new
	 * {@code ServerPlayer} and vanilla's {@code restoreFrom} only carries permanent
	 * modifiers across -- and only when its boolean parameter is true, which it is
	 * not for a death. Verified in bytecode, see CLAUDE.md.
	 *
	 * <p>Safe to call any number of times: every behavior's {@code apply} is
	 * required to be idempotent, and the attribute ones achieve that by replacing a
	 * modifier keyed on a stable per-effect Identifier rather than adding one.
	 */
	public void reapplyAll(MinecraftServer server, ServerPlayer player) {
		if (acquired.isEmpty()) {
			return;
		}
		List<EffectDefinition> definitions = acquired.definitions();
		for (EffectDefinition definition : definitions) {
			applyTo(server, player, definition, EffectContext.Reason.REAPPLIED);
		}
		EntropyMod.LOGGER.info("Re-applied {} effect(s) to {}", definitions.size(),
				player.getName().getString());

		List<String> unknown = acquired.unknownIds();
		if (!unknown.isEmpty()) {
			EntropyMod.LOGGER.warn("Saved run references {} effect id(s) this build does not define: {}. "
					+ "They were skipped; the rest of the run is intact.", unknown.size(), unknown);
		}
	}

	// ------------------------------------------------------------------
	// Accessors
	// ------------------------------------------------------------------

	/** The phase the NEXT pick will use. Strict GOOD -&gt; BAD alternation. */
	public EffectPhase currentPhase() {
		return (pickCount % 2 == 0) ? EffectPhase.GOOD : EffectPhase.BAD;
	}

	/** The run's acquired effects: active set, no-repeat set, and category source in one. */
	public AcquiredEffects acquired() {
		return acquired;
	}

	/** Full pick history for this run, oldest first. Unmodifiable. */
	public List<PickRecord> getHistory() {
		return Collections.unmodifiableList(history);
	}

	public boolean isRerollUsed() { return rerollUsed; }
	public boolean isWaitingOnChoice() { return waitingOnChoice; }
	public boolean isGameOver() { return gameOver; }
	public int getTicksIntoInterval() { return tickCounter; }
	public int getEntropy() { return entropy; }
	public int getPickCount() { return pickCount; }
	public int getEntropyCap() { return entropyCap; }
	public void setEntropyCap(int cap) { this.entropyCap = cap; setDirty(); }
	public int getIntervalTicks() { return intervalTicks; }
	public void setIntervalTicks(int ticks) { this.intervalTicks = ticks; setDirty(); }
}
