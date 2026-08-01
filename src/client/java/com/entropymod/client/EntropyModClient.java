package com.entropymod.client;

import com.entropymod.EntropyMod;
import com.entropymod.client.gui.ChoiceScreen;
import com.entropymod.entropy.EffectPhase;
import com.entropymod.entropy.EntropyManager;
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
import net.minecraft.client.Minecraft;

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

		// Persistent top-right entropy readout. addLast so it draws above the
		// other HUD elements rather than under them.
		HudElementRegistry.addLast(EntropyMod.id("entropy_hud"), new EntropyHud());

		// Cached entropy is per-world; drop it on disconnect so the next world
		// doesn't inherit the previous run's numbers.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> EntropyHud.reset());

		// Test-only command. Drives the same client-side state the real payload
		// does, so the screen AND the HUD can be checked without the 3-min timer.
		//   /entropytest
		//   /entropytest <good|bad> <entropy>
		//   /entropytest <good|bad> <entropy> <cap>
		//   /entropytest <good|bad> <entropy> [cap] long
		// Note this exercises the CLIENT rendering path only -- it does not
		// prove the server serialises entropyCap. For that, see the codec
		// round-trip check described in CLAUDE.md.
		// Remove once the full loop is verified end-to-end.
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(literal("entropytest")
					.executes(ctx -> openTestScreen(ctx.getSource().getClient(),
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
		});
	}

	private static int run(CommandContext<FabricClientCommandSource> ctx, int cap, boolean longDescriptions) {
		return openTestScreen(
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

	private static int openTestScreen(Minecraft client, EffectPhase phase, int entropy, int entropyCap,
									   boolean longDescriptions) {
		EntropyMod.LOGGER.info("/entropytest -- queueing ChoiceScreen phase={} entropy={}/{} long={}",
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
