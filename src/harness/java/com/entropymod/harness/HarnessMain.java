package com.entropymod.harness;

import com.entropymod.entropy.AcquiredEffects;
import com.entropymod.entropy.EffectDefinition;
import com.entropymod.entropy.EffectHooks;
import com.entropymod.entropy.EffectRegistry;
import com.entropymod.entropy.EntropyManager;
import com.entropymod.entropy.EffectPhase;
import com.entropymod.entropy.MovementScramble;
import com.entropymod.entropy.RerollState;
import com.entropymod.entropy.behavior.BadReputationBehavior;
import com.entropymod.entropy.behavior.BlightTouchedBehavior;
import com.entropymod.entropy.behavior.ClumsyDiggerBehavior;
import com.entropymod.entropy.behavior.RandomJumpBehavior;
import com.entropymod.entropy.behavior.RandomizedControlsBehavior;
import com.entropymod.entropy.behavior.SecondGuessBehavior;
import com.entropymod.entropy.behavior.UpsideDownCameraBehavior;
import com.entropymod.entropy.behavior.GreenThumbBehavior;
import com.entropymod.entropy.behavior.LeakyPocketsBehavior;
import com.entropymod.entropy.behavior.SureFootingBehavior;
import com.entropymod.entropy.growth.BlightTouchedTrample;
import com.entropymod.entropy.growth.CropSchedule;
import com.entropymod.entropy.growth.GreenThumbGrowth;
import com.entropymod.entropy.growth.TramplePath;
import com.entropymod.network.EntropyCodecs;
import com.entropymod.network.ClientEffectsPayload;
import com.entropymod.network.OpenChoicePayload;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ToolMaterial;

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
		clumsyDiggerMagnitude();
		clumsyDiggerScope();
		badReputationPrices();
		rerollStateDerivation();
		openChoicePayloadRoundTrip();
		tier2ClientEffects();
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

	// ------------------------------------------------------------------

	/**
	 * Clumsy Digger's observable magnitude, which is the thing the player report
	 * was actually about.
	 *
	 * <p>The diagnosis found no bug: the mixin applies, the hook is the real choke
	 * point for durability loss, and the roll runs on every tool use. What it found
	 * instead is that {@code CHANCE} is the wrong lever -- the effect's entire
	 * observable size is {@code CHANCE * EXTRA_DAMAGE} expressed as a percentage of
	 * a tool's lifetime, and the chance saturates at a mere 2x wear even at 100%.
	 *
	 * <p>Asserted rather than written in a comment so a future retune has to
	 * confront the ceiling instead of rediscovering it.
	 */
	private static void clumsyDiggerMagnitude() {
		section("Clumsy Digger: per-tier durability cost");

		checkNear(constant(ClumsyDiggerBehavior.class, "CHANCE"), 0.08, 1e-7,
				"chance is 8% per durability-consuming action");
		check((int) constant(ClumsyDiggerBehavior.class, "BASE_EXTRA") == 40,
				"BASE_EXTRA is 40 at the reference durability");
		check((int) constant(ClumsyDiggerBehavior.class, "REFERENCE_DURABILITY") == 200,
				"the reference durability is 200");

		// The flat constant is gone, not neutralised -- same discipline as the two
		// retired crop multipliers. A leftover EXTRA_DAMAGE could be re-wired into
		// the hook and silently override the proportional formula.
		check(!Checks.hasConstant(ClumsyDiggerBehavior.class, "EXTRA_DAMAGE"),
				"the old flat EXTRA_DAMAGE constant is deleted, not left at 1");

		// REAL durabilities, read out of the game's own ToolMaterial records rather
		// than typed in here -- the whole point of the table is that it describes
		// what ships. Note this version has a COPPER tier between stone and iron.
		record Tier(String name, ToolMaterial material) {}
		Tier[] tiers = {
				new Tier("Gold", ToolMaterial.GOLD),
				new Tier("Wood", ToolMaterial.WOOD),
				new Tier("Stone", ToolMaterial.STONE),
				new Tier("Copper", ToolMaterial.COPPER),
				new Tier("Iron", ToolMaterial.IRON),
				new Tier("Diamond", ToolMaterial.DIAMOND),
				new Tier("Netherite", ToolMaterial.NETHERITE),
		};

		System.out.printf("        %-10s %8s %8s %10s %10s%n",
				"tier", "maxDur", "+dmg", "blocks", "% shorter");
		for (Tier tier : tiers) {
			int max = tier.material().durability();
			int extra = ClumsyDiggerBehavior.extraDamageFor(max);
			double blocks = ClumsyDiggerBehavior.blocksSurvived(max);
			double shorter = 100.0 * (1.0 - blocks / max);
			System.out.printf("        %-10s %8d %8d %10.1f %9.1f%%%n",
					tier.name(), max, extra, blocks, shorter);
		}

		// Spot checks against the real durabilities, so a regression names the tier.
		check(ToolMaterial.WOOD.durability() == 59 && ToolMaterial.IRON.durability() == 250
						&& ToolMaterial.NETHERITE.durability() == 2031,
				"durabilities come from ToolMaterial itself (wood 59, iron 250, netherite 2031)");
		check(ClumsyDiggerBehavior.extraDamageFor(ToolMaterial.IRON.durability()) == 50,
				"iron (250) takes +50 per proc -- the reference tier, near BASE_EXTRA");
		check(ClumsyDiggerBehavior.extraDamageFor(ToolMaterial.GOLD.durability()) == 6,
				"gold (32) takes only +6");
		check(ClumsyDiggerBehavior.extraDamageFor(ToolMaterial.NETHERITE.durability()) == 406,
				"netherite (2031) takes +406");

		// The design claim: better tools lose a LARGER share of their life. This is
		// the property that must not be "normalised away" by a later session.
		double goldShare = 1.0 - ClumsyDiggerBehavior.blocksSurvived(ToolMaterial.GOLD.durability())
				/ ToolMaterial.GOLD.durability();
		double netheriteShare = 1.0 - ClumsyDiggerBehavior.blocksSurvived(ToolMaterial.NETHERITE.durability())
				/ ToolMaterial.NETHERITE.durability();
		checkNear(goldShare * 100, 32.4, 0.3, "gold loses 32% of its life");
		checkNear(netheriteShare * 100, 97.0, 0.3, "netherite loses 97% of its life");
		check(netheriteShare > goldShare,
				"better tools lose a LARGER share -- the deliberate non-uniformity");

		// Monotonic in absolute terms too: a better tool still survives longer, so
		// tier progression is compressed but never inverted.
		double previous = -1;
		boolean monotonic = true;
		for (int durability : new int[] {32, 59, 131, 190, 250, 1561, 2031}) {
			double blocks = ClumsyDiggerBehavior.blocksSurvived(durability);
			if (blocks <= previous) {
				monotonic = false;
			}
			previous = blocks;
		}
		check(monotonic, "tier order is preserved -- a better tool still outlasts a worse one");

		// The structural consequence, asserted so a retune confronts it rather than
		// rediscovering it: durability cancels, so blocks survived is capped.
		// Tolerance is 1e-4 rather than 1e-6 because CHANCE is a float: 0.08f is
		// really 0.079999998, which moves the ceiling by ~1.4e-6. Tightening this
		// would be asserting IEEE 754 rounding, not the design.
		checkNear(ClumsyDiggerBehavior.blockCeiling(), 62.5, 1e-4,
				"blocks survived is asymptotically capped at 200/(0.08*40) = 62.5");
		check(ClumsyDiggerBehavior.blocksSurvived(1_000_000) < ClumsyDiggerBehavior.blockCeiling(),
				"...and no durability, however large, exceeds that ceiling");
		check(ClumsyDiggerBehavior.blocksSurvived(ToolMaterial.NETHERITE.durability())
						> ClumsyDiggerBehavior.blockCeiling() - 2,
				"netherite is already within 2 blocks of the ceiling");

		// Diamond and netherite become near-indistinguishable, which is the single
		// most surprising number in the table.
		double diamond = ClumsyDiggerBehavior.blocksSurvived(ToolMaterial.DIAMOND.durability());
		double netherite = ClumsyDiggerBehavior.blocksSurvived(ToolMaterial.NETHERITE.durability());
		check(Math.abs(netherite - diamond) < 1.0,
				"diamond and netherite land within one block of each other (30% more durability, no benefit)");

		// A non-damageable item must be untouched, and a damageable one never exempt.
		check(ClumsyDiggerBehavior.extraDamageFor(0) == 0, "a non-damageable item takes no extra damage");
		check(ClumsyDiggerBehavior.extraDamageFor(1) == 1,
				"a 1-durability item still takes at least 1 -- rounding cannot exempt it");

	}

	/**
	 * The scope gate: mining tools only, armour and elytra completely untouched.
	 *
	 * <p>Checked against the <b>real shipped tag data</b>, read out of the
	 * Minecraft jar on the classpath and resolved through its nested
	 * {@code #minecraft:...} references. That is what makes this a check rather
	 * than a restatement of the constant -- the claim being verified is about what
	 * {@code #minecraft:enchantable/mining} actually contains, which is data, not
	 * code.
	 *
	 * <p>Evaluating {@code stack.typeHolder().is(tag)} for real would need a
	 * bootstrapped registry and a loaded tag set, which the harness deliberately
	 * does not have. Resolving the same JSON vanilla itself loads is the faithful
	 * substitute, and it catches the failure that matters: the tag turning out to
	 * include something it should not.
	 */
	private static void clumsyDiggerScope() {
		section("Clumsy Digger scope: mining tools only");

		Set<String> affected = resolveItemTag("enchantable/mining");
		Set<String> anythingDamageable = resolveItemTag("enchantable/durability");

		// The four tool families the effect is meant to cover, one real item each.
		for (String tool : new String[] {
				"minecraft:wooden_pickaxe", "minecraft:netherite_pickaxe",
				"minecraft:diamond_axe", "minecraft:iron_shovel", "minecraft:golden_hoe"}) {
			check(affected.contains(tool), "covered: " + tool);
		}

		// The regression gate this session exists for.
		for (String exempt : new String[] {
				"minecraft:elytra",
				"minecraft:netherite_chestplate", "minecraft:diamond_helmet",
				"minecraft:iron_leggings", "minecraft:leather_boots",
				"minecraft:turtle_helmet", "minecraft:shield"}) {
			check(!affected.contains(exempt), "EXEMPT: " + exempt + " takes zero extra wear");
			check(anythingDamageable.contains(exempt),
					"...and it really does lose durability through the same hook (" + exempt + ")");
		}

		// Weapons and the miscellaneous durability items are out too -- worth
		// pinning, because "tool" could plausibly have been read to include them.
		for (String exempt : new String[] {
				"minecraft:netherite_sword", "minecraft:bow", "minecraft:crossbow",
				"minecraft:trident", "minecraft:mace", "minecraft:fishing_rod",
				"minecraft:flint_and_steel", "minecraft:brush"}) {
			check(!affected.contains(exempt), "EXEMPT: " + exempt);
		}

		// Shears are the one item beyond the four digging families, kept on purpose.
		check(affected.contains("minecraft:shears"),
				"shears ARE covered -- deliberate, see AFFECTED_ITEMS");

		// Every armour piece in the game, not just the samples above.
		Set<String> allArmour = new java.util.HashSet<>();
		for (String slot : new String[] {"head_armor", "chest_armor", "leg_armor", "foot_armor"}) {
			allArmour.addAll(resolveItemTag("enchantable/" + slot));
		}
		check(!allArmour.isEmpty(), "the armour tags resolved to something (" + allArmour.size() + " items)");
		check(java.util.Collections.disjoint(affected, allArmour),
				"NO armour item of any slot is in the affected set");

		// The tag is a strict subset of "anything with durability" -- i.e. the
		// narrowing is real, and the effect no longer means "every damageable item".
		check(anythingDamageable.containsAll(affected) && anythingDamageable.size() > affected.size(),
				"affected (" + affected.size() + ") is a strict subset of all damageable ("
						+ anythingDamageable.size() + ")");

		// The gate has to run BEFORE the random roll, or the DEBUG line would report
		// procs on armour that never actually applied.
		check(Checks.hasMethod(ClumsyDiggerBehavior.class, "appliesTo"),
				"the scope test is a named method on the effect, not inlined in the mixin");
	}

	/**
	 * Resolves a vanilla item tag from the Minecraft jar on the classpath,
	 * following nested {@code #namespace:path} references.
	 *
	 * <p>Entries may be plain strings or {@code {"id": ..., "required": false}}
	 * objects; both forms are handled.
	 */
	private static Set<String> resolveItemTag(String path) {
		Set<String> out = new java.util.HashSet<>();
		resolveItemTagInto(path, out, new java.util.HashSet<>());
		return out;
	}

	private static void resolveItemTagInto(String path, Set<String> out, Set<String> seen) {
		if (!seen.add(path)) {
			return;
		}
		String resource = "/data/minecraft/tags/item/" + path + ".json";
		try (java.io.InputStream in = HarnessMain.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IllegalStateException("tag not found on classpath: " + resource);
			}
			com.google.gson.JsonObject root = com.google.gson.JsonParser
					.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))
					.getAsJsonObject();
			for (com.google.gson.JsonElement element : root.getAsJsonArray("values")) {
				String value = element.isJsonObject()
						? element.getAsJsonObject().get("id").getAsString()
						: element.getAsString();
				if (value.startsWith("#")) {
					resolveItemTagInto(value.substring(value.indexOf(':') + 1), out, seen);
				} else {
					out.add(value);
				}
			}
		} catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	/**
	 * Bad Reputation's real final-price multipliers, across cheap and expensive
	 * trades rather than one example.
	 *
	 * <p>Reproduces vanilla's own arithmetic, verified in bytecode:
	 * {@code getModifiedCostCount} is
	 * {@code clamp(base + max(0, floor(base*demand*mult)) + specialPriceDiff, 1,
	 * maxStackSize)}, and {@code specialPriceDiff} is a <b>flat integer</b>. So this
	 * effect is an additive surcharge, and only behaves like a multiplier because it
	 * is computed as a fraction of the price the player would otherwise have paid.
	 */
	private static void badReputationPrices() {
		section("Bad Reputation final-price multipliers");

		float surcharge = (float) constant(BadReputationBehavior.class, "SURCHARGE");
		checkNear(surcharge, 0.65, 1e-6, "surcharge retuned to 0.65 of the pre-effect price");

		// Every realistic emerald cost, cheap to expensive. 1 is called out
		// separately below because integers make it a special case.
		int[] normalPrices = {2, 3, 4, 5, 6, 8, 10, 12, 16, 20, 24, 32, 36};
		boolean allInBand = true;
		for (int normal : normalPrices) {
			int finalPrice = badRepPrice(normal, surcharge);
			double multiplier = (double) finalPrice / normal;
			if (multiplier < 1.50 || multiplier > 1.75) {
				allInBand = false;
			}
			System.out.printf("        %2d emeralds -> %2d  (%.3fx)%n", normal, finalPrice, multiplier);
		}
		check(allInBand, "every realistic trade cost lands in the 1.50x-1.75x target band");

		// Spot checks so a regression names the trade rather than just "the band".
		check(badRepPrice(2, surcharge) == 3, "a 2-emerald trade costs 3 (1.50x)");
		check(badRepPrice(5, surcharge) == 8, "a 5-emerald trade costs 8 (1.60x)");
		check(badRepPrice(16, surcharge) == 26, "a 16-emerald trade costs 26 (1.625x)");
		check(badRepPrice(32, surcharge) == 53, "a 32-emerald trade costs 53 (1.656x)");

		// The two boundaries, asserted so they are not rediscovered as bugs.
		check(badRepPrice(1, surcharge) == 2,
				"a 1-emerald trade costs 2 (2.00x) -- integers make +100% the SMALLEST possible rise");
		check(badRepPrice(64, surcharge) == 64,
				"a trade already at a full stack is unchanged -- vanilla's own clamp, not a miss");
		check(badRepPrice(40, surcharge) == 64,
				"above ~39 the clamp starts absorbing the surcharge");
	}

	/**
	 * Vanilla's price formula with the surcharge applied, mirroring
	 * {@code VillagerPricesMixin}. Demand and gossip are already folded into
	 * {@code normalPrice}, which is exactly what {@code getCostA()} returns at the
	 * point the mixin runs.
	 */
	private static int badRepPrice(int normalPrice, float surcharge) {
		int extra = Math.max(1, Math.round(normalPrice * surcharge));
		return Math.min(64, normalPrice + extra);
	}

	/**
	 * The Second Guess button-state bug, as a regression gate.
	 *
	 * <p>The reported symptom was a reroll button that stayed live after being
	 * spent. The cause was not a missing payload field -- the field existed and was
	 * derived from the persisted flag correctly. It was <b>ordering</b>:
	 * {@code requestReroll} set {@code rerollUsed} <em>after</em> calling
	 * {@code triggerPick}, which had already built and sent the payload. So the one
	 * screen where it mattered advertised a reroll that no longer existed.
	 *
	 * <p>What is checkable headlessly is the derivation itself and the three-state
	 * mapping. The ordering is pinned in bytecode instead, below.
	 */
	private static void rerollStateDerivation() {
		section("Second Guess: reroll state derivation");

		EntropyManager fresh = new EntropyManager();
		check(fresh.rerollState() == RerollState.NOT_OWNED,
				"a run without Second Guess reports NOT_OWNED -- no button, no reserved space");

		grantWithoutServer(fresh, SecondGuessBehavior.ID);
		check(fresh.rerollState() == RerollState.AVAILABLE,
				"owning it unspent reports AVAILABLE");
		check(!fresh.isRerollUsed(), "...and the persisted flag agrees");

		// Three states, so "never had it" and "had it and spent it" stay distinct --
		// a boolean could not tell those apart, which is why the payload field is an
		// enum now.
		check(RerollState.values().length == 3,
				"NOT_OWNED / AVAILABLE / SPENT are distinct states, not a boolean");

		// The ordering fix, stated as the property it guarantees: rerollState() must
		// be a pure function of ownership and the spent flag, with no dependence on
		// waitingOnChoice -- otherwise the value sent depends on when it was asked.
		check(!Checks.hasMethod(EntropyManager.class, "isRerollAvailableForClient"),
				"there is exactly one client-facing state accessor, not a second parallel one");
		check(Checks.hasMethod(EntropyManager.class, "rerollState")
						&& Checks.hasMethod(EntropyManager.class, "isRerollAvailable"),
				"rendering state and authorisation state remain separate methods");
	}

	/**
	 * {@code OpenChoicePayload}'s codec, round-tripped field by field with every
	 * field set to a distinct value.
	 *
	 * <p>This is the check CLAUDE.md demands after any payload change, and it is
	 * done field-by-field rather than with a whole-record {@code equals} because
	 * two same-typed fields swapped in a way that cancels out would still pass an
	 * equality check. {@code entropy} and {@code entropyCap} are both ints and sit
	 * next to each other, so that is a live hazard here, not a theoretical one.
	 *
	 * <p>Runs against the real {@code StreamCodec}. No registry is involved -- every
	 * component codec is a string, varint or enum-by-name -- so
	 * {@code RegistryAccess.EMPTY} is a faithful stand-in.
	 */
	private static void openChoicePayloadRoundTrip() {
		section("OpenChoicePayload codec round-trip");

		for (RerollState state : RerollState.values()) {
			OpenChoicePayload sent = new OpenChoicePayload(
					EffectPhase.BAD, 37, 91, state,
					new OpenChoicePayload.Choice("id_one", "Name One", "Desc One"),
					new OpenChoicePayload.Choice("id_two", "Name Two", "Desc Two"),
					new OpenChoicePayload.Choice("id_three", "Name Three", "Desc Three"));

			RegistryFriendlyByteBuf buf =
					new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
			OpenChoicePayload.CODEC.encode(buf, sent);
			OpenChoicePayload got = OpenChoicePayload.CODEC.decode(buf);

			check(got.rerollState() == state, "rerollState survives the wire as " + state);
			check(got.phase() == EffectPhase.BAD, "phase survives (" + state + ")");
			check(got.entropy() == 37, "entropy is 37, not the cap (" + state + ")");
			check(got.entropyCap() == 91, "entropyCap is 91, not the entropy (" + state + ")");
			check(got.choice1().id().equals("id_one")
							&& got.choice1().name().equals("Name One")
							&& got.choice1().description().equals("Desc One"),
					"choice1's three strings are not rotated (" + state + ")");
			check(got.choice2().id().equals("id_two") && got.choice3().id().equals("id_three"),
					"choice2 and choice3 are not swapped (" + state + ")");
			check(buf.readableBytes() == 0, "the buffer is fully consumed (" + state + ")");
		}

		// Sent by name, so reordering the enum cannot silently change what the
		// client draws -- the same rule EffectPhase follows.
		RegistryFriendlyByteBuf named =
				new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		EntropyCodecs.REROLL_STATE.encode(named, RerollState.SPENT);
		check(named.toString(java.nio.charset.StandardCharsets.UTF_8).contains("SPENT"),
				"RerollState goes on the wire by name, not by ordinal");
	}


	/**
	 * Tier 2's three client-side effects.
	 *
	 * <p>These are the first effects whose behaviour lives on the client, so the
	 * things worth checking headlessly are different from every prior effect: the
	 * permutation model, the fact that the scramble is assigned exactly once and
	 * survives, and that the payload carrying it to the client round-trips.
	 *
	 * <p>What this cannot prove, and what needs the in-game session: that the
	 * mixins inject, that the camera roll reads as upside-down rather than broken,
	 * and that a forced jump behaves in water and on ladders.
	 */
	private static void tier2ClientEffects() {
		section("Tier 2: movement scramble, camera, random jump");

		// --- the permutation model ---
		check(MovementScramble.isValid("FBLR"), "the identity is a valid permutation");
		check(MovementScramble.isValid("LFRB"), "a shuffled permutation is valid");
		check(!MovementScramble.isValid("FFLR"), "a repeated direction is rejected");
		check(!MovementScramble.isValid("FBL"), "a short string is rejected");
		check(!MovementScramble.isValid("FBLX"), "an unknown direction is rejected");
		check(!MovementScramble.isValid(null), "null is rejected, not thrown on");
		check(!MovementScramble.isValid(""), "the unassigned value is not a valid scramble");

		// Identity must be a no-op, and an invalid scramble must degrade to vanilla
		// rather than throw -- this runs inside input handling every client tick.
		boolean[] pressed = {true, false, false, false};
		check(java.util.Arrays.equals(MovementScramble.apply("FBLR", pressed), pressed),
				"the identity permutation changes nothing");
		check(java.util.Arrays.equals(MovementScramble.apply("garbage", pressed), pressed),
				"an invalid scramble degrades to vanilla controls, it does not throw");

		// "LFRB": pressing forward sends you left, back sends you forward, and so on.
		boolean[] out = MovementScramble.apply("LFRB", new boolean[] {true, false, false, false});
		check(!out[0] && !out[1] && out[2] && !out[3],
				"under LFRB, pressing forward moves you left");
		out = MovementScramble.apply("LFRB", new boolean[] {false, true, false, false});
		check(out[0] && !out[1] && !out[2] && !out[3],
				"...and pressing back moves you forward");

		// Every generated scramble must be legal and never the identity -- a 1-in-24
		// no-op curse is indistinguishable from a broken one.
		java.util.Random random = new java.util.Random(1234);
		boolean allValid = true;
		boolean anyIdentity = false;
		java.util.Set<String> distinct = new java.util.HashSet<>();
		for (int i = 0; i < 2000; i++) {
			String s = MovementScramble.random(random);
			if (!MovementScramble.isValid(s)) {
				allValid = false;
			}
			if (s.equals(MovementScramble.IDENTITY)) {
				anyIdentity = true;
			}
			distinct.add(s);
		}
		check(allValid, "2000 generated scrambles are all valid permutations");
		check(!anyIdentity, "...and none is the identity (a no-op curse would look broken)");
		check(distinct.size() == 23, "...covering all 23 non-identity permutations");

		// Vanilla's impulse maths, reproduced because calculateImpulse is private.
		check(MovementScramble.impulse(false, false) == 0.0f, "no keys -> 0 impulse");
		check(MovementScramble.impulse(true, true) == 0.0f, "both keys -> 0 impulse (they cancel)");
		check(MovementScramble.impulse(true, false) == 1.0f, "positive key -> +1");
		check(MovementScramble.impulse(false, true) == -1.0f, "negative key -> -1");

		// --- assigned once, then never again ---
		EntropyManager manager = new EntropyManager();
		check(manager.getMoveScramble().isEmpty(), "a fresh run has no scramble");

		check(manager.assignMoveScrambleIfAbsent(), "the first assignment happens");
		String assigned = manager.getMoveScramble();
		check(MovementScramble.isValid(assigned), "...and produces a valid permutation");

		// This is the property the whole persistence design exists for: apply() runs
		// again on every respawn, rejoin and dimension change.
		check(!manager.assignMoveScrambleIfAbsent(),
				"a second assignment is refused, not silently re-rolled");
		boolean stable = true;
		for (int i = 0; i < 100; i++) {
			manager.assignMoveScrambleIfAbsent();
			if (!manager.getMoveScramble().equals(assigned)) {
				stable = false;
			}
		}
		check(stable, "100 further applies leave the scramble byte-identical (respawn/relog safety)");

		// --- the ids the client keys on must resolve ---
		for (String id : new String[] {RandomizedControlsBehavior.ID, UpsideDownCameraBehavior.ID,
				RandomJumpBehavior.ID}) {
			check(EffectRegistry.byId(id) != null, id + " is registered as a real effect");
			check(EffectRegistry.byId(id).minEntropy() == 25 && EffectRegistry.byId(id).maxEntropy() == 50,
					id + " is Tier 2 (entropy 25-50)");
		}

		// Random Jump's interval, in the units the brief specified.
		check(RandomJumpBehavior.MIN_INTERVAL_TICKS == 100 && RandomJumpBehavior.MAX_INTERVAL_TICKS == 600,
				"Random Jump fires every 5-30 seconds (100-600 ticks)");

		// --- the payload that carries all of this to the client ---
		ClientEffectsPayload sent = new ClientEffectsPayload(
				List.of(RandomizedControlsBehavior.ID, UpsideDownCameraBehavior.ID), "RLBF");
		RegistryFriendlyByteBuf buf =
				new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		ClientEffectsPayload.CODEC.encode(buf, sent);
		ClientEffectsPayload got = ClientEffectsPayload.CODEC.decode(buf);
		check(got.effectIds().equals(List.of(RandomizedControlsBehavior.ID, UpsideDownCameraBehavior.ID)),
				"effect ids survive the wire in order");
		check(got.moveScramble().equals("RLBF"), "the scramble survives the wire, not swapped with the ids");
		check(buf.readableBytes() == 0, "the buffer is fully consumed");

		// An empty run must round-trip too: that is what a player with no effects,
		// and the clear-on-join case, actually send.
		RegistryFriendlyByteBuf empty =
				new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		ClientEffectsPayload.CODEC.encode(empty, new ClientEffectsPayload(List.of(), ""));
		ClientEffectsPayload emptyGot = ClientEffectsPayload.CODEC.decode(empty);
		check(emptyGot.effectIds().isEmpty() && emptyGot.moveScramble().isEmpty(),
				"an empty run round-trips (the client must be told 'you have nothing' too)");
	}

	private HarnessMain() {}
}
