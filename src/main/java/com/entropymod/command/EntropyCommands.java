package com.entropymod.command;

import com.entropymod.EntropyMod;
import com.entropymod.entropy.EffectDefinition;
import com.entropymod.entropy.EffectRegistry;
import com.entropymod.entropy.EntropyManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.stream.Collectors;

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
			registerGrant(dispatcher);
		});
	}

	/**
	 * Tab-completes the effect argument with every registered id. Remembering 34
	 * exact id strings is the friction {@code /entropygrant} exists to remove, so
	 * completing them is part of the feature rather than a nicety.
	 *
	 * <p>Reads {@link EffectRegistry} live rather than caching, so it cannot go
	 * stale against a registry that gained an effect.
	 */
	private static final SuggestionProvider<CommandSourceStack> EFFECT_IDS =
			(ctx, builder) -> SharedSuggestionProvider.suggest(
					EffectRegistry.all().stream().map(EffectDefinition::id), builder);

	/**
	 * {@code /entropygrant <effect_id>} -- adds one specific effect to the run
	 * immediately, so it can be tested without re-rolling until it is offered.
	 *
	 * <p>Like {@code /entropyforcepick}, this is a <b>real</b> path, not a preview:
	 * {@link EntropyManager#grantEffect} writes to the same acquired set, marks the
	 * same {@code SavedData} dirty and dispatches through the same private
	 * {@code applyToAll} a genuine pick uses. A granted effect therefore persists,
	 * is re-applied after death and rejoin, and occupies its category for
	 * anti-stacking.
	 *
	 * <p>It does <b>not</b> touch entropy, the pick count or the history -- it is a
	 * test tool, not a shortcut through the game loop.
	 *
	 * <p>A bad id and an already-acquired id are both rejected out loud. The second
	 * matters more than it looks: silently re-applying something already owned
	 * would exercise (and so could hide) exactly the idempotency guarantee the
	 * respawn/rejoin design depends on.
	 */
	private static void registerGrant(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("entropygrant")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("effect", StringArgumentType.word())
						.suggests(EFFECT_IDS)
						.executes(ctx -> {
							CommandSourceStack source = ctx.getSource();
							MinecraftServer server = source.getServer();
							String id = StringArgumentType.getString(ctx, "effect");

							EntropyManager manager = EntropyManager.get(server);
							EntropyManager.GrantResult result = manager.grantEffect(server, id);
							EntropyMod.LOGGER.info("/entropygrant {} -> {}", id, result);

							switch (result) {
								case GRANTED -> {
									EffectDefinition granted = EffectRegistry.byId(id);
									source.sendSuccess(() -> Component.literal(
											"[Entropy] Granted " + granted.displayName() + " (" + id + ") -- "
													+ granted.description()
													+ ". Entropy and pick count unchanged."), false);
									return 1;
								}
								case ALREADY_ACQUIRED -> {
									source.sendFailure(Component.literal(
											"[Entropy] '" + id + "' is already acquired this run -- not re-applied. "
													+ "Granting it twice would mask the idempotency guarantee."));
									return 0;
								}
								case UNKNOWN_EFFECT -> {
									source.sendFailure(Component.literal(
											"[Entropy] No effect with id '" + id + "'."));
									source.sendFailure(Component.literal(
											"[Entropy] Valid ids (" + EffectRegistry.all().size() + "): "
													+ EffectRegistry.all().stream()
															.map(EffectDefinition::id)
															.collect(Collectors.joining(", "))));
									return 0;
								}
							}
							return 0;
						})));
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
							"[Entropy] acquired (" + manager.acquired().size() + "): "
									+ (manager.acquired().isEmpty() ? "(none)" : manager.acquired().toString())), false);
					return 1;
				}));
	}

	private EntropyCommands() {}
}
