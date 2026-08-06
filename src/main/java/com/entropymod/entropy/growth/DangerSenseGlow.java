package com.entropymod.entropy.growth;

import com.entropymod.entropy.EntropyManager;
import com.entropymod.entropy.behavior.DangerSenseBehavior;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Danger Sense's scan: hostile mobs near a holder are given vanilla's Glowing
 * effect, refreshed while they stay in range and left to expire when they leave.
 *
 * <p>Third shape alongside attribute and mixin -- a tick service, the same one
 * {@code GreenThumbGrowth} and {@code SlashedPocketsSweep} use, and for the same
 * reason: this is a periodic proximity query, not a value vanilla already
 * computes.
 *
 * <p>See {@link DangerSenseBehavior} for the mechanism trace, the
 * visible-to-everyone limitation, and why the effect is refreshed rather than
 * explicitly removed.
 *
 * <h2>"Hostile" is {@code Enemy}, vanilla's own marker interface</h2>
 *
 * <p>Not {@code MobCategory.MONSTER}, which is a <em>spawning</em> category and
 * would answer a different question. {@code net.minecraft.world.entity.monster
 * .Enemy} is the interface vanilla itself tests for hostility -- it is what makes
 * villagers panic and what iron golems target -- so using it means the effect
 * covers exactly what the game already calls an enemy, including modded mobs that
 * implement it, with no list of ours to fall behind.
 *
 * <p>Boundary cases that follow from that choice, stated so they are not filed as
 * bugs later: endermen and zombified piglins <b>do</b> glow (both implement
 * {@code Enemy}); wolves, bees and iron golems do <b>not</b>, even when actively
 * hostile toward the player, because their hostility is a state rather than a
 * type.
 */
public final class DangerSenseGlow {

	private DangerSenseGlow() {}

	/**
	 * Called every server tick. Returns immediately except on scan ticks, and
	 * again if nobody holds the effect.
	 */
	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % DangerSenseBehavior.SCAN_INTERVAL_TICKS != 0) {
			return;
		}
		if (!EntropyManager.get(server).acquired().contains(DangerSenseBehavior.ID)) {
			return;
		}

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			// A spectator should not light up the world -- same guard the other tick
			// services need once a run can end with a permanently spectating player.
			if (player.isSpectator()) {
				continue;
			}
			glowNear(player);
		}
	}

	private static void glowNear(ServerPlayer player) {
		ServerLevel level = player.level();
		AABB box = player.getBoundingBox().inflate(DangerSenseBehavior.RADIUS);

		// getEntitiesOfClass is served by the entity section storage, so this is
		// O(entities in the box), not O(volume). See the behavior class -- the
		// distinction is what makes a 32-block radius affordable at all.
		List<LivingEntity> nearby = level.getEntitiesOfClass(LivingEntity.class, box,
				entity -> entity instanceof Enemy && entity.isAlive());

		for (LivingEntity mob : nearby) {
			if (!DangerSenseBehavior.inRange(mob.distanceToSqr(player))) {
				// The AABB is a box; the effect is a sphere. Without this the corners
				// would reach 32*sqrt(3) = 55.4 blocks.
				continue;
			}
			// ambient/visible/showIcon all false: this is a sensor reading, not a
			// potion, so it should not spray particles off every skeleton in a cave
			// or claim an inventory icon slot on the mob.
			mob.addEffect(new MobEffectInstance(MobEffects.GLOWING,
					DangerSenseBehavior.GLOW_DURATION_TICKS,
					DangerSenseBehavior.AMPLIFIER,
					false, false, false));
		}
	}
}
