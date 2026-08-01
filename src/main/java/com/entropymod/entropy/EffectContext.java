package com.entropymod.entropy;

import com.entropymod.EntropyMod;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Everything an {@link EffectBehavior} needs to do its job, in one object.
 *
 * <p>This exists instead of passing a raw {@code MinecraftServer} so that later
 * additions (the {@code EntropyManager} itself, an RNG seeded per-run, the
 * remaining duration, a "why" reason for removal) can be added here <em>once</em>
 * rather than editing the signature of every effect class. Treat this as the
 * extension point: adding a field here is cheap, changing
 * {@code apply(EffectContext)} is not.
 *
 * <p>Scoped to singleplayer per CLAUDE.md: {@link #player()} returns "the"
 * player. It is nullable rather than assumed-present because the server can
 * tick with nobody logged in (the integrated server does this briefly during
 * world load), and a real behavior that dereferences it blindly would crash
 * there. If this project ever goes multiplayer, {@link #players()} is the
 * honest accessor and {@code player()} is the one that has to go.
 */
public final class EffectContext {

	private final MinecraftServer server;
	private final EffectDefinition effect;

	public EffectContext(MinecraftServer server, EffectDefinition effect) {
		this.server = server;
		this.effect = effect;
	}

	public MinecraftServer server() {
		return server;
	}

	/** The definition of the effect being applied/removed -- id, duration, category, description. */
	public EffectDefinition effect() {
		return effect;
	}

	/** All online players. The honest accessor; {@link #player()} is the singleplayer shorthand. */
	public java.util.List<ServerPlayer> players() {
		return server.getPlayerList().getPlayers();
	}

	/** The single player, or null if nobody is online. See the class javadoc on why this is nullable. */
	public ServerPlayer player() {
		java.util.List<ServerPlayer> players = players();
		return players.isEmpty() ? null : players.get(0);
	}

	public void broadcast(String message) {
		server.getPlayerList().broadcastSystemMessage(Component.literal(message), false);
	}

	// ---------------------------------------------------------------------
	// Stub plumbing.
	//
	// These two exist so the 11 placeholder behaviors don't each repeat the
	// same log + broadcast boilerplate. They are SCAFFOLDING: as each effect
	// grows a real implementation, that class stops calling these, and once
	// none do, both methods should be deleted along with this comment.
	// ---------------------------------------------------------------------

	/** Stub announcement for apply: logs and tells the player the effect started. */
	public void announceApply() {
		EntropyMod.LOGGER.info("Applying effect: {} ({}, {} ticks)",
				effect.displayName(), effect.category(), effect.durationTicks());
		broadcast("[Entropy] Applying " + effect.displayName() + " -- " + effect.description());
	}

	/** Stub announcement for remove: logs and tells the player the effect ended. */
	public void announceRemove() {
		EntropyMod.LOGGER.info("Removing effect: {}", effect.displayName());
		broadcast("[Entropy] " + effect.displayName() + " has worn off.");
	}
}
