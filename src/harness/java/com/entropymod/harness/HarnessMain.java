package com.entropymod.harness;

import com.entropymod.entropy.EffectDefinition;
import com.entropymod.entropy.EffectRegistry;
import com.entropymod.entropy.EntropyManager;
import com.entropymod.entropy.behavior.BlightTouchedBehavior;
import com.entropymod.entropy.behavior.GreenThumbBehavior;
import com.entropymod.entropy.behavior.LeakyPocketsBehavior;
import com.entropymod.entropy.behavior.SureFootingBehavior;

import static com.entropymod.harness.Checks.check;
import static com.entropymod.harness.Checks.checkNear;
import static com.entropymod.harness.Checks.constant;
import static com.entropymod.harness.Checks.section;

/**
 * Headless checks. Run with {@code ./gradlew harness}.
 *
 * <p>Covers what can be established without booting Minecraft: the tuning
 * constants as they are actually compiled, the crop-growth timing derivation,
 * the divide-by-zero guard around vanilla's growth roll, and the parts of
 * {@code /entropygrant}'s contract that are observable off-server.
 *
 * <p><b>What it cannot prove</b>, and what still needs a real session: that the
 * mixins inject, that the effects are felt, and that a granted effect survives a
 * real death and relog. See CLAUDE.md.
 */
public final class HarnessMain {

	public static void main(String[] args) {
		tuningConstants();
		growthModelSanity();
		greenThumbDerivation();
		greenThumbPerCropTimings();
		overflowGuard();
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
		checkNear(constant(GreenThumbBehavior.class, "MULTIPLIER"), 26.0, 1e-7,
				"Green Thumb scales crop growth speed by 26x");
		checkNear(constant(GreenThumbBehavior.class, "RADIUS"), 8.0, 1e-7,
				"Green Thumb radius is unchanged at 8 blocks");
		checkNear(constant(BlightTouchedBehavior.class, "MULTIPLIER"), 0.5, 1e-7,
				"Blight Touched is unchanged at 0.5x");

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
	 * Why 26 and not something else, and the ceiling that made the 90-second
	 * target unreachable through this lever.
	 */
	private static void greenThumbDerivation() {
		section("Green Thumb derivation");

		float m = (float) constant(GreenThumbBehavior.class, "MULTIPLIER");

		// 1. It saturates: the roll bound bottoms out at 1 for every real layout.
		for (float base : new float[]{
				CropGrowthModel.BASE_SPEED_WORST_FARMLAND, 2.0f, 3.0f,
				CropGrowthModel.BASE_SPEED_PACKED_FIELD, 8.0f, CropGrowthModel.BASE_SPEED_ROWS}) {
			check(CropGrowthModel.saturated(base * m),
					"saturated at base speed " + base + " (bound " + CropGrowthModel.rollBound(base * m) + ")");
		}

		// 2. It is the SMALLEST whole multiplier that does. 25 lands exactly on
		//    25.0, and (int)(25/25) is 1, which halves the rate on the worst layout.
		check(!CropGrowthModel.saturated(CropGrowthModel.BASE_SPEED_WORST_FARMLAND * 25.0f),
				"25x does NOT saturate the worst farmland layout -- 26 is minimal");

		// 3. Above it, nothing changes. This is a saturation point, not a slider.
		check(CropGrowthModel.rollBound(CropGrowthModel.BASE_SPEED_WORST_FARMLAND * m)
						== CropGrowthModel.rollBound(CropGrowthModel.BASE_SPEED_WORST_FARMLAND * 1000.0f),
				"1000x behaves identically to 26x -- larger values buy nothing");

		// 4. The ceiling. Even at one stage per random tick, wheat cannot reach the
		//    90s (1800 tick) target, because the binding constraint is the
		//    random-tick RATE, which no multiplier touches.
		double fastestWheat = CropGrowthModel.expectedSeconds(7, 1.0,
				CropGrowthModel.BASE_SPEED_WORST_FARMLAND, m);
		checkNear(fastestWheat, 477.87, 0.1,
				"fastest possible wheat through this hook is ~478s, not 90s");
		check(fastestWheat > 90.0 * 5,
				"the 90s target is more than 5x beyond what this lever can reach");

		// 5. What it would take: the rate, not the chance.
		double neededRate = 7.0 * CropGrowthModel.SECTION_BLOCKS / 1800.0;
		checkNear(neededRate, 15.93, 0.01,
				"90s wheat would need random_tick_speed ~16 at this multiplier");
	}

	/**
	 * The per-crop table recorded in CLAUDE.md and in
	 * {@link GreenThumbBehavior}'s javadoc. Different crops have different stage
	 * counts, so one multiplier deliberately does not equalise them -- these
	 * numbers are the point, not a rounding of wheat's.
	 */
	private static void greenThumbPerCropTimings() {
		section("Green Thumb per-crop expected time to maturity");

		float m = (float) constant(GreenThumbBehavior.class, "MULTIPLIER");
		double[] expected = {477.87, 307.2, 204.8, 546.13, 273.07};

		for (int i = 0; i < CropGrowthModel.COVERED.length; i++) {
			CropGrowthModel.Crop crop = CropGrowthModel.COVERED[i];
			// Saturated, so the base layout no longer matters -- assert that too.
			double packed = CropGrowthModel.expectedSeconds(crop.stages(), crop.gate(),
					CropGrowthModel.BASE_SPEED_PACKED_FIELD, m);
			double dry = CropGrowthModel.expectedSeconds(crop.stages(), crop.gate(),
					CropGrowthModel.BASE_SPEED_WORST_FARMLAND, m);

			checkNear(packed, expected[i], 0.5,
					crop.name() + " -> " + CropGrowthModel.asMinutesSeconds(packed));
			checkNear(dry, packed, 1e-9,
					crop.name() + ": dry and hydrated land on the same time once saturated");
		}

		// And the vanilla comparison the table quotes, so a future retune can see
		// what was actually gained.
		double vanillaWheat = CropGrowthModel.expectedSeconds(7, 1.0,
				CropGrowthModel.BASE_SPEED_PACKED_FIELD, 1.0f);
		checkNear(vanillaWheat, 2867.2, 0.5,
				"vanilla packed-field wheat is " + CropGrowthModel.asMinutesSeconds(vanillaWheat));
		checkNear(vanillaWheat / expected[0], 6.0, 0.01,
				"Green Thumb is a 6x speedup over a packed field, 3x over rows");
	}

	/**
	 * Re-checks the divide-by-zero hazard at the new, much larger multiplier.
	 *
	 * <p>The hazard runs the other way -- it is small speeds, not large ones, that
	 * break -- but "a bigger number is obviously safe" is exactly the assumption
	 * worth testing rather than asserting.
	 */
	private static void overflowGuard() {
		section("Growth-roll overflow guard at the larger multiplier");

		float green = (float) constant(GreenThumbBehavior.class, "MULTIPLIER");
		float blight = (float) constant(BlightTouchedBehavior.class, "MULTIPLIER");
		float floor = (float) constant(com.entropymod.entropy.EffectHooks.class, "MIN_CROP_MULTIPLIER");

		// The failure mode being guarded against, demonstrated so it is not folklore:
		// a zero multiplier really does produce a negative nextInt bound, which throws.
		check(CropGrowthModel.rollBound(0.0f) < 0,
				"multiplier 0 would overflow to a negative nextInt bound (this is why the floor exists)");

		float[] multipliers = {floor, blight, 1.0f, green * blight, green, green * green};
		float[] baseSpeeds = {0.5f, CropGrowthModel.BASE_SPEED_WORST_FARMLAND, 2.0f,
				CropGrowthModel.BASE_SPEED_PACKED_FIELD, CropGrowthModel.BASE_SPEED_ROWS};

		boolean allSafe = true;
		for (float mult : multipliers) {
			for (float base : baseSpeeds) {
				int bound = CropGrowthModel.rollBound(base * mult);
				if (bound < 1) {
					allSafe = false;
					System.out.println("        unsafe: base " + base + " x " + mult + " -> bound " + bound);
				}
			}
		}
		check(allSafe, "every multiplier/layout pair yields a nextInt bound of at least 1");

		check(CropGrowthModel.rollBound(CropGrowthModel.BASE_SPEED_WORST_FARMLAND * green) == 1,
				"the larger multiplier moves AWAY from the hazard: bound is at its floor of 1");
		check(floor > 0.0f, "EffectHooks floors the combined multiplier above zero");
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
