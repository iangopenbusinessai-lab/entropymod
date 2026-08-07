package com.entropymod.entropy.spawn;

import com.entropymod.EntropyMod;
import com.entropymod.entropy.AcquiredEffects;
import com.entropymod.entropy.EntropyManager;
import com.entropymod.entropy.behavior.CreeperMagnetBehavior;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Creeper Magnet's schedule: a creeper near each holder every 30-120 seconds.
 *
 * <p>Same tick-service shape as {@link UnstableSpawner}, sharing {@link SafeSpawn}
 * and {@link SpawnSchedule} with it. See {@link CreeperMagnetBehavior} for the
 * distance band, the cadence and the derivation showing that one second of
 * invisibility cannot become a stealth kill.
 */
public final class CreeperMagnetSpawner {

	private static final SpawnSchedule<UUID> SCHEDULE = new SpawnSchedule<>(
			CreeperMagnetBehavior.MIN_INTERVAL_TICKS, CreeperMagnetBehavior.MAX_INTERVAL_TICKS);

	private CreeperMagnetSpawner() {}

	/** Whether the run's acquired set enables this at all. Harness-drivable gate. */
	public static boolean isActive(AcquiredEffects acquired) {
		return acquired.contains(CreeperMagnetBehavior.ID);
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
			// A creeper cannot target a spectator and would simply stand there; a
			// creative player is in no danger from one. Neither is worth an entity.
			if (player.isSpectator() || player.isCreative()) {
				continue;
			}
			if (SCHEDULE.tick(player.getUUID())) {
				spawnCreeper(player);
			}
		}
	}

	/** Drops all timers. Called on server stop. */
	public static void reset() {
		SCHEDULE.reset();
	}

	// ------------------------------------------------------------------

	private static void spawnCreeper(ServerPlayer player) {
		ServerLevel level = player.level();
		BlockPos pos = SafeSpawn.findNear(level, player, EntityType.CREEPER,
				CreeperMagnetBehavior.MIN_DISTANCE, CreeperMagnetBehavior.MAX_DISTANCE,
				level.getRandom());
		if (pos == null) {
			EntropyMod.LOGGER.debug("Creeper Magnet: no safe spawn position near {}",
					player.getName().getString());
			return;
		}

		// EVENT, not NATURAL: this is a summon. checkSpawnRules is deliberately not
		// consulted -- it is the light-level gate for natural spawning, and honouring
		// it would make this "a creeper appears, but only in the dark".
		Creeper creeper = EntityType.CREEPER.create(level, EntitySpawnReason.EVENT);
		if (creeper == null) {
			EntropyMod.LOGGER.debug("Creeper Magnet: EntityType.CREEPER.create returned null");
			return;
		}

		Vec3 centre = Vec3.atBottomCenterOf(pos);
		creeper.snapTo(centre.x, centre.y, centre.z, level.getRandom().nextFloat() * 360.0f, 0.0f);

		// Vanilla's own post-construction pass, against the position's real
		// difficulty. Nothing creeper-specific happens in it today, but running it is
		// what keeps this an ordinary spawn rather than one that skips whatever a
		// future version puts there.
		creeper.finalizeSpawn(level, level.getCurrentDifficultyAt(pos),
				EntitySpawnReason.EVENT, null);

		// showParticles = false is the load-bearing argument: invisibility WITH
		// particles would surround the creeper in telltale motes and defeat the whole
		// point. ambient and showIcon are false for the same reason DangerSenseGlow
		// sets them -- this is stagecraft, not a potion.
		creeper.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY,
				CreeperMagnetBehavior.INVISIBILITY_TICKS, 0, false, false, false));

		// Not a new capability: NearestAttackableTargetGoal would acquire the player
		// within a tick or two anyway, since a creeper's FOLLOW_RANGE is 16.0 and the
		// maximum spawn distance is 12. It makes the trigger reliable instead of
		// occasionally producing a creeper that strolls away. Vanilla still owns
		// releasing the target, through TargetGoal.canContinueToUse -- which uses the
		// RAW follow range, so a spawn at the very edge would be dropped again
		// immediately. That is why the band stops at 12; see CreeperMagnetBehavior.
		creeper.setTarget(player);

		// Deliberately NOT setPersistenceRequired(): one of these every 30-120
		// seconds forever would fill the world if none of them could ever despawn.
		level.addFreshEntity(creeper);
	}
}
