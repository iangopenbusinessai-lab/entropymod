package com.entropymod.entropy.growth;

import com.entropymod.entropy.EffectHooks;
import com.entropymod.entropy.EntropyManager;
import com.entropymod.entropy.behavior.GreenThumbBehavior;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.PitcherCropBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/**
 * Green Thumb's growth mechanism: bonus growth advances granted on a schedule of
 * this mod's own, entirely outside vanilla's random-tick system.
 *
 * <h2>Why this exists instead of the multiplier hook</h2>
 *
 * <p>Green Thumb used to scale {@code CropBlock.getGrowthSpeed}, and that
 * approach has a hard ceiling that no tuning can move. Vanilla growth is two
 * independent probabilities and the multiplier only touches one of them:
 *
 * <ul>
 *   <li><b>The rate.</b> {@code ServerLevel.tickChunk} draws
 *       {@code random_tick_speed} positions per game tick from each 16x16x16
 *       section's 4096 blocks, so one crop block expects a random tick every
 *       {@code 4096/3 = 1365.33} ticks -- <b>68.27 seconds</b>. This is fixed by
 *       chunk mechanics and a gamerule; no per-effect code can change it.</li>
 *   <li><b>The chance.</b> {@code nextInt((int)(25.0F / speed) + 1) == 0}, which
 *       saturates at "always succeeds" once speed exceeds 25.</li>
 * </ul>
 *
 * <p>So even at full saturation wheat needed {@code 7 x 68.27s = 478s}. The
 * ~90-second target was <b>unreachable through that lever at any value</b>,
 * because the binding constraint was the rate rather than the chance.
 *
 * <p><b>This is the established pattern for that class of problem:</b> when a
 * target lies beyond what vanilla's own tick or probability systems can deliver,
 * stop tuning vanilla's parameters and drive the outcome on an independent
 * schedule -- while still performing the change through vanilla's own state
 * transition, so the result is indistinguishable from a natural (very lucky)
 * growth tick.
 *
 * <h2>The schedule</h2>
 *
 * <p>Every crop type targets the same {@value #TARGET_TICKS}-tick (90 second)
 * total time from freshly planted to mature. Because the advance is granted
 * rather than rolled for, the interval is simply the budget divided by the
 * number of stage advances that crop needs -- which is why differing stage
 * counts now produce the <em>same</em> total time, where the old multiplier
 * could not.
 *
 * <table border="1">
 * <caption>Derived intervals, budget {@value #TARGET_TICKS} ticks</caption>
 * <tr><th>Crop</th><th>Stages</th><th>Interval</th><th>Total</th></tr>
 * <tr><td>Wheat / carrots / potatoes</td><td>7</td><td>255t</td><td>1785t = 89.3s</td></tr>
 * <tr><td>Beetroot</td><td>3</td><td>600t</td><td>1800t = 90.0s</td></tr>
 * <tr><td>Torchflower</td><td>2</td><td>900t</td><td>1800t = 90.0s</td></tr>
 * <tr><td>Pitcher crop</td><td>4</td><td>450t</td><td>1800t = 90.0s</td></tr>
 * <tr><td>Pumpkin / melon stem</td><td>7 + fruit</td><td>240t</td><td>1680t + ~120t = 90.0s</td></tr>
 * </table>
 *
 * <p>Stage counts come from {@code getMaxAge()} at runtime rather than a hardcoded
 * table, so a crop this build has never heard of is timed correctly too.
 *
 * <p><b>The stem is the one crop whose last step cannot be granted.</b> Reaching
 * age 7 is seven ordinary advances, but producing the first fruit is not a stage
 * change -- it is a placement with its own light, support and free-space
 * conditions, and vanilla exposes no deterministic way to trigger it (even
 * {@code performBonemeal} on a mature stem just calls {@code randomTick} and
 * re-rolls). So the fruit step is retried at the scan cadence using vanilla's own
 * {@code randomTick}, which keeps every one of those conditions. At an ordinary
 * base growth speed that succeeds in ~6 attempts, i.e. ~{@value #STEM_FRUIT_BUDGET_TICKS}
 * ticks, and the stem's stage budget is reduced by exactly that so the total still
 * lands on 90s. If nothing can host a fruit, it simply keeps trying -- which is
 * the correct behaviour, and is bounded at one cheap roll per scan.
 *
 * <h2>Cost</h2>
 *
 * <p><b>Rescan: O(players holding Green Thumb x 4913 block lookups) every
 * {@value #RESCAN_INTERVAL_TICKS} ticks</b> -- a 17x17x17 box around each player,
 * amortising to ~246 block-state reads per player per tick. For comparison,
 * vanilla's own random ticking reads roughly 1300 block states per second in a
 * single loaded chunk section.
 *
 * <p><b>Advance pass: O(tracked crops) every {@value #ADVANCE_INTERVAL_TICKS}
 * ticks</b>, where the tracked set holds only actual immature crop blocks in
 * range -- typically tens, bounded by the box. Each entry costs one long
 * comparison; only entries that are actually due pay for a block-state read and
 * a nearest-player query.
 *
 * <p>Nothing runs at all when no player holds Green Thumb: the run's acquired set
 * is checked once per pass before anything is scanned.
 */
public final class GreenThumbGrowth {

	/** Target total time from freshly planted to mature, for every covered crop. */
	public static final int TARGET_TICKS = 1800;

	/** How often the tracked set is walked for due advances. Also the advance granularity. */
	public static final int ADVANCE_INTERVAL_TICKS = 5;

	/** How often the radius is swept to find crops. */
	public static final int RESCAN_INTERVAL_TICKS = 20;

	/**
	 * Ticks set aside for the stem's fruit-placement step, which is probabilistic
	 * rather than grantable. Six attempts at the scan cadence -- see the class
	 * javadoc, and the harness, which derives the six from the same validated roll
	 * model rather than assuming it.
	 */
	public static final int STEM_FRUIT_BUDGET_TICKS = 120;

	/** What a scheduled advance does to a particular crop. */
	private enum Advance {
		/** Anything extending {@code CropBlock}: wheat, carrots, potatoes, beetroot, torchflower. */
		CROP,
		/** A stem below age 7 -- an ordinary age bump. */
		STEM_AGE,
		/** A stem at age 7 -- retry vanilla's fruit placement. */
		STEM_FRUIT,
		/** The lower half of a pitcher crop. */
		PITCHER
	}

	/** A tracked crop. {@code BlockPos} is value-equal, and the dimension keeps two worlds apart. */
	private record CropKey(ResourceKey<Level> dimension, BlockPos pos) {}

	private static final CropSchedule<CropKey> SCHEDULE = new CropSchedule<>();

	/**
	 * Called every server tick. Cheap to call: returns immediately on ticks that
	 * are neither a rescan nor an advance pass, and again if nobody holds the
	 * effect.
	 */
	public static void tick(MinecraftServer server) {
		long now = server.getTickCount();
		boolean rescanDue = now % RESCAN_INTERVAL_TICKS == 0;
		boolean advanceDue = now % ADVANCE_INTERVAL_TICKS == 0;
		if (!rescanDue && !advanceDue) {
			return;
		}

		if (!EntropyManager.get(server).acquired().contains(GreenThumbBehavior.ID)) {
			SCHEDULE.clear();
			return;
		}

		if (rescanDue) {
			rescan(server, now);
		}
		if (advanceDue) {
			advanceDueCrops(server, now);
		}
	}

	/** Drops all scheduling state. Called when the server stops so it cannot leak into the next world. */
	public static void reset() {
		SCHEDULE.clear();
	}

	// ------------------------------------------------------------------

	/**
	 * Sweeps the radius around every player and hands the result to the schedule,
	 * which adds newcomers and forgets everything no longer eligible.
	 */
	private static void rescan(MinecraftServer server, long now) {
		// Interval resolved here, while the block state is in hand, rather than
		// looked up again inside the schedule -- which is why the schedule can stay
		// Minecraft-free.
		Map<CropKey, Integer> eligible = new HashMap<>();
		double radius = GreenThumbBehavior.RADIUS;
		int reach = (int) Math.ceil(radius);

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			ServerLevel level = player.level();
			BlockPos centre = player.blockPosition();
			for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-reach, -reach, -reach),
					centre.offset(reach, reach, reach))) {
				// Spherical, to match the getNearestPlayer scoping the re-verification
				// and the Blight Touched hook both use -- a box would track corners
				// that then fail verification every time.
				if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
						> radius * radius) {
					continue;
				}
				BlockState state = level.getBlockState(pos);
				Advance advance = classify(state);
				if (advance == null) {
					continue;
				}
				eligible.put(new CropKey(level.dimension(), pos.immutable()),
						intervalTicks(state, advance));
			}
		}

		SCHEDULE.refresh(eligible.keySet(), now, eligible::get);
	}

	/**
	 * Advances every crop whose turn has come.
	 *
	 * <p><b>Eligibility is re-verified here, not trusted from the scan.</b> A crop
	 * discovered a moment ago may since have been harvested, replaced, matured, or
	 * left behind by a player who walked off -- so the block state is re-read and
	 * the nearest-player check re-run at the instant of the advance. Anything that
	 * fails is dropped rather than advanced.
	 */
	private static void advanceDueCrops(MinecraftServer server, long now) {
		for (CropKey key : SCHEDULE.due(now)) {
			ServerLevel level = server.getLevel(key.dimension());
			if (level == null) {
				SCHEDULE.drop(key);
				continue;
			}
			BlockState state = level.getBlockState(key.pos());
			Advance advance = classify(state);
			if (advance == null || !EffectHooks.hasGreenThumbNearby(level, key.pos())) {
				SCHEDULE.drop(key);
				continue;
			}
			apply(level, key.pos(), state, advance);
			SCHEDULE.reschedule(key, now, intervalTicks(state, advance));
		}
	}

	// ------------------------------------------------------------------

	/**
	 * What kind of advance this block wants, or null if it is not a covered crop or
	 * has nothing left to grow.
	 *
	 * <p>Coverage is deliberately the same three types the retired
	 * {@code getGrowthSpeed} hook reached -- {@code CropBlock}, {@code StemBlock}
	 * and {@code PitcherCropBlock} were its only three callers -- so the effect's
	 * scope did not change with its mechanism.
	 */
	private static Advance classify(BlockState state) {
		Block block = state.getBlock();

		if (block instanceof CropBlock crop) {
			// isMaxAge covers all five CropBlock crops, including torchflower, whose
			// last advance turns it into the flower rather than bumping its age.
			return crop.isMaxAge(state) ? null : Advance.CROP;
		}
		if (block instanceof StemBlock) {
			// AttachedStemBlock extends VegetationBlock, not StemBlock, so a stem that
			// has already fruited is not matched here and falls out of tracking.
			int age = state.getValue(StemBlock.AGE);
			return age < StemBlock.MAX_AGE ? Advance.STEM_AGE : Advance.STEM_FRUIT;
		}
		if (block instanceof PitcherCropBlock pitcher) {
			// Exactly vanilla's own "is there anything to do here" test: true only for
			// the LOWER half and only below max age. Using it is what stops a
			// two-block-tall pitcher crop being advanced twice per interval, once from
			// each half.
			return pitcher.isRandomlyTicking(state) ? Advance.PITCHER : null;
		}
		return null;
	}

	/**
	 * Performs one stage advance through vanilla's own state transition.
	 *
	 * <p>Each branch is the code vanilla itself runs on a successful growth roll,
	 * not a re-implementation of it: {@code setBlock(pos, getStateForAge(age + 1),
	 * UPDATE_CLIENTS)} is literally {@code CropBlock.randomTick}'s success branch,
	 * and {@code getStateForAge} is virtual, so beetroot's shorter age property and
	 * torchflower's conversion into the flower are handled by vanilla rather than
	 * by this method knowing about them. Same {@code UPDATE_CLIENTS} flag vanilla
	 * uses, so the block update and the client-side visual are identical to a
	 * natural growth tick.
	 */
	private static void apply(ServerLevel level, BlockPos pos, BlockState state, Advance advance) {
		switch (advance) {
			case CROP -> {
				CropBlock crop = (CropBlock) state.getBlock();
				level.setBlock(pos, crop.getStateForAge(crop.getAge(state) + 1), Block.UPDATE_CLIENTS);
			}
			case STEM_AGE -> level.setBlock(pos,
					state.setValue(StemBlock.AGE, state.getValue(StemBlock.AGE) + 1),
					Block.UPDATE_CLIENTS);
			// Not a stage change -- vanilla's own fruit placement, conditions and all.
			case STEM_FRUIT -> state.randomTick(level, pos, level.getRandom());
			// PitcherCropBlock.performBonemeal resolves the lower half itself and then
			// grows by exactly 1, which is the single-stage advance this crop needs.
			case PITCHER -> ((PitcherCropBlock) state.getBlock())
					.performBonemeal(level, level.getRandom(), pos, state);
		}
	}

	// ------------------------------------------------------------------
	// Interval derivation
	// ------------------------------------------------------------------

	/**
	 * Ticks between advances so that {@code stages} of them fill {@code budgetTicks}.
	 *
	 * <p>Floored onto the {@value #ADVANCE_INTERVAL_TICKS}-tick advance grid, since
	 * an interval the pass cannot actually observe would silently round up to the
	 * next grid step. Flooring biases very slightly fast, which is the right
	 * direction to miss in.
	 *
	 * <p>Public and free of Minecraft types so the harness can check the derived
	 * intervals against the 90-second target directly.
	 */
	public static int intervalForStages(int stages, int budgetTicks) {
		if (stages <= 0) {
			return budgetTicks;
		}
		int raw = budgetTicks / stages;
		int snapped = (raw / ADVANCE_INTERVAL_TICKS) * ADVANCE_INTERVAL_TICKS;
		return Math.max(ADVANCE_INTERVAL_TICKS, snapped);
	}

	/** How long this particular crop waits between advances. */
	private static int intervalTicks(BlockState state, Advance advance) {
		return switch (advance) {
			case CROP -> intervalForStages(((CropBlock) state.getBlock()).getMaxAge(), TARGET_TICKS);
			// The stem's stage budget is short by the fruit allowance so the total,
			// fruit included, still lands on TARGET_TICKS.
			case STEM_AGE -> intervalForStages(StemBlock.MAX_AGE, TARGET_TICKS - STEM_FRUIT_BUDGET_TICKS);
			// Probabilistic, so retried as often as the scan runs rather than on the
			// slow growth cadence.
			case STEM_FRUIT -> RESCAN_INTERVAL_TICKS;
			case PITCHER -> intervalForStages(PitcherCropBlock.MAX_AGE, TARGET_TICKS);
		};
	}

	private GreenThumbGrowth() {}
}
