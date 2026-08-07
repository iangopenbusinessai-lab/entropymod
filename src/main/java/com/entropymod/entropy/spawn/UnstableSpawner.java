package com.entropymod.entropy.spawn;

import com.entropymod.EntropyMod;
import com.entropymod.entropy.AcquiredEffects;
import com.entropymod.entropy.EntropyManager;
import com.entropymod.entropy.behavior.UnstableBehavior;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Unstable's schedule: primed TNT beside each holder every 30 seconds.
 *
 * <p>A tick service -- the established third shape in this project alongside
 * attribute and mixin (see {@code GreenThumbGrowth}, {@code BlightTouchedTrample}
 * and {@code DangerSenseGlow}) -- and the right one here for the usual reason:
 * this is a schedule of ours, not a value vanilla computes at a hookable point.
 *
 * <p>See {@link UnstableBehavior} for the fuse, the distance band and the
 * counterplay derivation, and {@link SafeSpawn} for the position rules.
 *
 * <h2>Cost</h2>
 *
 * <p>One hash lookup per server tick when nobody holds the effect. When someone
 * does, one integer decrement per holder per tick, and a {@link SafeSpawn} search
 * only on the one tick in 600 that fires.
 */
public final class UnstableSpawner {

	private static final SpawnSchedule<UUID> SCHEDULE = new SpawnSchedule<>(
			UnstableBehavior.INTERVAL_TICKS, UnstableBehavior.INTERVAL_TICKS);

	private UnstableSpawner() {}

	/**
	 * Whether the run's acquired set enables this at all.
	 *
	 * <p>Split out and free of Minecraft types so the harness can assert the gate
	 * directly: without Unstable in the set, no timer runs and no position is
	 * searched.
	 */
	public static boolean isActive(AcquiredEffects acquired) {
		return acquired.contains(UnstableBehavior.ID);
	}

	/** Called every server tick. Returns on the first check for a run without the effect. */
	public static void tick(MinecraftServer server) {
		if (!isActive(EntropyManager.get(server).acquired())) {
			if (!SCHEDULE.isEmpty()) {
				SCHEDULE.reset();
			}
			return;
		}

		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		// A Set rather than a List so SpawnSchedule.retainAll stays a hash lookup
		// per entry rather than a linear scan.
		Set<UUID> live = new HashSet<>(players.size());
		for (ServerPlayer player : players) {
			live.add(player.getUUID());
		}
		SCHEDULE.retainAll(live);

		for (ServerPlayer player : players) {
			// A spectator cannot be hurt and cannot move blocks; dropping live TNT
			// around a ghost would crater the world for nothing. Creative is skipped
			// for the same reason a debug tool is: the effect has no meaning there.
			// (Same guard DangerSenseGlow applies, for the same reason.)
			if (player.isSpectator() || player.isCreative()) {
				continue;
			}
			if (SCHEDULE.tick(player.getUUID())) {
				spawnTnt(player);
			}
		}
	}

	/** Drops all timers. Called on server stop so nothing leaks into the next world. */
	public static void reset() {
		SCHEDULE.reset();
	}

	// ------------------------------------------------------------------

	private static void spawnTnt(ServerPlayer player) {
		ServerLevel level = player.level();
		BlockPos pos = SafeSpawn.findNear(level, player, EntityType.TNT,
				UnstableBehavior.MIN_DISTANCE, UnstableBehavior.MAX_DISTANCE,
				level.getRandom());
		if (pos == null) {
			// A real outcome, not an error: a player sealed into a narrow shaft has
			// nowhere valid and visible nearby. The trigger is spent and the next one
			// is 30 seconds away. Logged because this effect is otherwise impossible
			// to tell apart from "the timer isn't running" -- the same reason Clumsy
			// Digger and Double Jump log.
			EntropyMod.LOGGER.debug("Unstable: no safe spawn position near {}",
					player.getName().getString());
			return;
		}

		Vec3 centre = Vec3.atBottomCenterOf(pos);

		// The public constructor TntBlock.prime uses. It sets the random horizontal
		// jitter, the 80-tick fuse and the owner; setFuse below re-states the fuse
		// from this effect's own constant so the constant is the authority rather
		// than a comment about vanilla's.
		//
		// owner = null, exactly as TntBlock.prime(Level, BlockPos) passes for TNT
		// nobody lit -- EntityReference.of(null) returns null cleanly, and the death
		// message becomes "Player blew up" rather than naming the player as their own
		// killer.
		PrimedTnt tnt = new PrimedTnt(level, centre.x, centre.y, centre.z, null);
		tnt.setFuse(UnstableBehavior.FUSE_TICKS);
		level.addFreshEntity(tnt);

		// The other two lines TntBlock.prime performs. The sound is the effect's
		// entire warning channel -- volume 1.0 gives SoundEvent.getRange 16 blocks,
		// more than twice the maximum spawn distance -- and passing null as the
		// "except" entity broadcasts it, which is what TntBlock does too. (Note that
		// parameter excludes a player rather than targeting one; see CLAUDE.md.)
		level.playSound(null, centre.x, centre.y, centre.z,
				SoundEvents.TNT_PRIMED, SoundSource.BLOCKS, 1.0f, 1.0f);
		level.gameEvent(tnt, GameEvent.PRIME_FUSE, pos);
	}
}
