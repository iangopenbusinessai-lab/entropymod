package com.entropymod.entropy;

import com.entropymod.EntropyMod;
import com.entropymod.network.OpenChoicePayload;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.WeakHashMap;

/**
 * Runs the core game loop: every intervalTicks, alternate GOOD/BAD, roll 3
 * choices, and broadcast them to all players. One EntropyManager per server
 * (world). Currently in-memory only -- NOT saved across server restarts yet.
 * TODO (next milestone): back this with a PersistentState so entropy/pickCount
 * survive a save/reload, instead of resetting to 0 each session.
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

	private int entropy = 0;
	private int pickCount = 0;
	private int tickCounter = 0;
	private boolean waitingOnChoice = false;
	private boolean gameOver = false;

	// Config, settable at world creation (wire up to a config screen later).
	private int intervalTicks = DEFAULT_INTERVAL_TICKS;
	private int entropyCap = DEFAULT_ENTROPY_CAP;

	public void tick(MinecraftServer server) {
		if (gameOver || waitingOnChoice) {
			return; // paused while a choice is pending, or run has ended
		}

		tickCounter++;
		if (tickCounter >= intervalTicks) {
			tickCounter = 0;
			triggerPick(server);
		}
	}

	private void triggerPick(MinecraftServer server) {
		if (entropy >= entropyCap) {
			gameOver = true;
			server.getPlayerList().broadcastSystemMessage(
					Component.literal(
							"[Entropy] Entropy has reached " + entropy + ". The run is over -- did you beat the dragon in time?"),
					false);
			return;
		}

		EffectPhase phase = (pickCount % 2 == 0) ? EffectPhase.GOOD : EffectPhase.BAD;
		List<EffectDefinition> choices = EffectRegistry.rollThree(phase, entropy, random);

		if (choices.isEmpty()) {
			EntropyMod.LOGGER.warn("No eligible {} effects found at entropy {} -- add more to EffectRegistry!", phase, entropy);
			return;
		}

		waitingOnChoice = true;
		EntropyMod.LOGGER.info("Pick #{}: phase={} entropy={} choices={}", pickCount, phase, entropy, choices);

		OpenChoicePayload payload = OpenChoicePayload.fromChoices(phase, entropy, choices);
		for (var player : PlayerLookup.all(server)) {
			ServerPlayNetworking.send(player, payload);
		}
	}

	/** Called by the server-side network receiver when a player submits their pick. */
	public void onChoiceMade(MinecraftServer server, String chosenEffectId) {
		if (!waitingOnChoice) {
			return; // ignore stray/duplicate submissions
		}
		EffectDefinition chosen = EffectRegistry.byId(chosenEffectId);
		if (chosen == null) {
			EntropyMod.LOGGER.warn("Unknown effect id submitted: {}", chosenEffectId);
			waitingOnChoice = false;
			return;
		}

		// TODO: hand off to EffectExecutor.apply(server, chosen) once effect
		// behaviors are implemented. For now, just log + advance the loop.
		EntropyMod.LOGGER.info("Applying effect: {}", chosen.displayName());
		server.getPlayerList().broadcastSystemMessage(
				Component.literal("[Entropy] Chosen: " + chosen.displayName() + " -- " + chosen.description()),
				false);

		entropy++;
		pickCount++;
		waitingOnChoice = false;
	}

	public int getEntropy() { return entropy; }
	public int getEntropyCap() { return entropyCap; }
	public void setEntropyCap(int cap) { this.entropyCap = cap; }
	public int getIntervalTicks() { return intervalTicks; }
	public void setIntervalTicks(int ticks) { this.intervalTicks = ticks; }
}
