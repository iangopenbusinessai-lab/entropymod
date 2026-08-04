package com.entropymod.harness;

import com.entropymod.entropy.AcquiredEffects;
import com.entropymod.entropy.EffectDefinition;
import com.entropymod.entropy.EffectHooks;
import com.entropymod.entropy.EffectRegistry;
import com.entropymod.entropy.EntropyManager;
import com.entropymod.entropy.EffectPhase;
import com.entropymod.entropy.KeybindSnapshot;
import com.entropymod.entropy.MovementScramble;
import com.entropymod.entropy.RerollState;
import com.entropymod.entropy.RunState;
import com.entropymod.entropy.AttributeEffectBehavior;
import com.entropymod.entropy.EffectBehaviors;
import com.entropymod.entropy.EffectCategory;
import com.entropymod.entropy.behavior.BadReputationBehavior;
import com.entropymod.entropy.behavior.BehemothGauntletsBehavior;
import com.entropymod.entropy.behavior.CreativeFlightBehavior;
import com.entropymod.entropy.behavior.CrouchInvincibilityBehavior;
import com.entropymod.entropy.behavior.EmbraceTheMoonBehavior;
import com.entropymod.entropy.behavior.FlamboyantBehavior;
import com.entropymod.entropy.behavior.GiantSizeBehavior;
import com.entropymod.entropy.behavior.MoonWalkerBehavior;
import com.entropymod.entropy.behavior.SlashedPocketsBehavior;
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
import com.entropymod.network.KeybindSnapshotPayload;
import com.entropymod.network.OpenChoicePayload;
import com.entropymod.network.RunSyncPayload;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
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
		runStartGate();
		keybindSnapshotRules();
		runStatePersistence();
		runLifecyclePayloadRoundTrip();
		endedStateNotBuilt();
		tier2BatchWiring();
		extremeGravityPhysics();
		giantSizeAttribute();
		giantSizeKit();
		multiAttributeIdempotency();
		behemothGauntletsBothCases();
		flamboyantFireOnly();
		crouchInvincibilityGate();
		slashedPocketsSlots();
		tier2BatchIdempotencyAndPersistence();
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

	// ------------------------------------------------------------------
	// Run lifecycle: the NOT_STARTED gate and the keybind snapshot.
	// The ENDED half of the architecture is deliberately not built -- see
	// endedStateNotBuilt() below, which asserts exactly that.
	// ------------------------------------------------------------------

	/**
	 * The gate is real: nothing counts before the run is started.
	 *
	 * <p>Ticked with a {@code null} server on purpose. That is not a shortcut
	 * around building one -- it is the assertion. {@code triggerPick} dereferences
	 * the server immediately, so a gate that let the loop run would blow up here
	 * rather than quietly passing, and ticking well past the interval length is
	 * what guarantees it would be reached.
	 */
	private static void runStartGate() {
		section("Run lifecycle: the NOT_STARTED gate");

		EntropyManager manager = new EntropyManager();
		check(manager.getRunState() == RunState.NOT_STARTED,
				"a fresh run starts NOT_STARTED, so a new world is gated by default");
		check(!manager.isStarted(), "isStarted() agrees with the state");

		boolean survived;
		try {
			for (int i = 0; i < EntropyManager.DEFAULT_INTERVAL_TICKS + 500; i++) {
				manager.tick(null);
			}
			survived = true;
		} catch (RuntimeException e) {
			survived = false;
		}
		check(survived,
				"ticking " + (EntropyManager.DEFAULT_INTERVAL_TICKS + 500)
						+ " times while NOT_STARTED never reaches triggerPick");
		check(manager.getTicksIntoInterval() == 0,
				"the interval counter does not advance either -- the gate is before the "
						+ "increment, so the loop is paused rather than running invisibly");

		check(manager.startRun(), "startRun() performs the transition and reports it");
		check(manager.getRunState() == RunState.IN_PROGRESS, "state is IN_PROGRESS afterwards");

		// Requirement: Start must not be actionable twice. The trigger is a client
		// packet, so a double-click or a replay really can arrive.
		check(!manager.startRun(), "startRun() is idempotent -- a second call is refused");
		check(manager.getTicksIntoInterval() == 0,
				"the refused second start does not reset the interval timer mid-run");

		for (int i = 0; i < 10; i++) {
			manager.tick(null);
		}
		check(manager.getTicksIntoInterval() == 10,
				"once started, the loop counts normally");

		// forcePick bypasses the clock and nothing else, so it must respect the gate.
		check(new EntropyManager().forcePick(null) == EntropyManager.PickTrigger.RUN_NOT_STARTED,
				"/entropyforcepick is refused while NOT_STARTED, and says which gate refused");
	}

	/** Write-once per player, and only while the run is live. */
	private static void keybindSnapshotRules() {
		section("Keybind snapshot: captured once, at Start");

		KeybindSnapshot wasd = new KeybindSnapshot(
				"key.keyboard.w", "key.keyboard.s", "key.keyboard.a", "key.keyboard.d");
		KeybindSnapshot arrows = new KeybindSnapshot(
				"key.keyboard.up", "key.keyboard.down", "key.keyboard.left", "key.keyboard.right");

		check(wasd.isPresent(), "a fully-bound snapshot is present");
		check(!KeybindSnapshot.EMPTY.isPresent(), "EMPTY is absent");
		check(!new KeybindSnapshot("key.keyboard.w", "", "key.keyboard.a", "key.keyboard.d").isPresent(),
				"a partially-filled snapshot counts as absent, not half-honoured");

		// The array handed to MovementScramble.apply is indexed by ORDER, so the
		// snapshot's field order has to match it or every direction is rotated.
		check(MovementScramble.ORDER.equals("FBLR"),
				"MovementScramble.ORDER is still forward/back/left/right");
		check(wasd.keys().equals(List.of(
						"key.keyboard.w", "key.keyboard.s", "key.keyboard.a", "key.keyboard.d")),
				"keys() is in ORDER -- forward, back, left, right");
		check(wasd.keys().size() == MovementScramble.LENGTH,
				"a snapshot has exactly as many keys as a scramble has slots");

		String uuid = "11111111-2222-3333-4444-555555555555";
		String other = "99999999-8888-7777-6666-555555555555";

		EntropyManager notStarted = new EntropyManager();
		check(!notStarted.storeKeybindSnapshotIfAbsent(uuid, wasd),
				"a snapshot is REFUSED while NOT_STARTED -- there is no run to anchor to, "
						+ "and the player may still rebind before clicking Start");
		check(!notStarted.keybindSnapshotFor(uuid).isPresent(), "so nothing is held");

		EntropyManager live = new EntropyManager();
		live.startRun();
		check(live.storeKeybindSnapshotIfAbsent(uuid, wasd), "stored once the run is live");
		check(live.keybindSnapshotFor(uuid).equals(wasd), "and reads back unchanged");

		// This is the whole anti-exploit guarantee. If a later capture could
		// overwrite, a client could re-anchor its curse at will and rebinding would
		// counter it again with one extra step.
		check(!live.storeKeybindSnapshotIfAbsent(uuid, arrows),
				"a SECOND snapshot for the same player is refused, not applied");
		check(live.keybindSnapshotFor(uuid).equals(wasd),
				"the original snapshot is still the one held");

		check(live.storeKeybindSnapshotIfAbsent(other, arrows),
				"a different player gets their own snapshot");
		check(live.keybindSnapshotFor(other).equals(arrows)
						&& live.keybindSnapshotFor(uuid).equals(wasd),
				"the two players' snapshots do not collide");

		check(!live.storeKeybindSnapshotIfAbsent("someone-else", KeybindSnapshot.EMPTY),
				"an absent snapshot is not stored as if it were real");
		check(!live.keybindSnapshotFor("nobody").isPresent(),
				"an unknown player reads back as absent rather than null");
	}

	/**
	 * Survives a relog, which for a {@code SavedData} means: survives the codec.
	 * Includes the migration path for saves written before run states existed.
	 */
	private static void runStatePersistence() {
		section("Run lifecycle persists across a relog");

		String uuid = "11111111-2222-3333-4444-555555555555";
		KeybindSnapshot wasd = new KeybindSnapshot(
				"key.keyboard.w", "key.keyboard.s", "key.keyboard.a", "key.keyboard.d");

		EntropyManager before = new EntropyManager();
		before.startRun();
		before.storeKeybindSnapshotIfAbsent(uuid, wasd);

		EntropyManager after = reencode(before);
		check(after.getRunState() == RunState.IN_PROGRESS, "run state survives the save");
		check(after.keybindSnapshotFor(uuid).equals(wasd),
				"the keybind snapshot survives the save, key for key and in order");

		EntropyManager fresh = reencode(new EntropyManager());
		check(fresh.getRunState() == RunState.NOT_STARTED,
				"an unstarted run reloads as unstarted -- the gate is not lost on reload");
		check(!fresh.keybindSnapshotFor(uuid).isPresent(), "and holds no snapshots");

		// Migration. A save from before this feature has no "run_state" field at
		// all, and defaulting it to NOT_STARTED would demand that a player already
		// mid-run click Start again on a world they had been playing for an hour.
		JsonElement legacy = encode(new EntropyManager());
		legacy.getAsJsonObject().remove("run_state");
		legacy.getAsJsonObject().addProperty("pick_count", 7);
		EntropyManager migrated = decode(legacy);
		check(migrated.getRunState() == RunState.IN_PROGRESS,
				"a pre-run-state save with picks made migrates to IN_PROGRESS");
		check(migrated.getPickCount() == 7, "and keeps its picks");

		JsonElement legacyUntouched = encode(new EntropyManager());
		legacyUntouched.getAsJsonObject().remove("run_state");
		check(decode(legacyUntouched).getRunState() == RunState.NOT_STARTED,
				"a pre-run-state save with no picks migrates to NOT_STARTED");

		// The migration keys on pickCount specifically, NOT on the acquired set --
		// /entropygrant adds effects without advancing the run and works while
		// NOT_STARTED, so a granted effect must not be mistaken for a started run.
		JsonElement granted = encode(new EntropyManager());
		granted.getAsJsonObject().remove("run_state");
		granted.getAsJsonObject().add("acquired",
				JsonOps.INSTANCE.createList(java.util.stream.Stream.of(
						JsonOps.INSTANCE.createString(SureFootingBehavior.ID))));
		check(decode(granted).getRunState() == RunState.NOT_STARTED,
				"a granted effect on a pre-run-state save does NOT look like a started run");

		// Unparseable rather than absent: must degrade, not make the world unloadable.
		JsonElement garbage = encode(new EntropyManager());
		garbage.getAsJsonObject().addProperty("run_state", "ENDED_SOMEHOW");
		check(decode(garbage).getRunState() == RunState.NOT_STARTED,
				"an unrecognised run_state degrades to NOT_STARTED instead of throwing");
	}

	private static JsonElement encode(EntropyManager manager) {
		return EntropyManager.CODEC.encodeStart(JsonOps.INSTANCE, manager)
				.getOrThrow(msg -> new IllegalStateException("encode failed: " + msg));
	}

	private static EntropyManager decode(JsonElement json) {
		return EntropyManager.CODEC.parse(JsonOps.INSTANCE, json)
				.getOrThrow(msg -> new IllegalStateException("decode failed: " + msg));
	}

	/** Save and reload, which is what a relog is for a SavedData. */
	private static EntropyManager reencode(EntropyManager manager) {
		return decode(encode(manager));
	}

	private static void runLifecyclePayloadRoundTrip() {
		section("Run lifecycle payloads round-trip");

		// Four same-typed string components in a row on both payloads -- exactly the
		// shape where a mis-wired getter compiles and silently sends the wrong
		// field, so every value here is distinct and each is checked by name.
		KeybindSnapshot distinct = new KeybindSnapshot("K_FWD", "K_BACK", "K_LEFT", "K_RIGHT");

		RegistryFriendlyByteBuf buf =
				new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		RunSyncPayload.CODEC.encode(buf, RunSyncPayload.of(RunState.IN_PROGRESS, distinct));
		RunSyncPayload gotSync = RunSyncPayload.CODEC.decode(buf);

		check(gotSync.state() == RunState.IN_PROGRESS, "run state survives the wire");
		check(gotSync.snapshot().forward().equals("K_FWD")
						&& gotSync.snapshot().back().equals("K_BACK")
						&& gotSync.snapshot().left().equals("K_LEFT")
						&& gotSync.snapshot().right().equals("K_RIGHT"),
				"the four keys are not rotated or swapped on RunSyncPayload");
		check(buf.readableBytes() == 0, "RunSyncPayload's buffer is fully consumed");

		RegistryFriendlyByteBuf named =
				new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		RunSyncPayload.CODEC.encode(named, RunSyncPayload.of(RunState.NOT_STARTED, KeybindSnapshot.EMPTY));
		check(named.toString(java.nio.charset.StandardCharsets.UTF_8).contains("NOT_STARTED"),
				"run state goes on the wire by name, not by ordinal");

		for (boolean start : new boolean[] {true, false}) {
			RegistryFriendlyByteBuf up =
					new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
			KeybindSnapshotPayload.CODEC.encode(up, new KeybindSnapshotPayload(distinct, start));
			KeybindSnapshotPayload got = KeybindSnapshotPayload.CODEC.decode(up);

			check(got.startRun() == start,
					"startRun survives the wire as " + start + " -- it is what starts the run");
			check(got.snapshot().equals(distinct),
					"the snapshot rides with it intact (startRun=" + start + ")");
			check(up.readableBytes() == 0,
					"KeybindSnapshotPayload's buffer is fully consumed (startRun=" + start + ")");
		}

		// The empty snapshot has to survive too: it is the "server holds nothing for
		// you" signal that makes a client capture its keybinds.
		RegistryFriendlyByteBuf empty =
				new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		RunSyncPayload.CODEC.encode(empty, RunSyncPayload.of(RunState.NOT_STARTED, KeybindSnapshot.EMPTY));
		check(!RunSyncPayload.CODEC.decode(empty).snapshot().isPresent(),
				"an absent snapshot survives as absent -- that is the capture cue");
	}

	/**
	 * The other half of the architecture is still unbuilt, and this asserts it
	 * rather than trusting a doc comment.
	 *
	 * <p>The scope line matters because {@code ENDED} is the state everything else
	 * hangs off: an end screen, dragon-death win detection, Become Hardcore's
	 * death-triggered loss, and a run-wide death counter. A later session finding
	 * a half-present {@code ENDED} constant would have no way to tell "started and
	 * abandoned" from "deliberately deferred".
	 */
	private static void endedStateNotBuilt() {
		section("The ENDED half is deliberately NOT built");

		check(RunState.values().length == 2,
				"RunState has exactly two constants -- no speculative ENDED to branch on");
		check(Arrays.stream(RunState.values()).noneMatch(s -> s.name().equals("ENDED")),
				"no ENDED constant exists yet");
		check(!Checks.hasMethod(EntropyManager.class, "endRun"),
				"no endRun() -- the three paths into ENDED are still unbuilt");
		check(!Checks.hasMethod(EntropyManager.class, "getDeathCount"),
				"no run-wide death counter yet (that belongs with the end screen)");
	}

	// ------------------------------------------------------------------
	// Tier 2 content batch: seven effects.
	// ------------------------------------------------------------------

	private static final List<String> TIER2_BATCH = List.of(
			EmbraceTheMoonBehavior.ID, CreativeFlightBehavior.ID, BehemothGauntletsBehavior.ID,
			CrouchInvincibilityBehavior.ID, GiantSizeBehavior.ID, SlashedPocketsBehavior.ID,
			FlamboyantBehavior.ID);

	private static void tier2BatchWiring() {
		section("Tier 2 batch: registration and wiring");

		// Touching EffectBehaviors class-inits EntropyAttributes against a frozen
		// registry, so the harness needs Fabric's ordering reproduced first.
		HarnessBootstrap.init();

		for (String id : TIER2_BATCH) {
			EffectDefinition def = EffectRegistry.byId(id);
			check(def != null, id + " is registered in EffectRegistry");
			if (def == null) {
				continue;
			}
			check(def.minEntropy() == 25 && def.maxEntropy() == 50,
					id + " is Tier 2 (entropy 25-50), got "
							+ def.minEntropy() + "-" + def.maxEntropy());
			// Bad effects below entropy 40 must be survivable -- Tier 2 starts at 25,
			// so every BAD effect in this batch is inside that window.
			check(def.phase() != EffectPhase.BAD || def.counterplay(),
					id + " is counterplay-survivable if BAD (required below entropy 40)");
		}

		check(EffectRegistry.byId(EmbraceTheMoonBehavior.ID).phase() == EffectPhase.GOOD
						&& EffectRegistry.byId(CreativeFlightBehavior.ID).phase() == EffectPhase.GOOD
						&& EffectRegistry.byId(BehemothGauntletsBehavior.ID).phase() == EffectPhase.GOOD
						&& EffectRegistry.byId(CrouchInvincibilityBehavior.ID).phase() == EffectPhase.GOOD,
				"the four GOOD effects are registered GOOD");
		check(EffectRegistry.byId(GiantSizeBehavior.ID).phase() == EffectPhase.BAD
						&& EffectRegistry.byId(SlashedPocketsBehavior.ID).phase() == EffectPhase.BAD
						&& EffectRegistry.byId(FlamboyantBehavior.ID).phase() == EffectPhase.BAD,
				"the three BAD effects are registered BAD");

		// Anti-stacking is keyed on category, so a collision inside one phase means
		// the two can never be offered together -- worth knowing, not a bug.
		check(EffectRegistry.byId(EmbraceTheMoonBehavior.ID).category() == EffectCategory.MOVEMENT
						&& EffectRegistry.byId(CreativeFlightBehavior.ID).category() == EffectCategory.MOVEMENT,
				"Embrace the Moon and Creative Flight are both MOVEMENT -- anti-stacking keeps "
						+ "flight and low gravity apart, which is the intended exclusivity");

		// The compiler cannot check id strings against EffectRegistry; this is the
		// same validation that runs at mod init.
		check(EffectBehaviors.definitionsWithoutBehavior().isEmpty(),
				"every registered effect has a behavior: "
						+ EffectBehaviors.definitionsWithoutBehavior());
		check(EffectBehaviors.behaviorsWithoutDefinition().isEmpty(),
				"no behavior is registered under an unknown id: "
						+ EffectBehaviors.behaviorsWithoutDefinition());
		for (String id : TIER2_BATCH) {
			check(EffectBehaviors.get(id).getClass().getSimpleName().endsWith("Behavior"),
					id + " resolves to a real behavior, not the MISSING no-op");
		}
	}

	/**
	 * Extreme Gravity's value, its distinctness from Moon Walker, and -- the part
	 * that actually matters -- why its attribute operation differs.
	 */
	private static void extremeGravityPhysics() {
		section("Embrace the Moon: value, distinctness, and stacking safety");

		double amount = constant(EmbraceTheMoonBehavior.class, "GRAVITY_AMOUNT");
		double jumpBonus = constant(EmbraceTheMoonBehavior.class, "JUMP_BONUS");
		double safeFallBonus = constant(EmbraceTheMoonBehavior.class, "SAFE_FALL_BONUS");
		double moonWalker = -0.30; // MoonWalkerBehavior passes this inline to super
		double base = 0.08;        // GRAVITY's registered default, verified against the live registry

		check(amount <= -0.75 && amount >= -0.80,
				"the reduction is inside the specified -75%..-80% band (" + amount + ")");

		double gravity = base * (1.0 + amount);
		checkNear(gravity, 0.0176, 1e-9, "resulting gravity");
		check(gravity > 0.0,
				"gravity stays strictly positive -- 0 would leave the player floating "
						+ "with no way down, and GRAVITY's clamp floors at -1.0, not 0");

		// --- 1b: the jump bonus, and that it was derived rather than guessed ------
		double jump = JUMP_DEFAULT * (1.0 + jumpBonus);
		double apexGravityOnly = apex(gravity);
		double apexShipped = apex(gravity, jump);
		checkNear(apexGravityOnly, 4.065, 0.002, "apex from the gravity change alone");
		checkNear(apexShipped, 4.567, 0.002, "apex as shipped, with the jump bonus");

		double gain = apexShipped / apexGravityOnly - 1.0;
		check(gain >= 0.10 && gain <= 0.15,
				"the jump bonus lands inside the intended +10-15% apex band ("
						+ Math.round(gain * 10000) / 100.0 + "%)");
		// The whole point of deriving it: taking the apex percentage as the attribute
		// percentage overshoots, because apex goes as the square of launch velocity.
		check(apex(gravity, JUMP_DEFAULT * (1.0 + gain)) > apexShipped,
				"a naive '+" + Math.round(gain * 100) + "% on the attribute' would overshoot "
						+ "the target apex -- which is why this is solved, not assumed");

		double moonGravity = base * (1.0 + moonWalker);
		checkNear(apex(moonGravity), 1.657, 0.002, "Moon Walker jump apex (blocks)");
		checkNear(apex(base), 1.2522, 0.002,
				"the model still reproduces vanilla's known 1.2522-block jump");
		check(apexShipped > apex(moonGravity) * 2.5,
				"Embrace the Moon is meaningfully distinct from Moon Walker, not a nudge: "
						+ Math.round(apexShipped / apex(moonGravity) * 100) + "% of its apex");
		check(apexShipped > 4.0 && apex(moonGravity) < 2.0,
				"it clears a 4-block ledge where Moon Walker cannot clear 2");

		// --- 1c: safe fall distance -----------------------------------------------
		double safeFall = 3.0 + safeFallBonus; // SAFE_FALL_DISTANCE default, from the live registry
		checkNear(safeFall, 6.0, 1e-9, "safe fall distance is doubled, 3 -> 6 blocks");
		// calculateFallDamage(d, 1) = floor((d + 1e-6 - SAFE_FALL_DISTANCE) * FALL_DAMAGE_MULTIPLIER)
		for (int d : new int[] {1, 2, 3, 4, 5, 6}) {
			check(fallDamage(d, safeFall) == 0,
					"a " + d + "-block fall is harmless (vanilla would deal "
							+ fallDamage(d, 3.0) + ")");
		}
		check(fallDamage(7, safeFall) == 1 && fallDamage(7, 3.0) == 4,
				"7 blocks: 1 damage instead of vanilla's 4");
		check(fallDamage(20, safeFall) == 14 && fallDamage(20, 3.0) == 17,
				"20 blocks: 14 instead of 17 -- the threshold moves, it is not a multiplier");
		// The distinction that matters: this is NOT FALL_DAMAGE_MULTIPLIER's mechanic.
		check(fallDamage(20, safeFall) - fallDamage(20, 3.0) == fallDamage(7, safeFall) - fallDamage(7, 3.0),
				"the reduction is a constant 3 damage at every height -- a subtracted "
						+ "threshold, not a scaled remainder like Featherlight");

		// --- stacking safety, RECOMPUTED at the new value --------------------------
		double stackedTotal = base * (1.0 + moonWalker) * (1.0 + amount);
		double stackedBase = base * (1.0 + moonWalker + amount);
		check(stackedTotal > 0.0,
				"ADD_MULTIPLIED_TOTAL stacked with Moon Walker stays positive ("
						+ round4(stackedTotal) + ")");
		check(apex(stackedTotal, jump) < 7.0 && sim(stackedTotal, jump)[1] < 100,
				"and stays playable: apex " + round1(apex(stackedTotal, jump))
						+ ", airtime " + (int) sim(stackedTotal, jump)[1] + "t");

		// THE regression this session had to re-derive: the old margin did NOT survive.
		check(stackedBase < 0.0,
				"had it used ADD_MULTIPLIED_BASE the same pairing would now give "
						+ round4(stackedBase) + " -- NEGATIVE gravity, launched upward permanently");
		check(base * (1.0 + moonWalker + (-0.65)) > 0.0,
				"at last session's -65% that additive form was still (barely) positive at "
						+ round4(base * (1.0 + moonWalker - 0.65))
						+ " -- so the safety margin was NOT inherited, it had to be rechecked");
	}

	/**
	 * Vanilla's per-tick vertical integration: integrate, then apply gravity and
	 * the 0.98 air drag. Validated against the known vanilla apex above.
	 */
	/** JUMP_STRENGTH's registered default, from the live registry. */
	private static final double JUMP_DEFAULT = 0.41999998688697815;

	private static double apex(double gravity) {
		return apex(gravity, JUMP_DEFAULT);
	}

	private static double apex(double gravity, double jump) {
		return sim(gravity, jump)[0];
	}

	/** @return {apex in blocks, airtime in ticks} */
	private static double[] sim(double gravity, double jump) {
		double y = 0.0;
		double v = jump;
		double best = 0.0;
		int t = 0;
		for (; t < 6000; t++) {
			y += v;
			v = (v - gravity) * 0.98;
			best = Math.max(best, y);
			if (y <= 0 && v < 0) {
				break;
			}
		}
		return new double[] {best, t + 1};
	}

	/**
	 * Vanilla's own formula, javap-verified:
	 * {@code floor((distance + 1e-6 - SAFE_FALL_DISTANCE) * FALL_DAMAGE_MULTIPLIER)},
	 * with the multiplier at its default of 1.0.
	 */
	private static int fallDamage(double distance, double safeFallDistance) {
		return Math.max(0, (int) Math.floor(distance + 1.0e-6 - safeFallDistance));
	}

	/** Solves the real integration for the jump strength that reaches a target apex. */
	private static double jumpStrengthForApex(double gravity, double targetApex) {
		double lo = 0.01;
		double hi = 5.0;
		for (int i = 0; i < 200; i++) {
			double mid = (lo + hi) / 2.0;
			if (apex(gravity, mid) < targetApex) {
				lo = mid;
			} else {
				hi = mid;
			}
		}
		return (lo + hi) / 2.0;
	}

	private static double round4(double v) { return Math.round(v * 10000.0) / 10000.0; }
	private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }

	private static void giantSizeAttribute() {
		section("Giant Size: the SCALE attribute is real and in range");
		HarnessBootstrap.init();

		net.minecraft.world.entity.ai.attributes.RangedAttribute scale =
				(net.minecraft.world.entity.ai.attributes.RangedAttribute)
						net.minecraft.world.entity.ai.attributes.Attributes.SCALE.value();

		checkNear(scale.getDefaultValue(), 1.0, 1e-9, "SCALE default");
		checkNear(scale.getMinValue(), 0.0625, 1e-9, "SCALE minimum");
		checkNear(scale.getMaxValue(), 16.0, 1e-9, "SCALE maximum");

		// Present on the PLAYER entity type specifically -- SCALE comes from
		// LivingEntity.createLivingAttributes(), not Player.createAttributes(), so
		// "it exists" and "players have it" are separate claims.
		check(net.minecraft.world.entity.ai.attributes.DefaultAttributes
						.getSupplier(net.minecraft.world.entity.EntityType.PLAYER)
						.hasAttribute(net.minecraft.world.entity.ai.attributes.Attributes.SCALE),
				"players actually have SCALE -- checked against DefaultAttributes, not assumed");

		double amount = constant(GiantSizeBehavior.class, "AMOUNT");
		double result = scale.getDefaultValue() + amount; // ADD_VALUE against base 1.0
		checkNear(result, 5.0, 1e-9, "resulting scale is the specified ~5x");
		checkNear(scale.sanitizeValue(result), result, 1e-9,
				"5.0 is not clamped -- it is well inside [0.0625, 16.0]");

		check(Checks.hasMethod(GiantSizeBehavior.class, "afterApply"),
				"a collision mitigation exists at all -- Entity.refreshDimensions() skips "
						+ "fudgePositionAfterSizeChange for players, so vanilla will NOT push a "
						+ "grown player out of blocks");
		check(Checks.classReferences(GiantSizeBehavior.class, "noCollision")
						&& Checks.classReferences(GiantSizeBehavior.class, "snapTo"),
				"the mitigation actually tests for collision and moves the player");
		check(constant(GiantSizeBehavior.class, "MAX_LIFT_BLOCKS") > 0,
				"the upward search has a bounded limit rather than looping forever");
	}

	/** The rest of Giant Size's kit: health, step-up, jump, damage, reach. */
	private static void giantSizeKit() {
		section("Giant Size: the full kit");
		HarnessBootstrap.init();

		// --- 2a: +10 hearts -------------------------------------------------------
		checkNear(constant(GiantSizeBehavior.class, "HEALTH_BONUS"), 20.0, 1e-9,
				"health bonus is +20.0 raw = +10 hearts");
		double maxHealth = ranged(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH)
				.getDefaultValue() + 20.0;
		checkNear(maxHealth, 40.0, 1e-9, "resulting max health is 40 (20 hearts)");
		checkNear(ranged(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH)
						.sanitizeValue(maxHealth), 40.0, 1e-9,
				"40 is not clamped -- MAX_HEALTH's ceiling is 1024");

		// --- 2b: STEP_HEIGHT ------------------------------------------------------
		var step = ranged(net.minecraft.world.entity.ai.attributes.Attributes.STEP_HEIGHT);
		checkNear(step.getDefaultValue(), 0.6, 1e-9, "STEP_HEIGHT default is 0.6");
		checkNear(step.getMinValue(), 0.0, 1e-9, "STEP_HEIGHT minimum is 0.0");
		checkNear(step.getMaxValue(), 10.0, 1e-9, "STEP_HEIGHT maximum is 10.0");
		check(net.minecraft.world.entity.ai.attributes.DefaultAttributes
						.getSupplier(net.minecraft.world.entity.EntityType.PLAYER)
						.hasAttribute(net.minecraft.world.entity.ai.attributes.Attributes.STEP_HEIGHT),
				"players actually have STEP_HEIGHT -- checked, not assumed");
		double stepResult = step.getDefaultValue()
				+ constant(GiantSizeBehavior.class, "STEP_HEIGHT_BONUS");
		checkNear(stepResult, 2.0, 1e-9, "resulting step height is exactly 2.0");
		check(stepResult >= 2.0,
				"which is the whole target: a 2-block ledge walked up without jumping");
		checkNear(step.sanitizeValue(stepResult), 2.0, 1e-9, "2.0 is not clamped");

		// --- 2c: double jump, DERIVED --------------------------------------------
		double vanillaApex = apex(0.08);
		double jumpBonus = constant(GiantSizeBehavior.class, "JUMP_BONUS");
		double jump = JUMP_DEFAULT * (1.0 + jumpBonus);
		double giantApex = apex(0.08, jump);
		checkNear(giantApex / vanillaApex, 2.0, 0.001,
				"apex is genuinely 2x vanilla (" + round4(vanillaApex) + " -> " + round4(giantApex) + ")");

		// The point of deriving against the real integration rather than the closed
		// form: sqrt(2) is the intuitive answer and it is WRONG.
		double naive = JUMP_DEFAULT * Math.sqrt(2.0);
		check(apex(0.08, naive) / vanillaApex < 1.90,
				"the intuitive sqrt(2) on the attribute gives only "
						+ round4(apex(0.08, naive) / vanillaApex) + "x, not 2x -- the 0.98 air drag "
						+ "costs proportionally more at higher launch velocity");
		checkNear(jumpStrengthForApex(0.08, vanillaApex * 2.0), jump, 1e-4,
				"the shipped jump strength is what solving the integration backwards produces");
		checkNear(ranged(net.minecraft.world.entity.ai.attributes.Attributes.JUMP_STRENGTH)
						.sanitizeValue(jump), jump, 1e-9,
				"the resulting jump strength is inside JUMP_STRENGTH's range");

		// --- 2d: damage reduction rides IRON SKIN's hook, not a new one ------------
		checkNear(constant(GiantSizeBehavior.class, "DAMAGE_MULTIPLIER"), 0.75, 1e-6,
				"damage multiplier is 0.75 = -25%");
		check(Checks.classReferences(com.entropymod.entropy.EffectHooks.class, "GiantSizeBehavior"),
				"EffectHooks itself reads Giant Size -- the reduction goes through the "
						+ "existing damageTakenMultiplier, not a parallel mechanism");
		check(!Checks.hasMethod(com.entropymod.entropy.EffectHooks.class, "giantDamageMultiplier"),
				"no second damage hook was introduced alongside Iron Skin's");
		float ironSkin = (float) constant(
				com.entropymod.entropy.behavior.IronSkinBehavior.class, "MULTIPLIER");
		float fragile = (float) constant(
				com.entropymod.entropy.behavior.FragileBehavior.class, "MULTIPLIER");
		checkNear(0.75 * ironSkin, 0.60, 1e-4,
				"stacked with Iron Skin it is 0.60 -- multiplicative, so it cannot reach zero");
		check(0.75 * fragile > 0.0 && 0.75 * fragile < 1.0,
				"stacked with Fragile it is still a net reduction (" + round4(0.75 * fragile) + ")");

		// --- 2e: reach ------------------------------------------------------------
		// SCALE raises the eye, and the reach check measures FROM the eye, so an
		// unmodified 5x player cannot touch the ground they stand on.
		double eye = 1.62;
		double scale = 1.0 + constant(GiantSizeBehavior.class, "AMOUNT");
		double giantEye = eye * scale;
		checkNear(giantEye, 8.1, 1e-9, "at 5x the eye sits 8.1 blocks above the feet");

		var blockRange =
				ranged(net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_INTERACTION_RANGE);
		var entityRange =
				ranged(net.minecraft.world.entity.ai.attributes.Attributes.ENTITY_INTERACTION_RANGE);
		checkNear(blockRange.getDefaultValue(), 4.5, 1e-9, "BLOCK_INTERACTION_RANGE default is 4.5");
		checkNear(entityRange.getDefaultValue(), 3.0, 1e-9, "ENTITY_INTERACTION_RANGE default is 3.0");

		check(blockRange.getDefaultValue() < giantEye,
				"WITHOUT a reach bonus a 5x player cannot reach the block at their feet: "
						+ "needs " + giantEye + ", has " + blockRange.getDefaultValue());
		double reachBonus = constant(GiantSizeBehavior.class, "REACH_BONUS");
		check(blockRange.getDefaultValue() + reachBonus > giantEye,
				"with the bonus they can: block reach "
						+ (blockRange.getDefaultValue() + reachBonus) + " vs the 8.1 needed");
		check(blockRange.getDefaultValue() + 2.0 < giantEye,
				"the originally-specified +2 would NOT have been enough ("
						+ (blockRange.getDefaultValue() + 2.0) + " vs 8.1) -- which is why it is larger");
		checkNear(reachBonus, giantEye - eye, 0.03,
				"the bonus is sized to the eye-height increase, restoring vanilla-equivalent "
						+ "reach relative to the giant's own body");
		check(blockRange.sanitizeValue(blockRange.getDefaultValue() + reachBonus)
						== blockRange.getDefaultValue() + reachBonus
						&& entityRange.sanitizeValue(entityRange.getDefaultValue() + reachBonus)
								== entityRange.getDefaultValue() + reachBonus,
				"both resulting ranges are inside the attributes' range");
	}

	private static net.minecraft.world.entity.ai.attributes.RangedAttribute ranged(
			net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> holder) {
		return (net.minecraft.world.entity.ai.attributes.RangedAttribute) holder.value();
	}

	/**
	 * Both reworked effects apply several attributes at once, so "idempotent" now
	 * has to mean "every one of them is", not just the first.
	 */
	private static void multiAttributeIdempotency() {
		section("Multi-attribute effects: every change is idempotent");
		HarnessBootstrap.init();

		for (String id : List.of(EmbraceTheMoonBehavior.ID, GiantSizeBehavior.ID)) {
			AttributeEffectBehavior behavior = (AttributeEffectBehavior) EffectBehaviors.get(id);
			List<AttributeEffectBehavior.Change> changes = behavior.changes();
			check(changes.size() > 1, id + " really is a multi-attribute effect (" + changes.size() + ")");

			for (AttributeEffectBehavior.Change change : changes) {
				String name = net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE
						.getKey(change.attribute().value()).getPath();
				var instance = new net.minecraft.world.entity.ai.attributes.AttributeInstance(
						change.attribute(), a -> {});
				double afterFirst = 0;
				for (int i = 0; i < 10; i++) {
					instance.addOrUpdateTransientModifier(
							new net.minecraft.world.entity.ai.attributes.AttributeModifier(
									AttributeEffectBehavior.modifierId(id),
									change.amount(), change.operation()));
					if (i == 0) {
						afterFirst = instance.getValue();
					}
				}
				checkNear(instance.getValue(), afterFirst, 1e-9,
						id + "/" + name + ": ten applications leave the value where one put it");
				check(instance.getModifiers().size() == 1,
						id + "/" + name + ": one modifier, not ten stacked copies");

				// remove() must clear every change too, or a future un-apply leaks.
				instance.removeModifier(AttributeEffectBehavior.modifierId(id));
				checkNear(instance.getValue(), instance.getBaseValue(), 1e-9,
						id + "/" + name + ": removal restores the base value");
			}
		}
	}


	/** Both branches driven for real against the shipped decision function. */
	private static void behemothGauntletsBothCases() {
		section("Behemoth Gauntlets: empty hand vs. held item");

		float bonus = (float) constant(BehemothGauntletsBehavior.class, "UNARMED_BONUS");
		float armed = (float) constant(BehemothGauntletsBehavior.class, "ARMED_MULTIPLIER");
		checkNear(bonus, 20.0, 1e-6, "unarmed bonus is the specified flat +20");
		checkNear(armed, 0.25, 1e-6, "armed multiplier is the specified -75%");

		// A vanilla bare-handed hit is 1.0; a diamond sword swing is about 7.
		checkNear(BehemothGauntletsBehavior.damageFor(1.0f, true), 21.0, 1e-4,
				"EMPTY HAND: a 1-damage punch becomes 21");
		checkNear(BehemothGauntletsBehavior.damageFor(7.0f, false), 1.75, 1e-4,
				"HELD ITEM: a 7-damage sword swing becomes 1.75");

		check(BehemothGauntletsBehavior.damageFor(7.0f, true)
						> BehemothGauntletsBehavior.damageFor(7.0f, false),
				"the two branches genuinely differ for the same input damage");
		check(BehemothGauntletsBehavior.damageFor(1.0f, true)
						> BehemothGauntletsBehavior.damageFor(7.0f, false),
				"punching out-damages a diamond sword -- the point of the effect");

		// Additive unarmed, multiplicative armed: a crit or a Strength potion should
		// scale the penalty rather than be swallowed by a flat number.
		checkNear(BehemothGauntletsBehavior.damageFor(0.0f, false), 0.0, 1e-6,
				"the armed branch is multiplicative, so zero damage stays zero");
		checkNear(BehemothGauntletsBehavior.damageFor(0.0f, true), 20.0, 1e-6,
				"the unarmed branch is additive, so it applies even to a zero-damage hit");

		check(Checks.classReferences(com.entropymod.entropy.EffectHooks.class, "getWeaponItem"),
				"the hook decides on getWeaponItem() -- the main-hand stack -- rather than "
						+ "guessing from an item tag");
	}

	private static void flamboyantFireOnly() {
		section("Flamboyant: fire specifically, not damage generally");

		float multiple = (float) constant(FlamboyantBehavior.class, "LETHAL_HEALTH_MULTIPLE");
		check(multiple > 1.0f, "the lethal amount is a multiple of max health, not a fixed number");
		check(Float.isFinite(multiple * 20.0f),
				"it stays finite -- Float.MAX_VALUE would risk non-finite damage maths downstream");

		// Armour caps at 80% reduction and Resistance at a further 80%; Iron Skin is
		// another 20%. Even all three together must not leave the player alive.
		float lethal = FlamboyantBehavior.lethalDamage(1.0f, 20.0f);
		check(lethal * 0.2f * 0.2f * 0.8f > 20.0f,
				"survives worst-case mitigation (armour + Resistance + Iron Skin) and is still lethal");
		checkNear(FlamboyantBehavior.lethalDamage(1.0f, 20.0f), 20.0f * multiple, 1e-3,
				"lethal damage scales with max health");
		check(FlamboyantBehavior.lethalDamage(Float.MAX_VALUE, 20.0f) == Float.MAX_VALUE,
				"an already-larger amount is left alone rather than scaled down");

		// THE regression check: the gate is the fire tag, and nothing else.
		check(Checks.classReferences(com.entropymod.entropy.EffectHooks.class, "IS_FIRE"),
				"the hook gates on the vanilla #minecraft:is_fire damage-type tag");

		Set<String> fire = resolveDamageTypeTag("is_fire");
		check(fire.contains("minecraft:on_fire") && fire.contains("minecraft:in_fire")
						&& fire.contains("minecraft:lava") && fire.contains("minecraft:campfire")
						&& fire.contains("minecraft:hot_floor"),
				"the shipped tag covers burning, standing in fire, lava, campfires and magma");
		// Read from the jar rather than restated, so this is a claim about game data.
		for (String notFire : List.of("minecraft:fall", "minecraft:drown", "minecraft:mob_attack",
				"minecraft:player_attack", "minecraft:cactus", "minecraft:out_of_world",
				"minecraft:explosion", "minecraft:starve", "minecraft:magic")) {
			check(!fire.contains(notFire),
					"NON-FIRE regression: " + notFire + " is not in #is_fire, so it stays ordinary damage");
		}
	}

	/** Reads a shipped damage-type tag out of the Minecraft jar on the classpath. */
	private static Set<String> resolveDamageTypeTag(String name) {
		String path = "/data/minecraft/tags/damage_type/" + name + ".json";
		try (java.io.InputStream in = HarnessMain.class.getResourceAsStream(path)) {
			if (in == null) {
				throw new IllegalStateException("Tag not found on the classpath: " + path);
			}
			String json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
			Set<String> out = new java.util.LinkedHashSet<>();
			java.util.regex.Matcher m =
					java.util.regex.Pattern.compile("\"([a-z0-9_.-]+:[a-z0-9_./-]+)\"").matcher(json);
			while (m.find()) {
				out.add(m.group(1));
			}
			return out;
		} catch (java.io.IOException e) {
			throw new IllegalStateException("Could not read " + path, e);
		}
	}

	private static void crouchInvincibilityGate() {
		section("Crouch Invincibility: only while actually sneaking");

		// The gate needs a live Player, which cannot be built headlessly -- but WHICH
		// vanilla method it consults is visible in the compiled hook, and the two
		// candidates behave differently (pose vs. raw key state).
		check(Checks.classReferences(com.entropymod.entropy.EffectHooks.class, "isCrouching"),
				"the hook consults isCrouching() -- vanilla's pose state, matching what the "
						+ "player sees on screen");
		check(!Checks.classReferences(com.entropymod.entropy.EffectHooks.class, "isShiftKeyDown"),
				"and NOT isShiftKeyDown(), the raw input flag, which disagrees when a player "
						+ "is crouched under a 1-block gap or shifting while forced upright");

		check(Checks.hasMethod(com.entropymod.entropy.EffectHooks.class, "ignoresAllDamage"),
				"the gate is a single hook, so there is one place the sneak test can live");

		// Evaluated per hit rather than latched: the check lives in the hook the
		// damage mixin calls, not in apply(). A behavior with no state cannot latch.
		check(CrouchInvincibilityBehavior.class.getSuperclass()
						== com.entropymod.entropy.HookEffectBehavior.class,
				"it is a HookEffectBehavior, whose apply() is final and empty -- so there is "
						+ "no per-player state that could latch 'has sneaked' into 'is sneaking'");
		check(Checks.classReferences(com.entropymod.mixin.ServerPlayerDamageMixin.class,
						"ignoresAllDamage"),
				"the damage mixin calls the gate on every hit");
	}

	private static void slashedPocketsSlots() {
		section("Slashed Pockets: which slots, in this version's layout");
		HarnessBootstrap.init();

		int first = (int) constant(SlashedPocketsBehavior.class, "FIRST_LOCKED_SLOT");
		int last = (int) constant(SlashedPocketsBehavior.class, "LAST_LOCKED_SLOT");
		check(first == 9 && last == 26, "locked range is slots 9-26 (got " + first + "-" + last + ")");
		check(SlashedPocketsBehavior.lockedSlotCount() == 18,
				"that is 18 slots -- exactly half of the 36 main inventory slots");

		// Read from the live Inventory class, so this is a claim about the game.
		check(net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE == 36,
				"INVENTORY_SIZE is 36");
		check(net.minecraft.world.entity.player.Inventory.SELECTION_SIZE == 9,
				"SELECTION_SIZE (the hotbar) is 9");
		check(SlashedPocketsBehavior.lockedSlotCount() * 2
						== net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE,
				"the locked half is exactly half of the main inventory");

		// The hotbar must stay usable, or the effect is unplayable rather than harsh.
		for (int slot = 0; slot <= 8; slot++) {
			check(net.minecraft.world.entity.player.Inventory.isHotbarSlot(slot)
							&& !SlashedPocketsBehavior.isLocked(slot),
					"hotbar slot " + slot + " is never locked");
		}
		check(!SlashedPocketsBehavior.isLocked(27) && !SlashedPocketsBehavior.isLocked(35),
				"the bottom storage row (27-35) stays usable");
		check(SlashedPocketsBehavior.isLocked(9) && SlashedPocketsBehavior.isLocked(17)
						&& SlashedPocketsBehavior.isLocked(18) && SlashedPocketsBehavior.isLocked(26),
				"both locked rows are locked end to end");

		// Equipment lives at 40/41/42, outside the main list entirely, so armour and
		// the offhand cannot be caught by an index test.
		check(!SlashedPocketsBehavior.isLocked(
						net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND)
						&& !SlashedPocketsBehavior.isLocked(
								net.minecraft.world.entity.player.Inventory.SLOT_BODY_ARMOR)
						&& !SlashedPocketsBehavior.isLocked(
								net.minecraft.world.entity.player.Inventory.SLOT_SADDLE),
				"offhand, body armour and saddle slots are outside the locked range");

		check(Checks.classReferences(SlashedPocketsBehavior.class, "isFreshPick"),
				"the one-time drop is gated on a fresh pick, so it cannot re-fire on every "
						+ "respawn, rejoin and dimension change");
	}

	/**
	 * The three guarantees the whole permanent-effect design rests on, applied to
	 * this batch: apply is idempotent, an effect is never offered twice, and the
	 * acquired set survives a save.
	 */
	private static void tier2BatchIdempotencyAndPersistence() {
		section("Tier 2 batch: idempotency, no-repeat and persistence");
		HarnessBootstrap.init();

		// --- idempotency, against the REAL AttributeInstance --------------------
		// This is the guarantee the respawn/rejoin design rests on: apply() runs an
		// unbounded number of times, so the value must not move after the first.
		for (String id : List.of(EmbraceTheMoonBehavior.ID, GiantSizeBehavior.ID)) {
			net.minecraft.world.entity.ai.attributes.AttributeInstance instance =
					instanceFor(id.equals(GiantSizeBehavior.ID)
							? net.minecraft.world.entity.ai.attributes.Attributes.SCALE
							: net.minecraft.world.entity.ai.attributes.Attributes.GRAVITY);
			double amount = constant(
					id.equals(GiantSizeBehavior.ID) ? GiantSizeBehavior.class : EmbraceTheMoonBehavior.class,
					id.equals(GiantSizeBehavior.ID) ? "AMOUNT" : "GRAVITY_AMOUNT");
			var op = id.equals(GiantSizeBehavior.ID)
					? net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE
					: net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;

			double after1 = 0;
			for (int i = 0; i < 10; i++) {
				instance.addOrUpdateTransientModifier(
						new net.minecraft.world.entity.ai.attributes.AttributeModifier(
								com.entropymod.entropy.AttributeEffectBehavior.modifierId(id), amount, op));
				if (i == 0) {
					after1 = instance.getValue();
				}
			}
			checkNear(instance.getValue(), after1, 1e-9,
					id + ": applying 10 times leaves the value where one application put it");
			check(instance.getModifiers().size() == 1,
					id + ": exactly one modifier, not ten stacked copies");
		}

		// --- no-repeat ----------------------------------------------------------
		AcquiredEffects acquired = new AcquiredEffects();
		TIER2_BATCH.forEach(acquired::add);
		for (int entropy : new int[] {25, 37, 50}) {
			for (EffectPhase phase : EffectPhase.values()) {
				EffectRegistry.RollResult roll = EffectRegistry.roll(
						phase, entropy, new java.util.Random(1234),
						acquired.ids(), acquired.occupiedCategories());
				if (roll.repeatFallback()) {
					continue; // the pool legitimately ran dry; that path is covered elsewhere
				}
				for (EffectDefinition offered : roll.choices()) {
					check(!TIER2_BATCH.contains(offered.id()),
							"no-repeat holds at entropy " + entropy + "/" + phase
									+ ": already-acquired '" + offered.id() + "' was not re-offered");
				}
			}
		}

		// --- persistence --------------------------------------------------------
		// grantEffect needs a live server to dispatch behaviors, so persistence is
		// driven through the acquired set, which is what the codec actually writes.
		EntropyManager saved = new EntropyManager();
		TIER2_BATCH.forEach(id -> saved.acquired().add(id));
		EntropyManager reloaded = reencode(saved);
		for (String id : TIER2_BATCH) {
			check(reloaded.acquired().contains(id),
					id + " survives a save/reload in the acquired set");
		}
		check(reloaded.acquired().unknownIds().isEmpty(),
				"every id in this batch is still defined after a reload -- no stale ids");
	}

	private static net.minecraft.world.entity.ai.attributes.AttributeInstance instanceFor(
			net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute) {
		return new net.minecraft.world.entity.ai.attributes.AttributeInstance(attribute, a -> {});
	}

	private HarnessMain() {}
}
