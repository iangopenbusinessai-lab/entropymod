package com.entropymod.entropy;

import com.entropymod.EntropyMod;
import com.entropymod.entropy.behavior.BadReputationBehavior;
import com.entropymod.entropy.behavior.BeastWhispererBehavior;
import com.entropymod.entropy.behavior.ClumsyDiggerBehavior;
import com.entropymod.entropy.behavior.GreenThumbBehavior;
import com.entropymod.entropy.behavior.LeakyPocketsBehavior;
import com.entropymod.entropy.behavior.FastLearnerBehavior;
import com.entropymod.entropy.behavior.FragileBehavior;
import com.entropymod.entropy.behavior.FrostWalkerInnateBehavior;
import com.entropymod.entropy.behavior.GrowlingStomachBehavior;
import com.entropymod.entropy.behavior.ExposedBehavior;
import com.entropymod.entropy.behavior.IronSkinBehavior;
import com.entropymod.entropy.behavior.IronStomachBehavior;
import com.entropymod.entropy.behavior.IronWillBehavior;
import com.entropymod.entropy.behavior.SlowLearnerBehavior;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

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
	 * True if projectile knockback should be skipped for this player (Iron Will).
	 * Read by the {@code LivingEntity.hurtServer} knockback redirect, which has
	 * already established that the damage source is projectile-based -- this
	 * answers only "does this player have the effect".
	 */
	public static boolean ignoresProjectileKnockback(Player player) {
		AcquiredEffects acquired = acquired(player);
		return acquired != null && acquired.contains(IronWillBehavior.ID);
	}

	/** True if this player's water-walking should be granted (Frost Walker Innate). */
	public static boolean hasInnateFrostWalker(Player player) {
		AcquiredEffects acquired = acquired(player);
		return acquired != null && acquired.contains(FrostWalkerInnateBehavior.ID);
	}

	/** Scales footstep loudness. Read by the Entity.playStepSound mixin. */
	public static float stepSoundVolumeMultiplier(Player player) {
		AcquiredEffects acquired = acquired(player);
		if (acquired == null) {
			return 1.0f;
		}
		return acquired.contains(ExposedBehavior.ID)
				? ExposedBehavior.VOLUME_MULTIPLIER
				: 1.0f;
	}

	/**
	 * Scales how far away mobs notice this player. Read by the
	 * {@code LivingEntity.getVisibilityPercent} mixin, which feeds
	 * {@code TargetingConditions.test} -- the same value vanilla reduces to 0.8
	 * while sneaking.
	 */
	public static float detectionRangeMultiplier(Player player) {
		AcquiredEffects acquired = acquired(player);
		if (acquired == null) {
			return 1.0f;
		}
		return acquired.contains(ExposedBehavior.ID)
				? ExposedBehavior.DETECTION_MULTIPLIER
				: 1.0f;
	}

	/**
	 * True if animals should not flee from this player (Beast Whisperer). Read by
	 * both the {@code PanicGoal} and {@code AvoidEntityGoal} mixins.
	 *
	 * <p>Takes an {@code Entity} rather than a {@code Player} because both call
	 * sites hold something more general -- a damage source's attacker, and a
	 * goal's avoid target -- and pushing the {@code instanceof} in here keeps the
	 * mixins to the one-line shape the rest of this class exists to preserve.
	 */
	public static boolean suppressesFleeing(Entity entity) {
		return entity instanceof Player player
				&& acquired(player) != null
				&& acquired(player).contains(BeastWhispererBehavior.ID);
	}

	/**
	 * True if a player holding Green Thumb is within {@link GreenThumbBehavior#RADIUS}
	 * of this block. Read by {@code GreenThumbGrowth} to re-verify a crop at the
	 * moment of each scheduled advance.
	 *
	 * <p>"Nearest" rather than "any" so two players cannot both claim the same
	 * block. This is the last remaining crop-proximity check in the mod: the
	 * {@code cropGrowthMultiplier} hook that used to sit beside it was deleted
	 * along with Blight Touched's growth-slowdown mechanic, since Blight Touched
	 * was the only effect still feeding it. See {@code BlightTouchedTrample}.
	 */
	public static boolean hasGreenThumbNearby(Level level, BlockPos pos) {
		AcquiredEffects acquired = acquired(nearestPlayerTo(level, pos));
		return acquired != null && acquired.contains(GreenThumbBehavior.ID);
	}

	/** The nearest player within the crop radius of a block, or null (including client-side). */
	private static Player nearestPlayerTo(Level level, BlockPos pos) {
		if (!(level instanceof ServerLevel serverLevel)) {
			return null;
		}
		return serverLevel.getNearestPlayer(
				pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
				GreenThumbBehavior.RADIUS, false);
	}

	/** True if this jump should spill an item (Leaky Pockets). Rolls the chance itself. */
	public static boolean rollLeakyPockets(Player player, RandomSource random) {
		AcquiredEffects acquired = acquired(player);
		return acquired != null
				&& acquired.contains(LeakyPocketsBehavior.ID)
				&& random.nextFloat() < LeakyPocketsBehavior.CHANCE;
	}

	/**
	 * Extra durability damage for this use, or 0 (Clumsy Digger). Rolls the chance
	 * itself so the mixin stays a single expression.
	 *
	 * <p>Logs each successful roll at DEBUG. This effect is invisible by
	 * construction -- there is no sound, no message and no on-screen change, only a
	 * durability bar moving marginally faster -- so "is it firing at all" and "is it
	 * just rare" are indistinguishable in play, which is exactly how it was
	 * reported. The log line is what separates them: grep {@code Clumsy Digger} in
	 * {@code run/logs/debug-N.log.gz} and count. It costs nothing for players
	 * without the effect, since both returns above it are taken first.
	 */
	public static int rollClumsyDiggerExtraDamage(Player player, RandomSource random, int maxDamage) {
		AcquiredEffects acquired = acquired(player);
		if (acquired == null || !acquired.contains(ClumsyDiggerBehavior.ID)) {
			return 0;
		}
		if (random.nextFloat() >= ClumsyDiggerBehavior.CHANCE) {
			return 0;
		}
		int extra = ClumsyDiggerBehavior.extraDamageFor(maxDamage);
		EntropyMod.LOGGER.debug("Clumsy Digger: +{} durability damage (tool max {}) for {}",
				extra, maxDamage, player.getName().getString());
		return extra;
	}

	/** True if villagers should overcharge this player (Bad Reputation). */
	public static boolean hasBadReputation(Player player) {
		AcquiredEffects acquired = acquired(player);
		return acquired != null && acquired.contains(BadReputationBehavior.ID);
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
