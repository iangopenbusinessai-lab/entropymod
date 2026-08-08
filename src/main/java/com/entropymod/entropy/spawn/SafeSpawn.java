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
 *   <li>{@code below.isValidSpawn(level, belowPos, type)} -- whose default
 *       predicate is {@code isFaceSturdy(UP) && getLightEmission() < 14}, i.e.
 *       solid ground this entity type can stand on;</li>
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
 * must never give.
 *
 * <h2>THE DISTANCE BAND IS HORIZONTAL. This was a real bug.</h2>
 *
 * <p>The first version drew a <em>horizontal</em> radius, let the vertical search
 * move the candidate up to four blocks up or down, and then re-checked the
 * resulting <em>3D</em> distance against the same band. <b>Those two rules fight
 * each other: finding a valid surface could disqualify the candidate for having
 * been found.</b> On the terrain where this was first played, that alone rejected
 * about one candidate in ten on top of everything else.
 *
 * <p>It is also the wrong reading of the design. "TNT appears 5 to 7 blocks away"
 * means 5 to 7 blocks <em>across the ground</em>; nobody counts the drop down a
 * slope as part of the distance. So the band is now tested horizontally, and the
 * vertical offset is bounded separately by {@link #VERTICAL_SEARCH}. The
 * horizontal re-check after flooring to a block is still required -- flooring can
 * move a candidate up to ~0.7 blocks radially, and without the re-check the band
 * would quietly leak at both ends.
 *
 * <h2>Line of sight aims at the ENTITY, not at the ground under it</h2>
 *
 * <p>A hazard the player cannot see is a hazard they cannot answer, so the search
 * requires an unobstructed clip -- the same one vanilla's own
 * {@code LivingEntity.hasLineOfSight} performs ({@code ClipContext.Block.COLLIDER},
 * {@code Fluid.NONE}, from the player's eye, requiring {@link HitResult.Type#MISS}).
 * Note this is a visibility test, not a facing test: a creeper appearing behind an
 * unturned back still passes. What it rules out is the wall.
 *
 * <p><b>But the first version aimed at the centre of the feet block -- half a
 * block above the ground -- and that made the ray graze the terrain for its whole
 * length.</b> Over any rise or rounded slope it clipped the ground short of the
 * target, which is not what the question means: the player would have seen the
 * creeper's head over the rise perfectly well. The aim points are now taken up
 * the spawned entity's own height ({@link #AIM_FRACTIONS}), and the candidate
 * passes if <em>any</em> of them is visible.
 *
 * <h2>Cost</h2>
 *
 * <p>At most {@link #ATTEMPTS} candidate columns, each costing up to
 * {@code 2 * VERTICAL_SEARCH + 1} vertical steps of a handful of block-state
 * reads, and clips only for a candidate that has already passed everything else.
 * Bounded by a few hundred block reads in the worst case, and the first success
 * returns immediately -- the normal case in open terrain.
 */
public final class SafeSpawn {

	/**
	 * How many horizontal candidate columns to try before giving up.
	 *
	 * <p>Failing has a real cost -- see {@link Attempt} -- and each attempt is a
	 * few block-state reads, so this is deliberately generous.
	 */
	public static final int ATTEMPTS = 32;

	/**
	 * How far up and down from the player's own feet the vertical search looks.
	 *
	 * <p><b>Raised from 4 to 8 after the first in-game session, and the number is
	 * measured rather than chosen.</b> The player was standing on a peak at Y=112;
	 * decoding the saved region file's heightmap around that point showed
	 * <b>34.4% of candidate columns in both bands had their surface more than four
	 * blocks from the player's own Y</b>, so the search never reached ground at
	 * all for a third of the map it looked at. At eight the same terrain admits
	 * <b>93%</b>. Interesting terrain is exactly where players stand, so a window
	 * that only works on flat ground is not a window.
	 */
	public static final int VERTICAL_SEARCH = 8;

	/**
	 * Where up the spawned entity's body to aim the visibility ray, as fractions
	 * of its height. Middle first, then the top.
	 *
	 * <p>Two points rather than one because the interesting case is a target
	 * partly hidden by a rise: the creeper's head clears it while its feet do not,
	 * and the player does see the creeper. Aiming only at the feet block -- the
	 * first version -- rejected all of those.
	 */
	public static final double[] AIM_FRACTIONS = {0.5, 1.0};

	private SafeSpawn() {}

	/**
	 * The outcome of a search: the position if one was found, and otherwise a
	 * breakdown of which gate did the rejecting.
	 *
	 * <p><b>The breakdown exists because its absence cost a session.</b> The first
	 * version logged only "no safe spawn position near X", which says <em>that</em>
	 * the search failed and not <em>why</em> -- so a bug report of "the effect
	 * fired once and then never again" could not be told apart from "the schedule
	 * stopped", and the real cause had to be reconstructed afterwards by decoding
	 * the world save's heightmap. This is the same lesson CLAUDE.md already
	 * records for {@code /entropyhistory}: <b>diagnostic output that does not say
	 * why is indistinguishable from no diagnostic output.</b>
	 *
	 * @param pos      the position found, or {@code null}
	 * @param unloaded columns rejected for an unloaded chunk or out-of-bounds Y
	 * @param ground   columns rejected by vanilla's ON_GROUND placement test
	 * @param band     candidates rejected for falling outside the horizontal band
	 * @param sight    candidates rejected for having no line of sight
	 */
	public record Attempt(BlockPos pos, int unloaded, int ground, int band, int sight) {

		public boolean found() {
			return pos != null;
		}

		/** One-line summary for the DEBUG log. Names the gate that did the work. */
		public String rejectionSummary() {
			return "rejected " + (unloaded + ground + band + sight)
					+ " candidate(s): unloaded=" + unloaded
					+ " no-ground=" + ground
					+ " out-of-band=" + band
					+ " no-line-of-sight=" + sight;
		}
	}

	/**
	 * A standable, unobstructed, visible position in the given horizontal distance
	 * band around the player.
	 *
	 * <p>A result with no position is a real and expected outcome -- a player
	 * walled into a 1x2 shaft has nowhere valid nearby, and the honest answer is
	 * that nothing spawns. Callers must not retry in a loop; they should log
	 * {@link Attempt#rejectionSummary()} and re-arm on a short retry so the whole
	 * interval is not lost to one bad spot.
	 *
	 * @param type        the entity type the position must be valid for
	 * @param minDistance inclusive lower bound, in blocks, on the HORIZONTAL distance
	 * @param maxDistance inclusive upper bound, in blocks, on the HORIZONTAL distance
	 */
	public static Attempt findNear(ServerLevel level, ServerPlayer player, EntityType<?> type,
								   double minDistance, double maxDistance, RandomSource random) {
		Vec3 eye = player.getEyePosition();
		double minSqr = minDistance * minDistance;
		double maxSqr = maxDistance * maxDistance;
		int baseY = player.blockPosition().getY();
		double height = Math.max(type.getHeight(), 1.0f);

		int unloaded = 0;
		int ground = 0;
		int band = 0;
		int sight = 0;

		for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
			double angle = random.nextDouble() * (Math.PI * 2.0);
			double distance = minDistance + random.nextDouble() * (maxDistance - minDistance);
			// Mth.floor, not a cast: a cast truncates toward zero and would be off by
			// one for every negative coordinate -- a bug that only shows up west or
			// north of the origin.
			int x = Mth.floor(player.getX() + Math.cos(angle) * distance);
			int z = Mth.floor(player.getZ() + Math.sin(angle) * distance);

			// The horizontal band is a property of the COLUMN, so it is checked once
			// here rather than once per vertical step. This is also what stops the
			// vertical search from disqualifying its own result -- see the class
			// javadoc; testing the 3D distance here was a real bug.
			if (!withinBand(horizontalDistanceSqr(x, z, player.getX(), player.getZ()),
					minSqr, maxSqr)) {
				band++;
				continue;
			}

			BlockPos found = null;
			for (int step = 0; step <= VERTICAL_SEARCH * 2 && found == null; step++) {
				BlockPos candidate = new BlockPos(x, baseY + verticalOffset(step), z);

				// Level.isLoaded, not hasChunkAt: it is isInValidBounds() AND a loaded
				// chunk, so it rejects a candidate the vertical search pushed below the
				// world floor or above its ceiling as well as one in an unloaded chunk.
				// Never force a chunk to load or generate for this.
				if (!level.isLoaded(candidate)) {
					unloaded++;
					continue;
				}
				if (!SpawnPlacementTypes.ON_GROUND.isSpawnPositionOk(level, candidate, type)) {
					ground++;
					continue;
				}
				if (!canSee(level, player, eye, candidate, height)) {
					sight++;
					continue;
				}
				found = candidate;
			}
			if (found != null) {
				return new Attempt(found, unloaded, ground, band, sight);
			}
		}
		return new Attempt(null, unloaded, ground, band, sight);
	}

	/**
	 * The vertical offset for a search step: 0, +1, -1, +2, -2, ...
	 *
	 * <p>Nearest first, so flat ground answers with the player's own level and a
	 * slope answers with the nearest surface rather than whichever the loop
	 * happened to reach first. Free of Minecraft types so the harness can drive the
	 * ordering directly.
	 */
	public static int verticalOffset(int step) {
		return (step % 2 == 0) ? -(step / 2) : (step + 1) / 2;
	}

	/** Squared horizontal distance from a block's centre to a point. Minecraft-free. */
	public static double horizontalDistanceSqr(int blockX, int blockZ, double px, double pz) {
		double dx = (blockX + 0.5) - px;
		double dz = (blockZ + 0.5) - pz;
		return dx * dx + dz * dz;
	}

	/**
	 * Whether a squared horizontal distance is inside the band.
	 *
	 * <p>Minecraft-free, and separated out because the band's <em>semantics</em>
	 * -- horizontal, not 3D -- is the thing that was wrong and the thing a future
	 * edit could most easily get wrong again.
	 */
	public static boolean withinBand(double horizontalSqr, double minSqr, double maxSqr) {
		return horizontalSqr >= minSqr && horizontalSqr <= maxSqr;
	}

	/**
	 * Whether the player can see an entity of this height standing at this
	 * position, testing each of {@link #AIM_FRACTIONS} up its body.
	 */
	public static boolean canSee(ServerLevel level, ServerPlayer player, Vec3 eye,
								 BlockPos pos, double height) {
		Vec3 feet = Vec3.atBottomCenterOf(pos);
		for (double fraction : AIM_FRACTIONS) {
			if (hasLineOfSight(level, player, eye, feet.add(0.0, height * fraction, 0.0))) {
				return true;
			}
		}
		return false;
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
