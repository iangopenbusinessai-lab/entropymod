package com.entropymod;

import com.entropymod.command.EntropyCommands;
import com.entropymod.entropy.EffectBehaviors;
import com.entropymod.entropy.EntropyAttributes;
import com.entropymod.entropy.EntropyManager;
import com.entropymod.entropy.growth.BlightTouchedTrample;
import com.entropymod.entropy.growth.GreenThumbGrowth;
import com.entropymod.network.ChoiceMadePayload;
import com.entropymod.network.ClientEffectsPayload;
import com.entropymod.network.HistoryRequestPayload;
import com.entropymod.network.HistoryResponsePayload;
import com.entropymod.network.KeybindSnapshotPayload;
import com.entropymod.network.OpenChoicePayload;
import com.entropymod.network.RerollRequestPayload;
import com.entropymod.network.RunSyncPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EntropyMod implements ModInitializer {
	public static final String MOD_ID = "entropymod";

	// This logger is used to write text to the console and the log file.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Entropy Mod initializing...");

		// Register this mod's own attributes before anything can look one up.
		// Vanilla has no item-pickup-range attribute -- see EntropyAttributes.
		EntropyAttributes.register();

		// Register both payload types so they can be sent/received at all.
		// NOTE: clientboundPlay()/serverboundPlay() -- these were playS2C()/playC2S()
		// in older Fabric API versions; renamed at some point. Verified against
		// current docs.fabricmc.net/develop/networking.
		PayloadTypeRegistry.clientboundPlay().register(OpenChoicePayload.TYPE, OpenChoicePayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ChoiceMadePayload.TYPE, ChoiceMadePayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(HistoryRequestPayload.TYPE, HistoryRequestPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(HistoryResponsePayload.TYPE, HistoryResponsePayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(RerollRequestPayload.TYPE, RerollRequestPayload.CODEC);
		// Tier 2's client-side effects need to know which effects the run holds --
		// see ClientEffectsPayload. Nothing before Tier 2 required this.
		PayloadTypeRegistry.clientboundPlay().register(ClientEffectsPayload.TYPE, ClientEffectsPayload.CODEC);
		// Run lifecycle: the state gate and the per-player keybind snapshot that
		// Randomized Movement Controls is anchored to.
		PayloadTypeRegistry.clientboundPlay().register(RunSyncPayload.TYPE, RunSyncPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(KeybindSnapshotPayload.TYPE, KeybindSnapshotPayload.CODEC);

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

		// Second Guess. Every condition is re-verified server-side inside
		// requestReroll -- the client's button only decides what is drawn, never
		// what is allowed.
		ServerPlayNetworking.registerGlobalReceiver(RerollRequestPayload.TYPE, (payload, context) -> {
			MinecraftServer server = ((ServerLevel) context.player().level()).getServer();
			EntropyManager.get(server).requestReroll(server);
		});

		// The start panel's button, and every keybind capture.
		//
		// ONE handler for both, because the snapshot IS the start message -- see
		// KeybindSnapshotPayload for why that removes the client/server ordering
		// question rather than answering it. Order inside the handler matters and is
		// the only ordering constraint left: storeKeybindSnapshotIfAbsent refuses
		// while the run is NOT_STARTED, so the transition has to land first.
		//
		// Nothing here trusts the client. startRun is idempotent and re-checks the
		// state; the snapshot is write-once per player. A client that sets startRun
		// on every packet, or re-sends a fresh capture to re-anchor its curse, gets
		// nowhere.
		ServerPlayNetworking.registerGlobalReceiver(KeybindSnapshotPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			MinecraftServer server = player.level().getServer();
			EntropyManager manager = EntropyManager.get(server);

			boolean started = payload.startRun() && manager.startRun();
			manager.storeKeybindSnapshotIfAbsent(player, payload.snapshot());

			if (started) {
				// Tells every client the run is live. For the player who clicked, this
				// is what closes the start panel -- the client never closes it on its
				// own click, so a refused start cannot strand them outside the gate.
				// For everyone else it is also the cue to capture their own keybinds.
				manager.syncRunToAll(server);
			} else {
				manager.syncRunTo(player);
			}
		});

		// History is fetched on demand, and answered only to the player who asked --
		// this is a read, so it never touches the loop's state.
		ServerPlayNetworking.registerGlobalReceiver(HistoryRequestPayload.TYPE, (payload, context) -> {
			MinecraftServer server = ((ServerLevel) context.player().level()).getServer();
			var history = EntropyManager.get(server).getHistory();
			LOGGER.info("History requested by {} -- {} pick(s).", context.player().getName().getString(), history.size());
			ServerPlayNetworking.send(context.player(), HistoryResponsePayload.from(history));
		});

		// Server-side debug commands (/entropyforcepick, /entropystatus). These talk
		// to the real EntropyManager, unlike the client-side /entropypreview.
		EntropyCommands.register();

		// ---- Effect re-application ----------------------------------------
		// All effects are permanent, but the state that implements them is NOT.
		// Attribute modifiers are applied transiently (never written to player NBT
		// -- see AttributeEffectBehavior for why), and on top of that a death
		// respawn builds a brand-new ServerPlayer whose attribute map starts empty:
		// vanilla's restoreFrom only carries permanent modifiers, and only when its
		// boolean parameter is true, which it is not for a death. Verified in
		// bytecode, see CLAUDE.md.
		//
		// So these three hooks are not a safety net -- they are the only thing that
		// puts effects back. Every apply() is required to be idempotent, so
		// re-applying an effect the player still has is a no-op rather than a
		// double-stack.
		// NOTE: there is no Entity.getServer() in this mapping. ServerPlayer.level()
		// returns ServerLevel covariantly, so level().getServer() is the accessor --
		// see the CLAUDE.md mapping note.
		ServerPlayerEvents.JOIN.register(EntropyMod::reapplyTo);

		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> reapplyTo(newPlayer));

		// Returning from the End goes through PlayerList.respawn too, but an
		// ordinary portal does not -- it moves the same entity between levels. This
		// covers that path so a trip to the Nether can't quietly drop everything.
		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register(
				(player, origin, destination) -> reapplyTo(player));

		// Every server tick, let the EntropyManager check whether it's time
		// for the next pick. This is the heartbeat of the whole mod.
		//
		// Green Thumb rides along here rather than on a hook, because its whole
		// point is a growth schedule independent of vanilla's random ticking --
		// see GreenThumbGrowth for why the multiplier hook could not reach 90s.
		// It returns immediately on ticks that are neither a rescan nor an advance
		// pass, and again if nobody holds the effect.
		//
		// Blight Touched rides along for the mirror-image reason: its mechanic is
		// "what block are this player's feet in right now", which is a per-tick
		// question about the player rather than anything vanilla exposes a hook
		// for. It too returns immediately if nobody holds the effect.
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			EntropyManager.get(server).tick(server);
			GreenThumbGrowth.tick(server);
			BlightTouchedTrample.tick(server);
		});

		// Both services' state is transient, not part of the run, so neither is
		// persisted -- but it must not survive into the next world either, which in
		// singleplayer is one trip through the main menu away. This says nothing
		// about the dead bushes Blight Touched leaves behind: those are ordinary
		// world blocks, saved by vanilla like any other block change.
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			GreenThumbGrowth.reset();
			BlightTouchedTrample.reset();
		});

		LOGGER.info("Entropy Mod ready. Default interval: {} ticks ({} min), cap: {}",
				EntropyManager.DEFAULT_INTERVAL_TICKS,
				EntropyManager.DEFAULT_INTERVAL_TICKS / 1200.0,
				EntropyManager.DEFAULT_ENTROPY_CAP);
	}

	/** Re-establishes every acquired effect on one player. See the registrations above. */
	private static void reapplyTo(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return;
		}
		EntropyManager manager = EntropyManager.get(server);
		manager.reapplyAll(server, player);
		// Sent unconditionally, including for an empty run: the client must be told
		// "you have nothing" too, or a stale cache from a previous world would
		// survive into this one.
		manager.syncTo(player);
		// Same discipline, and this one drives three separate client decisions on
		// join: show the start panel while NOT_STARTED, close it once started, and
		// capture keybinds if the server holds none for this player yet. See
		// RunSyncPayload.
		manager.syncRunTo(player);
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
