package com.entropymod.client;

import com.entropymod.EntropyMod;
import com.entropymod.client.gui.ChoiceScreen;
import com.entropymod.entropy.EffectPhase;
import com.entropymod.entropy.EntropyManager;
import com.entropymod.network.HistoryRequestPayload;
import com.entropymod.network.HistoryResponsePayload;
import com.entropymod.network.OpenChoicePayload;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Locale;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public class EntropyModClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		EntropyMod.LOGGER.info("Entropy Mod client initializing...");

		// Real trigger: when the server sends the choices, open the real screen.
		// Per Fabric docs, this handler already runs on the client thread.
		ClientPlayNetworking.registerGlobalReceiver(OpenChoicePayload.TYPE, (payload, context) -> {
			EntropyMod.LOGGER.info("Received OpenChoicePayload: phase={} entropy={}/{}",
					payload.phase(), payload.entropy(), payload.entropyCap());
			// This packet is the only entropy state the client ever sees, so it
			// feeds the persistent HUD as well as the screen.
			EntropyHud.update(payload.phase(), payload.entropy(), payload.entropyCap());
			context.client().setScreen(new ChoiceScreen(
					payload.phase(), payload.entropy(), payload.entropyCap(),
					payload.choice1().id(), payload.choice1().name(), payload.choice1().description(),
					payload.choice2().id(), payload.choice2().name(), payload.choice2().description(),
					payload.choice3().id(), payload.choice3().name(), payload.choice3().description()
			));
		});

		// Interim surface for the pick history, until a real screen exists.
		//
		// This used to log ONLY, which is why /entropyhistory looked broken in game:
		// the round-trip was working perfectly and printing to logs/latest.log, where
		// a player has no reason to look. Nothing was ever sent to chat. The history
		// data, the SavedData persistence and the payload pair were all fine --
		// see CLAUDE.md. Output now goes to chat; the log lines stay because they are
		// what made that diagnosis possible in the first place.
		ClientPlayNetworking.registerGlobalReceiver(HistoryResponsePayload.TYPE, (payload, context) -> {
			if (payload.entries().isEmpty()) {
				EntropyMod.LOGGER.info("Pick history: (empty -- no picks made yet this run)");
				context.player().sendSystemMessage(Component.literal(
						"[Entropy] No picks yet this run.").withStyle(ChatFormatting.GRAY));
				return;
			}
			EntropyMod.LOGGER.info("Pick history -- {} entries:", payload.entries().size());
			context.player().sendSystemMessage(Component.literal(
					"[Entropy] Pick history -- " + payload.entries().size() + " pick(s):")
					.withStyle(ChatFormatting.GOLD));
			for (HistoryResponsePayload.Entry entry : payload.entries()) {
				EntropyMod.LOGGER.info("  #{} [{}] entropy {} -> {} ({}) -- {}",
						entry.pickNumber(), entry.phase(), entry.entropyAtPick(),
						entry.effectName(), entry.effectId(), entry.effectDescription());

				ChatFormatting phaseColor = entry.phase() == EffectPhase.GOOD
						? ChatFormatting.GREEN
						: ChatFormatting.RED;
				context.player().sendSystemMessage(Component.literal(
						"  #" + entry.pickNumber() + " ")
						.withStyle(ChatFormatting.DARK_GRAY)
						.append(Component.literal("[" + entry.phase() + "] ").withStyle(phaseColor))
						.append(Component.literal(entry.effectName()).withStyle(ChatFormatting.WHITE))
						.append(Component.literal(" -- " + entry.effectDescription()
								+ " (entropy " + entry.entropyAtPick() + ")")
								.withStyle(ChatFormatting.GRAY)));
			}
		});

		// Persistent top-right entropy readout. addLast so it draws above the
		// other HUD elements rather than under them.
		HudElementRegistry.addLast(EntropyMod.id("entropy_hud"), new EntropyHud());

		// Cached entropy is per-world; drop it on disconnect so the next world
		// doesn't inherit the previous run's numbers.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> EntropyHud.reset());

		// PREVIEW-ONLY command -- FAKE DATA, client-side, never touches the server.
		//   /entropypreview
		//   /entropypreview <good|bad> <entropy>
		//   /entropypreview <good|bad> <entropy> <cap>
		//   /entropypreview <good|bad> <entropy> [cap] long
		//
		// This opens ChoiceScreen with three hardcoded effect strings so colour,
		// layout and description-wrapping can be checked instantly across the whole
		// entropy range, without a server, a world state, or the 3-min timer. That
		// is genuinely useful and is why it still exists.
		//
		// What it CANNOT do -- and this bit was previously easy to get wrong, since
		// it was called /entropytest and looks identical on screen to a real pick:
		// clicking a card here does NOT reach the server. No ChoiceMadePayload, no
		// onChoiceMade, so no history entry, no ActiveEffect, no anti-stacking, no
		// EffectBehavior. For any of that, use the server-side /entropyforcepick,
		// which drives the real pipeline. See CLAUDE.md.
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(literal("entropypreview")
					.executes(ctx -> openPreviewScreen(ctx.getSource().getClient(),
							EffectPhase.GOOD, 12, EntropyManager.DEFAULT_ENTROPY_CAP, false))
					.then(argument("phase", StringArgumentType.word())
							.then(argument("entropy", IntegerArgumentType.integer(0, 10000))
									.executes(ctx -> run(ctx, EntropyManager.DEFAULT_ENTROPY_CAP, false))
									.then(literal("long")
											.executes(ctx -> run(ctx, EntropyManager.DEFAULT_ENTROPY_CAP, true)))
									.then(argument("cap", IntegerArgumentType.integer(1, 10000))
											.executes(ctx -> run(ctx, IntegerArgumentType.getInteger(ctx, "cap"), false))
											.then(literal("long")
													.executes(ctx -> run(ctx,
															IntegerArgumentType.getInteger(ctx, "cap"), true)))))));

			// TEMPORARY: asks the server for the pick history; the receiver above
			// logs it. Unlike /entropypreview this DOES exercise the real server
			// round-trip, so it proves the payload pair rather than only the client
			// rendering path. Replace with a real history screen later.
			dispatcher.register(literal("entropyhistory").executes(ctx -> {
				if (!ClientPlayNetworking.canSend(HistoryRequestPayload.TYPE)) {
					EntropyMod.LOGGER.warn("/entropyhistory -- server does not accept HistoryRequestPayload "
							+ "(not an Entropy Mod server?)");
					// This failure was also log-only, so a genuinely unsupported server
					// looked identical to a working one that printed nothing.
					ctx.getSource().sendError(Component.literal(
							"This server does not support Entropy Mod's history request."));
					return 0;
				}
				ClientPlayNetworking.send(HistoryRequestPayload.INSTANCE);
				return 1;
			}));
		});
	}

	private static int run(CommandContext<FabricClientCommandSource> ctx, int cap, boolean longDescriptions) {
		return openPreviewScreen(
				ctx.getSource().getClient(),
				parsePhase(StringArgumentType.getString(ctx, "phase")),
				IntegerArgumentType.getInteger(ctx, "entropy"),
				cap,
				longDescriptions);
	}

	/** Accepts good/blessing/g/bl... anything else is treated as the curse phase. */
	private static EffectPhase parsePhase(String raw) {
		String value = raw.toLowerCase(Locale.ROOT);
		boolean good = value.startsWith("g") || value.startsWith("bl");
		return good ? EffectPhase.GOOD : EffectPhase.BAD;
	}

	/**
	 * Opens ChoiceScreen with fake, hardcoded choices. Preview only -- the ids
	 * below are real registry ids so the screen looks right, but nothing is rolled
	 * and clicking sends nothing anywhere.
	 */
	private static int openPreviewScreen(Minecraft client, EffectPhase phase, int entropy, int entropyCap,
										  boolean longDescriptions) {
		EntropyMod.LOGGER.info("/entropypreview -- FAKE DATA, client-only. Queueing ChoiceScreen "
						+ "phase={} entropy={}/{} long={}. Clicking a card here does NOT reach the server; "
						+ "use /entropyforcepick for the real pipeline.",
				phase, entropy, entropyCap, longDescriptions);

		// Feed the same cache the real payload does, so the HUD colour can be
		// checked across the range without waiting on the server loop.
		EntropyHud.update(phase, entropy, entropyCap);

		// Deliberately ONE long + two short, so this exercises the "all three
		// panels resize together" rule rather than only the long panel.
		String desc1 = longDescriptions
				? "+10% movement speed for 3 minutes, and you no longer slow down in cobwebs or soul sand"
				: "+10% movement speed for 3 min";

		// MUST be deferred. Client commands are dispatched synchronously from
		// inside ChatScreen's enter handling, and ChatScreen then calls
		// setScreen(null) immediately afterwards -- which closes the screen we
		// just opened, in the same frame, with nothing logged anywhere. Using
		// client.execute() runs this on the next tick, after chat has closed.
		client.execute(() -> client.setScreen(new ChoiceScreen(
				phase, entropy, entropyCap,
				"sure_footing", "Sure Footing", desc1,
				"iron_stomach", "Iron Stomach", "Hunger drains 25% slower for 3 min",
				"featherlight", "Featherlight", "No fall damage for 2 min"
		)));
		return 1;
	}
}
