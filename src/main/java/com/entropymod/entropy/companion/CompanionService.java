package com.entropymod.entropy.companion;

import com.entropymod.EntropyMod;
import com.entropymod.entropy.EntropyManager;
import com.entropymod.entropy.behavior.EmotionalSupportLlamaBehavior;
import com.entropymod.entropy.behavior.LoyalPackBehavior;
import com.entropymod.entropy.behavior.TheAudienceBehavior;
import com.entropymod.entropy.behavior.TheEntourageBehavior;
import com.entropymod.entropy.spawn.SafeSpawn;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Movement for the companions vanilla will not do it for, plus the catch-up
 * teleport shared by all the following ones.
 *
 * <p>Loyal Pack remains absent from the <em>navigation</em> half -- wolves get
 * following and defending free from {@code FollowOwnerGoal} and the two owner
 * target goals, and a second follow loop would mean two systems writing one
 * navigation every tick. It <b>is</b> included in the catch-up half, because that
 * is a teleport rather than a path and cannot conflict with vanilla's steering.
 *
 * <h2>The crowding bug, root cause CONFIRMED rather than assumed</h2>
 *
 * <p>The first version ended with escorts standing on the player. Two independent
 * causes, both read out of bytecode rather than guessed:
 *
 * <ol>
 *   <li><b>{@code moveTo(Entity, speed)} does not respect any stop distance.</b> It
 *       is {@code createPath(entity, 1)} -> {@code createPath(Set.of(entity
 *       .blockPosition()), 1, ...)}, so the path targets the player's own block with
 *       an accuracy of <b>1</b>. {@code FOLLOW_START} only gated <em>when a new path
 *       was issued</em>; every path issued always aimed to within a block of the
 *       player.</li>
 *   <li><b>Nothing ever called {@code stop()}.</b> So a path issued at 6.1 blocks
 *       ran to completion at ~1 block, and the escort arrived on top of the player
 *       exactly as instructed. There was no stop-distance behaviour anywhere in the
 *       loop.</li>
 * </ol>
 *
 * <p>Both are fixed the same way: navigate to a computed point on a standoff
 * <em>ring</em> rather than to the player, and {@code stop()} on arrival in band.
 *
 * <h2>Cost</h2>
 *
 * <p>O(players holding a companion effect x recorded companions) every
 * {@value #TICK_INTERVAL} ticks, with a stall sample every
 * {@value CompanionMotion#STALL_SAMPLE_INTERVAL}. Each companion costs one UUID
 * lookup, one squared-distance compare, and a {@code moveTo} only when it is
 * actually out of band -- a settled escort issues no paths at all, which is a
 * strict improvement on the previous version's re-path every 5 ticks. Line of
 * sight is clipped only for Audience villagers, at most 10 per player per
 * evaluation. Nothing runs when no companion effect is held.
 */
public final class CompanionService {

	/** How often movement is re-evaluated. Four times a second. */
	public static final int TICK_INTERVAL = 5;

	/** Distance history for the catch-up teleport, keyed by entity UUID string. */
	private static final CompanionMotion.StallTracker STALLS = new CompanionMotion.StallTracker();

	/** Which audience villagers were approaching last evaluation -- for hysteresis. */
	private static final Set<String> APPROACHING = new HashSet<>();

	private CompanionService() {}

	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % TICK_INTERVAL != 0) {
			return;
		}
		EntropyManager manager = EntropyManager.get(server);
		boolean pack = manager.acquired().contains(LoyalPackBehavior.ID);
		boolean entourage = manager.acquired().contains(TheEntourageBehavior.ID);
		boolean audience = manager.acquired().contains(TheAudienceBehavior.ID);
		boolean llama = manager.acquired().contains(EmotionalSupportLlamaBehavior.ID);
		if (!pack && !entourage && !audience && !llama) {
			return;
		}

		boolean stallSample = server.getTickCount() % CompanionMotion.STALL_SAMPLE_INTERVAL == 0;
		CompanionRoster roster = manager.companions();

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.isSpectator()) {
				continue;
			}
			// Catch-up applies to the three FOLLOWING effects. The Audience is
			// excluded by design: it is supposed to lose track of the player and
			// wander, so teleporting it to heel would delete the effect.
			if (pack) {
				catchUp(player, roster, LoyalPackBehavior.ID, stallSample);
			}
			if (entourage) {
				catchUp(player, roster, TheEntourageBehavior.ID, stallSample);
				holdBand(player, roster, TheEntourageBehavior.ID,
						TheEntourageBehavior.MIN_HOLD, TheEntourageBehavior.MAX_HOLD,
						TheEntourageBehavior.FOLLOW_SPEED, true);
			}
			if (llama) {
				catchUp(player, roster, EmotionalSupportLlamaBehavior.ID, stallSample);
				holdBand(player, roster, EmotionalSupportLlamaBehavior.ID,
						EmotionalSupportLlamaBehavior.MIN_HOLD,
						EmotionalSupportLlamaBehavior.MAX_HOLD,
						EmotionalSupportLlamaBehavior.FOLLOW_SPEED, false);
			}
			if (audience) {
				audience(player, roster);
			}
		}
	}

	/** Drops all transient movement state. Called on server stop. */
	public static void reset() {
		STALLS.reset();
		APPROACHING.clear();
	}

	// ==================================================================

	/**
	 * The shared catch-up teleport: far away AND not closing the gap for three
	 * consecutive seconds means put it down beside the player.
	 */
	private static void catchUp(ServerPlayer player, CompanionRoster roster, String effectId,
								boolean stallSample) {
		if (!stallSample) {
			return;
		}
		ServerLevel level = player.level();
		for (String id : roster.uuidsFor(player.getUUID().toString(), effectId)) {
			Entity entity = level.getEntity(UUID.fromString(id));
			if (!(entity instanceof Mob companion) || !companion.isAlive()) {
				// Unloaded or gone. Drop the history so a companion that reappears is
				// measured afresh rather than against a distance from minutes ago.
				STALLS.forget(id);
				continue;
			}
			if (!STALLS.sample(id, Math.sqrt(companion.distanceToSqr(player)))) {
				continue;
			}

			SafeSpawn.Attempt spot = SafeSpawn.findNear(level, player, companion.getType(),
					CompanionMotion.TELEPORT_MIN_DISTANCE, CompanionMotion.TELEPORT_MAX_DISTANCE,
					level.getRandom(), SafeSpawn.DistanceMode.HORIZONTAL);
			if (!spot.found()) {
				// Same honest outcome SafeSpawn always has: nowhere valid means nothing
				// happens, and the next sample will try again.
				EntropyMod.LOGGER.debug("Companion catch-up: no safe spot near {} for {}",
						player.getName().getString(), effectId);
				continue;
			}
			Vec3 centre = Vec3.atBottomCenterOf(spot.pos());
			companion.getNavigation().stop();
			companion.snapTo(centre.x, centre.y, centre.z, companion.getYRot(), companion.getXRot());
			EntropyMod.LOGGER.debug("Companion catch-up: teleported a {} to {} for {}",
					companion.getType().toShortString(), spot.pos(), effectId);
		}
	}

	/**
	 * Distance-holding: keep companions inside [minHold, maxHold] of the player.
	 *
	 * <p>Both the too-far and too-close cases navigate to the SAME point -- the
	 * middle of the band along the line from the player through the companion -- so
	 * approach and retreat cannot drift apart, and arriving lands mid-band rather
	 * than on a boundary that could flip on the next sample.
	 */
	private static void holdBand(ServerPlayer player, CompanionRoster roster, String effectId,
								 double minHold, double maxHold, double speed, boolean defends) {
		List<String> ids = roster.uuidsFor(player.getUUID().toString(), effectId);
		if (ids.isEmpty()) {
			return;
		}
		ServerLevel level = player.level();
		LivingEntity threat = defends ? player.getLastHurtByMob() : null;
		if (threat != null && (!threat.isAlive() || threat == player)) {
			threat = null;
		}

		double ideal = CompanionMotion.idealHoldDistance(minHold, maxHold);
		for (String id : ids) {
			Entity entity = level.getEntity(UUID.fromString(id));
			if (!(entity instanceof Mob companion) || !companion.isAlive()) {
				continue;
			}

			if (threat != null && companion.getTarget() != threat) {
				companion.setTarget(threat);
			}
			// Never fight the companion's own combat pathing.
			if (companion.getTarget() != null) {
				continue;
			}

			double distance = Math.sqrt(companion.distanceToSqr(player));
			CompanionMotion.BandAction action =
					CompanionMotion.bandAction(distance, minHold, maxHold);
			if (action == CompanionMotion.BandAction.HOLD) {
				// The line the first version was missing entirely. Without it the last
				// path issued -- which aimed a block from the player -- kept running.
				if (!companion.getNavigation().isDone()) {
					companion.getNavigation().stop();
				}
				continue;
			}
			moveToRing(companion, player, ideal, speed);
		}
	}

	/** The Audience's state machine. See {@link CompanionMotion#audienceState}. */
	private static void audience(ServerPlayer player, CompanionRoster roster) {
		List<String> ids = roster.uuidsFor(player.getUUID().toString(), TheAudienceBehavior.ID);
		if (ids.isEmpty()) {
			return;
		}
		ServerLevel level = player.level();
		Vec3 eye = player.getEyePosition();

		for (String id : ids) {
			Entity entity = level.getEntity(UUID.fromString(id));
			if (!(entity instanceof Mob villager) || !villager.isAlive()) {
				APPROACHING.remove(id);
				continue;
			}

			double distance = Math.sqrt(villager.distanceToSqr(player));
			// SafeSpawn's own clip, reused rather than reimplemented -- the same
			// COLLIDER/NONE ray LivingEntity.hasLineOfSight uses. Aimed at the
			// villager's eye height so a head visible over a rise counts as seen,
			// which is the same reason SafeSpawn aims up the entity's body.
			boolean seen = SafeSpawn.hasLineOfSight(level, player, eye, villager.getEyePosition());

			CompanionMotion.AudienceState state = CompanionMotion.audienceState(
					distance, seen, APPROACHING.contains(id),
					TheAudienceBehavior.MIN_DISTANCE, TheAudienceBehavior.RESUME_DISTANCE,
					TheAudienceBehavior.LOST_TRACK_DISTANCE);

			if (state == CompanionMotion.AudienceState.APPROACHING) {
				APPROACHING.add(id);
			} else {
				APPROACHING.remove(id);
			}

			switch (state) {
				case FROZEN, IDLE -> {
					// FROZEN is an active stop, not merely "issue nothing": the villager's
					// brain would otherwise keep walking wherever it was already going,
					// and the effect is that being looked at stops them dead.
					if (!villager.getNavigation().isDone()) {
						villager.getNavigation().stop();
					}
				}
				case APPROACHING, BACK_OFF ->
						moveToRing(villager, player, TheAudienceBehavior.MIN_DISTANCE,
								TheAudienceBehavior.FOLLOW_SPEED);
			}
		}
	}

	/**
	 * Navigates a companion to the point at {@code radius} from the player, along
	 * the line from the player through the companion.
	 *
	 * <p>This is the fix for the crowding bug in one method: it never targets the
	 * player, so no path can ever end on top of them, and the same call serves
	 * approach and retreat because the target depends only on which side of the
	 * ring the companion is on.
	 */
	private static void moveToRing(Mob companion, ServerPlayer player, double radius, double speed) {
		Vec3 from = player.position();
		Vec3 offset = companion.position().subtract(from);
		double horizontal = Math.sqrt(offset.x * offset.x + offset.z * offset.z);
		// Degenerate case: standing exactly on the player. Any direction will do, and
		// normalising a zero vector would give NaN.
		double dirX = horizontal < 1.0e-4 ? 1.0 : offset.x / horizontal;
		double dirZ = horizontal < 1.0e-4 ? 0.0 : offset.z / horizontal;

		double targetX = from.x + dirX * radius;
		double targetZ = from.z + dirZ * radius;
		companion.getNavigation().moveTo(targetX, from.y, targetZ, speed);
	}
}
