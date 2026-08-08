package com.entropymod.entropy.companion;

import com.entropymod.EntropyMod;
import com.entropymod.entropy.spawn.SafeSpawn;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Places a companion group around a player, once, at distinct safe positions.
 *
 * <p>Reuses {@code SafeSpawn} wholesale -- the same vanilla {@code ON_GROUND}
 * placement test, chunk/bounds check and line-of-sight requirement the two hazard
 * effects use. Companions want {@code HORIZONTAL} distance for the same reason
 * Creeper Magnet does: they walk to you, so the height they start at is not part
 * of what "spawns beside you" means. (Unstable is the exception, and the reason is
 * recorded on {@code SafeSpawn.DistanceMode}.)
 *
 * <h2>Distinct positions, not a stack</h2>
 *
 * <p>{@code SafeSpawn} draws a fresh random column per call, so two of fifteen
 * calls can land on the same block by chance -- roughly a coin flip somewhere in
 * fifteen draws over a small annulus. Entities spawned in one block do not merge,
 * but they do push each other apart over the following seconds, which reads as a
 * spawning glitch. {@link #spawnGroup} therefore remembers the positions it has
 * used and re-draws rather than reusing one.
 *
 * <p><b>A short group is a real outcome and is not retried into the ground.</b>
 * If the terrain cannot supply fifteen distinct valid spots, fifteen wolves are
 * not delivered; the count actually placed is logged. That is the same honesty
 * {@code SafeSpawn} returning no position already has, and the alternative --
 * loosening the placement rules until the number is hit -- is how you get
 * companions inside walls.
 */
public final class CompanionSpawner {

	/** Closest a companion is placed. Far enough not to shove the player on arrival. */
	public static final double MIN_DISTANCE = 2.0;

	/** Furthest. Close enough that the group reads as "yours" the moment it appears. */
	public static final double MAX_DISTANCE = 6.0;

	/**
	 * Extra placement attempts beyond the requested count, so a few unusable draws
	 * do not cost members of the group one-for-one.
	 */
	public static final int EXTRA_ATTEMPTS = 12;

	private CompanionSpawner() {}

	/**
	 * Spawns up to {@code count} entities around the player and records them.
	 *
	 * <p><b>Callers must gate this on {@code EffectContext.isFreshPick()}.</b>
	 * Nothing here checks whether companions already exist -- see
	 * {@link CompanionRoster} for why the one-shot gate is the guarantee and a
	 * liveness lookup would be a duplication bug.
	 *
	 * @param configure applied to each entity before it is added to the level, so
	 *                  taming, invulnerability and equipment are set while the
	 *                  entity is still off-world
	 * @return the entity UUIDs actually placed, in placement order
	 */
	public static <T extends Mob> List<String> spawnGroup(ServerPlayer player, EntityType<T> type,
														  int count, Consumer<T> configure) {
		ServerLevel level = player.level();
		Set<BlockPos> used = new HashSet<>();
		List<String> spawned = new ArrayList<>(count);

		int attempts = count + EXTRA_ATTEMPTS;
		for (int attempt = 0; attempt < attempts && spawned.size() < count; attempt++) {
			SafeSpawn.Attempt found = SafeSpawn.findNear(level, player, type,
					MIN_DISTANCE, MAX_DISTANCE, level.getRandom(),
					SafeSpawn.DistanceMode.HORIZONTAL);
			if (!found.found() || !used.add(found.pos())) {
				continue;
			}

			T entity = type.create(level, EntitySpawnReason.EVENT);
			if (entity == null) {
				continue;
			}
			Vec3 centre = Vec3.atBottomCenterOf(found.pos());
			entity.snapTo(centre.x, centre.y, centre.z, level.getRandom().nextFloat() * 360.0f, 0.0f);
			entity.finalizeSpawn(level, level.getCurrentDifficultyAt(found.pos()),
					EntitySpawnReason.EVENT, null);

			// Configured BEFORE addFreshEntity so the first tick the client ever sees
			// already has the tamed flag, the armour and the invulnerability -- rather
			// than a frame of untamed wolf that then changes.
			configure.accept(entity);

			level.addFreshEntity(entity);
			spawned.add(entity.getUUID().toString());
		}

		if (spawned.size() < count) {
			EntropyMod.LOGGER.debug(
					"Companions: placed {} of {} {} for {} -- terrain had no more distinct "
							+ "safe positions nearby",
					spawned.size(), count, type.toShortString(), player.getName().getString());
		}
		return spawned;
	}
}
