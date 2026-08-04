package com.entropymod.entropy.behavior;

import com.entropymod.entropy.EffectBehavior;
import com.entropymod.entropy.EffectContext;
import net.minecraft.server.level.ServerPlayer;

/**
 * Creative Flight (GOOD / MOVEMENT, Tier 2) -- permanent creative-style flight
 * while still in Survival.
 *
 * <p>Sets the <b>real</b> {@code Abilities.mayfly} flag rather than imitating
 * flight with motion code, so double-tap-to-fly, the flight HUD behaviour, and
 * the server's own movement validation all behave exactly as they do in
 * Creative. {@code Abilities.mayfly} is a public field; {@code onUpdateAbilities()}
 * is what sends {@code ClientboundPlayerAbilitiesPacket}, and without it the
 * server would believe the player can fly while the client did not.
 *
 * <p><b>Idempotent by construction:</b> setting a boolean that is already true
 * and re-sending the packet is a no-op, which is what {@code apply} running
 * again on every respawn, rejoin and dimension change requires.
 *
 * <p><b>Known limit, stated rather than hidden:</b> vanilla rewrites the whole
 * ability set on a gamemode change ({@code GameType.updatePlayerAbilities}), so
 * switching gamemode and back clears this until the next respawn, rejoin or
 * dimension change re-applies it. Not worth a gamemode hook: the mod is played
 * in Survival, and the three existing re-application hooks already cover every
 * path a normal run takes.
 *
 * <p>No client-side awareness is needed. Unlike Tier 2's input and camera
 * effects, the ability flag is vanilla state that vanilla already synchronises.
 */
public final class CreativeFlightBehavior implements EffectBehavior {

	public static final String ID = "creative_flight";

	@Override
	public void apply(EffectContext ctx) {
		ServerPlayer player = ctx.target();
		if (player.getAbilities().mayfly) {
			return;
		}
		player.getAbilities().mayfly = true;
		player.onUpdateAbilities();
	}

	@Override
	public void remove(EffectContext ctx) {
		ServerPlayer player = ctx.target();
		// Effects are permanent, so this is only reachable by a future un-apply
		// path. Clearing `flying` as well matters: leaving it set would strand the
		// player airborne with no way to descend.
		player.getAbilities().mayfly = false;
		player.getAbilities().flying = false;
		player.onUpdateAbilities();
	}
}
