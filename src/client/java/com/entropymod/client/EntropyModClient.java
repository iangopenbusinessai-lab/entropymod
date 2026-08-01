package com.entropymod.client;

import com.entropymod.EntropyMod;
import com.entropymod.client.gui.ChoiceScreen;
import com.entropymod.entropy.EffectPhase;
import com.entropymod.network.OpenChoicePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public class EntropyModClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		EntropyMod.LOGGER.info("Entropy Mod client initializing...");

		// Real trigger: when the server sends the choices, open the real screen.
		// Per Fabric docs, this handler already runs on the client thread.
		ClientPlayNetworking.registerGlobalReceiver(OpenChoicePayload.TYPE, (payload, context) -> {
			EntropyMod.LOGGER.info("Received OpenChoicePayload: phase={} entropy={}", payload.phase(), payload.entropy());
			context.client().setScreen(new ChoiceScreen(
					payload.phase(), payload.entropy(),
					payload.choice1().id(), payload.choice1().name(), payload.choice1().description(),
					payload.choice2().id(), payload.choice2().name(), payload.choice2().description(),
					payload.choice3().id(), payload.choice3().name(), payload.choice3().description()
			));
		});

		// Test-only command: lets you open and click through the GUI locally
		// without waiting for the real 3-minute timer or server networking.
		// Remove once the full loop is verified end-to-end.
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(literal("entropytest").executes(context -> {
				var client = context.getSource().getClient();
				EntropyMod.LOGGER.info("/entropytest -- queueing ChoiceScreen");

				// MUST be deferred. Client commands are dispatched synchronously from
				// inside ChatScreen's enter handling, and ChatScreen then calls
				// setScreen(null) immediately afterwards -- which closes the screen we
				// just opened, in the same frame, with nothing logged anywhere. Using
				// client.execute() runs this on the next tick, after chat has closed.
				client.execute(() -> client.setScreen(new ChoiceScreen(
						EffectPhase.GOOD, 12,
						"sure_footing", "Sure Footing", "+10% movement speed for 3 min",
						"iron_stomach", "Iron Stomach", "Hunger drains 25% slower for 3 min",
						"featherlight", "Featherlight", "No fall damage for 2 min"
				)));
				return 1;
			}));
		});
	}
}
