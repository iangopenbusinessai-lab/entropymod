package com.entropymod.client;

import com.entropymod.entropy.DoubleJumpState;
import net.minecraft.client.player.LocalPlayer;

/**
 * Double Jump's client driver: reads the jump key, decides whether the current
 * state permits an air jump, and performs it through vanilla's own
 * {@code jumpFromGround()}.
 *
 * <p>Client-side because <b>player jumping is client-driven in this version</b> --
 * {@code LivingEntity.jumping} is written only by {@code LocalPlayer.applyInput()}
 * and never by {@code ServerPlayer}. See {@code DoubleJumpBehavior} for the
 * bytecode behind that, for why {@code ClientInput.makeJump()} cannot work
 * mid-air, and for the full state table this class's {@link #allowsAirJump}
 * implements.
 *
 * <p>Driven from {@code END_CLIENT_TICK} rather than another {@code aiStep}
 * mixin. The jump is a velocity change consumed by the next tick's movement, so a
 * one-tick offset is imperceptible -- and this project already has four mixins on
 * the movement path, which is enough contention on one method.
 */
public final class ClientDoubleJump {

	private static final DoubleJumpState STATE = new DoubleJumpState();

	private ClientDoubleJump() {}

	/** Called once per client tick. Cheap for players without the effect. */
	public static void tick(LocalPlayer player) {
		if (!ClientRunState.hasDoubleJump()) {
			// Keep the machine clean so acquiring the effect mid-air cannot
			// immediately spend a charge banked from before it was held.
			STATE.reset();
			return;
		}
		boolean jumpHeld = player.input.keyPresses.jump();
		if (STATE.tick(jumpHeld, player.onGround(), allowsAirJump(player))) {
			// Vanilla's own transition, not a hand-rolled velocity poke: correct
			// height, the sprint impulse, and the jump statistic all come free.
			player.jumpFromGround();
		}
	}

	/** Drops the charge. Registered on DISCONNECT alongside the other client caches. */
	public static void reset() {
		STATE.reset();
	}

	/** Exposed for the harness's benefit and for debugging. */
	public static int chargesLeft() {
		return STATE.chargesLeft();
	}

	/**
	 * Whether the player's current state permits an air jump at all.
	 *
	 * <p>Each clause is a decision recorded in {@code DoubleJumpBehavior}'s state
	 * table, not an incidental guard. A refused state does <b>not</b> consume the
	 * charge, so the effect is intact the moment the state ends.
	 */
	private static boolean allowsAirJump(LocalPlayer player) {
		return !player.getAbilities().flying
				&& !player.isFallFlying()
				&& !player.isInWater()
				&& !player.isInLava()
				&& !player.onClimbable()
				&& !player.isPassenger();
	}
}
