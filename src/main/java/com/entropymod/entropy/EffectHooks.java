package com.entropymod.entropy;

import com.entropymod.entropy.behavior.FastLearnerBehavior;
import com.entropymod.entropy.behavior.FragileBehavior;
import com.entropymod.entropy.behavior.GrowlingStomachBehavior;
import com.entropymod.entropy.behavior.IronSkinBehavior;
import com.entropymod.entropy.behavior.IronStomachBehavior;
import com.entropymod.entropy.behavior.SlowLearnerBehavior;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

/**
 * The single door between the mixins and the run state. Every hook-driven effect
 * is a multiplier looked up here.
 *
 * <p>Mixins are the most fragile code in the project, so they are kept to one
 * line each: compute nothing, decide nothing, just multiply by what this returns.
 * All the "does this player have that effect" logic lives here where it is
 * ordinary readable Java.
 *
 * <p><b>Always returns a usable multiplier, never throws, never returns null.</b>
 * A mixin runs inside vanilla's own call stack; an exception thrown from one
 * takes the server down with a stack trace that blames Minecraft rather than
 * this mod. Every path here falls back to {@code 1.0f} (no change).
 *
 * <p><b>Client-side calls return 1.0f.</b> The acquired-effect set only exists
 * on the server, and {@code player.level()} is a {@code ClientLevel} on the
 * client, so the {@code instanceof ServerLevel} check below is what makes these
 * safe to call from a mixin that runs on both sides. The three effects routed
 * through here are all server-authoritative (hunger, damage, XP), so nothing is
 * lost by the client seeing 1.0.
 */
public final class EffectHooks {

	/** Scales hunger drain. Read by the Player.causeFoodExhaustion mixin. */
	public static float exhaustionMultiplier(Player player) {
		AcquiredEffects acquired = acquired(player);
		if (acquired == null) {
			return 1.0f;
		}
		float multiplier = 1.0f;
		if (acquired.contains(IronStomachBehavior.ID)) {
			multiplier *= IronStomachBehavior.MULTIPLIER;
		}
		if (acquired.contains(GrowlingStomachBehavior.ID)) {
			multiplier *= GrowlingStomachBehavior.MULTIPLIER;
		}
		return multiplier;
	}

	/** Scales incoming damage. Read by the LivingEntity.hurtServer mixin. */
	public static float damageTakenMultiplier(Player player) {
		AcquiredEffects acquired = acquired(player);
		if (acquired == null) {
			return 1.0f;
		}
		float multiplier = 1.0f;
		if (acquired.contains(IronSkinBehavior.ID)) {
			multiplier *= IronSkinBehavior.MULTIPLIER;
		}
		if (acquired.contains(FragileBehavior.ID)) {
			multiplier *= FragileBehavior.MULTIPLIER;
		}
		return multiplier;
	}

	/** Scales experience gained. Read by the Player.giveExperiencePoints mixin. */
	public static float experienceMultiplier(Player player) {
		AcquiredEffects acquired = acquired(player);
		if (acquired == null) {
			return 1.0f;
		}
		float multiplier = 1.0f;
		if (acquired.contains(FastLearnerBehavior.ID)) {
			multiplier *= FastLearnerBehavior.MULTIPLIER;
		}
		if (acquired.contains(SlowLearnerBehavior.ID)) {
			multiplier *= SlowLearnerBehavior.MULTIPLIER;
		}
		return multiplier;
	}

	/**
	 * The run's acquired set, or null if unavailable.
	 *
	 * <p>The {@code ServerLevel} cast is the accessor pattern recorded in
	 * CLAUDE.md — {@code ServerPlayer.server} is a private field in this mapping,
	 * and there is no {@code serverLevel()} method. Here the {@code instanceof}
	 * doubles as the client-side guard.
	 */
	private static AcquiredEffects acquired(Player player) {
		if (player == null || !(player.level() instanceof ServerLevel level)) {
			return null;
		}
		MinecraftServer server = level.getServer();
		if (server == null) {
			return null;
		}
		return EntropyManager.get(server).acquired();
	}

	/**
	 * Pairs a multiplier with a whole-number amount without letting rounding eat
	 * small values. {@code (int)(1 * 1.5f)} is 1, so a stream of 1-XP orbs under
	 * Fast Learner would gain nothing at all; rounding instead of truncating makes
	 * it 2 half the time, which is the intended average. Guaranteed non-negative.
	 */
	public static int scaleAmount(int amount, float multiplier) {
		return Math.max(0, Math.round(amount * multiplier));
	}

	private EffectHooks() {}
}
