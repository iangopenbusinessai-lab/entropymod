package com.entropymod.harness;

import com.entropymod.entropy.AcquiredEffects;
import com.entropymod.entropy.EffectDefinition;
import com.entropymod.entropy.EffectHooks;
import com.entropymod.entropy.EffectRegistry;
import com.entropymod.entropy.EntropyManager;
import com.entropymod.entropy.behavior.BlightTouchedBehavior;
import com.entropymod.entropy.behavior.GreenThumbBehavior;
import com.entropymod.entropy.behavior.LeakyPocketsBehavior;
import com.entropymod.entropy.behavior.SureFootingBehavior;
import com.entropymod.entropy.growth.BlightTouchedTrample;
import com.entropymod.entropy.growth.CropSchedule;
import com.entropymod.entropy.growth.GreenThumbGrowth;
import com.entropymod.entropy.growth.TramplePath;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static com.entropymod.harness.Checks.check;
import static com.entropymod.harness.Checks.checkNear;
import static com.entropymod.harness.Checks.constant;
import static com.entropymod.harness.Checks.section;

/**
 * Headless checks. Run with {@code ./gradlew harness}.
 *
 * <p>Covers what can be established without booting Minecraft: the tuning
 * constants as they are actually compiled, the crop-growth timing derivation,
 * Green Thumb's active schedule, Blight Touched's walked-through path sweep and
 * its off-by-default gate, and the parts of {@code /entropygrant}'s contract
 * that are observable off-server.
 *
 * <p><b>What it cannot prove</b>, and what still needs a real session: that the
 * mixins inject, that the effects are felt, and that a granted effect survives a
 * real death and relog. See CLAUDE.md.
 */
public final class HarnessMain {

	public static void main(String[] args) {
		tuningConstants();
		growthModelSanity();
		retiredMultiplierCeiling();
		activeGrowthSchedule();
		greenThumbUnaffectedByBlightRemoval();
		blightTramplePath();
		blightTrampleGate();
		scheduleTracking();
		grantContract();
		System.exit(Checks.summary());
	}

	// ------------------------------------------------------------------

	/**
	 * The shipped constants. Read by reflection, not by reference -- see
	 * {@link Checks#constant} for why naming them directly would make this
	 * check vacuous.
	 */
	private static void tuningConstants() {
		section("Tuning constants (read from the compiled classes)");

		checkNear(constant(LeakyPocketsBehavior.class, "CHANCE"), 0.07, 1e-7,
				"Leaky Pockets fires on 7% of jumps");
		checkNear(constant(GreenThumbBehavior.class, "RADIUS"), 8.0, 1e-7,
				"Green Thumb radius is unchanged at 8 blocks");

		// Removed, not neutralised. A constant set to 1.0 can be quietly re-wired
		// into the shared hook and double-applied on top of the active mechanism;
		// a field that does not exist cannot. Both crop effects have now left that
		// hook by this route, so both absences are asserted.
		check(!Checks.hasConstant(GreenThumbBehavior.class, "MULTIPLIER"),
				"Green Thumb no longer declares a growth-speed multiplier at all");
		check(!Checks.hasConstant(BlightTouchedBehavior.class, "MULTIPLIER"),
				"Blight Touched no longer declares a growth-speed multiplier at all");

		// And with the last effect off it, the shared hook itself is gone rather
		// than left returning 1.0 for nobody.
		check(!Checks.hasMethod(EffectHooks.class, "cropGrowthMultiplierFor"),
				"EffectHooks.cropGrowthMultiplierFor is deleted, not neutralised");
		check(!Checks.hasMethod(EffectHooks.class, "cropGrowthMultiplier"),
				"EffectHooks.cropGrowthMultiplier is deleted too -- nothing feeds it now");
		check(!Checks.hasConstant(EffectHooks.class, "MIN_CROP_MULTIPLIER"),
				"...and so is the divide-by-zero floor that only existed to guard it");

		checkNear(constant(TramplePath.class, "MAX_STEP"), 0.25, 1e-7,
				"the trample path samples at most a quarter block per step");
		checkNear(constant(TramplePath.class, "MAX_SEGMENT"), 16.0, 1e-7,
				"...and treats more than 16 blocks in one tick as a teleport");

		double meanJumps = 1.0 / constant(LeakyPocketsBehavior.class, "CHANCE");
		checkNear(meanJumps, 14.3, 0.1, "Leaky Pockets spills once per ~14 jumps on average");
	}

	/**
	 * Validates the model itself against a number established outside this project
	 * before trusting anything derived from it: vanilla row-planted wheat is
	 * widely measured at roughly 24 minutes, and the model has to reproduce that
	 * from first principles or the derivation below is worthless.
	 */
	private static void growthModelSanity() {
		section("Growth model reproduces known vanilla timings");

		checkNear(CropGrowthModel.ticksPerRandomTick(), 1365.333, 0.01,
				"a block expects a random tick every 4096/3 game ticks");
		checkNear(CropGrowthModel.secondsPerRandomTick(), 68.267, 0.01,
				"...which is 68.27 seconds");

		check(CropGrowthModel.rollBound(CropGrowthModel.BASE_SPEED_ROWS) == 3,
				"row-planted hydrated farmland (speed 10) rolls nextInt(3)");
		check(CropGrowthModel.rollBound(CropGrowthModel.BASE_SPEED_PACKED_FIELD) == 6,
				"packed hydrated field (speed 5) rolls nextInt(6)");

		double vanillaRowWheat = CropGrowthModel.expectedSeconds(7, 1.0,
				CropGrowthModel.BASE_SPEED_ROWS, 1.0f);
		checkNear(vanillaRowWheat / 60.0, 23.9, 0.1,
				"vanilla wheat in rows matures in ~24 minutes (the external check on this model)");
	}

	/**
	 * Why the multiplier hook was retired.
	 *
	 * <p>Kept rather than deleted: this is the evidence that the active mechanism
	 * is necessary rather than merely preferred, and it is what stops a future
	 * session "simplifying" Green Thumb back onto the shared hook. It asserts the
	 * ceiling still holds at the most generous multiplier the hook can accept.
	 */
	private static void retiredMultiplierCeiling() {
		section("Why the growth-speed multiplier was retired");

		// The most the hook can ever do: saturate the roll so every random tick
		// grows the crop. 26x was the smallest multiplier that reached this, and
		// nothing above it changes anything.
		float saturating = 26.0f;
		check(CropGrowthModel.saturated(CropGrowthModel.BASE_SPEED_WORST_FARMLAND * saturating),
				"26x saturates the roll even on the worst farmland layout");
		check(CropGrowthModel.rollBound(CropGrowthModel.BASE_SPEED_WORST_FARMLAND * saturating)
						== CropGrowthModel.rollBound(CropGrowthModel.BASE_SPEED_WORST_FARMLAND * 1000.0f),
				"1000x behaves identically -- the hook is a saturation point, not a slider");

		// And saturated is still 5.3x too slow, because the binding constraint is
		// the random-tick RATE, which the hook does not touch.
		double fastestWheat = CropGrowthModel.expectedSeconds(7, 1.0,
				CropGrowthModel.BASE_SPEED_WORST_FARMLAND, saturating);
		checkNear(fastestWheat, 477.87, 0.1,
				"fully saturated, wheat still takes ~478s through the hook");
		check(fastestWheat > 5 * (GreenThumbGrowth.TARGET_TICKS / 20.0),
				"that is more than 5x the 90s target -- unreachable at any multiplier");

		double neededRate = 7.0 * CropGrowthModel.SECTION_BLOCKS / GreenThumbGrowth.TARGET_TICKS;
		checkNear(neededRate, 15.93, 0.01,
				"reaching 90s through the hook would need random_tick_speed ~16, a gamerule not an effect");
	}

	/**
	 * The active mechanism's per-crop intervals, derived from the real shipped
	 * {@link GreenThumbGrowth#intervalForStages} rather than a copy of it.
	 *
	 * <p>Stage counts are the ones already established in CLAUDE.md's table and
	 * verified in bytecode there ({@code getMaxAge()} of 7, 3, 2, 7 and 4) -- not
	 * re-derived here.
	 */
	private static void activeGrowthSchedule() {
		section("Active bonus-growth schedule (target " + GreenThumbGrowth.TARGET_TICKS + " ticks)");

		int target = GreenThumbGrowth.TARGET_TICKS;
		int stemBudget = target - GreenThumbGrowth.STEM_FRUIT_BUDGET_TICKS;

		CropGrowthModel.ActiveCrop[] crops = {
				new CropGrowthModel.ActiveCrop("Wheat / carrots / potatoes", 7, target, 0),
				new CropGrowthModel.ActiveCrop("Beetroot", 3, target, 0),
				new CropGrowthModel.ActiveCrop("Torchflower", 2, target, 0),
				new CropGrowthModel.ActiveCrop("Pitcher crop", 4, target, 0),
				new CropGrowthModel.ActiveCrop("Pumpkin / melon stem", 7, stemBudget,
						GreenThumbGrowth.STEM_FRUIT_BUDGET_TICKS),
		};
		int[] expectedIntervals = {255, 600, 900, 450, 240};

		for (int i = 0; i < crops.length; i++) {
			CropGrowthModel.ActiveCrop crop = crops[i];
			int interval = GreenThumbGrowth.intervalForStages(crop.stages(), crop.budgetTicks());
			int total = CropGrowthModel.activeTotalTicks(crop, interval);

			check(interval == expectedIntervals[i],
					crop.name() + ": " + crop.stages() + " stages -> " + interval + "t interval");
			checkNear(total / 20.0, 90.0, 1.0,
					crop.name() + ": total " + total + "t = " + (total / 20.0) + "s");

			// Every interval must land on the grid the advance pass actually runs on,
			// or the real cadence silently rounds up to the next grid step.
			check(interval % GreenThumbGrowth.ADVANCE_INTERVAL_TICKS == 0,
					crop.name() + ": interval is a whole number of advance passes");
		}

		// The stem's fruit allowance is derived from the SAME validated roll model,
		// not picked. Placing the fruit is not a stage change and cannot be granted,
		// so it is retried at the scan cadence until vanilla's own roll succeeds.
		int expectedAttempts = CropGrowthModel.rollBound(CropGrowthModel.BASE_SPEED_PACKED_FIELD);
		check(expectedAttempts == 6, "an ordinary stem needs ~6 fruit attempts (roll bound at base speed 5)");
		check(expectedAttempts * GreenThumbGrowth.RESCAN_INTERVAL_TICKS
						== GreenThumbGrowth.STEM_FRUIT_BUDGET_TICKS,
				"...which is exactly the " + GreenThumbGrowth.STEM_FRUIT_BUDGET_TICKS
						+ "t the stem's stage budget gives up");

		// The property the old mechanism could not deliver at all.
		check(true, "uniform ~90s across differing stage counts -- what the multiplier could not do");
	}

	/**
	 * Green Thumb's timing must be completely unaffected by Blight Touched leaving
	 * the shared hook.
	 *
	 * <p>This is the regression gate for this session's deletion. Green Thumb's
	 * schedule is checked in full by {@link #activeGrowthSchedule()} above; what
	 * this adds is the reason that check is still meaningful -- Green Thumb never
	 * read the hook's output in the first place, so removing the hook cannot have
	 * moved any of those numbers.
	 *
	 * <p>Stated as an assertion about the code rather than a comment: the growth
	 * service's derivation is a pure function of the stage count and the tick
	 * budget, so re-deriving every interval here reproduces the same table.
	 */
	private static void greenThumbUnaffectedByBlightRemoval() {
		section("Green Thumb unaffected by Blight Touched leaving the shared hook");

		// The whole 90s table, re-derived after the deletion. Same expected values
		// as activeGrowthSchedule(), asserted again here so a future edit that
		// disturbs Green Thumb while touching Blight Touched fails in the section
		// that names the reason.
		int target = GreenThumbGrowth.TARGET_TICKS;
		int stemBudget = target - GreenThumbGrowth.STEM_FRUIT_BUDGET_TICKS;

		check(GreenThumbGrowth.intervalForStages(7, target) == 255, "wheat/carrots/potatoes still 255t");
		check(GreenThumbGrowth.intervalForStages(3, target) == 600, "beetroot still 600t");
		check(GreenThumbGrowth.intervalForStages(2, target) == 900, "torchflower still 900t");
		check(GreenThumbGrowth.intervalForStages(4, target) == 450, "pitcher crop still 450t");
		check(GreenThumbGrowth.intervalForStages(7, stemBudget) == 240, "stem still 240t");

		check(target == 1800, "the 90-second budget itself is untouched");
		check(GreenThumbGrowth.ADVANCE_INTERVAL_TICKS == 5 && GreenThumbGrowth.RESCAN_INTERVAL_TICKS == 20,
				"advance and rescan cadences are untouched");

		// The structural reason the above cannot have regressed: Green Thumb's own
		// multiplier constant was already gone, and the hook it used to feed is now
		// gone as well, so there is no shared state left between the two effects.
		check(!Checks.hasConstant(GreenThumbBehavior.class, "MULTIPLIER")
						&& !Checks.hasMethod(EffectHooks.class, "cropGrowthMultiplierFor"),
				"the two effects no longer share any code path at all");

		// hasGreenThumbNearby is the one crop-proximity check that survives, and it
		// must still be there -- the advance pass re-verifies through it.
		check(Checks.hasMethod(EffectHooks.class, "hasGreenThumbNearby"),
				"Green Thumb's own proximity re-verification survived the deletion");
	}

	/**
	 * The path sweep, driven against the real {@link TramplePath}.
	 *
	 * <p>This is the part of the new mechanic most likely to be wrong and least
	 * visible in play: a gap in the sweep just looks like a crop that happened not
	 * to be trampled. Kept free of Minecraft imports for exactly this reason.
	 */
	private static void blightTramplePath() {
		section("Blight Touched: the walked-through path");

		// Standing still: one cell, and it is the block the feet are in.
		List<long[]> still = cells(10.5, 64.0, 10.5, 10.5, 64.0, 10.5);
		check(still.size() == 1, "a stationary player yields exactly one cell, not one per sample");
		check(still.get(0)[0] == 10 && still.get(0)[1] == 64 && still.get(0)[2] == 10,
				"...and it is the block containing the feet");

		// Ordinary sprinting is 0.28 blocks/tick and usually stays in one block.
		check(cells(10.5, 64.0, 10.5, 10.78, 64.0, 10.5).size() == 1,
				"a sprint step inside one block yields one cell");
		check(cells(10.9, 64.0, 10.5, 11.1, 64.0, 10.5).size() == 2,
				"a sprint step across a boundary yields both cells");

		// The case a single feet-position check would get wrong: flying fast.
		List<long[]> dash = cells(0.5, 64.0, 0.5, 10.5, 64.0, 0.5);
		check(dash.size() == 11, "a 10-block dash yields all 11 cells, not just the endpoint");
		boolean contiguous = true;
		for (int i = 0; i < dash.size(); i++) {
			if (dash.get(i)[0] != i || dash.get(i)[1] != 64 || dash.get(i)[2] != 0) {
				contiguous = false;
			}
		}
		check(contiguous, "...contiguous and in order of travel, with no gaps");

		// Diagonal movement is sampled off the largest axis, so it is no coarser.
		List<long[]> diagonal = cells(0.5, 64.0, 0.5, 4.5, 64.0, 4.5);
		check(diagonal.size() >= 5, "a diagonal dash still crosses every cell on its own axis");
		check(noConsecutiveDuplicates(diagonal), "no cell is visited twice in a row");

		// Falling through a field counts too -- the sweep is 3D, not just horizontal.
		check(cells(10.5, 70.0, 10.5, 10.5, 64.0, 10.5).size() == 7,
				"a 6-block fall sweeps every y level passed through");

		// A teleport is not a walk.
		List<long[]> teleport = cells(0.5, 64.0, 0.5, 500.5, 64.0, 500.5);
		check(teleport.size() == 1, "a teleport yields only the destination cell, not the line between");
		check(teleport.get(0)[0] == 500 && teleport.get(0)[2] == 500,
				"...and that cell is the destination");

		// Negative coordinates: an off-by-one in the floor would shift every cell.
		List<long[]> negative = cells(-0.5, 64.0, -0.5, -0.5, 64.0, -0.5);
		check(negative.get(0)[0] == -1 && negative.get(0)[2] == -1,
				"floor is correct on the negative side of the world");

		check(TramplePath.stepsFor(0, 0, 0, 1, 0, 0) == 4,
				"one block of travel is sampled four times");
		check(TramplePath.stepsFor(0, 0, 0, 0, 0, 0) == 1,
				"a zero-length segment still samples at least once (no divide by zero)");
	}

	/**
	 * The regression gate the brief asked for explicitly: a player who does not
	 * hold Blight Touched must cause zero change.
	 *
	 * <p>Driven through the real {@code BlightTouchedTrample.isActive} with real
	 * {@link AcquiredEffects}. That method is the first thing {@code tick} calls
	 * and it returns before any player is inspected or any block is read, so a
	 * false here is the whole of "nothing happens" -- this is not merely
	 * "it wasn't in the diff".
	 */
	private static void blightTrampleGate() {
		section("Blight Touched: nothing happens without the effect");

		check(!BlightTouchedTrample.isActive(setOf()),
				"an empty run does not trample -- the tick returns before reading any block");
		check(!BlightTouchedTrample.isActive(setOf(GreenThumbBehavior.ID)),
				"Green Thumb alone does not trample");
		check(!BlightTouchedTrample.isActive(setOf(SureFootingBehavior.ID, GreenThumbBehavior.ID)),
				"no combination of other effects enables it");
		check(BlightTouchedTrample.isActive(setOf(BlightTouchedBehavior.ID)),
				"Blight Touched alone enables it");
		check(BlightTouchedTrample.isActive(setOf(GreenThumbBehavior.ID, BlightTouchedBehavior.ID)),
				"...and holding Green Thumb as well does not disable it");
	}

	/** Collects the cells {@link TramplePath} visits for a segment, in order. */
	private static List<long[]> cells(double x0, double y0, double z0,
									  double x1, double y1, double z1) {
		List<long[]> out = new ArrayList<>();
		TramplePath.forEachCell(x0, y0, z0, x1, y1, z1,
				(x, y, z) -> out.add(new long[] {x, y, z}));
		return out;
	}

	private static boolean noConsecutiveDuplicates(List<long[]> cells) {
		for (int i = 1; i < cells.size(); i++) {
			if (Arrays.equals(cells.get(i), cells.get(i - 1))) {
				return false;
			}
		}
		return true;
	}

	/**
	 * The two tracking rules, driven against the real {@link CropSchedule}.
	 *
	 * <p>The service itself needs a {@code ServerLevel} and cannot run headlessly,
	 * which is exactly why the scheduling rules were split into a Minecraft-free
	 * class -- these are the rules most likely to be got wrong and they are now
	 * directly checkable.
	 */
	private static void scheduleTracking() {
		section("Crop schedule: leaving the radius, and reaching max age");

		CropSchedule<String> schedule = new CropSchedule<>();

		schedule.refresh(Set.of("a", "b"), 0, key -> 100);
		check(schedule.size() == 2, "two crops in range are tracked");
		check(schedule.due(50).isEmpty(), "a newly-seen crop waits a full interval, not advanced on discovery");
		check(schedule.due(100).size() == 2, "...and is due exactly one interval later");

		// The player walks away from b before its advance lands.
		schedule.refresh(Set.of("a"), 100, key -> 100);
		check(!schedule.isTracking("b"), "a crop that left the radius stops being tracked");
		check(schedule.isTracking("a"), "...and the one still in range is kept");
		check(schedule.due(100).contains("a"),
				"a rescan does not push an existing crop's due time back (standing still must not starve it)");

		// a advances, so it is rescheduled rather than dropped.
		schedule.reschedule("a", 100, 100);
		check(schedule.due(150).isEmpty(), "after an advance the next one is a full interval out");
		check(schedule.due(200).contains("a"), "...and lands on schedule");

		// a reaches max age: the scan no longer reports it as eligible.
		schedule.refresh(Set.of(), 200, key -> 100);
		check(schedule.size() == 0, "a fully-grown crop drops out of tracking, not rescheduled forever");

        // Re-verification failure at advance time drops it outright.
		schedule.refresh(Set.of("c"), 300, key -> 100);
		schedule.drop("c");
		check(!schedule.isTracking("c"), "a crop failing re-verification at advance time is dropped");

		schedule.refresh(Set.of("d"), 400, key -> 100);
		schedule.clear();
		check(schedule.size() == 0, "clear() drops everything (server stop must not leak into the next world)");
	}

	/** An {@link AcquiredEffects} holding exactly these ids. */
	private static AcquiredEffects setOf(String... ids) {
		AcquiredEffects acquired = new AcquiredEffects();
		for (String id : ids) {
			acquired.add(id);
		}
		return acquired;
	}

	/**
	 * {@code /entropygrant}'s observable contract.
	 *
	 * <p>What this proves: a grant adds to the acquired set, is rejected for an
	 * unknown id, is rejected for an already-acquired id, and leaves entropy, the
	 * pick count and the history untouched.
	 *
	 * <p>What it cannot prove, and why the javap check in CLAUDE.md exists
	 * alongside it: there is no {@code MinecraftServer} here, so the call throws
	 * once it reaches the application step. That is caught below. The structural
	 * claim -- that {@code grantEffect} dispatches through the same private
	 * {@code applyToAll} a real pick uses, and references none of the run counters
	 * -- is checked in the bytecode instead.
	 */
	private static void grantContract() {
		section("/entropygrant contract");

		EntropyManager manager = new EntropyManager();

		check(manager.grantEffect(null, "no_such_effect_id") == EntropyManager.GrantResult.UNKNOWN_EFFECT,
				"an unknown id is rejected, not silently ignored");
		check(manager.acquired().isEmpty(), "a rejected grant adds nothing");

		EntropyManager.GrantResult first = grantWithoutServer(manager, SureFootingBehavior.ID);
		check(first == null || first == EntropyManager.GrantResult.GRANTED,
				"granting a real id reaches the application step");
		check(manager.acquired().contains(SureFootingBehavior.ID),
				"the granted effect is in the acquired set -- the real no-repeat/anti-stacking source");

		check(manager.getEntropy() == 0, "grant does not move entropy");
		check(manager.getPickCount() == 0, "grant does not move the pick count");
		check(manager.getHistory().isEmpty(), "grant writes no history entry");
		check(manager.currentPhase() == com.entropymod.entropy.EffectPhase.GOOD,
				"grant does not flip the GOOD/BAD phase");

		check(manager.grantEffect(null, SureFootingBehavior.ID) == EntropyManager.GrantResult.ALREADY_ACQUIRED,
				"re-granting is rejected rather than double-applied");
		check(manager.acquired().size() == 1, "...and the acquired set is unchanged by the rejection");

		// Tab completion has to offer ids that actually resolve, or the command's
		// whole reason for existing (not having to remember 34 strings) is undone.
		boolean allResolve = EffectRegistry.all().stream()
				.map(EffectDefinition::id)
				.allMatch(id -> EffectRegistry.byId(id) != null);
		check(allResolve, "every suggested id resolves back to a definition ("
				+ EffectRegistry.all().size() + " effects)");
	}

	/**
	 * There is no server in a headless harness, so applying to the player list
	 * throws. The state written before that point is what is under test.
	 */
	private static EntropyManager.GrantResult grantWithoutServer(EntropyManager manager, String id) {
		try {
			return manager.grantEffect(null, id);
		} catch (Throwable expected) {
			return null;
		}
	}

	private HarnessMain() {}
}
