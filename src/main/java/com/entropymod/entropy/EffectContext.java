package com.entropymod.entropy;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Everything an {@link EffectBehavior} needs to do its job, in one object.
 *
 * <p>This exists instead of passing a raw {@code MinecraftServer} so that later
 * additions can be added here <em>once</em> rather than editing the signature of
 * every effect class. Treat this as the extension point: adding a field here is
 * cheap, changing {@code apply(EffectContext)} is not.
 *
 * <p>Unlike the first version, this now names a <b>specific target player</b>
 * rather than "the" player. Re-application after a respawn happens for one
 * player at a time, so a behavior must be told which one — guessing
 * "first in the list" would silently buff the wrong player the moment a second
 * one exists.
 */
public final class EffectContext {

	/** Why {@link EffectBehavior#apply} is being called. */
	public enum Reason {
		/** The player just chose this effect. Happens once per effect, ever. */
		PICKED,
		/**
		 * Re-establishing an effect the player already owns, after a respawn, a
		 * rejoin, or a dimension change that rebuilt their attribute map. Happens an
		 * unbounded number of times — behaviors must be idempotent under this.
		 */
		REAPPLIED
	}

	private final MinecraftServer server;
	private final ServerPlayer target;
	private final EffectDefinition effect;
	private final Reason reason;

	public EffectContext(MinecraftServer server, ServerPlayer target, EffectDefinition effect, Reason reason) {
		this.server = server;
		this.target = target;
		this.effect = effect;
		this.reason = reason;
	}

	public MinecraftServer server() {
		return server;
	}

	/** The player this apply/remove is for. Never null. */
	public ServerPlayer target() {
		return target;
	}

	/** The definition being applied/removed -- id, category, description. */
	public EffectDefinition effect() {
		return effect;
	}

	public Reason reason() {
		return reason;
	}

	/** True on a fresh pick, false when re-establishing an effect after respawn/rejoin. */
	public boolean isFreshPick() {
		return reason == Reason.PICKED;
	}

	/** Message just the target player. */
	public void tell(String message) {
		target.sendSystemMessage(Component.literal(message));
	}

	/** Message everyone. Used for run-wide announcements, not per-effect chatter. */
	public void broadcast(String message) {
		server.getPlayerList().broadcastSystemMessage(Component.literal(message), false);
	}
}
