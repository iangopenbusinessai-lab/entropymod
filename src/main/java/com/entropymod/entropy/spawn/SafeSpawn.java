package com.entropymod.entropy.spawn;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Finds a position near a player that something can actually be spawned at.
 *
 * <p><b>Shared groundwork for every spawn-based effect, not a helper belonging to
 * either of the two that use it today.</b> Unstable and Creeper Magnet ask the
 * same question -- "where near this player is there a real, visible, standable
 * spot" -- and a future companion-mob cluster will ask it again. Solving it twice
 * would have produced two subtly different answers, and the failure mode of a
 * wrong answer is severe in a way that is hard to see: an entity spawned inside
 * stone suffocates or is shoved through the wall, and one spawned behind a wall
 * gives the player no warning at all, which is exactly what the counterplay rule
 * exists to prevent.
 *
 * <h2>The position test is VANILLA'S OWN, not one of ours</h2>
 *
 * <p>{@code SpawnPlacementTypes.ON_GROUND.isSpawnPositionOk(level, pos, type)} is
 * the predicate the game's own natural spawner uses. Read out of its bytecode, it
 * is four things:
 *
 * <ol>
 *   <li>the position is inside the world border;</li>
 *   <li>{@code below.isValidSpawn(level, belowPos, type)} -- solid ground this
 *       entity type can stand on;</li>
 *   <li>{@code NaturalSpawner.isValidEmptySpawnBlock} at the feet, which requires
 *       a non-full collision shape, no fluid, not a signal source, not in
 *       {@code #prevent_mob_spawning_inside}, and not a block dangerous to this
 *       type;</li>
 *   <li>the same test one block higher, for the head.</li>
 * </ol>
 *
 * <p>So "not embedded in blocks" and "on solid ground" are answered by the
 * definition the rest of the game already uses, including for modded blocks and
 * datapack tag changes, rather than by a hand-rolled {@code isAir} check that
 * would be wrong for slabs, water, fire, cactus and half the block list.
 *
 * <p><b>ON_GROUND is used for both effects, including for TNT, which is not a
 * mob.</b> {@code SpawnPlacements.isSpawnPositionOk} would look up the entity
 * type's registered placement, and TNT -- having no natural spawn -- gets
 * {@code NO_RESTRICTIONS}, i.e. "anywhere", which is the one answer this class
 * must never give. Naming the placement type explicitly is what prevents that.
 * It is also the behaviour Unstable wants on its own merits: TNT dropped into
 * mid-air falls somewhere unpredictable, while TNT placed on the floor the player
 * is standing on is a threat they can judge.
 *
 * <h2>Line of sight is required, and it is the counterplay guarantee</h2>
 *
 * <p>A hazard the player cannot see is a hazard they cannot answer. The check is
 * the same clip vanilla's own {@code LivingEntity.hasLineOfSight} performs --
 * {@code ClipContext.Block.COLLIDER}, {@code Fluid.NONE}, from the player's eye
 * to the candidate's centre, requiring {@link HitResult.Type#MISS}. Note this is
 * a visibility test, not a facing test: a creeper appearing behind an unturned
 * back still passes, which is intended. What it rules out is the wall.
 *
 * <h2>The distance band is a spherical shell, checked twice</h2>
 *
 * <p>The horizontal offset is drawn in the requested band, but the vertical
 * search then moves the candidate up or down, so the true distance is re-checked
 * against the band before the position is returned. Without that a candidate 5
 * blocks out and 4 blocks down would be handed back as "5 blocks away" when it is
 * really 6.4 -- the same box-versus-sphere leak Danger Sense and Ore Sense both
 * guard against, and just as invisible in play.
 *
 * <h2>Cost</h2>
 *
 * <p>At most {@link #ATTEMPTS} candidates, each costing up to
 * {@code 2 * VERTICAL_SEARCH + 1} vertical steps of a handful of block-state
 * reads, and one clip only for a candidate that has already passed everything
 * else. That is bounded by a few hundred block reads in the worst case, once per
 * trigger -- i.e. once per 30 seconds per player, against Green Thumb's 4,913
 * reads every second. Returning {@code null} early on the first success is the
 * normal case in open terrain.
 */
public final class SafeSpawn {

	/**
	 * How many horizontal candidates to try before giving up.
	 *
	 * <p>Generous, because failing has a real cost -- the trigger is consumed and
	 * the player gets nothing for 30 seconds -- and cheap, because each attempt is
	 * a few block-state reads.
	 */
	public static final int ATTEMPTS = 24;

	/**
	 * How far up and down from the player's own feet the vertical search looks.
	 *
	 * <p>Four blocks each way covers ordinary terrain, staircases and the floor
	 * below a ledge without letting a spawn appear on a completely different
	 * storey of a build. Searched nearest-first, so a flat field always answers
	 * with the player's own Y.
	 */
	public static final int VERTICAL_SEARCH = 4;

	private SafeSpawn() {}

	/**
	 * A standable, unobstructed, visible position in the given distance band
	 * around the player, or {@code null} if none was found.
	 *
	 * <p>{@code null} is a real and expected outcome -- a player walled into a
	 * 1x2 shaft has nowhere valid nearby, and the honest answer is that nothing
	 * spawns. Callers must not retry in a loop; the next interval will ask again.
	 *
	 * @param type        the entity type the position must be valid for
	 * @param minDistance inclusive lower bound, in blocks, on the true distance
	 * @param maxDistance inclusive upper bound, in blocks, on the true distance
	 */
	public static BlockPos findNear(ServerLevel level, ServerPlayer player, EntityType<?> type,
									double minDistance, double maxDistance, RandomSource random) {
		Vec3 eye = player.getEyePosition();
		double minSqr = minDistance * minDistance;
		double maxSqr = maxDistance * maxDistance;
		int baseY = player.blockPosition().getY();

		for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
			double angle = random.nextDouble() * (Math.PI * 2.0);
			double distance = minDistance + random.nextDouble() * (maxDistance - minDistance);
			// Mth.floor, not a cast: a cast truncates toward zero and would be off by
			// one for every negative coordinate -- a bug that only shows up west or
			// north of the origin.
			int x = Mth.floor(player.getX() + Math.cos(angle) * distance);
			int z = Mth.floor(player.getZ() + Math.sin(angle) * distance);

			// Nearest Y first: 0, -1, +1, -2, +2, ... so flat ground answers with the
			// player's own level and a slope answers with the nearest surface rather
			// than whichever one the loop happened to reach first.
			for (int step = 0; step <= VERTICAL_SEARCH * 2; step++) {
				int dy = (step % 2 == 0) ? -(step / 2) : (step + 1) / 2;
				BlockPos candidate = new BlockPos(x, baseY + dy, z);

				// Level.isLoaded, not hasChunkAt: it is isInValidBounds() AND a loaded
				// chunk, so it rejects a candidate the vertical search pushed below the
				// world floor or above its ceiling as well as one in an unloaded chunk.
				// (hasChunkAt would have answered only the second question -- and its
				// underlying hasChunk is deprecated in this version.) Never force a
				// chunk to load or generate for this.
				if (!level.isLoaded(candidate)) {
					continue;
				}
				if (!SpawnPlacementTypes.ON_GROUND.isSpawnPositionOk(level, candidate, type)) {
					continue;
				}

				Vec3 centre = Vec3.atBottomCenterOf(candidate).add(0.0, 0.5, 0.0);
				double actualSqr = centre.distanceToSqr(player.position());
				if (actualSqr < minSqr || actualSqr > maxSqr) {
					continue;
				}
				if (!hasLineOfSight(level, player, eye, centre)) {
					continue;
				}
				return candidate;
			}
		}
		return null;
	}

	/**
	 * Whether nothing solid stands between the player's eye and a point.
	 *
	 * <p>Exactly the clip {@code LivingEntity.hasLineOfSight} performs, with the
	 * player as the clip context's entity so its own collision is excluded.
	 */
	public static boolean hasLineOfSight(ServerLevel level, ServerPlayer player, Vec3 from, Vec3 to) {
		return level.clip(new ClipContext(from, to,
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)).getType()
				== HitResult.Type.MISS;
	}
}
