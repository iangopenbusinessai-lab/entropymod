package com.entropymod.entropy.companion;

import com.entropymod.entropy.EntropyManager;
import com.entropymod.entropy.behavior.EmotionalSupportLlamaBehavior;
import com.entropymod.entropy.behavior.TheAudienceBehavior;
import com.entropymod.entropy.behavior.TheEntourageBehavior;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.List;
import java.util.UUID;

/**
 * Follow-and-defend for the companions vanilla will not do it for.
 *
 * <p><b>Loyal Pack is deliberately absent from this class.</b> Wolves get
 * following and defending entirely free -- see {@code LoyalPackBehavior} -- and
 * running a second, mod-side follow loop alongside vanilla's own
 * {@code FollowOwnerGoal} would mean two systems writing the same navigation every
 * tick. The rule is the one Green Thumb already established: drive the outcome
 * yourself only where vanilla's own machinery cannot reach it.
 *
 * <h2>Why a tick service rather than custom Goals</h2>
 *
 * <p>The investigation behind this cluster found that vanilla's three owner goals
 * -- {@code FollowOwnerGoal}, {@code OwnerHurtByTargetGoal},
 * {@code OwnerHurtTargetGoal} -- are all typed to <b>{@code TamableAnimal}</b>,
 * not to the broader {@code OwnableEntity} interface. So they cannot be
 * constructed for a villager, a llama or an iron golem at all, and reusing them
 * was never an option.
 *
 * <p>Adding <em>custom</em> goals to a live entity is also worse than it looks:
 * {@code Mob.goalSelector} and {@code targetSelector} are {@code protected final},
 * so it needs an accessor mixin -- and for villagers it would not work anyway.
 * <b>{@code Villager}'s bytecode contains zero references to {@code goalSelector}:
 * it is entirely brain-driven</b>, so a goal added to its selector would be
 * overridden by {@code MoveToTargetSink} on the same tick.
 *
 * <p>A tick service that re-issues navigation sidesteps both problems, works
 * uniformly across brain-driven and goal-driven mobs, and is this project's
 * established third shape (Green Thumb, Blight Touched, Danger Sense, the two
 * spawn effects).
 *
 * <h2>Defending, without a single new Goal class</h2>
 *
 * <p>{@code OwnerHurtByTargetGoal}'s whole content is "read the owner's
 * {@code getLastHurtByMob()} and target it". That accessor is public and vanilla
 * maintains it, so the same behaviour is one line here -- and because the escort
 * is an iron golem, which already has a {@code MeleeAttackGoal},
 * {@code setTarget} is immediately actionable. <b>Nothing about the fighting is
 * reimplemented; only the choice of target is.</b>
 *
 * <h2>Cost</h2>
 *
 * <p>O(players holding a companion effect x recorded companions) every
 * {@value #TICK_INTERVAL} ticks, and each companion costs one UUID lookup plus at
 * most one {@code moveTo}. Nothing runs when no companion effect is held -- the
 * acquired set is checked first. Entities in unloaded chunks resolve to null and
 * are skipped, which costs nothing and is correct: an unloaded companion is not
 * being simulated anyway.
 */
public final class CompanionService {

	/**
	 * How often navigation is re-issued. Four times a second.
	 *
	 * <p>Not every tick: {@code PathNavigation.moveTo} recomputes a path, which is
	 * the expensive part, and re-pathing 26 companions 20 times a second would be
	 * real work for no visible gain. Vanilla's own {@code FollowOwnerGoal} runs on
	 * a comparable reduced-tick cadence.
	 */
	public static final int TICK_INTERVAL = 5;

	private CompanionService() {}

	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % TICK_INTERVAL != 0) {
			return;
		}
		EntropyManager manager = EntropyManager.get(server);
		boolean entourage = manager.acquired().contains(TheEntourageBehavior.ID);
		boolean audience = manager.acquired().contains(TheAudienceBehavior.ID);
		boolean llama = manager.acquired().contains(EmotionalSupportLlamaBehavior.ID);
		if (!entourage && !audience && !llama) {
			return;
		}

		CompanionRoster roster = manager.companions();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.isSpectator()) {
				continue;
			}
			if (entourage) {
				escort(player, roster, TheEntourageBehavior.ID,
						TheEntourageBehavior.FOLLOW_START, TheEntourageBehavior.FOLLOW_SPEED, true);
			}
			if (audience) {
				escort(player, roster, TheAudienceBehavior.ID,
						TheAudienceBehavior.FOLLOW_START, TheAudienceBehavior.FOLLOW_SPEED, false);
			}
			if (llama) {
				escort(player, roster, EmotionalSupportLlamaBehavior.ID,
						EmotionalSupportLlamaBehavior.FOLLOW_START,
						EmotionalSupportLlamaBehavior.FOLLOW_SPEED, false);
			}
		}
	}

	// ------------------------------------------------------------------

	private static void escort(ServerPlayer player, CompanionRoster roster, String effectId,
							   double followStart, double speed, boolean defends) {
		List<String> ids = roster.uuidsFor(player.getUUID().toString(), effectId);
		if (ids.isEmpty()) {
			return;
		}
		ServerLevel level = player.level();
		// getLastHurtByMob is exactly what OwnerHurtByTargetGoal reads. Resolved once
		// per player rather than once per companion.
		LivingEntity threat = defends ? player.getLastHurtByMob() : null;
		if (threat != null && (!threat.isAlive() || threat == player)) {
			threat = null;
		}

		for (String id : ids) {
			Entity entity = level.getEntity(UUID.fromString(id));
			// Null means dead, despawned, or simply in an unloaded chunk or another
			// dimension. All three are skips, not errors -- and note this is a read,
			// so a companion that is merely unloaded is untouched rather than forgotten.
			if (!(entity instanceof Mob companion) || !companion.isAlive()) {
				continue;
			}

			if (threat != null && companion.getTarget() != threat) {
				// The whole of OwnerHurtByTargetGoal's behaviour. The companion's own
				// MeleeAttackGoal does the actual fighting.
				companion.setTarget(threat);
			}

			// Do not fight the companion's own combat pathing while it has a target.
			if (companion.getTarget() != null) {
				continue;
			}
			double distanceSqr = companion.distanceToSqr(player);
			if (distanceSqr > followStart * followStart) {
				companion.getNavigation().moveTo(player, speed);
			}
		}
	}
}
