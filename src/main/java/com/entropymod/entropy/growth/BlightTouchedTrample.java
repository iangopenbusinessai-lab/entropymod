package com.entropymod.entropy.growth;

import com.entropymod.entropy.AcquiredEffects;
import com.entropymod.entropy.EntropyManager;
import com.entropymod.entropy.behavior.BlightTouchedBehavior;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.PitcherCropBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Blight Touched's mechanism: every crop the player walks through is replaced by
 * a dead bush, at any growth stage, on the spot.
 *
 * <h2>Why this replaced the growth-speed multiplier</h2>
 *
 * <p>Blight Touched used to halve {@code CropBlock.getGrowthSpeed} for crops
 * near the player, the exact inverse of what Green Thumb did before <em>it</em>
 * left that hook. That mechanic is gone, not disabled: the shared hook and its
 * mixin were deleted outright, because Blight Touched was the last thing on
 * them.
 *
 * <p>The old version was invisible. Halved growth speed is a change to a
 * probability the player never sees, on a timescale of tens of minutes, against
 * a baseline they have no reading of -- failure mode 3 from CLAUDE.md's
 * mixin-cluster list, "real, but below the perceptual threshold". Destroying a
 * crop is the opposite in every respect: instant, local, visible, and caused by
 * something the player did.
 *
 * <h2>Scope, deliberately wide</h2>
 *
 * <p><b>Any crop, anyone's.</b> There is no radius, no ownership test and no
 * "crops you planted" check -- walking across a village farm ruins the village's
 * farm. That is the intended chaos of a permanent curse, not an oversight, and
 * it is why this needs no proximity scan at all: the question is only ever "what
 * block is this player's feet in", which is one block-state read per player per
 * tick rather than Green Thumb's 4913-block sweep.
 *
 * <p>Covered blocks are the same family the retired hook reached --
 * {@link CropBlock}, {@link StemBlock} and {@link PitcherCropBlock} -- so wheat,
 * carrots, potatoes, beetroot, torchflower, pumpkin and melon stems, and the
 * pitcher crop, at every age including age 0. {@link AttachedStemBlock} is
 * included on top of those three: a stem that has already grown its fruit is
 * still a stem to anyone looking at it, and leaving fruited stems standing while
 * their unfruited neighbours die would read as a bug. The fruit itself -- the
 * pumpkin or melon block -- is a separate block and is untouched.
 *
 * <p>Not covered, for the same reason the old hook did not cover them: sugar
 * cane, cactus, bamboo, saplings, nether wart, cocoa and sweet berry bushes are
 * not crop blocks.
 *
 * <h2>Why a dead bush actually stays put</h2>
 *
 * <p>This was the one part of the design with a real chance of silently
 * reverting, and it verifies clean. {@code minecraft:dead_bush} is a
 * {@code DryVegetationBlock} (there is no {@code DeadBushBlock} class in this
 * version), and its survival check is
 * {@code below.is(BlockTags.SUPPORTS_DRY_VEGETATION)}. That tag contains
 * {@code #supports_vegetation}, which contains {@code minecraft:farmland}
 * explicitly -- and {@code BlockTags.SUPPORTS_CROPS}, the tag every crop here
 * requires beneath it, contains <em>farmland and nothing else</em>. So the
 * ground under any crop is always ground a dead bush survives on. It also
 * survives if that farmland is later trampled back to dirt, since
 * {@code #dirt} is in the tag too.
 *
 * <p>The one exception is the pitcher crop's upper half, which stands on the
 * lower half rather than on the ground -- a dead bush there would have no valid
 * support and would pop off on the next block update. {@link #blight} redirects
 * to the lower half for exactly that case.
 *
 * <p>The conversion is a plain block replacement, so the crop drops nothing.
 * Losing the seeds is the point.
 */
public final class BlightTouchedTrample {

	/**
	 * Where each player was at the end of the previous tick, so the path between
	 * then and now can be swept rather than just the single block they ended in.
	 *
	 * <p>Transient by design and never persisted: it describes where someone was
	 * one twentieth of a second ago. The dead bushes it produces are ordinary
	 * world blocks and are saved by vanilla like any other block change, so
	 * nothing about the <em>result</em> depends on this surviving anything.
	 */
	private static final Map<UUID, Trail> TRAILS = new HashMap<>();

	/** A player's last known position, with the dimension it was measured in. */
	private record Trail(ResourceKey<Level> dimension, Vec3 pos) {}

	/**
	 * Whether the run's acquired set enables this at all.
	 *
	 * <p>Split out and free of Minecraft types so the harness can assert the
	 * regression gate directly: without Blight Touched in the set, nothing here
	 * runs and no block is read, let alone changed.
	 */
	public static boolean isActive(AcquiredEffects acquired) {
		return acquired.contains(BlightTouchedBehavior.ID);
	}

	/**
	 * Called every server tick. Returns immediately when nobody holds the effect,
	 * before any player is inspected.
	 */
	public static void tick(MinecraftServer server) {
		if (!isActive(EntropyManager.get(server).acquired())) {
			if (!TRAILS.isEmpty()) {
				TRAILS.clear();
			}
			return;
		}

		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		if (TRAILS.size() > players.size()) {
			forgetDepartedPlayers(players);
		}

		for (ServerPlayer player : players) {
			ServerLevel level = player.level();
			// Entity position is the feet point, which is exactly the block a
			// player walks "through" -- crops have no collision, so this is the
			// block they are standing inside rather than the one they stand on.
			Vec3 to = player.position();
			Trail previous = TRAILS.put(player.getUUID(), new Trail(level.dimension(), to));

			// A dimension change is a discontinuity, same as a teleport: start
			// afresh from the destination rather than sweeping a line between two
			// unrelated worlds' coordinates.
			Vec3 from = previous != null && previous.dimension() == level.dimension()
					? previous.pos()
					: to;

			TramplePath.forEachCell(from.x, from.y, from.z, to.x, to.y, to.z,
					(x, y, z) -> blight(level, new BlockPos(x, y, z)));
		}
	}

	/** Drops all trail state. Called on server stop so it cannot leak into the next world. */
	public static void reset() {
		TRAILS.clear();
	}

	// ------------------------------------------------------------------

	/**
	 * Replaces one crop with a dead bush, if this position holds one.
	 *
	 * <p>Uses {@code setBlockAndUpdate} (i.e. {@code UPDATE_ALL}) rather than the
	 * {@code UPDATE_CLIENTS} Green Thumb uses. Green Thumb is imitating a natural
	 * growth tick; this is destroying a block, and neighbours need to hear about
	 * it -- most visibly the pitcher crop's upper half, which loses its support
	 * and breaks itself off the same update.
	 */
	private static void blight(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!isCrop(state)) {
			return;
		}

		BlockPos target = pos;
		if (state.getBlock() instanceof PitcherCropBlock
				&& state.getValue(PitcherCropBlock.HALF) == DoubleBlockHalf.UPPER) {
			// A dead bush cannot stand on the lower half of a pitcher crop, so
			// converting the upper half in place would place a block that pops off
			// on the next update. Convert the half that is actually on the ground;
			// the upper half then breaks itself for lack of support.
			target = pos.below();
			state = level.getBlockState(target);
			if (!(state.getBlock() instanceof PitcherCropBlock)) {
				return;
			}
		}

		// Vanilla's own block-destruction effect: the crop's break sound and its
		// texture's break particles, driven from the state being removed. One line,
		// and it matches what breaking that crop by hand looks and sounds like.
		level.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, target, Block.getId(state));
		level.setBlockAndUpdate(target, Blocks.DEAD_BUSH.defaultBlockState());
	}

	/** The crop families this effect destroys -- see the class javadoc for the scope rationale. */
	private static boolean isCrop(BlockState state) {
		Block block = state.getBlock();
		return block instanceof CropBlock
				|| block instanceof StemBlock
				|| block instanceof AttachedStemBlock
				|| block instanceof PitcherCropBlock;
	}

	/** Drops trail entries for players who have logged out, so the map cannot grow without bound. */
	private static void forgetDepartedPlayers(List<ServerPlayer> players) {
		Set<UUID> online = new HashSet<>(players.size());
		for (ServerPlayer player : players) {
			online.add(player.getUUID());
		}
		TRAILS.keySet().retainAll(online);
	}

	private BlightTouchedTrample() {}
}
