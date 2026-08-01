package com.entropymod;

import com.entropymod.entropy.EffectBehaviors;
import com.entropymod.entropy.EntropyManager;
import com.entropymod.network.ChoiceMadePayload;
import com.entropymod.network.HistoryRequestPayload;
import com.entropymod.network.HistoryResponsePayload;
import com.entropymod.network.OpenChoicePayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EntropyMod implements ModInitializer {
	public static final String MOD_ID = "entropymod";

	// This logger is used to write text to the console and the log file.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Entropy Mod initializing...");

		// Register both payload types so they can be sent/received at all.
		// NOTE: clientboundPlay()/serverboundPlay() -- these were playS2C()/playC2S()
		// in older Fabric API versions; renamed at some point. Verified against
		// current docs.fabricmc.net/develop/networking.
		PayloadTypeRegistry.clientboundPlay().register(OpenChoicePayload.TYPE, OpenChoicePayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ChoiceMadePayload.TYPE, ChoiceMadePayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(HistoryRequestPayload.TYPE, HistoryRequestPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(HistoryResponsePayload.TYPE, HistoryResponsePayload.CODEC);

		// Effect ids are matched between EffectRegistry and EffectBehaviors by string,
		// which the compiler cannot check. Report mismatches once, at startup.
		EffectBehaviors.validate();

		// When a player submits their pick, hand it to that player's EntropyManager.
		// Per Fabric docs, this handler already runs on the server thread, so no
		// extra .execute() hop is needed (unlike older Fabric API versions).
		// player.level() + cast to ServerLevel is the pattern shown directly in
		// docs.fabricmc.net/develop/networking (both context.player().level()
		// and the (ServerLevel) cast appear there, just not combined this way).
		ServerPlayNetworking.registerGlobalReceiver(ChoiceMadePayload.TYPE, (payload, context) -> {
			MinecraftServer server = ((ServerLevel) context.player().level()).getServer();
			EntropyManager.get(server).onChoiceMade(server, payload.chosenEffectId());
		});

		// History is fetched on demand, and answered only to the player who asked --
		// this is a read, so it never touches the loop's state.
		ServerPlayNetworking.registerGlobalReceiver(HistoryRequestPayload.TYPE, (payload, context) -> {
			MinecraftServer server = ((ServerLevel) context.player().level()).getServer();
			var history = EntropyManager.get(server).getHistory();
			LOGGER.info("History requested by {} -- {} pick(s).", context.player().getName().getString(), history.size());
			ServerPlayNetworking.send(context.player(), HistoryResponsePayload.from(history));
		});

		// Every server tick, let the EntropyManager check whether it's time
		// for the next pick. This is the heartbeat of the whole mod.
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			EntropyManager.get(server).tick(server);
		});

		LOGGER.info("Entropy Mod ready. Default interval: {} ticks ({} min), cap: {}",
				EntropyManager.DEFAULT_INTERVAL_TICKS,
				EntropyManager.DEFAULT_INTERVAL_TICKS / 1200.0,
				EntropyManager.DEFAULT_ENTROPY_CAP);
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
