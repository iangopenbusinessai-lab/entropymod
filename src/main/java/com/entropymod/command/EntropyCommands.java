package com.entropymod.command;

import com.entropymod.EntropyMod;
import com.entropymod.entropy.EntropyManager;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * Server-side debug commands. These run on the server and talk to the real
 * {@link EntropyManager} for the world, which is the whole point of them
 * existing separately from the client-side preview command.
 *
 * <p><b>Why this class exists at all.</b> The client-only {@code /entropytest}
 * (now {@code /entropypreview}) opens {@code ChoiceScreen} with hardcoded
 * strings and never touches the server. Clicking a card in it therefore never
 * reaches {@code onChoiceMade}, so it can't exercise history, active-effect
 * tracking, anti-stacking, or the behavior stubs -- despite looking exactly
 * like a real pick on screen. It was easy to mistake one for the other. These
 * commands are the real path; the preview command is the fake one, and the
 * names now say which is which.
 *
 * <p>NOTE on permissions: {@code source.hasPermission(2)} -- the idiom in every
 * tutorial -- does not exist in this Minecraft version. See CLAUDE.md Part 0.
 *
 * <p>{@code LEVEL_GAMEMASTERS} is permission level 2, so in singleplayer the
 * world needs <b>Allow Cheats: ON</b> or these commands won't appear.
 */
public final class EntropyCommands {

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			registerForcePick(dispatcher);
			registerStatus(dispatcher);
		});
	}

	/**
	 * {@code /entropyforcepick} -- opens a pick immediately instead of waiting out
	 * the interval.
	 *
	 * <p>This deliberately does NOT roll its own choices or build its own payload.
	 * It calls {@link EntropyManager#forcePick}, which delegates to the same
	 * private {@code triggerPick} the tick loop uses, so the roll, anti-stacking
	 * filter, phase alternation, entropy value, expiry of interval-scoped effects
	 * and the outgoing packet are all the production code path. If this command
	 * ever starts constructing choices itself, that is a bug -- it would stop
	 * testing the thing it exists to test.
	 */
	private static void registerForcePick(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("entropyforcepick")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(ctx -> {
					CommandSourceStack source = ctx.getSource();
					MinecraftServer server = source.getServer();
					EntropyManager manager = EntropyManager.get(server);

					EntropyManager.PickTrigger result = manager.forcePick(server);
					EntropyMod.LOGGER.info("/entropyforcepick -> {}", result);

					switch (result) {
						case OPENED -> {
							// Deliberately terse: the choices themselves arrive on the
							// client via the normal OpenChoicePayload, exactly as they
							// would from a real interval firing.
							// Parens matter: pickCount hasn't incremented yet, and without
							// them string concat turns pick 2 into "#21".
							source.sendSuccess(() -> Component.literal(
									"[Entropy] Pick #" + (manager.getPickCount() + 1) + " forced open."), false);
							return 1;
						}
						case CHOICE_PENDING -> {
							source.sendFailure(Component.literal(
									"[Entropy] A pick is already open and unanswered -- answer it first."));
							return 0;
						}
						case RUN_ALREADY_OVER -> {
							source.sendFailure(Component.literal(
									"[Entropy] The run is already over (entropy reached the cap)."));
							return 0;
						}
						case RUN_ENDED_NOW -> {
							source.sendFailure(Component.literal(
									"[Entropy] Entropy is at the cap -- this ended the run instead of opening a pick."));
							return 0;
						}
						case NO_ELIGIBLE_EFFECTS -> {
							source.sendFailure(Component.literal(
									"[Entropy] No eligible " + manager.currentPhase() + " effects at entropy "
											+ manager.getEntropy() + " -- see the log."));
							return 0;
						}
					}
					return 0;
				}));
	}

	/**
	 * {@code /entropystatus} -- reads out the real manager state. Read-only.
	 * Mostly here so a forced pick's anti-stacking behavior can be checked against
	 * what is actually active, rather than inferred from chat scrollback.
	 */
	private static void registerStatus(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("entropystatus")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(ctx -> {
					CommandSourceStack source = ctx.getSource();
					EntropyManager manager = EntropyManager.get(source.getServer());

					source.sendSuccess(() -> Component.literal(
							"[Entropy] entropy " + manager.getEntropy() + "/" + manager.getEntropyCap()
									+ ", picks made " + manager.getPickCount()
									+ ", next phase " + manager.currentPhase()
									+ (manager.isGameOver() ? ", RUN OVER" : "")
									+ (manager.isWaitingOnChoice() ? ", waiting on a choice" : "")), false);
					source.sendSuccess(() -> Component.literal(
							"[Entropy] interval " + manager.getTicksIntoInterval() + "/"
									+ manager.getIntervalTicks() + " ticks"), false);
					source.sendSuccess(() -> Component.literal(
							"[Entropy] active: " + (manager.getActiveEffects().isEmpty()
									? "(none)" : manager.getActiveEffects().toString())), false);
					return 1;
				}));
	}

	private EntropyCommands() {}
}
