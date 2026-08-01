package com.entropymod.client;

import com.entropymod.EntropyMod;
import com.entropymod.client.gui.ChoiceScreen;
import com.entropymod.entropy.EffectPhase;
import com.entropymod.network.OpenChoicePayload;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
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
			EntropyMod.LOGGER.info("Received OpenChoicePayload: phase={} entropy={}",
					payload.phase(), payload.entropy());
			context.client().execute(() -> context.client().setScreen(new ChoiceScreen(
					payload.phase(), payload.entropy(),
					payload.choice1().id(), payload.choice1().name(), payload.choice1().description(),
					payload.choice2().id(), payload.choice2().name(), payload.choice2().description(),
					payload.choice3().id(), payload.choice3().name(), payload.choice3().description()
			)));
		});

		// Test-only command: lets you open and click through the GUI locally
		// without waiting for the real 3-minute timer or server networking.
		//   /entropytest                         -> blessing at entropy 12
		//   /entropytest <good|bad> <entropy>    -> e.g. /entropytest bad 80
		//   /entropytest <good|bad> <entropy> long -> forces compact descriptions
		// Remove once the full loop is verified end-to-end.
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(literal("entropytest")
					.executes(ctx -> openTestScreen(ctx.getSource().getClient(), EffectPhase.GOOD, 12, false))
					.then(argument("phase", StringArgumentType.word())
							.then(argument("entropy", IntegerArgumentType.integer(0, 1000))
									.executes(ctx -> openTestScreen(
											ctx.getSource().getClient(),
											parsePhase(StringArgumentType.getString(ctx, "phase")),
											IntegerArgumentType.getInteger(ctx, "entropy"),
											false))
									.then(literal("long").executes(ctx -> openTestScreen(
											ctx.getSource().getClient(),
											parsePhase(StringArgumentType.getString(ctx, "phase")),
											IntegerArgumentType.getInteger(ctx, "entropy"),
											true))))));
		});
	}

	/** Accepts good/blessing/g/b... anything else is treated as the curse phase. */
	private static EffectPhase parsePhase(String raw) {
		String value = raw.toLowerCase(Locale.ROOT);
		boolean good = value.startsWith("g") || value.startsWith("bl");
		return good ? EffectPhase.GOOD : EffectPhase.BAD;
	}

	private static int openTestScreen(Minecraft client, EffectPhase phase, int entropy,
									   boolean longDescriptions) {
		EntropyMod.LOGGER.info("/entropytest -- queueing ChoiceScreen phase={} entropy={} long={}",
				phase, entropy, longDescriptions);

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
				phase, entropy,
				"sure_footing", "Sure Footing", desc1,
				"iron_stomach", "Iron Stomach", "Hunger drains 25% slower for 3 min",
				"featherlight", "Featherlight", "No fall damage for 2 min"
		)));
		return 1;
	}
}
